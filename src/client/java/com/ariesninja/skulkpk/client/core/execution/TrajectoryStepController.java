package com.ariesninja.skulkpk.client.core.execution;

import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import com.ariesninja.skulkpk.client.core.physics.ParkourPhysics;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import com.ariesninja.skulkpk.client.core.planning.ControlFrame;
import com.ariesninja.skulkpk.client.core.planning.ControlPhase;
import com.ariesninja.skulkpk.client.core.planning.FrameGuard;
import com.ariesninja.skulkpk.client.core.planning.LandingStabilityTracker;
import com.ariesninja.skulkpk.client.core.planning.MovementPlan;
import com.ariesninja.skulkpk.client.core.planning.SupportResolver;
import com.ariesninja.skulkpk.client.core.planning.TrajectorySample;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Executes predicted controls against observed phases instead of elapsed client ticks. */
public final class TrajectoryStepController implements StepController {
    private static final double AIRBORNE_POSITION_DEADBAND = 0.18;
    private static final double AIRBORNE_VELOCITY_DEADBAND = 0.10;
    private static final double AIRBORNE_VERTICAL_POSITION_DEADBAND = 0.16;
    private static final double AIRBORNE_VERTICAL_VELOCITY_DEADBAND = 0.10;
    private static final int RECOVERY_HORIZON_TICKS = 120;
    private static final float MAX_YAW_CHANGE = 12;
    private static final int MAX_SNEAK_TICKS = 6;
    private static final int MAX_ALIGNMENT_TICKS = 30;
    private static final int MAX_TAKEOFF_LOOKAHEAD_TICKS = 8;
    private static final double OBSTACLE_RECOVERY_CLEARANCE = 0.18;

    private final ParkourPhysics physics = new ParkourPhysics();

    private MovementPlan plan;
    private WorldView world;
    private LandingStabilityTracker landingTracker;
    private int waypointIndex;
    private int frameIndex;
    private int outsideGroundTicks;
    private int consecutiveSneakTicks;
    private int jumpWaitTicks;
    private int alignmentTicks;
    private int airborneMismatchTicks;
    private boolean launched;
    private boolean jumpCommanded;
    private ControlFrame pendingTakeoffFrame;
    private double takeoffCommandFeetY;
    private boolean launchStaged;
    private boolean launchAligned;
    private boolean runUpStarted;
    private double previousTargetDistance = Double.MAX_VALUE;
    private String reason = "";

    @Override
    public void start(MovementPlan plan, WorldView world) {
        this.plan = plan;
        this.world = world;
        landingTracker = new LandingStabilityTracker(plan.landingRegion());
        waypointIndex = 0;
        frameIndex = 0;
        outsideGroundTicks = 0;
        consecutiveSneakTicks = 0;
        jumpWaitTicks = 0;
        alignmentTicks = 0;
        airborneMismatchTicks = 0;
        launched = false;
        jumpCommanded = false;
        pendingTakeoffFrame = null;
        takeoffCommandFeetY = 0;
        runUpStarted = false;
        // A validated current-state launch must apply its first input on the handoff tick.
        // Spending even one idle tick here changes velocity and invalidates the simulation.
        launchStaged = plan.immediateLaunch();
        launchAligned = plan.immediateLaunch();
        previousTargetDistance = Double.MAX_VALUE;
        reason = "";
    }

    @Override
    public StepTickResult tick(MinecraftClient client) {
        if (plan == null || client.player == null) return fail("The player or active plan is unavailable.");
        if (client.world == null || world.identityToken() != client.world) {
            return fail("The active world changed after planning.");
        }
        if (world.fingerprint(plan.fingerprintRegion()) != plan.worldFingerprint()) {
            return fail("World geometry changed after planning.");
        }

        PlayerEntity player = client.player;
        if (jumpCommanded && !launched) {
            StepTickResult pending = acknowledgeTakeoff(client, player);
            if (pending != null) return pending;
        }
        boolean targetSupported = targetSupported(player);
        if (!targetSupported) client.options.sneakKey.setPressed(false);

        if (launched && targetSupported) return tickLanding(client, player);
        if (launched && player.isOnGround()) {
            double distance = SupportResolver.distanceToRegion(player.getPos(), plan.landingRegion());
            boolean approaching = distance < previousTargetDistance - 0.005;
            previousTargetDistance = distance;
            if (++outsideGroundTicks > 2 || !approaching) {
                return fail("Wrong support: the jump grounded outside the connected target region.");
            }
        } else {
            outsideGroundTicks = 0;
            previousTargetDistance = SupportResolver.distanceToRegion(player.getPos(), plan.landingRegion());
        }

        if (waypointIndex < plan.positioningPath().size()) return position(client, player);
        if (!launchStaged) return stageForLaunch(client, player);
        if (!launchAligned) return alignForLaunch(client, player);
        return executeTrajectory(client, player, targetSupported);
    }

    private StepTickResult position(MinecraftClient client, PlayerEntity player) {
        Vec3d target = plan.positioningPath().get(waypointIndex);
        boolean finalWaypoint = waypointIndex == plan.positioningPath().size() - 1;
        boolean reached = finalWaypoint ? insideLaunchEnvelope(player)
                : horizontalDistance(player.getPos(), target) <= 0.12
                    && player.getVelocity().horizontalLength() <= 0.07;
        if (reached) {
            release(client);
            waypointIndex++;
            if (waypointIndex >= plan.positioningPath().size()) {
                launchStaged = true;
                alignmentTicks = 0;
            }
            return StepTickResult.RUNNING;
        }
        if (++alignmentTicks > MAX_ALIGNMENT_TICKS) return replan(
                "The fixed launch envelope was not reached within 30 ticks.");
        laneRelativePosition(client, player, target);
        return StepTickResult.RUNNING;
    }

    private StepTickResult stageForLaunch(MinecraftClient client, PlayerEntity player) {
        if (currentStateLaunchDrifted(player)) {
            release(client);
            return replan("The captured current-state launch changed while planning.");
        }
        if (insideLaunchEnvelope(player)) {
            release(client);
            launchStaged = true;
            alignmentTicks = 0;
            return StepTickResult.RUNNING;
        }
        if (++alignmentTicks > MAX_ALIGNMENT_TICKS) return replan(
                "The fixed launch envelope was not reached within 30 ticks.");
        laneRelativePosition(client, player, plan.stagingPosition());
        return StepTickResult.RUNNING;
    }

    private void laneRelativePosition(MinecraftClient client, PlayerEntity player, Vec3d target) {
        float desiredYaw = plan.launchEnvelope().desiredYaw();
        float yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        if (!isLaunchYawAligned(yawError)) {
            release(client);
            smoothYaw(player, desiredYaw);
            return;
        }
        Vec3d heading = plan.launchEnvelope().routeHeading();
        Vec3d side = ControlInput.strafeDirection(heading);
        Vec3d delta = target.subtract(player.getPos());
        double longitudinal = delta.dotProduct(heading);
        double lateral = delta.dotProduct(side);
        float forward = Math.abs(longitudinal) <= 0.035 ? 0 : Math.signum((float) longitudinal);
        float strafe = Math.abs(lateral) <= 0.035 ? 0 : Math.signum((float) lateral);
        if (player.getVelocity().horizontalLength() > 0.08 && Math.hypot(longitudinal, lateral) < 0.16) {
            Vec3d velocity = player.getVelocity();
            forward = (float) -Math.signum(velocity.dotProduct(heading));
            strafe = (float) -Math.signum(velocity.dotProduct(side));
        }
        setMovement(client, forward, strafe, false, false, false);
    }

    private StepTickResult alignForLaunch(MinecraftClient client, PlayerEntity player) {
        float desiredYaw = plan.launchEnvelope().desiredYaw();
        float yawError = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        release(client);
        if (!plan.launchEnvelope().containsPosition(player.getPos())) {
            if (plan.currentStateLaunch()) {
                return replan("The captured current-state launch changed during alignment.");
            }
            launchStaged = false;
            return StepTickResult.RUNNING;
        }
        if (++alignmentTicks > MAX_ALIGNMENT_TICKS) return replan(
                "Launch alignment exceeded 30 ticks.");
        if (Math.abs(yawError) > plan.launchEnvelope().yawTolerance()
                || !plan.launchEnvelope().containsVelocity(player.getVelocity())) {
            if (!isLaunchYawAligned(yawError)) smoothYaw(player, desiredYaw);
            return StepTickResult.RUNNING;
        }
        launchAligned = true;
        alignmentTicks = 0;
        return StepTickResult.RUNNING;
    }

    private boolean insideLaunchEnvelope(PlayerEntity player) {
        return plan.launchEnvelope().containsPosition(player.getPos())
                && player.getVelocity().horizontalLength()
                    <= Math.max(0.06, plan.launchEnvelope().maximumForwardSpeed() + 0.02);
    }

    private boolean currentStateLaunchDrifted(PlayerEntity player) {
        return plan.currentStateLaunch() && (!plan.launchEnvelope().containsPosition(player.getPos())
                || !plan.launchEnvelope().containsVelocity(player.getVelocity()));
    }

    private StepTickResult executeTrajectory(MinecraftClient client, PlayerEntity player,
                                             boolean targetSupported) {
        if (!player.isOnGround() && !launched) {
            release(client);
            return fail("Approach left its support before the planned jump transition.");
        }
        if (frameIndex >= plan.controlFrames().size()) {
            if (!player.isOnGround() && launched) {
                ControlFrame recovery = runtimeAirborneControl(ParkourState.capture(PlayerSnapshot.capture(player)),
                        lastAirborneFrame(), plan.controlFrames().size() - 1);
                applyFrame(client, player, recovery, false);
                launched = true;
                return StepTickResult.RUNNING;
            }
            release(client);
            return fail("The executed route exhausted its controls before reaching target support.");
        }

        ControlFrame planned = plan.controlFrames().get(frameIndex);
        if (!planned.guard().permits(player.isOnGround(), targetSupported)) {
            if (!player.isOnGround()) {
                if (!launched) {
                    release(client);
                    return fail("Approach left support without a confirmed jump impulse.");
                }
                ControlFrame recovery = runtimeAirborneControl(ParkourState.capture(PlayerSnapshot.capture(player)),
                        lastAirborneFrame(), frameIndex);
                applyFrame(client, player, recovery, false);
                launched = true;
                return StepTickResult.RUNNING;
            }
            release(client);
            return StepTickResult.RUNNING;
        }

        boolean jump = planned.jump() && !jumpCommanded && !launched;
        if (jump && plan.launchLane() != null) {
            TakeoffDecision decision = takeoffDecision(
                    ParkourState.capture(PlayerSnapshot.capture(player)), planned, frameIndex);
            if (decision == TakeoffDecision.ABORT) {
                release(client);
                return fail("No validated takeoff state remained before the safe edge.");
            }
            if (decision == TakeoffDecision.APPROACH) {
                ControlFrame approach = new ControlFrame(1, planned.strafe(), planned.sprint(),
                        false, false, planned.desiredYaw(), ControlPhase.RUN_UP, FrameGuard.GROUNDED);
                applyFrame(client, player, approach, false);
                runUpStarted = true;
                return StepTickResult.RUNNING;
            }
        }

        if (jump && plan.launchLane() != null && !plan.launchLane().inTriggerInterval(player.getPos())) {
            double remaining = plan.launchLane().distanceBeforeEdge(player.getPos());
            if (remaining < plan.launchLane().triggerMinimum() - 0.05) {
                return fail("The launch trigger interval was passed before takeoff.");
            }
            ControlFrame approach = new ControlFrame(planned.forward(), planned.strafe(), planned.sprint(),
                    false, false, planned.desiredYaw(), ControlPhase.RUN_UP, FrameGuard.GROUNDED);
            applyFrame(client, player, approach, false);
            runUpStarted = true;
            return StepTickResult.RUNNING;
        }
        ControlFrame guarded = new ControlFrame(planned.forward(), planned.strafe(), planned.sprint(), jump,
                false, planned.desiredYaw(), planned.phase(), planned.guard());
        ControlFrame corrected = !player.isOnGround()
                ? runtimeAirborneControl(ParkourState.capture(PlayerSnapshot.capture(player)),
                    guarded, frameIndex) : guarded;
        applyFrame(client, player, corrected, false);
        runUpStarted |= corrected.phase() == ControlPhase.RUN_UP || corrected.phase() == ControlPhase.TAKEOFF;
        if (jump) {
            // Command issuance is not takeoff acknowledgement. Keep this frame pending until
            // Minecraft exposes upward motion (or the expected head-contact rise) next tick.
            jumpCommanded = true;
            pendingTakeoffFrame = corrected;
            takeoffCommandFeetY = player.getY();
            jumpWaitTicks = 0;
            return StepTickResult.RUNNING;
        }
        frameIndex++;
        return StepTickResult.RUNNING;
    }

    private StepTickResult acknowledgeTakeoff(MinecraftClient client, PlayerEntity player) {
        double verticalDisplacement = player.getY() - takeoffCommandFeetY;
        if (isTakeoffConfirmed(player.isOnGround(), verticalDisplacement,
                player.getVelocity().y, player.verticalCollision)) {
            launched = true;
            client.options.jumpKey.setPressed(false);
            frameIndex++;
            jumpWaitTicks = 0;
            return null;
        }
        if (!player.isOnGround()) {
            release(client);
            return fail("Missed takeoff: the player walked off support without receiving a jump impulse.");
        }
        if (++jumpWaitTicks <= 2 && pendingTakeoffFrame != null
                && plan.launchLane() != null
                && plan.launchLane().inTriggerInterval(player.getPos())) {
            applyFrame(client, player, pendingTakeoffFrame, false);
            return StepTickResult.RUNNING;
        }
        release(client);
        return fail("The jump command was not accepted inside the launch window.");
    }

    static boolean isTakeoffConfirmed(boolean onGround, double verticalDisplacement,
                                      double verticalVelocity) {
        return isTakeoffConfirmed(onGround, verticalDisplacement, verticalVelocity, false);
    }

    static boolean isTakeoffConfirmed(boolean onGround, double verticalDisplacement,
                                      double verticalVelocity, boolean upwardCollision) {
        return !onGround && (verticalDisplacement > 0.01 || verticalVelocity > 0.01
                || upwardCollision && verticalDisplacement >= -0.001);
    }

    TakeoffDecision takeoffDecision(ParkourState observed, ControlFrame planned, int controlIndex) {
        if (stableJumpRollout(observed, planned, controlIndex)) return TakeoffDecision.JUMP_NOW;

        ParkourState probe = observed;
        ControlFrame approach = new ControlFrame(1, planned.strafe(), planned.sprint(), false,
                false, planned.desiredYaw(), ControlPhase.RUN_UP, FrameGuard.GROUNDED);
        for (int tick = 0; tick < MAX_TAKEOFF_LOOKAHEAD_TICKS; tick++) {
            try { probe = physics.tick(world, probe, boundedInput(probe, approach)); }
            catch (RuntimeException exception) { return TakeoffDecision.ABORT; }
            if (!probe.onGround() || plan.launchLane().distanceBeforeEdge(probe.feetPosition())
                    < plan.launchLane().triggerMinimum() - 0.02) return TakeoffDecision.ABORT;
            boolean supported = SupportResolver.overlapArea(probe.boundingBox(), probe.feetPosition().y,
                    List.of(plan.launchLane().takeoffSurface())) > 1.0E-4;
            if (stableJumpRollout(probe, planned, controlIndex)) {
                return supported || maximumReachLane()
                        ? TakeoffDecision.APPROACH : TakeoffDecision.ABORT;
            }
            // Ordinary and obstacle lanes may never deliberately advance into a supportless
            // coyote state. Only a true maximum platform gap is allowed to search that exact
            // retained-ground transition.
            if (!supported && !maximumReachLane()) return TakeoffDecision.ABORT;
        }
        return TakeoffDecision.ABORT;
    }

    private boolean stableJumpRollout(ParkourState state, ControlFrame planned, int controlIndex) {
        if (!state.onGround() || planned.sprint() && !state.sprinting()) return false;
        if (plan.launchLane().distanceBeforeEdge(state.feetPosition())
                < plan.launchLane().triggerMinimum() - 0.02) return false;
        return recoveryScore(state, planned, controlIndex, 0).stableTarget();
    }

    private boolean maximumReachLane() {
        if (plan.launchLane() == null) return false;
        double gap = plan.landingRegion().stream().mapToDouble(surface -> edgeDistance(
                plan.launchLane().takeoffSurface().footprint(), surface.footprint()))
                .min().orElse(Double.MAX_VALUE);
        return gap >= 3.75;
    }

    private double edgeDistance(net.minecraft.util.math.Box first, net.minecraft.util.math.Box second) {
        double dx = Math.max(0, Math.max(first.minX - second.maxX, second.minX - first.maxX));
        double dz = Math.max(0, Math.max(first.minZ - second.maxZ, second.minZ - first.maxZ));
        return Math.hypot(dx, dz);
    }

    private StepTickResult tickLanding(MinecraftClient client, PlayerEntity player) {
        LandingStabilityTracker.State state = landingTracker.observe(player.getBoundingBox(), player.getPos(),
                player.getVelocity(), player.isOnGround(), true);
        if (state == LandingStabilityTracker.State.FAILED) return fail(landingTracker.reason());
        if (state == LandingStabilityTracker.State.STABLE) {
            release(client);
            return StepTickResult.COMPLETE;
        }

        ControlFrame selected = selectLandingControl(player);
        boolean allowSneak = isSneakAllowed(selected.phase(), player.isOnGround(),
                targetSupported(player), selected.sneak()) && consecutiveSneakTicks < MAX_SNEAK_TICKS;
        if (allowSneak) consecutiveSneakTicks++;
        else consecutiveSneakTicks = 0;
        ControlFrame guarded = new ControlFrame(selected.forward(), selected.strafe(), false, false,
                allowSneak, selected.desiredYaw(), selected.phase(), FrameGuard.TARGET_GROUNDED);
        applyFrame(client, player, guarded, true);
        return StepTickResult.RUNNING;
    }

    private ControlFrame selectLandingControl(PlayerEntity player) {
        float yaw = player.getYaw();
        if (player.getVelocity().horizontalLength() <= LandingStabilityTracker.MAX_FINAL_SPEED) {
            return new ControlFrame(0, 0, false, false, false, yaw,
                    ControlPhase.SETTLING, FrameGuard.TARGET_GROUNDED);
        }
        List<ControlFrame> noSneak = List.of(
                landingFrame(0, 0, false, yaw), landingFrame(-1, 0, false, yaw),
                landingFrame(0, -1, false, yaw), landingFrame(0, 1, false, yaw));
        List<LandingOutcome> outcomes = new ArrayList<>();
        for (int index = 0; index < noSneak.size(); index++) {
            outcomes.add(projectLanding(player, noSneak.get(index), index));
        }
        LandingOutcome bestNoSneak = outcomes.stream().max(Comparator
                .comparingInt(LandingOutcome::supportedTicks)
                .thenComparing((LandingOutcome outcome) -> -outcome.finalSpeed())
                .thenComparing((LandingOutcome outcome) -> -outcome.preference())).orElseThrow();
        if (bestNoSneak.supportedTicks == 6) return bestNoSneak.frame;

        ControlFrame sneak = landingFrame(0, 0, true, yaw);
        LandingOutcome sneakOutcome = projectLanding(player, sneak, 4);
        return sneakOutcome.supportedTicks == 6 && bestNoSneak.supportedTicks < 6
                ? sneak : bestNoSneak.frame;
    }

    private LandingOutcome projectLanding(PlayerEntity player, ControlFrame frame, int preference) {
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        ParkourState state = ParkourState.capture(snapshot);
        int supported = 0;
        for (int tick = 0; tick < 6; tick++) {
            state = physics.tick(world, state, input(frame));
            if (!SupportResolver.targetSupported(state.boundingBox(), state.feetPosition(), state.onGround(),
                    plan.landingRegion())) break;
            supported++;
        }
        return new LandingOutcome(frame, supported, state.velocity().horizontalLength(), preference);
    }

    private ControlFrame landingFrame(float forward, float strafe, boolean sneak, float yaw) {
        ControlPhase phase = forward == 0 && strafe == 0 && !sneak
                ? ControlPhase.SETTLING : ControlPhase.LANDED_BRAKING;
        return new ControlFrame(forward, strafe, false, false, sneak, yaw,
                phase, FrameGuard.TARGET_GROUNDED);
    }

    /**
     * Keeps the validated control sequence authoritative inside a real-state deadband. Once
     * outside it, every alternative is evaluated against the remaining planned suffix. This
     * avoids the old one-tick controller replacing a valid neo/long-jump trajectory with a
     * locally attractive correction that could not land.
     */
    private ControlFrame runtimeAirborneControl(ParkourState observed, ControlFrame planned,
                                                int controlIndex) {
        TrajectorySample expected = expectedSample(controlIndex);
        ControlFrame nominal = airborneFrame(planned.forward(), planned.strafe(), planned.sprint(),
                planned.desiredYaw());
        boolean obstacleThreat = observed.horizontalCollision()
                || bodyObstacleClearance(observed) < OBSTACLE_RECOVERY_CLEARANCE;
        if (!requiresAirborneRecovery(observed, expected) && !obstacleThreat) {
            airborneMismatchTicks = 0;
            return nominal;
        }
        if (obstacleThreat) {
            airborneMismatchTicks = 0;
            return selectAirborneControl(observed, planned, controlIndex, true);
        }
        airborneMismatchTicks++;
        if (airborneMismatchTicks < 2 && !requiresImmediateAirborneRecovery(observed, expected)) {
            return nominal;
        }
        return selectAirborneControl(observed, planned, controlIndex);
    }

    ControlFrame selectAirborneControl(ParkourState observed, ControlFrame planned, int controlIndex) {
        return selectAirborneControl(observed, planned, controlIndex, false);
    }

    private ControlFrame selectAirborneControl(ParkourState observed, ControlFrame planned,
                                               int controlIndex, boolean force) {
        TrajectorySample expected = plan.predictedTrajectory().get(Math.min(
                Math.max(0, controlIndex), plan.predictedTrajectory().size() - 1));
        ControlFrame nominal = airborneFrame(planned.forward(), planned.strafe(), planned.sprint(),
                planned.desiredYaw());
        if (!force && !requiresAirborneRecovery(observed, expected)) return nominal;

        List<ControlFrame> choices = List.of(nominal,
                airborneFrame(0, 0, false, nominal.desiredYaw()),
                airborneFrame(-1, 0, false, nominal.desiredYaw()),
                airborneFrame(nominal.forward(), -1, nominal.sprint(), nominal.desiredYaw()),
                airborneFrame(nominal.forward(), 1, nominal.sprint(), nominal.desiredYaw()));
        RecoveryOutcome plannedOutcome = force
                ? twoTickRecoveryScore(observed, choices.getFirst(), controlIndex, 0)
                : recoveryScore(observed, choices.getFirst(), controlIndex, 0);
        ControlFrame best = choices.getFirst();
        double bestScore = plannedOutcome.score();
        for (int index = 1; index < choices.size(); index++) {
            ControlFrame choice = choices.get(index);
            RecoveryOutcome outcome = force
                    ? twoTickRecoveryScore(observed, choice, controlIndex, index)
                    : recoveryScore(observed, choice, controlIndex, index);
            if (outcome.score() < bestScore) { bestScore = outcome.score(); best = choice; }
        }
        return best;
    }

    /**
     * Tight obstacle routes need a coordinated correction, not one alternate frame followed by
     * the unchanged suffix. Evaluate one additional command and apply only the first result;
     * this remains a small receding-horizon controller rather than an open-loop rewrite.
     */
    private RecoveryOutcome twoTickRecoveryScore(ParkourState start, ControlFrame first,
                                                   int controlIndex, int preference) {
        ParkourState afterFirst;
        try { afterFirst = physics.tick(world, start, boundedInput(start, first)); }
        catch (RuntimeException exception) { return new RecoveryOutcome(Double.MAX_VALUE, false); }
        if (afterFirst.onGround()) return recoveryScore(start, first, controlIndex, preference);

        ControlFrame nextPlanned = nextAirborneFrame(controlIndex + 1);
        List<ControlFrame> secondChoices = List.of(nextPlanned,
                airborneFrame(0, 0, false, nextPlanned.desiredYaw()),
                airborneFrame(-1, 0, false, nextPlanned.desiredYaw()),
                airborneFrame(nextPlanned.forward(), -1, nextPlanned.sprint(), nextPlanned.desiredYaw()),
                airborneFrame(nextPlanned.forward(), 1, nextPlanned.sprint(), nextPlanned.desiredYaw()));
        RecoveryOutcome best = new RecoveryOutcome(Double.MAX_VALUE, false);
        for (int index = 0; index < secondChoices.size(); index++) {
            RecoveryOutcome outcome = recoveryScore(afterFirst, secondChoices.get(index),
                    controlIndex + 1, preference * 10 + index);
            outcome = new RecoveryOutcome(outcome.score() + obstacleRisk(afterFirst),
                    outcome.stableTarget());
            if (outcome.score() < best.score()) best = outcome;
        }
        return best;
    }

    private ControlFrame nextAirborneFrame(int index) {
        for (int cursor = Math.max(0, index); cursor < plan.controlFrames().size(); cursor++) {
            ControlFrame frame = plan.controlFrames().get(cursor);
            if (frame.phase() == ControlPhase.AIRBORNE) {
                return airborneFrame(frame.forward(), frame.strafe(), frame.sprint(), frame.desiredYaw());
            }
        }
        return airborneFrame(0, 0, false, plan.launchEnvelope().desiredYaw());
    }

    private RecoveryOutcome recoveryScore(ParkourState start, ControlFrame first,
                                          int controlIndex, int preference) {
        ParkourState state;
        try { state = physics.tick(world, start, boundedInput(start, first)); }
        catch (RuntimeException exception) { return new RecoveryOutcome(Double.MAX_VALUE, false); }
        int cursor = Math.max(0, controlIndex + 1);
        int groundedTargetTicks = 0;
        int slowTargetTicks = 0;
        double obstacleRisk = obstacleRisk(state);
        double score = state.feetPosition().squaredDistanceTo(expectedSample(cursor).feetPosition()) * 0.25;
        for (int tick = 0; tick < RECOVERY_HORIZON_TICKS; tick++) {
            boolean target = SupportResolver.targetSupported(state.boundingBox(), state.feetPosition(),
                    state.onGround(), plan.landingRegion());
            if (state.onGround()) {
                if (!target) return new RecoveryOutcome(1_000_000
                        + SupportResolver.distanceToRegion(state.feetPosition(), plan.landingRegion()) * 1_000
                        + preference, false);
                groundedTargetTicks++;
                if (state.velocity().horizontalLength() <= LandingStabilityTracker.MAX_FINAL_SPEED) {
                    slowTargetTicks++;
                } else slowTargetTicks = 0;
                if (groundedTargetTicks >= LandingStabilityTracker.REQUIRED_GROUNDED_TICKS
                        && slowTargetTicks >= LandingStabilityTracker.REQUIRED_SETTLE_TICKS) {
                    double margin = SupportResolver.edgeMargin(state.boundingBox(), state.feetPosition().y,
                            plan.landingRegion());
                    return new RecoveryOutcome(-1_000_000 - margin * 1_000
                            + state.velocity().horizontalLength() * 100 + preference + obstacleRisk, true);
                }
            }

            ControlFrame continuation;
            if (!state.onGround()) {
                while (cursor < plan.controlFrames().size()
                        && plan.controlFrames().get(cursor).phase() != ControlPhase.AIRBORNE) cursor++;
                if (cursor < plan.controlFrames().size()) {
                    ControlFrame suffix = plan.controlFrames().get(cursor++);
                    continuation = airborneFrame(suffix.forward(), suffix.strafe(), suffix.sprint(),
                            suffix.desiredYaw());
                } else continuation = airborneFrame(0, 0, false, plan.launchEnvelope().desiredYaw());
            } else {
                while (cursor < plan.controlFrames().size()
                        && !plan.controlFrames().get(cursor).phase().isLandingPhase()) cursor++;
                if (cursor < plan.controlFrames().size()) continuation = plan.controlFrames().get(cursor++);
                else continuation = projectedStoppingFrame(state);
            }
            try { state = physics.tick(world, state, boundedInput(state, continuation)); }
            catch (RuntimeException exception) { return new RecoveryOutcome(Double.MAX_VALUE, false); }
            obstacleRisk += obstacleRisk(state);
            if (state.feetPosition().y < plan.settleAnchor().y - 4) {
                return new RecoveryOutcome(2_000_000 + preference, false);
            }
        }
        boolean target = SupportResolver.targetSupported(state.boundingBox(), state.feetPosition(),
                state.onGround(), plan.landingRegion());
        if (state.onGround() && !target) score += 1_000_000;
        else if (target) {
            score -= 100_000;
            score -= SupportResolver.edgeMargin(state.boundingBox(), state.feetPosition().y,
                    plan.landingRegion()) * 1_000;
            score += state.velocity().horizontalLength() * 80;
        } else score += SupportResolver.distanceToRegion(state.feetPosition(), plan.landingRegion()) * 500;
        score += horizontalDistance(state.feetPosition(), plan.settleAnchor()) * 20 + preference * 0.01;
        return new RecoveryOutcome(score + obstacleRisk, false);
    }

    private double obstacleRisk(ParkourState state) {
        double clearance = bodyObstacleClearance(state);
        return (state.horizontalCollision() ? 5_000 : 0)
                + Math.max(0, 0.22 - clearance) * 2_000;
    }

    private double bodyObstacleClearance(ParkourState state) {
        double minimum = 10;
        for (var obstacle : world.collisionBoxes(state.boundingBox().expand(0.35))) {
            if (obstacle.maxY <= state.feetPosition().y + 0.05
                    || obstacle.minY >= state.boundingBox().maxY - 0.05) continue;
            double dx = Math.max(0, Math.max(obstacle.minX - state.boundingBox().maxX,
                    state.boundingBox().minX - obstacle.maxX));
            double dz = Math.max(0, Math.max(obstacle.minZ - state.boundingBox().maxZ,
                    state.boundingBox().minZ - obstacle.maxZ));
            minimum = Math.min(minimum, Math.hypot(dx, dz));
        }
        return minimum;
    }

    private ControlFrame projectedStoppingFrame(ParkourState state) {
        if (state.velocity().horizontalLength() <= LandingStabilityTracker.MAX_FINAL_SPEED) {
            return landingFrame(0, 0, false, state.yaw());
        }
        Vec3d heading = plan.launchEnvelope().routeHeading();
        Vec3d side = ControlInput.strafeDirection(heading);
        Vec3d opposite = new Vec3d(state.velocity().x, 0, state.velocity().z).normalize().multiply(-1);
        return landingFrame((float) MathHelper.clamp(opposite.dotProduct(heading), -1, 1),
                (float) MathHelper.clamp(opposite.dotProduct(side), -1, 1), false, state.yaw());
    }

    private ControlInput boundedInput(ParkourState state, ControlFrame frame) {
        float delta = MathHelper.wrapDegrees(frame.desiredYaw() - state.yaw());
        float yaw = state.yaw() + MathHelper.clamp(delta, -MAX_YAW_CHANGE, MAX_YAW_CHANGE);
        return new ControlInput(frame.forward(), frame.strafe(), frame.sprint(), frame.jump(),
                frame.sneak() && state.onGround(), yaw);
    }

    private TrajectorySample expectedSample(int index) {
        return plan.predictedTrajectory().get(Math.min(Math.max(0, index),
                plan.predictedTrajectory().size() - 1));
    }

    static boolean requiresAirborneRecovery(ParkourState observed, TrajectorySample expected) {
        Vec3d positionError = observed.feetPosition().subtract(expected.feetPosition());
        Vec3d velocityError = observed.velocity().subtract(expected.velocity());
        return Math.hypot(positionError.x, positionError.z) > AIRBORNE_POSITION_DEADBAND
                || Math.hypot(velocityError.x, velocityError.z) > AIRBORNE_VELOCITY_DEADBAND
                || Math.abs(positionError.y) > AIRBORNE_VERTICAL_POSITION_DEADBAND
                || Math.abs(velocityError.y) > AIRBORNE_VERTICAL_VELOCITY_DEADBAND;
    }

    private static boolean requiresImmediateAirborneRecovery(ParkourState observed,
                                                              TrajectorySample expected) {
        Vec3d positionError = observed.feetPosition().subtract(expected.feetPosition());
        Vec3d velocityError = observed.velocity().subtract(expected.velocity());
        return Math.hypot(positionError.x, positionError.z) > AIRBORNE_POSITION_DEADBAND * 2
                || Math.hypot(velocityError.x, velocityError.z) > AIRBORNE_VELOCITY_DEADBAND * 2
                || Math.abs(positionError.y) > AIRBORNE_VERTICAL_POSITION_DEADBAND * 2
                || Math.abs(velocityError.y) > AIRBORNE_VERTICAL_VELOCITY_DEADBAND * 2;
    }

    private ControlFrame airborneFrame(float forward, float strafe, boolean sprint, float yaw) {
        return new ControlFrame(forward, strafe, sprint, false, false, yaw,
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE);
    }

    private ControlInput input(ControlFrame frame) {
        return new ControlInput(frame.forward(), frame.strafe(), frame.sprint(), frame.jump(),
                frame.sneak(), frame.desiredYaw());
    }

    private ControlFrame lastAirborneFrame() {
        for (int index = Math.min(frameIndex, plan.controlFrames().size() - 1); index >= 0; index--) {
            ControlFrame frame = plan.controlFrames().get(index);
            if (frame.phase() == ControlPhase.AIRBORNE || frame.phase() == ControlPhase.TAKEOFF) {
                return new ControlFrame(frame.forward(), frame.strafe(), frame.sprint(), false, false,
                        frame.desiredYaw(), ControlPhase.AIRBORNE, FrameGuard.AIRBORNE);
            }
        }
        return new ControlFrame(0, 0, false, false, false, plan.launchEnvelope().desiredYaw(),
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE);
    }

    private void applyFrame(MinecraftClient client, PlayerEntity player, ControlFrame frame, boolean targetSupported) {
        smoothYaw(player, frame.desiredYaw());
        boolean sneak = isSneakAllowed(frame.phase(), player.isOnGround(), targetSupported, frame.sneak());
        setMovement(client, frame.forward(), frame.strafe(), frame.sprint(), frame.jump(), sneak);
    }

    private boolean targetSupported(PlayerEntity player) {
        return SupportResolver.targetSupported(player.getBoundingBox(), player.getPos(), player.isOnGround(),
                plan.landingRegion());
    }
    private void smoothYaw(PlayerEntity player, float desiredYaw) {
        float delta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(delta, -MAX_YAW_CHANGE, MAX_YAW_CHANGE));
    }
    private void setMovement(MinecraftClient client, float forward, float strafe,
                             boolean sprint, boolean jump, boolean sneak) {
        client.options.forwardKey.setPressed(forward > 0.01);
        client.options.backKey.setPressed(forward < -0.01);
        client.options.leftKey.setPressed(strafe > 0.01);
        client.options.rightKey.setPressed(strafe < -0.01);
        client.options.sprintKey.setPressed(sprint);
        client.options.jumpKey.setPressed(jump);
        client.options.sneakKey.setPressed(sneak);
    }
    private void release(MinecraftClient client) { setMovement(client, 0, 0, false, false, false); }
    private StepTickResult fail(String message) { reason = message; return StepTickResult.FAILED; }
    private StepTickResult replan(String message) { reason = message; return StepTickResult.REPLAN; }
    private Vec3d direction(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(radians), 0, Math.cos(radians));
    }
    private double horizontalDistance(Vec3d a, Vec3d b) { return Math.hypot(a.x - b.x, a.z - b.z); }

    static boolean isSneakAllowed(ControlPhase phase, boolean onGround,
                                  boolean targetSupported, boolean requested) {
        return requested && phase.isLandingPhase() && onGround && targetSupported;
    }
    static boolean isLaunchYawAligned(float yawError) { return Math.abs(yawError) <= 2; }
    @Override
    public void stop(MinecraftClient client) {
        if (client != null && client.options != null) release(client);
        plan = null;
        landingTracker = null;
    }
    @Override public String reason() { return reason.isBlank() ? StepController.super.reason() : reason; }

    private record LandingOutcome(ControlFrame frame, int supportedTicks,
                                  double finalSpeed, int preference) {}
    private record RecoveryOutcome(double score, boolean stableTarget) {}
    enum TakeoffDecision { JUMP_NOW, APPROACH, ABORT }
}

package com.ariesninja.skulkpk.client.core.execution;

import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import com.ariesninja.skulkpk.client.core.physics.CollisionContact;
import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import com.ariesninja.skulkpk.client.core.physics.ParkourPhysics;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import com.ariesninja.skulkpk.client.core.physics.PhysicsStep;
import com.ariesninja.skulkpk.client.core.planning.ContactEvent;
import com.ariesninja.skulkpk.client.core.planning.ContactRequirement;
import com.ariesninja.skulkpk.client.core.planning.ControlFrame;
import com.ariesninja.skulkpk.client.core.planning.ControlPhase;
import com.ariesninja.skulkpk.client.core.planning.FrameGuard;
import com.ariesninja.skulkpk.client.core.planning.LandingStabilityTracker;
import com.ariesninja.skulkpk.client.core.planning.LadderColumn;
import com.ariesninja.skulkpk.client.core.planning.LadderContinuation;
import com.ariesninja.skulkpk.client.core.planning.LaunchEnvelope;
import com.ariesninja.skulkpk.client.core.planning.MovementPlan;
import com.ariesninja.skulkpk.client.core.planning.RouteMode;
import com.ariesninja.skulkpk.client.core.planning.SupportKind;
import com.ariesninja.skulkpk.client.core.planning.SupportResolver;
import com.ariesninja.skulkpk.client.core.planning.TrajectorySample;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Executes one acknowledged command transition at a time against the planned state tube. */
public final class TrajectoryStepController implements StepController {
    private static final double AIRBORNE_POSITION_DEADBAND = 0.18;
    private static final double AIRBORNE_VELOCITY_DEADBAND = 0.10;
    private static final double AIRBORNE_VERTICAL_POSITION_DEADBAND = 0.16;
    private static final double AIRBORNE_VERTICAL_VELOCITY_DEADBAND = 0.10;
    private static final double PRECOMMIT_POSITION_LIMIT = 0.25;
    private static final double PRECOMMIT_VELOCITY_LIMIT = 0.12;
    private static final float MAX_YAW_CHANGE = 12;
    private static final int MAX_STAGING_TICKS = 40;
    private static final int STALL_TICKS = 6;
    private static final int STAGING_OBSERVATIONS = 2;
    private static final int TAKEOFF_LATCH_EPOCHS = 2;
    private static final int MPC_HORIZON = 4;

    enum ControllerPhase { STAGING, APPROACH_TRACKING, TAKEOFF_COMMITTED, CONTACT_TRANSIT, LANDING }
    enum TakeoffDecision { JUMP_NOW, APPROACH, ABORT }

    private final ParkourPhysics physics;
    private final MovementIO movementIO;
    private MovementPlan plan;
    private WorldView world;
    private LandingStabilityTracker landingTracker;
    private ControllerPhase phase;
    private PendingCommand pending;
    private LaunchEnvelope launchEnvelope;
    private List<TrajectorySample> referenceTrajectory;
    private List<LadderColumn> ladderColumns = List.of();
    private String stagingDiagnostic = "No nearby candidate tested.";
    private int controlIndex;
    private int jumpIndex;
    private int stagingTicks;
    private int stagingObservations;
    private int stalledTicks;
    private int takeoffLatchEpochs;
    private int airborneExtraTicks;
    private int ladderRecoveryTicks;
    private boolean approachValidated;
    private double bestStagingScore;
    private double takeoffFeetY;
    private ControlFrame takeoffFrame;
    private final Set<String> satisfiedContacts = new LinkedHashSet<>();
    private String reason = "";

    public TrajectoryStepController() { this(new MinecraftMovementIO(), new ParkourPhysics()); }
    public TrajectoryStepController(MovementIO movementIO) {
        this(movementIO, new ParkourPhysics());
    }
    TrajectoryStepController(MovementIO movementIO, ParkourPhysics physics) {
        this.movementIO = java.util.Objects.requireNonNull(movementIO);
        this.physics = java.util.Objects.requireNonNull(physics);
    }

    @Override
    public void start(MovementPlan plan, WorldView world) {
        this.plan = java.util.Objects.requireNonNull(plan);
        launchEnvelope = plan.launchEnvelope();
        referenceTrajectory = plan.predictedTrajectory();
        stagingDiagnostic = "No nearby candidate tested.";
        this.world = java.util.Objects.requireNonNull(world);
        ladderColumns = plan.routeMode() == RouteMode.LADDER_ASSIST
                ? LadderColumn.discover(world, plan.fingerprintRegion().contract(0.01)) : List.of();
        landingTracker = new LandingStabilityTracker(plan.landingRegion());
        jumpIndex = plan.approachPlan().commitIndex();
        if (jumpIndex < 0 || jumpIndex >= plan.controlFrames().size()
                || !plan.controlFrames().get(jumpIndex).jump()) {
            // Compatibility fallback for plans produced before the explicit final-commit
            // contract. New plans may contain an earlier preparatory jump.
            jumpIndex = 0;
            while (jumpIndex < plan.controlFrames().size()
                    && !plan.controlFrames().get(jumpIndex).jump()) jumpIndex++;
        }
        controlIndex = 0;
        phase = plan.immediateLaunch() ? ControllerPhase.APPROACH_TRACKING : ControllerPhase.STAGING;
        pending = null;
        stagingTicks = 0;
        stagingObservations = 0;
        stalledTicks = 0;
        takeoffLatchEpochs = 0;
        airborneExtraTicks = 0;
        ladderRecoveryTicks = 0;
        approachValidated = false;
        bestStagingScore = Double.MAX_VALUE;
        takeoffFeetY = 0;
        takeoffFrame = null;
        satisfiedContacts.clear();
        reason = "";
    }

    @Override
    public StepTickResult tick(MinecraftClient client) {
        if (plan == null || world == null) return fail("The active movement plan is unavailable.");
        if (client != null && (client.world == null || world.identityToken() != client.world)) {
            return fail("The active world changed after planning.");
        }
        if (world.fingerprint(plan.fingerprintRegion()) != plan.worldFingerprint()) {
            return fail("World geometry changed after planning.");
        }

        MovementObservation observation;
        try { observation = movementIO.observe(client); }
        catch (RuntimeException exception) { return fail("The observed player state is unavailable."); }
        ParkourState state = observation.state();
        boolean targetSupported = targetSupported(state);
        if (pending != null) {
            if (observation.observedCommandEpoch() < pending.epoch) return StepTickResult.RUNNING;
            StepTickResult acknowledged = acknowledge(state, targetSupported, client);
            if (acknowledged != null) return acknowledged;
        }
        if (targetSupported(state) && state.onGround()) phase = ControllerPhase.LANDING;
        return switch (phase) {
            case STAGING -> tickStaging(state, client);
            case APPROACH_TRACKING -> tickApproach(state, client);
            case TAKEOFF_COMMITTED -> tickCommitted(state, client);
            case CONTACT_TRANSIT -> tickAirborne(state, client);
            case LANDING -> tickLanding(state, client);
        };
    }

    private StepTickResult acknowledge(ParkourState observed, boolean targetSupported,
                                       MinecraftClient client) {
        PendingCommand command = pending;
        pending = null;
        matchContacts(command, observed);
        if (isAvoidanceRoute() && (observed.horizontalCollision()
                || command.expected.collisions().contacts().stream()
                    .anyMatch(contact -> !contact.support() && !contact.face().headContact()))) {
            movementIO.release(client);
            String features = command.expected.collisions().contacts().stream()
                    .filter(contact -> !contact.support())
                    .map(contact -> contact.featureId() + ":" + contact.face())
                    .collect(java.util.stream.Collectors.joining(","));
            return fail(String.format("Unplanned obstacle contact during %s at command epoch %d "
                            + "(plan index %d, observedHorizontal=%s, expected=%s).",
                    command.frame.phase(), command.epoch, command.planIndex,
                    observed.horizontalCollision(), features.isEmpty() ? "none" : features));
        }
        if (command.jump) {
            double displacement = observed.feetPosition().y - takeoffFeetY;
            boolean upwardContact = observed.verticalCollision()
                    || command.expected.collisions().hasHeadContact();
            if (isTakeoffConfirmed(observed.onGround(), displacement,
                    observed.velocity().y, upwardContact)) {
                phase = ControllerPhase.CONTACT_TRANSIT;
                controlIndex = Math.max(controlIndex, command.planIndex + 1);
                takeoffLatchEpochs = 0;
                return null;
            }
            if (!observed.onGround()) {
                movementIO.release(client);
                return fail("Missed takeoff: support was lost without a jump impulse.");
            }
            if (takeoffLatchEpochs >= TAKEOFF_LATCH_EPOCHS) {
                movementIO.release(client);
                return fail("The jump command was not accepted within two command epochs.");
            }
            takeoffLatchEpochs++;
            return issue(observed, takeoffFrame, jumpIndex, false, client);
        }
        if (command.advancePlan) {
            controlIndex = Math.max(controlIndex, command.planIndex + 1);
            if (phase == ControllerPhase.APPROACH_TRACKING) {
                double positionResidual = horizontalDistance(observed.feetPosition(),
                        command.expected.state().feetPosition());
                double velocityResidual = observed.velocity().subtract(
                        command.expected.state().velocity()).horizontalLength();
                if (positionResidual > PRECOMMIT_POSITION_LIMIT
                        || velocityResidual > PRECOMMIT_VELOCITY_LIMIT) {
                    movementIO.release(client);
                    return replan(String.format("Approach transition diverged before commitment "
                                    + "(position %.3f, velocity %.3f, epoch %d).",
                            positionResidual, velocityResidual, command.epoch));
                }
            }
        }
        if (phase == ControllerPhase.CONTACT_TRANSIT && targetSupported) {
            phase = ControllerPhase.LANDING;
        }
        return null;
    }

    private StepTickResult tickStaging(ParkourState state, MinecraftClient client) {
        if (!state.onGround() || support(state) != SupportKind.TAKEOFF) {
            movementIO.release(client);
            return fail("Staging lost the supported approach region; automatic replanning was refused.");
        }
        stagingTicks++;
        Box staging = plan.approachPlan().stagingRegion();
        double distance = distanceToBox(state.feetPosition(), staging);
        float yawError = Math.abs(MathHelper.wrapDegrees(
                launchEnvelope.desiredYaw() - state.yaw()));
        double progressScore = distance * 20
                + state.velocity().horizontalLength() * 4
                + yawError * 0.02;
        if (progressScore < bestStagingScore - 0.02) {
            bestStagingScore = progressScore;
            stalledTicks = 0;
        } else if (distance > 0.025 || yawError > launchEnvelope.yawTolerance()) {
            stalledTicks++;
        }
        if (stalledTicks >= STALL_TICKS) {
            movementIO.release(client);
            return replan(String.format("Staging made no useful progress for six controlled "
                            + "observations (feet %.3f/%.3f, region %.3f..%.3f/%.3f..%.3f, "
                            + "distance %.3f, speed %.3f, yaw %.2f).",
                    state.feetPosition().x, state.feetPosition().z,
                    staging.minX, staging.maxX, staging.minZ, staging.maxZ,
                    distance, state.velocity().horizontalLength(), yawError));
        }
        if (stagingTicks > MAX_STAGING_TICKS) {
            movementIO.release(client);
            return replan("The staging region was not reached within 40 ticks. " + stagingDiagnostic);
        }
        boolean outcomeValidatedEntry = distance <= 0.10
                && state.velocity().horizontalLength() <= 0.06
                && yawError <= launchEnvelope.yawTolerance()
                && stagingStateSupportsPlan(state);
        if (outcomeValidatedEntry) {
            if (++stagingObservations >= STAGING_OBSERVATIONS) {
                phase = ControllerPhase.APPROACH_TRACKING;
                controlIndex = 0;
                approachValidated = true;
                return tickApproach(state, client);
            }
            return issue(state, neutral(ControlPhase.ALIGNING,
                    launchEnvelope.desiredYaw()), -1, false, client);
        }
        stagingObservations = 0;
        if (yawError > 6) {
            return issue(state, neutral(ControlPhase.ALIGNING,
                    launchEnvelope.desiredYaw()), -1, false, client);
        }
        return issue(state, stagingMpc(state, staging), -1, false, client);
    }

    private StepTickResult tickApproach(ParkourState state, MinecraftClient client) {
        // Planning releases movement while it runs. A current-state seed's captured velocity
        // may have decayed before delivery, so even a no-positioning plan needs a fresh replay.
        if (!approachValidated) {
            if (stagingStateSupportsPlan(state)) approachValidated = true;
            else {
                phase = ControllerPhase.STAGING;
                return tickStaging(state, client);
            }
        }
        if (controlIndex >= plan.controlFrames().size() || jumpIndex >= plan.controlFrames().size()) {
            movementIO.release(client);
            return fail("The plan contains no executable jump transition.");
        }
        if (controlIndex < jumpIndex) {
            ControlFrame approach = plan.controlFrames().get(controlIndex);
            boolean ladder = !state.onGround() && ladderAttached(state);
            if (!approach.guard().permits(state.onGround(), targetSupported(state), ladder)) {
                movementIO.release(client);
                return replan("The observed approach phase no longer matches its guarded state tube.");
            }
            if (state.onGround() && support(state) != SupportKind.TAKEOFF
                    && !retainedGroundCommit(state, controlIndex)) {
                movementIO.release(client);
                return fail("The approach lost support before takeoff commitment; automatic replanning was refused.");
            }
            TrajectorySample expected = expectedSample(controlIndex);
            if (state.feetPosition().distanceTo(expected.feetPosition()) > PRECOMMIT_POSITION_LIMIT
                    || state.velocity().subtract(expected.velocity()).length() > PRECOMMIT_VELOCITY_LIMIT) {
                movementIO.release(client);
                return replan("The observed approach state left its validated state tube.");
            }
            ParkourState next;
            try { next = physics.tickState(world, state, input(state, approach)); }
            catch (RuntimeException exception) {
                movementIO.release(client);
                return replan("The next approach command left the captured physics region.");
            }
            int nextIndex = controlIndex + 1;
            ControlFrame nextFrame = nextIndex < plan.controlFrames().size()
                    ? plan.controlFrames().get(nextIndex) : null;
            boolean nextLadder = !next.onGround() && ladderAttached(next);
            boolean phaseMatches = nextFrame == null || nextFrame.guard().permits(next.onGround(),
                    targetSupported(next), nextLadder);
            boolean groundSafe = !next.onGround() || support(next) == SupportKind.TAKEOFF
                    || retainedGroundCommit(next, nextIndex);
            if (!phaseMatches || !groundSafe) {
                // Validate departure and its following guarded state before spending the last
                // supported command. This applies equally to a preparatory takeoff and the
                // final zero-ground-tick commit.
                movementIO.release(client);
                return replan("Stopped before the edge: the next approach command would leave its validated state tube.");
            }
            return issue(state, approach, controlIndex, true, client);
        }
        ControlFrame jump = plan.controlFrames().get(jumpIndex);
        TakeoffDecision decision = takeoffDecision(state, jump, jumpIndex);
        if (decision == TakeoffDecision.ABORT) {
            movementIO.release(client);
            return replan(takeoffMissReason(state, jump));
        }
        if (decision == TakeoffDecision.APPROACH) {
            ControlFrame approach = new ControlFrame(1, jump.strafe(), jump.sprint(), false,
                    false, jump.desiredYaw(), ControlPhase.RUN_UP, FrameGuard.GROUNDED);
            return issue(state, approach, jumpIndex, false, client);
        }
        phase = ControllerPhase.TAKEOFF_COMMITTED;
        takeoffFrame = jump;
        takeoffFeetY = state.feetPosition().y;
        takeoffLatchEpochs = 1;
        return issue(state, jump, jumpIndex, false, client);
    }

    private StepTickResult tickCommitted(ParkourState state, MinecraftClient client) {
        if (!state.onGround()) {
            movementIO.release(client);
            return fail("Takeoff became airborne before its command epoch was acknowledged.");
        }
        return StepTickResult.RUNNING;
    }

    private StepTickResult tickAirborne(ParkourState state, MinecraftClient client) {
        if (state.onGround()) {
            if (!targetSupported(state)) {
                LadderColumn exit = ladderColumns.stream().filter(column -> column.supportsExit(state))
                        .findFirst().orElse(null);
                if (exit != null) return issue(state, LadderContinuation.choose(world, physics, state,
                        exit, exit.exit(plan.landingZone())), controlIndex, false, client);
                movementIO.release(client);
                return fail("Wrong support: first grounded contact was outside the connected target.");
            }
            phase = ControllerPhase.LANDING;
            return tickLanding(state, client);
        }
        LadderColumn attached = attachedColumn(state);
        if (attached != null) {
            if (++ladderRecoveryTicks > 120) {
                movementIO.release(client);
                return fail("Ladder attachment did not reach its exit within 120 observations.");
            }
            // Attachment is an observed mode, not a position on the nominal flight clock.
            // A late catch may need a full climb even after the original flight frames end.
            airborneExtraTicks = 0;
            return issue(state, LadderContinuation.choose(world, physics, state, attached,
                    attached.exit(plan.landingZone())), controlIndex, false, client);
        }
        controlIndex = Math.max(controlIndex, matchedAirborneIndex(state));
        int plannedIndex = nextAirborneIndex(controlIndex);
        ControlFrame planned = plannedIndex >= 0 ? plan.controlFrames().get(plannedIndex)
                : airborneFrame(0, 0, false, launchEnvelope.desiredYaw());
        if (plannedIndex < 0) {
            if (++airborneExtraTicks > 12) {
                movementIO.release(client);
                return fail("The airborne route exhausted its recovery horizon.");
            }
            plannedIndex = Math.min(controlIndex, plan.controlFrames().size() - 1);
        }
        return issue(state, selectAirborneControl(state, planned, plannedIndex),
                plannedIndex, true, client);
    }

    private StepTickResult tickLanding(ParkourState state, MinecraftClient client) {
        LandingStabilityTracker.State landing = landingTracker.observe(state.boundingBox(),
                state.feetPosition(), state.velocity(), state.onGround(), true);
        if (landing == LandingStabilityTracker.State.FAILED) {
            movementIO.release(client);
            return fail(landingTracker.reason());
        }
        if (landing == LandingStabilityTracker.State.STABLE) {
            if (!requiredContactsSatisfied()) {
                movementIO.release(client);
                return fail("The landing succeeded without the route's required contact event.");
            }
            movementIO.release(client);
            return StepTickResult.COMPLETE;
        }
        return issue(state, selectLandingControl(state), -1, false, client);
    }

    private StepTickResult issue(ParkourState state, ControlFrame requested, int planIndex,
                                 boolean advancePlan, MinecraftClient client) {
        if (pending != null) return StepTickResult.RUNNING;
        boolean target = targetSupported(state);
        ControlFrame frame = guardedFrame(state, requested, target);
        PhysicsStep expected;
        try { expected = physics.tick(world, state, input(state, frame)); }
        catch (RuntimeException exception) { return fail("Physics rejected the next movement command."); }
        long epoch;
        try { epoch = movementIO.apply(client, frame, target); }
        catch (RuntimeException exception) { return fail("The movement command could not be applied."); }
        pending = new PendingCommand(epoch, frame, expected, planIndex, advancePlan,
                frame.jump() && phase == ControllerPhase.TAKEOFF_COMMITTED);
        return StepTickResult.RUNNING;
    }

    private ControlFrame stagingMpc(ParkourState state, Box staging) {
        float yaw = launchEnvelope.desiredYaw();
        List<ControlFrame> actions = new java.util.ArrayList<>();
        for (float forward : new float[]{-1, 0, 1}) {
            for (float strafe : new float[]{-1, 0, 1}) {
                actions.add(groundFrame(forward, strafe, yaw));
            }
        }
        boolean refining = distanceToBox(state.feetPosition(), staging) < 0.25;
        if (refining) {
            // Binary keys at one fixed yaw form a coarse positioning lattice. Small real
            // camera rotations add physically executable fine-positioning directions.
            for (float delta : new float[]{-6, -3, 3, 6}) {
                for (float forward : new float[]{-1, 0, 1}) {
                    for (float strafe : new float[]{-1, 0, 1}) {
                        if (forward != 0 || strafe != 0) actions.add(groundFrame(forward, strafe, yaw + delta));
                    }
                }
            }
        }
        List<StagingNode> frontier = List.of(new StagingNode(state, actions.get(4), 0));
        for (int depth = 0; depth < (refining ? 6 : MPC_HORIZON); depth++) {
            java.util.Map<StagingKey, StagingNode> next = new LinkedHashMap<>();
            for (StagingNode node : frontier) {
                for (ControlFrame action : actions) {
                    // Refine the first command's heading, then hold it or return to the lane
                    // within this short rollout. Branching all five camera angles at every
                    // depth spent most frame time on redundant yaw chatter. We still re-solve
                    // from the observed state next tick and simulate every retained transition.
                    if (depth > 0 && action.desiredYaw() != yaw
                            && action.desiredYaw() != node.first.desiredYaw()) continue;
                    ParkourState advanced;
                    try { advanced = physics.tickState(world, node.state,
                            input(node.state, action)); }
                    catch (RuntimeException exception) { continue; }
                    if (!advanced.onGround() || support(advanced) != SupportKind.TAKEOFF) continue;
                    ControlFrame first = depth == 0 ? action : node.first;
                    // Running cost prevents receding-horizon procrastination: a terminal-only
                    // score repeatedly chose "wait now, move at the end of the horizon".
                    double score = node.score + stagingScore(advanced, staging);
                    StagingKey key = new StagingKey(Math.round(advanced.feetPosition().x * 100),
                            Math.round(advanced.feetPosition().z * 100),
                            Math.round(advanced.velocity().x * 100), Math.round(advanced.velocity().z * 100));
                    StagingNode existing = next.get(key);
                    if (existing == null || score < existing.score) {
                        next.put(key, new StagingNode(advanced, first, score));
                    }
                }
            }
            frontier = next.values().stream()
                    .sorted(Comparator.comparingDouble(StagingNode::score))
                    .limit(refining ? 96 : 32).toList();
            if (frontier.isEmpty()) return actions.get(4);
        }
        return frontier.stream().min(Comparator.comparingDouble(node -> node.score
                + stagingScore(node.state, staging) * (refining ? 100 : 8))).orElseThrow().first;
    }

    private double stagingScore(ParkourState state, Box staging) {
        // Project friction-only resting position for ranking; all actual transitions are
        // still simulated with shapes/support above. Aim into the region, not its nearest rim.
        Vec3d resting = state.feetPosition().add(state.velocity().multiply(1 / (1 - 0.546)));
        Vec3d center = new Vec3d((staging.minX + staging.maxX) / 2, state.feetPosition().y,
                (staging.minZ + staging.maxZ) / 2);
        return distanceToBox(resting, staging) * 24
                + horizontalDistance(resting, center) * 8
                + distanceToBox(state.feetPosition(), staging) * 2
                + state.velocity().horizontalLength()
                + Math.abs(MathHelper.wrapDegrees(
                    launchEnvelope.desiredYaw() - state.yaw())) * 0.02;
    }

    /**
     * Binary movement keys cannot place the player at every real-valued coordinate in a tiny
     * perturbation box. When a nearby settled state replays the complete immutable plan safely,
     * it is a stronger admission test than endlessly oscillating around the box boundary.
     */
    private boolean stagingStateSupportsPlan(ParkourState initial) {
        List<ParkourState> states = replayStagingRoute(initial, 0);
        if (states == null || jumpIndex >= states.size()) {
            stagingDiagnostic = "The nearby state did not preserve the route's nominal landing/contact.";
            return false;
        }
        ParkourState launch = states.get(jumpIndex);
        boolean insideEnvelope = launchEnvelope.containsPosition(launch.feetPosition())
                && launchEnvelope.containsVelocity(launch.velocity());
        // Rebase only after proving the same immutable controls and the entire contact
        // contract from the actual staging state, including a fresh launch tolerance tube.
        Vec3d heading = launchEnvelope.routeHeading();
        Vec3d side = ControlInput.strafeDirection(heading);
        double positionTolerance = Math.min(0.025, plan.launchEnvelope().maximumPositionError());
        double speedTolerance = Math.min(0.02, plan.launchEnvelope().maximumVelocityError());
        float yawTolerance = Math.min(1.5f, plan.launchEnvelope().yawTolerance());
        boolean strict = plan.routeMode() != RouteMode.DIRECT;
        double[] minimum = {0, 0}, maximum = {0, 0};
        double minimumSpeed = 0, maximumSpeed = 0;
        float validatedYaw = yawTolerance;
        int axisIndex = 0;
        if (!insideEnvelope) {
            for (Vec3d axis : List.of(heading, side)) {
                for (double offset : new double[]{-positionTolerance, positionTolerance}) {
                    if (replayStagingRoute(perturbed(launch, axis.multiply(offset), Vec3d.ZERO, 0), jumpIndex) == null) {
                        stagingDiagnostic = "The rebased position tube failed at offset " + axis.multiply(offset);
                        if (strict) return false;
                    } else if (offset < 0) minimum[axisIndex] = offset;
                    else maximum[axisIndex] = offset;
                }
                axisIndex++;
            }
            for (double offset : new double[]{-speedTolerance, speedTolerance}) {
                if (replayStagingRoute(perturbed(launch, Vec3d.ZERO, heading.multiply(offset), 0), jumpIndex) == null) {
                    stagingDiagnostic = "The rebased speed tube failed at offset " + offset;
                    if (strict) return false;
                } else if (offset < 0) minimumSpeed = offset;
                else maximumSpeed = offset;
            }
            for (float offset : new float[]{-yawTolerance, yawTolerance}) {
                if (replayStagingRoute(perturbed(launch, Vec3d.ZERO, Vec3d.ZERO, offset), jumpIndex) == null) {
                    stagingDiagnostic = "The rebased yaw tube failed at offset " + offset;
                    if (strict) return false;
                    validatedYaw = 0;
                }
            }
            Vec3d feet = launch.feetPosition();
            double speed = launch.velocity().dotProduct(heading);
            launchEnvelope = new LaunchEnvelope(new Box(feet.x - 0.04, feet.y - 0.06, feet.z - 0.04,
                    feet.x + 0.04, feet.y + 0.18, feet.z + 0.04), launch.velocity(), positionTolerance, speedTolerance,
                    launchEnvelope.desiredYaw(), validatedYaw, feet, heading,
                    minimum[0], maximum[0], minimum[1], maximum[1],
                    speed + minimumSpeed, speed + maximumSpeed);
        }
        // Always rebase the reference prefix, including when its launch already fits the old
        // envelope. Otherwise accumulated staging velocity is compared with a stale trajectory.
        java.util.ArrayList<TrajectorySample> samples = new java.util.ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            ParkourState state = states.get(index);
            var contact = SupportResolver.resolve(state.boundingBox(), state.feetPosition(), state.onGround(),
                    plan.landingRegion(), plan.approachPlan().supportRegion(), world);
            samples.add(new TrajectorySample(index, state.feetPosition(), state.velocity(), state.boundingBox(),
                    state.onGround(), state.horizontalCollision(), state.verticalCollision(),
                    plan.predictedTrajectory().get(Math.min(index, plan.predictedTrajectory().size() - 1)).phase(),
                    contact.kind(), contact.targetSupported() ? contact.overlapArea() : 0));
        }
        referenceTrajectory = List.copyOf(samples);
        return true;
    }

    private ParkourState perturbed(ParkourState base, Vec3d position, Vec3d velocity, float yaw) {
        return new ParkourState(base.feetPosition().add(position), base.velocity().add(velocity),
                base.boundingBox().offset(position), base.yaw() + yaw, base.onGround(), base.sprinting(),
                base.jumpUsed(), base.horizontalCollision(), base.verticalCollision(), base.elapsedTicks(),
                base.baseMovementSpeed(), base.jumpStrength(), base.stepHeight(), base.gravity(), base.activeEffects(),
                base.previousSneak(), base.sneakingSpeed(), base.collidedSoftly(),
                base.sprintTapTicks(), base.previousForward(), base.sprintAllowed());
    }

    private List<ParkourState> replayStagingRoute(ParkourState initial, int firstFrame) {
        boolean chainedPlan = plan.controlFrames().subList(0,
                Math.min(jumpIndex, plan.controlFrames().size())).stream()
                .anyMatch(value -> value.phase().isPreparatoryPhase());
        if (!chainedPlan) return replayOrdinaryStagingRoute(initial, firstFrame);
        if (support(initial) != SupportKind.TAKEOFF
                && !(firstFrame == jumpIndex && initial.onGround()
                    && expectedSample(jumpIndex).support() == SupportKind.NONE)
                || world.collisionBoxes(initial.boundingBox()).stream().anyMatch(initial.boundingBox()::intersects))
            return null;
        ParkourState state = initial;
        boolean finalAirborne = firstFrame > jumpIndex && !state.onGround();
        Set<String> required = new LinkedHashSet<>();
        java.util.ArrayList<ParkourState> states = new java.util.ArrayList<>();
        states.add(state);
        for (int tick = firstFrame; tick < plan.controlFrames().size(); tick++) {
            ControlFrame frame = plan.controlFrames().get(tick);
            // A nearby validated staging state may touch the target one tick before the
            // nominal flight cursor. Consuming an airborne command from grounded target
            // support is unsafe; skip it and let the landing controller prove stability.
            if (frame.guard() == FrameGuard.AIRBORNE && targetSupported(state)) continue;
            boolean ladderAttached = !state.onGround() && ladderAttached(state);
            if (tick < jumpIndex
                    && !frame.guard().permits(state.onGround(), targetSupported(state), ladderAttached)) return null;
            LadderColumn column = attachedColumn(state);
            if (column != null) frame = LadderContinuation.choose(world, physics, state, column,
                    column.exit(plan.landingZone()));
            ControlInput input = new ControlInput(frame.forward(), frame.strafe(), frame.sprint(),
                    frame.jump() && (state.onGround() || ladderAttached(state)),
                    frame.sneak() && (targetSupported(state) || frame.phase() == ControlPhase.LADDER && ladderAttached(state)),
                    boundedYaw(state.yaw(), frame.desiredYaw()));
            PhysicsStep step;
            try { step = physics.tick(world, state, input); }
            catch (RuntimeException exception) { return null; }
            if (isAvoidanceRoute() && step.collisions().contacts().stream()
                    .anyMatch(contact -> !contact.support() && !contact.face().headContact())) return null;
            for (CollisionContact contact : step.collisions().contacts()) {
                if (contact.support()) continue;
                for (ContactEvent event : plan.contactEvents()) {
                    if (event.requirement() == ContactRequirement.REQUIRED
                            && tick >= event.earliestTick() && tick <= event.latestTick()
                            && event.featureId().equals(contact.featureId())
                            && sameNormalClass(event, contact)) {
                        required.add(event.featureId() + ":" + event.face());
                    }
                }
            }
            state = step.state();
            states.add(state);
            if (tick < jumpIndex) {
                if (state.onGround() && support(state) != SupportKind.TAKEOFF
                        && !(tick + 1 == jumpIndex && expectedSample(jumpIndex).support() == SupportKind.NONE)) {
                    return null;
                }
            } else {
                finalAirborne |= !state.onGround();
                if (finalAirborne && state.onGround() && !targetSupported(state)
                        && ladderColumns.stream().noneMatch(value -> value.supportsExit(step.state()))) return null;
            }
        }
        return finalAirborne && state.onGround() && targetSupported(state)
                && plan.contactEvents().stream()
                    .filter(event -> event.requirement() == ContactRequirement.REQUIRED)
                    .allMatch(event -> required.contains(event.featureId() + ":" + event.face()))
                && state.velocity().horizontalLength() <= LandingStabilityTracker.MAX_FINAL_SPEED
                ? List.copyOf(states) : null;
    }

    /** Stable legacy replay for the ordinary single-takeoff fast path. */
    private List<ParkourState> replayOrdinaryStagingRoute(ParkourState initial, int firstFrame) {
        if (support(initial) != SupportKind.TAKEOFF
                && !(firstFrame == jumpIndex && initial.onGround()
                    && expectedSample(jumpIndex).support() == SupportKind.NONE)
                || world.collisionBoxes(initial.boundingBox()).stream().anyMatch(initial.boundingBox()::intersects))
            return null;
        ParkourState state = initial;
        boolean airborne = false;
        Set<String> required = new LinkedHashSet<>();
        java.util.ArrayList<ParkourState> states = new java.util.ArrayList<>();
        states.add(state);
        for (int tick = firstFrame; tick < plan.controlFrames().size(); tick++) {
            ControlFrame frame = plan.controlFrames().get(tick);
            LadderColumn column = attachedColumn(state);
            if (column != null) frame = LadderContinuation.choose(world, physics, state, column,
                    column.exit(plan.landingZone()));
            ControlInput input = new ControlInput(frame.forward(), frame.strafe(), frame.sprint(),
                    frame.jump() && (state.onGround() || ladderAttached(state)),
                    frame.sneak() && (targetSupported(state)
                        || frame.phase() == ControlPhase.LADDER && ladderAttached(state)),
                    boundedYaw(state.yaw(), frame.desiredYaw()));
            PhysicsStep step;
            try { step = physics.tick(world, state, input); }
            catch (RuntimeException exception) { return null; }
            if (isAvoidanceRoute() && step.collisions().contacts().stream()
                    .anyMatch(contact -> !contact.support() && !contact.face().headContact())) return null;
            for (CollisionContact contact : step.collisions().contacts()) {
                if (contact.support()) continue;
                for (ContactEvent event : plan.contactEvents()) {
                    if (event.requirement() == ContactRequirement.REQUIRED
                            && tick >= event.earliestTick() && tick <= event.latestTick()
                            && event.featureId().equals(contact.featureId()) && sameNormalClass(event, contact)) {
                        required.add(event.featureId() + ":" + event.face());
                    }
                }
            }
            state = step.state();
            states.add(state);
            airborne |= !state.onGround();
            if (!airborne && support(state) != SupportKind.TAKEOFF
                    && !(tick + 1 == jumpIndex && state.onGround()
                        && expectedSample(jumpIndex).support() == SupportKind.NONE)) return null;
            if (airborne && state.onGround() && !targetSupported(state)
                    && ladderColumns.stream().noneMatch(value -> value.supportsExit(step.state()))) return null;
        }
        return airborne && state.onGround() && targetSupported(state)
                && plan.contactEvents().stream().filter(event -> event.requirement() == ContactRequirement.REQUIRED)
                    .allMatch(event -> required.contains(event.featureId() + ":" + event.face()))
                && state.velocity().horizontalLength() <= LandingStabilityTracker.MAX_FINAL_SPEED
                ? List.copyOf(states) : null;
    }

    ControlFrame selectLandingControl(ParkourState state) {
        float yaw = launchEnvelope.desiredYaw();
        ControlFrame neutral = landingFrame(0, 0, false, yaw);
        // Low speed is not itself safe: a few millimetres of residual motion can still
        // leave a legal fringe landing. Prove the neutral tail before releasing braking.
        if (stoppingSurvives(state, neutral)) return neutral;
        ControlFrame counter = counterLandingFrame(state, yaw);
        if (stoppingSurvives(state, counter)) return counter;
        ControlFrame sneak = landingFrame(0, 0, true, yaw);
        return stoppingSurvives(state, sneak) ? sneak : counter;
    }

    private ControlFrame counterLandingFrame(ParkourState state, float yaw) {
        Vec3d heading = launchEnvelope.routeHeading();
        Vec3d side = ControlInput.strafeDirection(heading);
        Vec3d opposite = new Vec3d(state.velocity().x, 0, state.velocity().z).normalize().multiply(-1);
        return landingFrame(
                (float) MathHelper.clamp(opposite.dotProduct(heading), -1, 1),
                (float) MathHelper.clamp(opposite.dotProduct(side), -1, 1), false, yaw);
    }

    private boolean stoppingSurvives(ParkourState initial, ControlFrame frame) {
        ParkourState state = initial;
        boolean braking = frame.forward() != 0 || frame.strafe() != 0;
        for (int tick = 0; tick < LandingStabilityTracker.REQUIRED_GROUNDED_TICKS
                + LandingStabilityTracker.REQUIRED_SETTLE_TICKS; tick++) {
            // Match planning's closed-loop stop: counter the remaining velocity, then
            // release. Repeating the first backward key for six ticks drove the rollout
            // off the opposite edge and falsely made harmless braking look impossible.
            ControlFrame next = frame;
            if (braking) next = state.velocity().horizontalLength() <= LandingStabilityTracker.MAX_FINAL_SPEED
                    ? landingFrame(0, 0, false, frame.desiredYaw())
                    : counterLandingFrame(state, frame.desiredYaw());
            try { state = physics.tick(world, state, input(state, next)).state(); }
            catch (RuntimeException exception) { return false; }
            if (!targetSupported(state) || !state.onGround()) return false;
        }
        return state.velocity().horizontalLength() <= LandingStabilityTracker.MAX_FINAL_SPEED;
    }

    ControlFrame selectAirborneControl(ParkourState observed, ControlFrame planned, int plannedIndex) {
        LadderColumn column = attachedColumn(observed);
        if (column != null) return LadderContinuation.choose(world, physics, observed, column,
                column.exit(plan.landingZone()));
        TrajectorySample expected = expectedSample(plannedIndex);
        if (!requiresAirborneRecovery(observed, expected) && airActionAllowed(observed, planned)) {
            return planned;
        }
        List<ControlFrame> choices = List.of(planned,
                airborneFrame(0, 0, false, planned.desiredYaw()),
                airborneFrame(-1, 0, false, planned.desiredYaw()),
                airborneFrame(planned.forward(), -1, planned.sprint(), planned.desiredYaw()),
                airborneFrame(planned.forward(), 1, planned.sprint(), planned.desiredYaw()));
        return choices.stream().filter(choice -> airActionAllowed(observed, choice))
                .min(Comparator.comparingDouble(choice -> airMpcScore(observed, choice,
                        plannedIndex, choice == planned ? -0.01 : 0)))
                .orElse(airborneFrame(0, 0, false, planned.desiredYaw()));
    }

    private boolean airActionAllowed(ParkourState state, ControlFrame frame) {
        try {
            PhysicsStep step = physics.tick(world, state, input(state, frame));
            return !isAvoidanceRoute() || step.collisions().contacts().stream()
                    .noneMatch(contact -> !contact.support() && !contact.face().headContact());
        } catch (RuntimeException exception) { return false; }
    }

    private double airMpcScore(ParkourState initial, ControlFrame first,
                               int plannedIndex, double preference) {
        ParkourState state = initial;
        double score = preference;
        for (int tick = 0; tick < MPC_HORIZON; tick++) {
            ControlFrame frame = tick == 0 ? first : nextAirborneFrame(plannedIndex + tick);
            PhysicsStep step;
            try { step = physics.tick(world, state, input(state, frame)); }
            catch (RuntimeException exception) { return Double.MAX_VALUE; }
            if (isAvoidanceRoute() && step.collisions().contacts().stream()
                    .anyMatch(contact -> !contact.support() && !contact.face().headContact())) return Double.MAX_VALUE;
            state = step.state();
            if (state.onGround()) {
                return targetSupported(state) ? -100_000
                        - SupportResolver.edgeMargin(state.boundingBox(), state.feetPosition().y,
                            plan.landingRegion()) * 100 : 1_000_000;
            }
            TrajectorySample expected = expectedSample(plannedIndex + tick + 1);
            score += state.feetPosition().squaredDistanceTo(expected.feetPosition()) * 2
                    + state.velocity().squaredDistanceTo(expected.velocity());
        }
        return score + SupportResolver.distanceToRegion(state.feetPosition(),
                plan.landingRegion()) * 10;
    }

    TakeoffDecision takeoffDecision(ParkourState observed, ControlFrame planned, int plannedIndex) {
        if (retainedGroundCommit(observed, plannedIndex)) return TakeoffDecision.JUMP_NOW;
        if (!observed.onGround() || support(observed) != SupportKind.TAKEOFF) {
            return TakeoffDecision.ABORT;
        }
        float yawError = Math.abs(MathHelper.wrapDegrees(planned.desiredYaw() - observed.yaw()));
        if (launchEnvelope.containsPosition(observed.feetPosition())
                && launchEnvelope.containsVelocity(observed.velocity())
                && takeoffYawReachable(yawError)) {
            return TakeoffDecision.JUMP_NOW;
        }
        double remaining = plan.launchLane() == null ? 0
                : plan.launchLane().distanceBeforeEdge(observed.feetPosition());
        if (plan.launchLane() != null && remaining >= plan.launchLane().triggerMinimum()
                && remaining <= plan.launchLane().triggerMaximum() + 0.20) {
            try {
                ControlFrame approach = new ControlFrame(1, planned.strafe(), planned.sprint(),
                        false, false, planned.desiredYaw(), ControlPhase.RUN_UP, FrameGuard.GROUNDED);
                ParkourState next = physics.tick(world, observed, input(observed, approach)).state();
                float nextYawError = Math.abs(MathHelper.wrapDegrees(
                        planned.desiredYaw() - next.yaw()));
                if (next.onGround() && support(next) == SupportKind.TAKEOFF
                        && launchEnvelope.containsPosition(next.feetPosition())
                        && launchEnvelope.containsVelocity(next.velocity())
                        && takeoffYawReachable(nextYawError)) {
                    return TakeoffDecision.APPROACH;
                }
            } catch (RuntimeException ignored) { }
        }
        return TakeoffDecision.ABORT;
    }

    /** Vanilla resolves vertical support before horizontal edge departure. Only the exact
     * acknowledged final ground state may use that flag; it can issue jump, never another run tick. */
    private boolean retainedGroundCommit(ParkourState observed, int index) {
        if (index != jumpIndex || !observed.onGround() || support(observed) != SupportKind.NONE) return false;
        TrajectorySample expected = expectedSample(jumpIndex);
        return expected.onGround() && expected.support() == SupportKind.NONE
                && launchEnvelope.containsPosition(observed.feetPosition())
                && launchEnvelope.containsVelocity(observed.velocity())
                && takeoffYawReachable(Math.abs(MathHelper.wrapDegrees(
                        plan.controlFrames().get(jumpIndex).desiredYaw() - observed.yaw())));
    }

    private int matchedAirborneIndex(ParkourState state) {
        int best = Math.max(controlIndex, jumpIndex + 1);
        double bestError = Double.MAX_VALUE;
        for (int index = best; index <= Math.min(plan.controlFrames().size() - 1, best + 2); index++) {
            if (!plan.controlFrames().get(index).phase().isTransitPhase()) continue;
            TrajectorySample sample = expectedSample(index);
            double error = state.feetPosition().squaredDistanceTo(sample.feetPosition())
                    + state.velocity().squaredDistanceTo(sample.velocity()) * 2;
            if (error < bestError) { bestError = error; best = index; }
        }
        return best;
    }

    private int nextAirborneIndex(int from) {
        for (int index = Math.max(from, jumpIndex + 1); index < plan.controlFrames().size(); index++) {
            if (plan.controlFrames().get(index).phase().isTransitPhase()) return index;
        }
        return -1;
    }

    private ControlFrame nextAirborneFrame(int from) {
        int index = nextAirborneIndex(from);
        if (index >= 0) {
            ControlFrame frame = plan.controlFrames().get(index);
            return frame;
        }
        return airborneFrame(0, 0, false, launchEnvelope.desiredYaw());
    }

    private void matchContacts(PendingCommand command, ParkourState observed) {
        int tick = Math.max(0, command.planIndex);
        for (CollisionContact contact : command.expected.collisions().contacts()) {
            if (contact.support()) continue;
            boolean observedClass = contact.face().headContact() ? observed.verticalCollision()
                    : contact.face().sideContact() && observed.horizontalCollision();
            if (!observedClass) continue;
            for (ContactEvent event : plan.contactEvents()) {
                if (event.requirement() != ContactRequirement.REQUIRED
                        || tick < event.earliestTick() || tick > event.latestTick()) continue;
                if (event.featureId().equals(contact.featureId()) && sameNormalClass(event, contact)) {
                    satisfiedContacts.add(event.featureId() + ":" + event.face());
                }
            }
        }
    }

    private boolean sameNormalClass(ContactEvent event, CollisionContact contact) {
        if (event.face().headContact()) return contact.face().headContact();
        if (!event.face().sideContact() || !contact.face().sideContact()) return event.face() == contact.face();
        return (event.face().normal().x != 0) == (contact.face().normal().x != 0);
    }

    private boolean requiredContactsSatisfied() {
        return plan.contactEvents().stream()
                .filter(event -> event.requirement() == ContactRequirement.REQUIRED)
                .allMatch(event -> satisfiedContacts.contains(event.featureId() + ":" + event.face()));
    }

    private ControlFrame guardedFrame(ParkourState state, ControlFrame frame,
                                      boolean targetSupported) {
        boolean ladder = frame.phase() == ControlPhase.LADDER && !state.onGround() && ladderAttached(state);
        boolean preparatoryJump = frame.phase() == ControlPhase.PREPARATORY_TAKEOFF
                && phase == ControllerPhase.APPROACH_TRACKING;
        return new ControlFrame(frame.forward(), frame.strafe(), frame.sprint(),
                frame.jump() && (state.onGround()
                    && (phase == ControllerPhase.TAKEOFF_COMMITTED || preparatoryJump) || ladder),
                frame.allowsSneak(state.onGround(), targetSupported, ladder), boundedYaw(state.yaw(), frame.desiredYaw()),
                frame.phase(), frame.guard());
    }

    private ControlInput input(ParkourState state, ControlFrame frame) {
        boolean ladder = !state.onGround() && ladderAttached(state);
        return new ControlInput(frame.forward(), frame.strafe(), frame.sprint(),
                frame.jump() && (frame.phase() != ControlPhase.LADDER || ladder),
                frame.allowsSneak(state.onGround(), targetSupported(state), ladder),
                boundedYaw(state.yaw(), frame.desiredYaw()));
    }

    private boolean ladderAttached(ParkourState state) {
        return world.isLadder(net.minecraft.util.math.BlockPos.ofFloored(state.feetPosition()));
    }

    private LadderColumn attachedColumn(ParkourState state) {
        return state.onGround() ? null : ladderColumns.stream()
                .filter(column -> column.contains(state.feetPosition())).findFirst().orElse(null);
    }

    private float boundedYaw(float current, float desired) {
        return current + MathHelper.clamp(MathHelper.wrapDegrees(desired - current),
                -MAX_YAW_CHANGE, MAX_YAW_CHANGE);
    }

    static boolean takeoffYawReachable(float yawError) {
        // Replay assumes this command's requested heading, not a clipped approximation.
        // Launch-state yaw tolerance does not authorize extra command yaw error.
        return yawError <= MAX_YAW_CHANGE + 1.0E-4;
    }

    private String takeoffMissReason(ParkourState state, ControlFrame jump) {
        Vec3d relative = state.feetPosition().subtract(launchEnvelope.routeOrigin());
        Vec3d side = ControlInput.strafeDirection(launchEnvelope.routeHeading());
        double longitudinal = relative.dotProduct(launchEnvelope.routeHeading());
        double lateral = relative.dotProduct(side);
        double speed = state.velocity().dotProduct(launchEnvelope.routeHeading());
        double yaw = Math.abs(MathHelper.wrapDegrees(jump.desiredYaw() - state.yaw()));
        return String.format("No validated takeoff state remained before the safe edge "
                        + "(longitudinal %.3f in [%.3f, %.3f], lateral %.3f in [%.3f, %.3f], "
                        + "speed %.3f in [%.3f, %.3f], yaw %.2f/%.2f).",
                longitudinal, launchEnvelope.minimumLongitudinal(),
                launchEnvelope.maximumLongitudinal(), lateral,
                launchEnvelope.minimumLateral(), launchEnvelope.maximumLateral(),
                speed, launchEnvelope.minimumForwardSpeed(),
                launchEnvelope.maximumForwardSpeed(), yaw,
                launchEnvelope.yawTolerance());
    }

    private SupportKind support(ParkourState state) {
        return SupportResolver.resolve(state.boundingBox(), state.feetPosition(), state.onGround(),
                plan.landingRegion(), plan.approachPlan().supportRegion(), world).kind();
    }

    private boolean targetSupported(ParkourState state) {
        return SupportResolver.targetSupported(state.boundingBox(), state.feetPosition(),
                state.onGround(), plan.landingRegion());
    }

    private boolean isAvoidanceRoute() {
        return plan.routeMode() == RouteMode.AVOID_LEFT || plan.routeMode() == RouteMode.AVOID_RIGHT;
    }

    private TrajectorySample expectedSample(int index) {
        return referenceTrajectory.get(Math.min(Math.max(0, index), referenceTrajectory.size() - 1));
    }

    private ControlFrame neutral(ControlPhase phase, float yaw) {
        return new ControlFrame(0, 0, false, false, false, yaw, phase, FrameGuard.GROUNDED);
    }
    private ControlFrame groundFrame(float forward, float strafe, float yaw) {
        return new ControlFrame(forward, strafe, false, false, false, yaw,
                ControlPhase.POSITIONING, FrameGuard.GROUNDED);
    }
    private ControlFrame airborneFrame(float forward, float strafe, boolean sprint, float yaw) {
        return new ControlFrame(forward, strafe, sprint, false, false, yaw,
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE);
    }
    private ControlFrame landingFrame(float forward, float strafe, boolean sneak, float yaw) {
        return new ControlFrame(forward, strafe, false, false, sneak, yaw,
                sneak || Math.abs(forward) + Math.abs(strafe) > 0
                        ? ControlPhase.LANDED_BRAKING : ControlPhase.SETTLING,
                FrameGuard.TARGET_GROUNDED);
    }

    private double distanceToBox(Vec3d point, Box box) {
        double dx = Math.max(0, Math.max(box.minX - point.x, point.x - box.maxX));
        double dz = Math.max(0, Math.max(box.minZ - point.z, point.z - box.maxZ));
        return Math.hypot(dx, dz);
    }
    private double horizontalDistance(Vec3d first, Vec3d second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }

    private record StagingNode(ParkourState state, ControlFrame first, double score) { }
    private record StagingKey(long x, long z, long vx, long vz) { }

    static boolean isTakeoffConfirmed(boolean onGround, double verticalDisplacement,
                                      double verticalVelocity) {
        return isTakeoffConfirmed(onGround, verticalDisplacement, verticalVelocity, false);
    }
    static boolean isTakeoffConfirmed(boolean onGround, double verticalDisplacement,
                                      double verticalVelocity, boolean upwardCollision) {
        return !onGround && (verticalDisplacement > 0.01 || verticalVelocity > 0.01
                || upwardCollision && verticalDisplacement >= -0.001);
    }
    static boolean requiresAirborneRecovery(ParkourState observed, TrajectorySample expected) {
        Vec3d positionError = observed.feetPosition().subtract(expected.feetPosition());
        Vec3d velocityError = observed.velocity().subtract(expected.velocity());
        return Math.hypot(positionError.x, positionError.z) > AIRBORNE_POSITION_DEADBAND
                || Math.hypot(velocityError.x, velocityError.z) > AIRBORNE_VELOCITY_DEADBAND
                || Math.abs(positionError.y) > AIRBORNE_VERTICAL_POSITION_DEADBAND
                || Math.abs(velocityError.y) > AIRBORNE_VERTICAL_VELOCITY_DEADBAND;
    }
    static boolean isSneakAllowed(ControlPhase phase, boolean onGround,
                                  boolean targetSupported, boolean requested) {
        return requested && phase.isLandingPhase() && onGround && targetSupported;
    }
    static boolean isLaunchYawAligned(float yawError) { return Math.abs(yawError) <= 2; }

    @Override
    public void stop(MinecraftClient client) {
        movementIO.release(client);
        plan = null;
        world = null;
        landingTracker = null;
        pending = null;
    }
    @Override public String reason() {
        return reason.isBlank() ? StepController.super.reason() : reason;
    }

    ControllerPhase phase() { return phase; }
    int controlIndex() { return controlIndex; }
    boolean commandPending() { return pending != null; }
    private StepTickResult fail(String message) { reason = message; return StepTickResult.FAILED; }
    private StepTickResult replan(String message) { reason = message; return StepTickResult.REPLAN; }

    private record PendingCommand(long epoch, ControlFrame frame, PhysicsStep expected,
                                  int planIndex, boolean advancePlan, boolean jump) {}
}

package com.ariesninja.skulkpk.client.core;

import com.ariesninja.skulkpk.client.core.analysis.JumpProblem;
import com.ariesninja.skulkpk.client.core.analysis.JumpProblemResult;
import com.ariesninja.skulkpk.client.core.analysis.MinecraftWorldView;
import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import com.ariesninja.skulkpk.client.core.execution.ExecutionState;
import com.ariesninja.skulkpk.client.core.execution.ExecutionStatus;
import com.ariesninja.skulkpk.client.core.execution.StepController;
import com.ariesninja.skulkpk.client.core.execution.StepTickResult;
import com.ariesninja.skulkpk.client.core.execution.TrajectoryStepController;
import com.ariesninja.skulkpk.client.core.planning.MovementPlan;
import com.ariesninja.skulkpk.client.core.planning.PlanningPolicy;
import com.ariesninja.skulkpk.client.core.planning.PlanningRequest;
import com.ariesninja.skulkpk.client.core.planning.PlanningSession;
import com.ariesninja.skulkpk.client.core.planning.PlanningTickResult;
import com.ariesninja.skulkpk.client.core.planning.AsyncPlanningSession;
import com.ariesninja.skulkpk.client.core.planning.SupportKind;
import com.ariesninja.skulkpk.client.core.planning.SupportResolver;
import com.ariesninja.skulkpk.client.core.rendering.SelectionRenderer;
import com.ariesninja.skulkpk.client.core.utils.ChatMessageUtil;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public final class StepExecutor {
    public static final int MAX_EXECUTION_TICKS = 260;
    public static final long PLANNING_BUDGET_NANOS = 10_000_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger("Skulk/Trajectory");
    private static final StepExecutor INSTANCE = new StepExecutor(new TrajectoryStepController());

    private final StepController controller;
    private ExecutionState state = ExecutionState.IDLE;
    private PlanningSession planningSession;
    private WorldView planningWorld;
    private JumpProblem planningProblem;
    private MovementPlan activePlan;
    private int executionTicks;
    private int replanAttempts;
    private String reason = "";

    public StepExecutor(StepController controller) { this.controller = Objects.requireNonNull(controller); }
    public static StepExecutor getInstance() { return INSTANCE; }

    public void executeSequence(MinecraftClient client) {
        if (isExecuting()) {
            ChatMessageUtil.sendWarn(client, state == ExecutionState.PLANNING
                    ? "A jump is already being planned." : "A jump is already executing.");
            return;
        }
        replanAttempts = 0;
        beginPlanning(client);
    }

    private boolean beginPlanning(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null
                || BlockSelector.getCurrentProblem().isEmpty()) {
            ChatMessageUtil.sendError(client, "No valid jump selected! Use SELECT first.");
            return false;
        }

        releaseMovementInputs(client);
        JumpProblemResult refreshed = BlockSelector.refreshProblem(client);
        if (refreshed instanceof JumpProblemResult.Rejected rejected) {
            finish(ExecutionState.FAILED, rejected.message(), client);
            return false;
        }
        planningProblem = ((JumpProblemResult.Valid) refreshed).problem();
        planningWorld = new MinecraftWorldView(client.world);
        PlanningRequest request = new PlanningRequest(planningWorld, planningProblem.player(),
                planningProblem.selectedBlock(), PlanningPolicy.AGGRESSIVE, planningProblem);
        try { planningSession = new AsyncPlanningSession(request, problemBounds()); }
        catch (IllegalArgumentException exception) {
            finish(ExecutionState.FAILED, exception.getMessage(), client);
            return false;
        }
        activePlan = null;
        executionTicks = 0;
        reason = "";
        state = ExecutionState.PLANNING;
        ChatMessageUtil.sendInfo(client, "Searching for a stable jump route...");
        LOGGER.info("plan_start target={} takeoffs={} approachSurfaces={} landingSurfaces={}",
                planningProblem.selectedBlock().toShortString(), planningProblem.reachableTakeoffs().size(),
                planningProblem.approachRegion().size(), planningProblem.landingRegion().size());
        return true;
    }

    public void tick(MinecraftClient client) {
        if (state == ExecutionState.PLANNING) tickPlanning(client);
        else if (state == ExecutionState.RUNNING) tickExecution(client);
    }

    private void tickPlanning(MinecraftClient client) {
        if (planningProblem == null || planningWorld == null || (client != null && client.world == null)) {
            finish(ExecutionState.CANCELLED, "Planning lost its world state.", client);
            return;
        }
        if (client != null && planningWorld.identityToken() != client.world) {
            finish(ExecutionState.CANCELLED, "The active world changed during planning.", client);
            return;
        }
        if (planningWorld.fingerprint(problemBounds()) != planningProblem.worldFingerprint()) {
            finish(ExecutionState.CANCELLED, "World geometry changed during planning.", client);
            return;
        }
        // The planning snapshot is a movement state, not just geometry. Do not let an old
        // physical key press walk the real player away while the incremental solver runs.
        releaseMovementInputs(client);
        PlanningTickResult result = planningSession.tick(PLANNING_BUDGET_NANOS);
        if (result instanceof PlanningTickResult.Planning) return;
        if (result instanceof PlanningTickResult.Rejected rejected) {
            LOGGER.info("plan_rejected target={} states={} reason={}",
                    planningProblem.selectedBlock().toShortString(), rejected.candidatesEvaluated(), rejected.reason());
            finish(ExecutionState.FAILED, rejected.message(), client);
            return;
        }
        activePlan = ((PlanningTickResult.Ready) result).plan();
        if (planningSession instanceof AsyncPlanningSession isolated) planningWorld = isolated.executionWorld();
        SelectionRenderer.setMovementPlan(activePlan);
        controller.start(activePlan, planningWorld);
        executionTicks = 0;
        state = ExecutionState.RUNNING;
        var metrics = activePlan.metrics();
        LOGGER.info("plan_ready target={} stage={} searchMs={} directMs={} obstacleMs={} validationMs={} "
                        + "launchStates={} flightStates={} deduplicated={} diversityBuckets={} coreTouchdowns={} "
                        + "fringeTouchdowns={} runUp={} landingSpeed={} robustness={} edgeMargin={} stopping={} "
                        + "sneak={} route={} contacts={} latencyTicks={} validatedVariants={} "
                        + "positionTolerance={} velocityTolerance={} yawTolerance={} termination={}",
                planningProblem.selectedBlock().toShortString(), metrics.planningStage(),
                metrics.searchNanos() / 1_000_000.0, metrics.directNanos() / 1_000_000.0,
                metrics.obstacleNanos() / 1_000_000.0, metrics.landingValidationNanos() / 1_000_000.0,
                metrics.launchSeeds(), metrics.flightStatesExpanded(), metrics.statesDeduplicated(),
                metrics.diversityBuckets(), metrics.coreTouchdowns(), metrics.fringeTouchdowns(), metrics.runUpLength(),
                metrics.landingSpeed(), metrics.robustnessScore(), metrics.edgeMargin(),
                metrics.stoppingMethod(), metrics.usesSneak(), activePlan.routeMode(),
                activePlan.contactEvents().size(), activePlan.commandLatencyTicks(),
                activePlan.validatedToleranceVariants(), activePlan.launchEnvelope().maximumPositionError(),
                activePlan.launchEnvelope().maximumVelocityError(), activePlan.launchEnvelope().yawTolerance(),
                metrics.terminationReason());
        if (client != null) ChatMessageUtil.sendSuccess(client, String.format(
                "Route ready: %.2f block lead-up, %d validated launch variants%s.",
                metrics.runUpLength(), metrics.robustnessScore(),
                activePlan.launchEnvelope().maximumPositionError() < 0.01 ? " (precision launch)" : ""));
        // Apply the first command on the same END_CLIENT_TICK that hands the plan to the
        // controller. An extra idle game tick would invalidate a velocity-bearing launch.
        tickExecution(client);
    }

    private net.minecraft.util.math.Box problemBounds() {
        net.minecraft.util.math.Box bounds = planningProblem.player().boundingBox();
        for (var surface : planningProblem.approachRegion()) bounds = bounds.union(surface.footprint());
        for (var surface : planningProblem.landingRegion()) bounds = bounds.union(surface.footprint());
        return bounds.expand(2, 3, 2);
    }

    private void releaseMovementInputs(MinecraftClient client) {
        if (client == null || client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private void tickExecution(MinecraftClient client) {
        executionTicks++;
        StepTickResult result;
        try {
            result = controller.tick(client);
        } catch (RuntimeException exception) {
            LOGGER.error("Trajectory controller failed", exception);
            finish(ExecutionState.FAILED, "Movement controller error.", client);
            return;
        }
        if (result == StepTickResult.REPLAN) {
            if (!isSafelySupportedForReplan(client)) {
                finish(ExecutionState.FAILED,
                        "Automatic replan refused because the player is no longer supported "
                                + "by the original approach platform.", client);
                return;
            }
            if (replanAttempts++ >= 1) {
                finish(ExecutionState.FAILED,
                        "Launch alignment missed twice; execution stopped cleanly.", client);
                return;
            }
            String replanReason = controller.reason();
            controller.stop(client);
            state = ExecutionState.IDLE;
            activePlan = null;
            SelectionRenderer.clearMovementPlan();
            LOGGER.info("plan_restage attempt={} reason={}", replanAttempts, replanReason);
            if (client != null) ChatMessageUtil.sendWarn(client,
                    "Launch envelope moved; replanning once from the current state.");
            beginPlanning(client);
        } else if (result == StepTickResult.FAILED) finish(ExecutionState.FAILED, controller.reason(), client);
        else if (result == StepTickResult.COMPLETE) finish(ExecutionState.SUCCEEDED,
                "Jump completed with a stable landing.", client);
        else if (executionTicks >= MAX_EXECUTION_TICKS) finish(ExecutionState.FAILED,
                "Trajectory execution timed out.", client);
    }

    private boolean isSafelySupportedForReplan(MinecraftClient client) {
        if (client == null || client.player == null || activePlan == null || planningWorld == null) {
            return false;
        }
        PlayerSnapshot observed = PlayerSnapshot.capture(client.player);
        return SupportResolver.resolve(observed.boundingBox(), observed.feetPosition(),
                observed.onGround(), activePlan.landingRegion(),
                activePlan.approachPlan().supportRegion(), planningWorld).kind() == SupportKind.TAKEOFF;
    }

    boolean startPlan(MovementPlan plan, WorldView world, MinecraftClient client) {
        if (isExecuting()) return false;
        activePlan = Objects.requireNonNull(plan);
        planningWorld = Objects.requireNonNull(world);
        controller.start(plan, world);
        executionTicks = 0;
        reason = "";
        state = ExecutionState.RUNNING;
        return true;
    }

    boolean startPlanning(PlanningSession session, JumpProblem problem, WorldView world) {
        if (isExecuting()) return false;
        planningSession = Objects.requireNonNull(session);
        planningProblem = Objects.requireNonNull(problem);
        planningWorld = Objects.requireNonNull(world);
        activePlan = null;
        executionTicks = 0;
        reason = "";
        state = ExecutionState.PLANNING;
        return true;
    }

    public void cancel(MinecraftClient client, String cancellationReason) {
        if (state == ExecutionState.PLANNING && planningSession != null) planningSession.cancel();
        if (isExecuting()) finish(ExecutionState.CANCELLED, cancellationReason, client);
    }
    public void stopExecution() { cancel(MinecraftClient.getInstance(), "Jump execution stopped."); }

    private void finish(ExecutionState terminalState, String terminalReason, MinecraftClient client) {
        if (planningSession != null) planningSession.cancel();
        try { controller.stop(client); }
        catch (RuntimeException exception) { LOGGER.error("Movement cleanup failed", exception); }
        state = terminalState;
        reason = terminalReason;
        planningSession = null;
        LOGGER.info("plan_terminated state={} ticks={} reason={}", terminalState, executionTicks, terminalReason);
        if (client != null && client.player != null) {
            if (terminalState == ExecutionState.SUCCEEDED) ChatMessageUtil.sendSuccess(client, terminalReason);
            else if (terminalState == ExecutionState.CANCELLED) ChatMessageUtil.sendWarn(client, terminalReason);
            else ChatMessageUtil.sendError(client, terminalReason);
        }
    }

    public boolean isExecuting() { return state == ExecutionState.PLANNING || state == ExecutionState.RUNNING; }
    public boolean isPlanning() { return state == ExecutionState.PLANNING; }

    public ExecutionStatus getStatus() {
        int count = activePlan == null ? 0 : activePlan.controlFrames().size();
        return new ExecutionStatus(state, executionTicks, count, reason);
    }
    public String getExecutionStatus() {
        ExecutionStatus status = getStatus();
        if (status.state() == ExecutionState.PLANNING) return "Planning trajectory";
        if (status.state() != ExecutionState.RUNNING) return status.state()
                + (status.reason().isBlank() ? "" : ": " + status.reason());
        return "Trajectory tick " + status.stepIndex() + "/" + status.stepCount();
    }
    public void clear() {
        cancel(MinecraftClient.getInstance(), "Jump execution cleared.");
        activePlan = null;
        planningProblem = null;
        planningWorld = null;
        state = ExecutionState.IDLE;
        executionTicks = 0;
        reason = "";
        SelectionRenderer.clearMovementPlan();
    }
}

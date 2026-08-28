package com.ariesninja.skulkpk.client.core;

import com.ariesninja.skulkpk.client.core.analysis.JumpProblem;
import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import com.ariesninja.skulkpk.client.core.execution.ExecutionState;
import com.ariesninja.skulkpk.client.core.execution.StepController;
import com.ariesninja.skulkpk.client.core.execution.StepTickResult;
import com.ariesninja.skulkpk.client.core.planning.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class StepExecutorTest {
    @Test void advancesToSuccessAndCleansUp() {
        FakeController controller = new FakeController(StepTickResult.RUNNING, StepTickResult.COMPLETE);
        StepExecutor executor = new StepExecutor(controller);
        assertTrue(executor.startPlan(plan(2), world(7), null));
        executor.tick(null); executor.tick(null);
        assertEquals(ExecutionState.SUCCEEDED, executor.getStatus().state());
        assertEquals(1, controller.starts);
        assertEquals(1, controller.stops);
    }

    @Test void controllerFailureUsesItsReasonAndCleansUp() {
        FakeController controller = new FakeController(StepTickResult.FAILED);
        controller.reason = "predicted collision";
        StepExecutor executor = new StepExecutor(controller);
        executor.startPlan(plan(1), world(7), null); executor.tick(null);
        assertEquals(ExecutionState.FAILED, executor.getStatus().state());
        assertEquals("predicted collision", executor.getStatus().reason());
        assertEquals(1, controller.stops);
    }

    @Test void executorRefusesAReplanWithoutObservedOriginalApproachSupport() {
        FakeController controller = new FakeController(StepTickResult.REPLAN);
        controller.reason = "restage";
        StepExecutor executor = new StepExecutor(controller);
        executor.startPlan(plan(1), world(7), null);

        executor.tick(null);

        assertEquals(ExecutionState.FAILED, executor.getStatus().state());
        assertTrue(executor.getStatus().reason().contains("original approach platform"));
        assertEquals(1, controller.stops);
    }

    @Test void executionTimesOutAndCleansUp() {
        FakeController controller = new FakeController();
        StepExecutor executor = new StepExecutor(controller);
        executor.startPlan(plan(1), world(7), null);
        for (int tick = 0; tick < StepExecutor.MAX_EXECUTION_TICKS; tick++) executor.tick(null);
        assertEquals(ExecutionState.FAILED, executor.getStatus().state());
        assertTrue(executor.getStatus().reason().contains("timed out"));
        assertEquals(1, controller.stops);
    }

    @Test void cancellationAndDuplicateStartAreBounded() {
        FakeController controller = new FakeController();
        StepExecutor executor = new StepExecutor(controller);
        assertTrue(executor.startPlan(plan(1), world(7), null));
        assertFalse(executor.startPlan(plan(1), world(7), null));
        executor.cancel(null, "user cancelled");
        assertEquals(ExecutionState.CANCELLED, executor.getStatus().state());
        assertEquals(1, controller.stops);
    }

    @Test void planningReadyAutomaticallyStartsExecution() {
        FakeController controller = new FakeController();
        StepExecutor executor = new StepExecutor(controller);
        FakePlanningSession session = new FakePlanningSession(new PlanningTickResult.Ready(plan(3)));
        assertTrue(executor.startPlanning(session, problem(7), world(7)));
        executor.tick(null);
        assertEquals(ExecutionState.RUNNING, executor.getStatus().state());
        assertEquals(1, controller.starts);
        assertEquals(1, controller.ticks);
    }

    @Test void planningCancellationAndRejectionCleanUp() {
        FakeController controller = new FakeController();
        StepExecutor executor = new StepExecutor(controller);
        FakePlanningSession session = new FakePlanningSession(new PlanningTickResult.Planning(0));
        executor.startPlanning(session, problem(7), world(7));
        executor.cancel(null, "disconnect");
        assertTrue(session.cancelled);
        assertEquals(ExecutionState.CANCELLED, executor.getStatus().state());
        assertEquals(1, controller.stops);

        StepExecutor rejected = new StepExecutor(new FakeController());
        rejected.startPlanning(new FakePlanningSession(new PlanningTickResult.Rejected(
                PlanRejectionReason.UNREACHABLE, "no stable route", 12)), problem(7), world(7));
        rejected.tick(null);
        assertEquals(ExecutionState.FAILED, rejected.getStatus().state());
        assertEquals("no stable route", rejected.getStatus().reason());
    }

    @Test void planningRejectsChangedWorldFingerprint() {
        StepExecutor executor = new StepExecutor(new FakeController());
        executor.startPlanning(new FakePlanningSession(new PlanningTickResult.Planning(0)), problem(7), world(9));
        executor.tick(null);
        assertEquals(ExecutionState.CANCELLED, executor.getStatus().state());
        assertTrue(executor.getStatus().reason().contains("changed"));
    }

    private static MovementPlan plan(int frames) {
        List<ControlFrame> controls = java.util.stream.IntStream.range(0, frames)
                .mapToObj(i -> ControlFrame.neutral(0, ControlPhase.AIRBORNE)).toList();
        return new MovementPlan(List.of(), controls,
                List.of(new TrajectorySample(0, Vec3d.ZERO, Vec3d.ZERO, true)),
                List.of(surface(2)), Vec3d.ZERO, Vec3d.ZERO, false,
                new PlanMetrics(1, 1, 0, 0, 1, 0.2), 7, new Box(-2, -2, -2, 3, 3, 3));
    }
    private static JumpProblem problem(long fingerprint) {
        return new JumpProblem(new BlockPos(2, 0, 0), surface(0), List.of(surface(2)), List.of(surface(0)),
                List.of(), new PlayerSnapshot(new Vec3d(0.5, 0, 0.5)), fingerprint);
    }
    private static StandableSurface surface(int x) {
        return new StandableSurface(new BlockPos(x, -1, 0), new Box(x, -1, 0, x + 1, 0, 1), 0);
    }
    private static WorldView world(long fingerprint) {
        return new WorldView() {
            @Override public boolean isSolid(BlockPos pos) { return false; }
            @Override public boolean isAir(BlockPos pos) { return true; }
            @Override public boolean isLadder(BlockPos pos) { return false; }
            @Override public boolean hasCollision(Box box) { return false; }
            @Override public int topY() { return 64; }
            @Override public long fingerprint(Box region) { return fingerprint; }
        };
    }
    private static final class FakePlanningSession implements PlanningSession {
        private final PlanningTickResult result;
        private boolean cancelled;
        private FakePlanningSession(PlanningTickResult result) { this.result = result; }
        @Override public PlanningTickResult tick(long budgetNanos) { return result; }
        @Override public void cancel() { cancelled = true; }
    }
    private static final class FakeController implements StepController {
        private final Queue<StepTickResult> results = new ArrayDeque<>();
        private int starts;
        private int stops;
        private int ticks;
        private String reason = "failed";
        private FakeController(StepTickResult... results) { this.results.addAll(List.of(results)); }
        @Override public void start(MovementPlan plan, WorldView world) { starts++; }
        @Override public StepTickResult tick(MinecraftClient client) {
            ticks++;
            return results.isEmpty() ? StepTickResult.RUNNING : results.remove();
        }
        @Override public void stop(MinecraftClient client) { stops++; }
        @Override public String reason() { return reason; }
    }
}

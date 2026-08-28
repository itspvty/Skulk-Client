package com.ariesninja.skulkpk.client.core.execution;

import com.ariesninja.skulkpk.client.core.analysis.JumpProblem;
import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import com.ariesninja.skulkpk.client.core.physics.InMemoryPhysicsWorld;
import com.ariesninja.skulkpk.client.core.physics.ParkourPhysics;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import com.ariesninja.skulkpk.client.core.planning.ControlFrame;
import com.ariesninja.skulkpk.client.core.planning.MovementPlan;
import com.ariesninja.skulkpk.client.core.planning.PlanningPolicy;
import com.ariesninja.skulkpk.client.core.planning.PlanningRequest;
import com.ariesninja.skulkpk.client.core.planning.PlanningTickResult;
import com.ariesninja.skulkpk.client.core.planning.SearchPlanningSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrajectoryStepControllerIntegrationTest {
    @Test void commandCursorWaitsUntilTheIssuedEpochIsObservable() {
        Fixture fixture = fixture(90);
        DelayedPhysicsMovementIO io = new DelayedPhysicsMovementIO(fixture.world,
                ParkourState.capture(fixture.player), 1);
        TrajectoryStepController controller = new TrajectoryStepController(io);
        controller.start(fixture.plan, fixture.world);

        assertEquals(StepTickResult.RUNNING, controller.tick(null));
        int issued = io.commandsApplied;
        int cursor = controller.controlIndex();
        assertTrue(controller.commandPending());

        assertEquals(StepTickResult.RUNNING, controller.tick(null));
        assertEquals(issued, io.commandsApplied,
                "No later command may overwrite one whose result has not been observed.");
        assertEquals(cursor, controller.controlIndex(),
                "Prediction progress must be tied to acknowledged command epochs.");

        assertEquals(StepTickResult.RUNNING, controller.tick(null));
        assertTrue(io.commandsApplied > issued);
    }

    @Test void wrongFacingStartRotatesStationaryThenCompletesThePlannedJump() {
        Fixture fixture = fixture(90);
        DelayedPhysicsMovementIO io = new DelayedPhysicsMovementIO(fixture.world,
                ParkourState.capture(fixture.player), 0);
        TrajectoryStepController controller = new TrajectoryStepController(io);
        controller.start(fixture.plan, fixture.world);

        StepTickResult result = StepTickResult.RUNNING;
        for (int tick = 0; tick < 220 && result == StepTickResult.RUNNING; tick++) {
            result = controller.tick(null);
        }

        assertEquals(StepTickResult.COMPLETE, result, controller.reason());
        assertTrue(io.stationaryYawCommands >= 8,
                "A backward-facing player should rotate with movement released before staging.");
        assertFalse(io.sneakBeforeTargetSupport);
        controller.stop(null);
        assertTrue(io.released);
    }

    @Test void requiredHeadContactIsAcknowledgedByTheDelayedCommandLoop() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int x = -2; x <= 0; x++) {
            world.floor(x, 0, 0);
            approach.add(surface(x, 0));
        }
        world.floor(2, 0, 0).box(new Box(0, 2, 0, 1.35, 2.25, 1));
        StandableSurface landing = surface(2, 0);
        Fixture fixture = fixture(world, List.of(approach.getLast()), approach,
                List.of(landing), new Vec3d(0.5, 0, 0.5), -90);

        DelayedPhysicsMovementIO io = new DelayedPhysicsMovementIO(world,
                ParkourState.capture(fixture.player), 0);
        TrajectoryStepController controller = new TrajectoryStepController(io);
        controller.start(fixture.plan, world);
        StepTickResult result = run(controller, 180);

        assertEquals(StepTickResult.COMPLETE, result, controller.reason());
        assertTrue(fixture.plan.contactEvents().stream().anyMatch(event -> event.face().headContact()));
    }

    @Test void stagingStallReleasesMovementAndRequestsOneStoppedReplan() {
        Fixture fixture = fixture(90);
        FrozenMovementIO io = new FrozenMovementIO(ParkourState.capture(fixture.player));
        TrajectoryStepController controller = new TrajectoryStepController(io);
        controller.start(fixture.plan, fixture.world);

        StepTickResult result = run(controller, 20);

        assertEquals(StepTickResult.REPLAN, result);
        assertTrue(controller.reason().contains("six controlled observations"));
        assertTrue(io.released);
    }

    @Test void supportLossBeforeCommitFailsWithoutRequestingAnUnsafeReplan() {
        Fixture fixture = fixture(90);
        Vec3d fallenFeet = new Vec3d(8.5, -2, 8.5);
        ParkourState fallen = ParkourState.at(fixture.player, fallenFeet, Vec3d.ZERO,
                fixture.player.yaw(), true, false);
        FrozenMovementIO io = new FrozenMovementIO(fallen);
        TrajectoryStepController controller = new TrajectoryStepController(io);
        controller.start(fixture.plan, fixture.world);

        StepTickResult result = controller.tick(null);

        assertEquals(StepTickResult.FAILED, result);
        assertTrue(controller.reason().contains("automatic replanning was refused"));
        assertTrue(io.released);
    }

    @Test void nominalNeoAvoidanceExecutesWithoutClippingItsPillar() {
        Fixture fixture = neoFixture();
        DelayedPhysicsMovementIO io = new DelayedPhysicsMovementIO(fixture.world,
                ParkourState.capture(fixture.player), 0);
        TrajectoryStepController controller = new TrajectoryStepController(io);
        controller.start(fixture.plan, fixture.world);
        StepTickResult result = run(controller, 220);

        assertEquals(StepTickResult.COMPLETE, result, controller.reason());
        assertFalse(io.horizontalCollisionObserved,
                "A validated avoidance route must not clip the pillar at runtime.");
    }

    @Test void nearbyStagingStartsDoNotPostponeMovementOrEnterWithAStaleLaunchOffset() {
        Fixture fixture = neoFixture();
        // Supported world positions, independent of which symmetric lane search selected.
        // Adding an outward offset to an already-fringe staging point invents a midair start.
        for (Vec3d start : List.of(new Vec3d(0.5, 0, 1.15), new Vec3d(0.32, 0, 0.35),
                new Vec3d(0.68, 0, 0.35))) {
            ParkourState initial = ParkourState.at(fixture.player,
                    start, Vec3d.ZERO,
                    fixture.plan.launchEnvelope().desiredYaw(), true, false);
            var io = new DelayedPhysicsMovementIO(fixture.world, initial, 0);
            var controller = new TrajectoryStepController(io);
            controller.start(fixture.plan, fixture.world);
            assertEquals(StepTickResult.COMPLETE, run(controller, 180),
                    "start=" + start + ": " + controller.reason() + "; feet=" + io.state.feetPosition()
                            + "; velocity=" + io.state.velocity() + "; yaw=" + io.state.yaw()
                            + "; staging=" + fixture.plan.stagingPosition());
            assertFalse(io.horizontalCollisionObserved);
        }
    }

    private Fixture neoFixture() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int z = 0; z <= 2; z++) {
            world.floor(0, z, 0);
            approach.add(surface(0, z, 0));
        }
        StandableSurface landing = surface(0, -2, 0);
        world.floor(0, -2, 0).floor(0, -1, 0)
                .box(new Box(0, 0, -1, 1, 3, 0));
        return fixture(world, List.of(surface(0, 0, 0)), approach,
                List.of(landing), new Vec3d(0.5, 0, 1.5), 180);
    }

    @Test void fourBlockRunupCommitsDespiteDecayingStagingVelocityAtTheBoundary() {
        var world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int x = -3; x <= 0; x++) {
            world.floor(x, 0, 0);
            approach.add(surface(x, 0));
        }
        world.floor(5, 0, 0);
        Fixture fixture = fixture(world, List.of(approach.getLast()), approach,
                List.of(surface(5, 0)), new Vec3d(0.5, 0, 0.5), -90);
        var io = new DelayedPhysicsMovementIO(world, ParkourState.capture(fixture.player), 0);
        var controller = new TrajectoryStepController(io);
        controller.start(fixture.plan, world);
        assertEquals(StepTickResult.COMPLETE, run(controller, 180), controller.reason());
        assertFalse(io.sneakBeforeTargetSupport);
    }

    @Test void oneBlockRunwayValidatesResidualVelocityBeforeSpendingItsLastSupportedCommand() {
        var world = new InMemoryPhysicsWorld().floor(0, 0, 0).floor(5, 0, 0);
        Fixture fixture = fixture(world, List.of(surface(0, 0)), List.of(surface(0, 0)),
                List.of(surface(5, 0)), new Vec3d(0.3, 0, 0.5), -90);
        for (Vec3d velocity : List.of(Vec3d.ZERO, new Vec3d(-0.0204, -0.0784, 0.0034))) {
            ParkourState initial = ParkourState.at(fixture.player, fixture.player.feetPosition(),
                    velocity, fixture.player.yaw(), true, false);
            var io = new DelayedPhysicsMovementIO(world, initial, 0);
            var controller = new TrajectoryStepController(io);
            controller.start(fixture.plan, world);
            assertEquals(StepTickResult.COMPLETE, run(controller, 180), controller.reason());
            assertFalse(io.walkedOffBeforeJump, "No command may cross the last supported epoch before commitment.");
        }
    }

    private Fixture fixture(float playerYaw) {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int x = -3; x <= 0; x++) {
            world.floor(x, 0, 0);
            approach.add(surface(x, 0));
        }
        world.floor(3, 0, 0);
        StandableSurface landing = surface(3, 0);
        Vec3d feet = new Vec3d(-2.5, 0, 0.5);
        Fixture result = fixture(world, List.of(approach.getLast()), approach, List.of(landing), feet,
                playerYaw);
        assertFalse(result.plan.immediateLaunch(), "The opposite yaw must use the staging phase.");
        return result;
    }

    private Fixture fixture(InMemoryPhysicsWorld world, List<StandableSurface> legalTakeoffs,
                            List<StandableSurface> approach, List<StandableSurface> landing,
                            Vec3d feet, float playerYaw) {
        PlayerSnapshot player = new PlayerSnapshot(feet,
                new Box(feet.x - 0.3, feet.y, feet.z - 0.3,
                        feet.x + 0.3, feet.y + 1.8, feet.z + 0.3), Vec3d.ZERO, playerYaw,
                true, false, false, 0.1, 0.42, 0.6, Map.of());
        JumpProblem problem = new JumpProblem(landing.getFirst().block(), approach.getLast(), landing,
                legalTakeoffs, approach, world.boxes(), player, 11);
        PlanningRequest request = new PlanningRequest(world, player, landing.getFirst().block(),
                PlanningPolicy.AGGRESSIVE, problem);
        SearchPlanningSession session = new SearchPlanningSession(request);
        PlanningTickResult terminal = null;
        for (int tick = 0; tick < 1000; tick++) {
            terminal = session.tick(100_000_000L);
            if (!(terminal instanceof PlanningTickResult.Planning)) break;
        }
        if (terminal instanceof PlanningTickResult.Rejected rejected) return fail(rejected.message());
        MovementPlan plan = assertInstanceOf(PlanningTickResult.Ready.class, terminal).plan();
        return new Fixture(world, player, plan);
    }

    private StepTickResult run(TrajectoryStepController controller, int maximumTicks) {
        StepTickResult result = StepTickResult.RUNNING;
        for (int tick = 0; tick < maximumTicks && result == StepTickResult.RUNNING; tick++) {
            result = controller.tick(null);
        }
        return result;
    }

    private StandableSurface surface(int x, double topY) {
        return new StandableSurface(new BlockPos(x, (int) topY - 1, 0),
                new Box(x, topY - 1, 0, x + 1, topY, 1), topY);
    }

    private StandableSurface surface(int x, int z, double topY) {
        return new StandableSurface(new BlockPos(x, (int) topY - 1, z),
                new Box(x, topY - 1, z, x + 1, topY, z + 1), topY);
    }

    private record Fixture(InMemoryPhysicsWorld world, PlayerSnapshot player, MovementPlan plan) {}

    private static final class DelayedPhysicsMovementIO implements MovementIO {
        private final InMemoryPhysicsWorld world;
        private final ParkourPhysics physics = new ParkourPhysics();
        private final int observationDelay;
        private ParkourState state;
        private ControlFrame pending;
        private long pendingEpoch;
        private long observedEpoch;
        private int remainingDelay;
        private int commandsApplied;
        private int stationaryYawCommands;
        private boolean sneakBeforeTargetSupport;
        private boolean horizontalCollisionObserved;
        private boolean released;
        private boolean jumped;
        private boolean walkedOffBeforeJump;

        private DelayedPhysicsMovementIO(InMemoryPhysicsWorld world, ParkourState initial,
                                         int observationDelay) {
            this.world = world;
            this.state = initial;
            this.observationDelay = observationDelay;
        }

        @Override
        public MovementObservation observe(MinecraftClient client) {
            if (pending != null) {
                if (remainingDelay-- <= 0) {
                    ControlInput input = new ControlInput(pending.forward(), pending.strafe(),
                            pending.sprint(), pending.jump(), pending.sneak(), pending.desiredYaw());
                    state = physics.tick(world, state, input).state();
                    jumped |= pending.jump();
                    walkedOffBeforeJump |= !state.onGround() && !jumped;
                    horizontalCollisionObserved |= state.horizontalCollision();
                    observedEpoch = pendingEpoch;
                    pending = null;
                }
            }
            return new MovementObservation(state, observedEpoch);
        }

        @Override
        public long apply(MinecraftClient client, ControlFrame frame, boolean targetSupported) {
            assertNull(pending, "The controller overwrote an unacknowledged command.");
            if (frame.forward() == 0 && frame.strafe() == 0
                    && Math.abs(frame.desiredYaw() - state.yaw()) > 0.5) stationaryYawCommands++;
            if (frame.sneak() && !targetSupported) sneakBeforeTargetSupport = true;
            pending = frame;
            pendingEpoch = ++commandsApplied;
            remainingDelay = observationDelay;
            return pendingEpoch;
        }

        @Override
        public void release(MinecraftClient client) {
            pending = null;
            released = true;
        }
    }

    private static final class FrozenMovementIO implements MovementIO {
        private final ParkourState state;
        private long epoch;
        private boolean released;

        private FrozenMovementIO(ParkourState state) { this.state = state; }
        @Override public MovementObservation observe(MinecraftClient client) {
            return new MovementObservation(state, epoch);
        }
        @Override public long apply(MinecraftClient client, ControlFrame frame,
                                    boolean targetSupported) { return ++epoch; }
        @Override public void release(MinecraftClient client) { released = true; }
    }
}

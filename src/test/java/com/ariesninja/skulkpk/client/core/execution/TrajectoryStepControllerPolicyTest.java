package com.ariesninja.skulkpk.client.core.execution;

import com.ariesninja.skulkpk.client.core.planning.ControlPhase;
import com.ariesninja.skulkpk.client.core.planning.ControlFrame;
import com.ariesninja.skulkpk.client.core.planning.LaunchEnvelope;
import com.ariesninja.skulkpk.client.core.planning.MovementPlan;
import com.ariesninja.skulkpk.client.core.planning.PlanMetrics;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import com.ariesninja.skulkpk.client.core.physics.InMemoryPhysicsWorld;
import com.ariesninja.skulkpk.client.core.planning.SupportKind;
import com.ariesninja.skulkpk.client.core.planning.TrajectorySample;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrajectoryStepControllerPolicyTest {
    @Test void takeoffRequiresAnObservedUpwardTransitionRatherThanAnyAirborneState() {
        assertFalse(TrajectoryStepController.isTakeoffConfirmed(false, -0.02, -0.08),
                "Walking off an edge must not masquerade as a jump.");
        assertFalse(TrajectoryStepController.isTakeoffConfirmed(true, 0, 0));
        assertTrue(TrajectoryStepController.isTakeoffConfirmed(false, 0.18, -0.08),
                "A headhitter can confirm from upward displacement after its velocity is clipped.");
        assertTrue(TrajectoryStepController.isTakeoffConfirmed(false, 0, -0.08, true),
                "An immediate upward collision still acknowledges the jump command.");
        assertTrue(TrajectoryStepController.isTakeoffConfirmed(false, 0, 0.33));
    }
    @Test void launchFramesWaitForTightYawAlignment() {
        assertTrue(TrajectoryStepController.isLaunchYawAligned(2));
        assertTrue(TrajectoryStepController.isLaunchYawAligned(-2));
        assertFalse(TrajectoryStepController.isLaunchYawAligned(2.1f));
        assertFalse(TrajectoryStepController.isLaunchYawAligned(90));
    }

    @Test void takeoffCommandMustReachTheSimulatedHeadingWithinOneCameraUpdate() {
        assertTrue(TrajectoryStepController.takeoffYawReachable(12));
        assertFalse(TrajectoryStepController.takeoffYawReachable(12.01f));
        assertFalse(TrajectoryStepController.takeoffYawReachable(14));
    }

    @Test void launchUsesLaneRangesRatherThanOneExactStagingPoint() {
        LaunchEnvelope envelope = new LaunchEnvelope(new Box(-0.1, -0.05, -0.08, 0.12, 0.1, 0.08),
                new Vec3d(0.08, 0, 0), 0.12, 0.04, -90, 4,
                Vec3d.ZERO, new Vec3d(1, 0, 0), -0.10, 0.12,
                -0.08, 0.08, 0.04, 0.12);
        assertTrue(envelope.containsPosition(new Vec3d(-0.08, 0, 0.06)));
        assertTrue(envelope.containsPosition(new Vec3d(0.10, 0, -0.06)));
        assertFalse(envelope.containsPosition(new Vec3d(0.13, 0, 0)));
        assertTrue(envelope.containsVelocity(new Vec3d(0.08, 0, 0.01)));
        assertFalse(envelope.containsVelocity(new Vec3d(-0.02, 0, 0)));
    }

    @Test void launchEnvelopeLateralCoordinatesMatchMinecraftStrafeSign() {
        LaunchEnvelope envelope = new LaunchEnvelope(new Box(-0.1, -0.05, -0.2, 0.1, 0.1, 0.2),
                Vec3d.ZERO, 0.12, 0.04, -90, 4,
                Vec3d.ZERO, new Vec3d(1, 0, 0), -0.1, 0.1,
                0.04, 0.12, 0, 0.04);

        assertTrue(envelope.containsPosition(new Vec3d(0, 0, -0.08)));
        assertFalse(envelope.containsPosition(new Vec3d(0, 0, 0.08)));
    }

    @Test void launchVelocityBoundaryAllowsMeasuredFloatNoiseButNotMeaningfulUnderspeed() {
        Vec3d heading = new Vec3d(0.9971399047226776, 0, -0.07557784337786906);
        Vec3d nominal = new Vec3d(0.15271998132363906, -0.0784000015258789, -0.011575359748422949);
        double speed = nominal.dotProduct(heading);
        var envelope = new LaunchEnvelope(new Box(1.4, -0.06, 0.3, 1.7, 0.18, 0.6), nominal,
                0.1, 0.03, -94.33443f, 4, new Vec3d(1.5083466611, 0, 0.4386449452),
                heading, -0.06, 0.1, -0.08, 0.08, speed, speed + 0.03);
        // Observed at the final retained-ground command in the real four-block fixture.
        assertTrue(envelope.containsVelocity(new Vec3d(0.1527172536741826,
                -0.0784000015258789, -0.011570818259413253)));
        assertFalse(envelope.containsVelocity(nominal.subtract(heading.multiply(0.00002))));
        assertFalse(envelope.containsVelocity(nominal.subtract(heading.multiply(0.02))));
    }

    @Test void launchPositionBoundaryAllowsMeasuredFloatNoiseWithoutWideningTheTube() {
        var envelope = new LaunchEnvelope(new Box(-0.1, -0.1, -0.1, 0.1, 0.1, 0.1),
                Vec3d.ZERO, 0.025, 0.02, -90, 1.5f, Vec3d.ZERO, new Vec3d(1, 0, 0),
                0, 0.025, -0.025, 0.025, 0, 0.02);
        assertTrue(envelope.containsPosition(new Vec3d(-0.000008, 0, 0)));
        assertFalse(envelope.containsPosition(new Vec3d(-0.00002, 0, 0)));
    }

    @Test void sneakIsImpossibleUntilActualTargetGroundSupport() {
        assertFalse(TrajectoryStepController.isSneakAllowed(
                ControlPhase.AIRBORNE, false, false, true));
        assertFalse(TrajectoryStepController.isSneakAllowed(
                ControlPhase.LANDED_BRAKING, false, true, true));
        assertFalse(TrajectoryStepController.isSneakAllowed(
                ControlPhase.LANDED_BRAKING, true, false, true));
        assertFalse(TrajectoryStepController.isSneakAllowed(
                ControlPhase.LANDED_BRAKING, true, true, false));
        assertTrue(TrajectoryStepController.isSneakAllowed(
                ControlPhase.LANDED_BRAKING, true, true, true));
        assertThrows(IllegalArgumentException.class, () ->
                new com.ariesninja.skulkpk.client.core.planning.ControlFrame(
                        0, 0, false, false, true, 0, ControlPhase.AIRBORNE));
    }

    @Test void ordinaryPredictionNoiseDoesNotInvokeAirborneCorrection() {
        Vec3d expectedFeet = new Vec3d(2.0, 1.1, 0.5);
        Vec3d expectedVelocity = new Vec3d(0.24, 0.16, 0.01);
        Box expectedBox = new Box(1.7, 1.1, 0.2, 2.3, 2.9, 0.8);
        TrajectorySample expected = new TrajectorySample(8, expectedFeet, expectedVelocity,
                expectedBox, false, false, false, ControlPhase.AIRBORNE, SupportKind.NONE, 0);
        ParkourState near = new ParkourState(expectedFeet.add(0.10, 0.08, -0.07),
                expectedVelocity.add(0.05, -0.04, 0.03), expectedBox.offset(0.10, 0.08, -0.07),
                -90, false, true, true, false, false, 8, 0.1, 0.42, 0.6,
                java.util.Map.of());
        ParkourState far = new ParkourState(expectedFeet.add(0.20, 0, 0), expectedVelocity,
                expectedBox.offset(0.20, 0, 0), -90, false, true, true, false, false,
                8, 0.1, 0.42, 0.6, java.util.Map.of());

        assertFalse(TrajectoryStepController.requiresAirborneRecovery(near, expected));
        assertTrue(TrajectoryStepController.requiresAirborneRecovery(far, expected));
    }

    @Test void matchedAirborneStateKeepsTheValidatedControlUnchanged() {
        Vec3d feet = new Vec3d(1.4, 1.0, 0.5);
        Vec3d velocity = new Vec3d(0.22, 0.25, 0);
        Box bounds = new Box(1.1, 1.0, 0.2, 1.7, 2.8, 0.8);
        ControlFrame planned = new ControlFrame(1, -1, true, false, false,
                -100, ControlPhase.AIRBORNE);
        TrajectorySample sample = new TrajectorySample(0, feet, velocity, bounds,
                false, false, false, ControlPhase.AIRBORNE, SupportKind.NONE, 0);
        com.ariesninja.skulkpk.client.core.analysis.StandableSurface landing =
                new com.ariesninja.skulkpk.client.core.analysis.StandableSurface(
                        new net.minecraft.util.math.BlockPos(2, 0, 0),
                        new Box(2, 0, 0, 3, 1, 1), 1);
        MovementPlan plan = new MovementPlan(java.util.List.of(), java.util.List.of(planned),
                java.util.List.of(sample), java.util.List.of(landing), feet, feet, true,
                new PlanMetrics(1, 1, 0, 0, 1, 0.2), 1,
                new Box(-2, -2, -2, 5, 5, 5));
        TrajectoryStepController controller = new TrajectoryStepController();
        controller.start(plan, new InMemoryPhysicsWorld().floor(2, 1, 0));
        ParkourState observed = new ParkourState(feet, velocity, bounds, -100,
                false, true, true, false, false, 1, 0.1, 0.42, 0.6,
                java.util.Map.of());

        assertEquals(planned, controller.selectAirborneControl(observed, planned, 0));
    }

    @Test void takeoffUsesTheValidatedEnvelopeAndAtMostOneSafeApproachCommand() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        java.util.List<com.ariesninja.skulkpk.client.core.analysis.StandableSurface> takeoffs =
                new java.util.ArrayList<>();
        for (int x = -2; x <= 0; x++) {
            world.floor(x, 0, 0);
            takeoffs.add(new com.ariesninja.skulkpk.client.core.analysis.StandableSurface(
                    new BlockPos(x, -1, 0), new Box(x, -1, 0, x + 1, 0, 1), 0));
        }
        world.floor(2, 0, 0);
        var landing = new com.ariesninja.skulkpk.client.core.analysis.StandableSurface(
                new BlockPos(2, -1, 0), new Box(2, -1, 0, 3, 0, 1), 0);
        Vec3d feet = new Vec3d(-0.5, 0, 0.5);
        var player = new com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot(feet,
                new Box(-0.8, 0, 0.2, -0.2, 1.8, 0.8), Vec3d.ZERO, -90,
                true, false, false, 0.1, 0.42, 0.6, java.util.Map.of());
        var problem = new com.ariesninja.skulkpk.client.core.analysis.JumpProblem(landing.block(),
                takeoffs.getLast(), java.util.List.of(landing), takeoffs, world.boxes(), player, 11);
        var request = new com.ariesninja.skulkpk.client.core.planning.PlanningRequest(world, player,
                landing.block(), com.ariesninja.skulkpk.client.core.planning.PlanningPolicy.AGGRESSIVE,
                problem);
        var session = new com.ariesninja.skulkpk.client.core.planning.SearchPlanningSession(request);
        com.ariesninja.skulkpk.client.core.planning.PlanningTickResult result = null;
        for (int tick = 0; tick < 1000; tick++) {
            result = session.tick(100_000_000L);
            if (!(result instanceof com.ariesninja.skulkpk.client.core.planning.PlanningTickResult.Planning)) break;
        }
        MovementPlan plan = assertInstanceOf(
                com.ariesninja.skulkpk.client.core.planning.PlanningTickResult.Ready.class, result).plan();
        int jumpIndex = 0;
        while (!plan.controlFrames().get(jumpIndex).jump()) jumpIndex++;
        ControlFrame jump = plan.controlFrames().get(jumpIndex);
        TrajectorySample expected = plan.predictedTrajectory().get(jumpIndex);
        ParkourState exact = new ParkourState(expected.feetPosition(), expected.velocity(),
                expected.boundingBox(), jump.desiredYaw(), true, jump.sprint(), false,
                false, false, jumpIndex, 0.1, 0.42, 0.6, java.util.Map.of());
        ParkourState lagged = new ParkourState(expected.feetPosition().subtract(
                plan.launchLane().heading().multiply(0.12)), expected.velocity().multiply(0.65),
                expected.boundingBox().offset(plan.launchLane().heading().multiply(-0.12)),
                jump.desiredYaw(), true, jump.sprint(), false, false, false,
                jumpIndex, 0.1, 0.42, 0.6, java.util.Map.of());
        TrajectoryStepController controller = new TrajectoryStepController();
        controller.start(plan, world);

        assertEquals(TrajectoryStepController.TakeoffDecision.JUMP_NOW,
                controller.takeoffDecision(exact, jump, jumpIndex));
        assertNotEquals(TrajectoryStepController.TakeoffDecision.ABORT,
                controller.takeoffDecision(lagged, jump, jumpIndex));
    }

    @Test void fringeLandingUsesBriefCounterInputThenNeutralRatherThanUnnecessarySneak() {
        // Actual headhitter-back touchdown captured in normal Minecraft. Holding back
        // forever falls off the near edge, but two braking commands stop safely.
        Vec3d feet = new Vec3d(2.0807619646, 0, -0.2342114150);
        Vec3d velocity = new Vec3d(0.2506460913, -0.0784000015, -0.0713073208);
        Box box = new Box(feet.x - 0.3, 0, feet.z - 0.3, feet.x + 0.3, 1.8, feet.z + 0.3);
        float yaw = -105.87595f;
        var target = new com.ariesninja.skulkpk.client.core.analysis.StandableSurface(
                new BlockPos(2, -1, 0), new Box(2, -1, 0, 3, 0, 1), 0);
        var sample = new TrajectorySample(0, feet, velocity, box, true, false, true,
                ControlPhase.LANDED_BRAKING, SupportKind.TARGET, 0.01);
        var envelope = new LaunchEnvelope(box, velocity, 0.1, 0.1, yaw, 2);
        var plan = new MovementPlan(java.util.List.of(), java.util.List.of(new ControlFrame(
                0, 0, false, false, false, yaw, ControlPhase.SETTLING)), java.util.List.of(sample),
                java.util.List.of(target), feet, feet, false, envelope,
                new PlanMetrics(1, 1, 0, 0, 1, 0), 1, new Box(-1, -2, -2, 5, 4, 3));
        var world = new InMemoryPhysicsWorld().floor(2, 0, 0);
        var controller = new TrajectoryStepController();
        controller.start(plan, world);
        ParkourState state = new ParkourState(feet, velocity, box, yaw, true, true, true,
                false, true, 10, 0.1, 0.42, 0.6, java.util.Map.of());
        var physics = new com.ariesninja.skulkpk.client.core.physics.ParkourPhysics();
        ControlFrame first = controller.selectLandingControl(state);
        assertEquals(-1, first.forward());
        assertFalse(first.sneak());
        for (int tick = 0; tick < 40; tick++) {
            ControlFrame control = controller.selectLandingControl(state);
            assertFalse(control.sneak(), "No-sneak stopping remains supported at tick " + tick);
            state = physics.tick(world, state, new com.ariesninja.skulkpk.client.core.physics.ControlInput(
                    control.forward(), control.strafe(), false, false, control.sneak(), yaw)).state();
            assertTrue(com.ariesninja.skulkpk.client.core.planning.SupportResolver.targetSupported(
                    state.boundingBox(), state.feetPosition(), state.onGround(), java.util.List.of(target)));
        }
        assertEquals(0, state.velocity().horizontalLength(), 1.0E-9);
    }
}

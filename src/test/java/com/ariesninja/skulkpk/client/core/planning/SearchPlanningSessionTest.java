package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.JumpProblem;
import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.physics.InMemoryPhysicsWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchPlanningSessionTest {
    private static final PlanningPolicy TEST_POLICY = new PlanningPolicy(
            5_000_000_000L, 256, 60, 120, false);

    @Test void ordinaryOneThroughFourBlockJumpsUseProductionPhysicsAndDirectStage() {
        for (int targetX = 2; targetX <= 4; targetX++) {
            PlanningRequest request = request(targetX, 0, -90, TEST_POLICY, false);
            MovementPlan plan = ready(new SearchPlanningSession(request));
            assertEquals(PlanningStage.DIRECT, plan.metrics().planningStage(), "target x=" + targetX);
            assertTrue(plan.metrics().directNanos() < 200_000_000L,
                    "ordinary jump exhausted direct budget at target x=" + targetX);
            assertTrue(plan.controlFrames().stream().anyMatch(ControlFrame::jump));
            assertTrue(plan.predictedTrajectory().stream().anyMatch(sample -> !sample.onGround()));
            assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        }
    }

    @Test void fourBlockJumpFromOneTakeoffBlockUsesTheWholeLegalRunway() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().floor(0, 0, 0).floor(4, 0, 0);
        List<StandableSurface> takeoffs = List.of(surface(0, 0));
        List<StandableSurface> landing = List.of(surface(4, 0));
        Vec3d feet = new Vec3d(0.31, 0, 0.5);
        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world, takeoffs, landing, feet, -90)));
        assertEquals(PlanningStage.DIRECT, plan.planningStage());
        assertTrue(plan.metrics().directNanos() < 200_000_000L);
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        assertTrue(plan.controlFrames().stream().anyMatch(frame -> frame.sprint() && frame.jump()));
    }

    @Test void shortCurrentStateJumpSkipsPositioningAndUsesCenteredCoreLanding() {
        PlanningRequest request = request(2, 0, -90, TEST_POLICY, false);
        MovementPlan plan = ready(new SearchPlanningSession(request));
        assertTrue(plan.positioningPath().isEmpty());
        assertTrue(plan.currentStateLaunch());
        assertTrue(plan.landingZone().isCore(plan.predictedTrajectory().getLast().boundingBox(),
                plan.predictedTrajectory().getLast().feetPosition()));
        assertEquals(plan.settleAnchor(), plan.launchLane().landingAnchor().feet());
        assertTrue(plan.metrics().edgeMargin() >= LandingZone.CORE_SAFETY_MARGIN);
    }

    @Test void twoBlockRiseAndConnectedPlatformUseFixedLandingZone() {
        PlanningRequest raised = request(2, 1, -90, TEST_POLICY, false);
        MovementPlan risePlan = ready(new SearchPlanningSession(raised));
        assertEquals(1, risePlan.predictedTrajectory().getLast().feetPosition().y, 1.0E-7);

        PlanningRequest connected = request(2, 0, -90, TEST_POLICY, true);
        MovementPlan platformPlan = ready(new SearchPlanningSession(connected));
        assertEquals(3, platformPlan.landingRegion().size());
        assertEquals(platformPlan.settleAnchor(), platformPlan.launchLane().landingAnchor().feet());
    }

    @Test void wrongYawKeepsOneFixedHeadingAndDoesNotInventImmediateAlignment() {
        PlanningRequest request = request(2, 0, 90, TEST_POLICY, false);
        MovementPlan plan = ready(new SearchPlanningSession(request));
        assertFalse(plan.immediateLaunch());
        assertTrue(plan.currentStateLaunch());
        assertEquals(-90, plan.launchEnvelope().desiredYaw(), 8);
        assertTrue(plan.controlFrames().stream().noneMatch(frame -> frame.sneak()
                && !frame.phase().isLandingPhase()));
    }

    @Test void landingSneakIsNeverGeneratedBeforeTargetGrounding() {
        MovementPlan plan = ready(new SearchPlanningSession(request(4, 0, -90,
                TEST_POLICY, false)));
        for (int index = 0; index < plan.controlFrames().size(); index++) {
            ControlFrame frame = plan.controlFrames().get(index);
            if (!frame.sneak()) continue;
            assertTrue(frame.phase().isLandingPhase());
            assertEquals(FrameGuard.TARGET_GROUNDED, frame.guard());
            TrajectorySample observed = plan.predictedTrajectory().get(Math.min(index,
                    plan.predictedTrajectory().size() - 1));
            assertEquals(SupportKind.TARGET, observed.support());
            assertTrue(observed.onGround());
        }
    }

    @Test void requiredHeadhitterContactRemainsAValidPhysicsTransition() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> takeoffs = new ArrayList<>();
        for (int x = -2; x <= 0; x++) { world.floor(x, 0, 0); takeoffs.add(surface(x, 0)); }
        world.floor(2, 0, 0).box(new Box(0, 2.0, 0, 1.35, 2.25, 1));
        List<StandableSurface> landing = List.of(surface(2, 0));
        Vec3d feet = new Vec3d(0.5, 0, 0.5);
        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world, takeoffs, landing, feet, -90)));
        assertTrue(plan.predictedTrajectory().stream().anyMatch(TrajectorySample::verticalCollision));
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
    }

    @Test void cornerObstacleRouteRetainsUsefulSideContactOrLateralFlight() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> takeoffs = new ArrayList<>();
        for (int x = -2; x <= 0; x++) { world.floor(x, 0, 0); takeoffs.add(surface(x, 0)); }
        world.floor(1, 1, 0).box(new Box(1, 0, 0, 2, 2.4, 1));
        StandableSurface target = new StandableSurface(new BlockPos(1, -1, 1),
                new Box(1, -1, 1, 2, 0, 2), 0);
        Vec3d feet = new Vec3d(0.5, 0, 0.5);
        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world, takeoffs,
                List.of(target), feet, -45)));
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        assertTrue(plan.predictedTrajectory().stream().anyMatch(sample -> sample.horizontalCollision()
                || Math.abs(sample.feetPosition().z - 0.5) > 0.35));
    }

    @Test void timeoutCancellationAndObstructionAreSpecific() {
        PlanningRequest normal = request(4, 0, -90, TEST_POLICY, false);
        SearchPlanningSession cancelled = new SearchPlanningSession(normal);
        cancelled.cancel();
        assertInstanceOf(PlanningTickResult.Rejected.class, cancelled.tick(1_000_000));

        PlanningPolicy immediate = new PlanningPolicy(1, 256, 60, 120, false);
        PlanningTickResult.Rejected timeout = assertInstanceOf(PlanningTickResult.Rejected.class,
                new SearchPlanningSession(request(4, 0, -90, immediate, false)).tick(10_000_000));
        assertEquals(PlanRejectionReason.SEARCH_TIMEOUT, timeout.reason());

        PlanningRequest blocked = request(3, 0, -90, TEST_POLICY, false);
        ((InMemoryPhysicsWorld) blocked.world()).box(new Box(0.9, 0, -4, 1.1, 3.5, 5));
        PlanningTickResult rejected = terminal(new SearchPlanningSession(blocked));
        if (rejected instanceof PlanningTickResult.Ready ready) {
            fail("Blocked route unexpectedly reached: " + ready.plan().predictedTrajectory().stream()
                    .map(sample -> String.format("%.2f/%.2f/%.2f", sample.feetPosition().x,
                            sample.feetPosition().y, sample.feetPosition().z)).toList());
        }
        assertInstanceOf(PlanningTickResult.Rejected.class, rejected);
    }

    private MovementPlan ready(SearchPlanningSession session) {
        PlanningTickResult result = terminal(session);
        if (result instanceof PlanningTickResult.Rejected rejected) return fail(rejected.message());
        return assertInstanceOf(PlanningTickResult.Ready.class, result).plan();
    }

    private PlanningTickResult terminal(SearchPlanningSession session) {
        for (int tick = 0; tick < 1000; tick++) {
            PlanningTickResult result = session.tick(100_000_000L);
            if (!(result instanceof PlanningTickResult.Planning)) return result;
        }
        return fail("Planning did not terminate.");
    }

    private PlanningRequest request(int targetX, int targetY, float yaw,
                                    PlanningPolicy policy, boolean connected) {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> takeoffs = new ArrayList<>();
        for (int x = -3; x <= 0; x++) {
            world.floor(x, 0, 0);
            takeoffs.add(surface(x, 0));
        }
        List<StandableSurface> landing = new ArrayList<>();
        int count = connected ? 3 : 1;
        for (int x = targetX; x < targetX + count; x++) {
            world.floor(x, 0, targetY);
            landing.add(surface(x, targetY));
        }
        Vec3d feet = new Vec3d(-1.5, 0, 0.5);
        PlayerSnapshot player = new PlayerSnapshot(feet, box(feet), Vec3d.ZERO, yaw,
                true, false, false, 0.1, 0.42, 0.6, Map.of());
        JumpProblem problem = new JumpProblem(landing.getFirst().block(), takeoffs.get(1), landing,
                takeoffs, world.boxes(), player, 11);
        return new PlanningRequest(world, player, landing.getFirst().block(), policy, problem);
    }

    private PlanningRequest customRequest(InMemoryPhysicsWorld world, List<StandableSurface> takeoffs,
                                          List<StandableSurface> landing, Vec3d feet, float yaw) {
        PlayerSnapshot player = new PlayerSnapshot(feet, box(feet), Vec3d.ZERO, yaw,
                true, false, false, 0.1, 0.42, 0.6, Map.of());
        JumpProblem problem = new JumpProblem(landing.getFirst().block(), takeoffs.getLast(), landing,
                takeoffs, world.boxes(), player, 11);
        return new PlanningRequest(world, player, landing.getFirst().block(), TEST_POLICY, problem);
    }

    private static StandableSurface surface(int x, int topY) {
        return new StandableSurface(new BlockPos(x, topY - 1, 0),
                new Box(x, topY - 1, 0, x + 1, topY, 1), topY);
    }
    private static Box box(Vec3d feet) {
        return new Box(feet.x - 0.3, feet.y, feet.z - 0.3,
                feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
    }
}

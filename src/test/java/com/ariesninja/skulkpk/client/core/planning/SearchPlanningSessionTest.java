package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.JumpProblem;
import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.physics.InMemoryPhysicsWorld;
import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import com.ariesninja.skulkpk.client.core.physics.ParkourPhysics;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
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

    @Test void trueFourBlockEdgeGapUsesRunwayBehindTheOnlyLegalTakeoff() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int x = -3; x <= 0; x++) {
            world.floor(x, 0, 0);
            approach.add(surface(x, 0));
        }
        world.floor(5, 0, 0);
        List<StandableSurface> legalTakeoffs = List.of(surface(0, 0));
        List<StandableSurface> landing = List.of(surface(5, 0));
        Vec3d feet = new Vec3d(-1.5, 0, 0.5);
        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world, legalTakeoffs,
                approach, landing, feet, -90, PlanningPolicy.AGGRESSIVE)));
        assertEquals(PlanningStage.DIRECT, plan.planningStage());
        assertTrue(plan.metrics().directNanos() < 200_000_000L);
        assertTrue(plan.metrics().searchNanos() <= PlanningPolicy.AGGRESSIVE.maximumWallNanos());
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        assertTrue(plan.metrics().runUpLength() >= 1.0);
        assertTrue(plan.controlFrames().stream().anyMatch(frame -> frame.sprint() && frame.jump()));
        assertTrue(plan.predictedTrajectory().stream().anyMatch(sample -> sample.onGround()
                && sample.support() == SupportKind.NONE && sample.phase() == ControlPhase.RUN_UP));
    }

    @Test void trueFourBlockDirectRouteIsNotStarvedByReachableFloorBelowTheCourse() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        for (int x = -3; x <= 0; x++) world.floor(x, 0, 0);
        world.floor(5, 0, 0);
        for (int x = -10; x <= 10; x++) for (int z = 1; z <= 10; z++) {
            world.floor(x, z, -2);
        }
        Vec3d feet = new Vec3d(-1.5, 0, 0.5);
        PlayerSnapshot player = new PlayerSnapshot(feet, box(feet), Vec3d.ZERO, -90,
                true, false, false, 0.1, 0.42, 0.6, Map.of());
        var analyzed = assertInstanceOf(
                com.ariesninja.skulkpk.client.core.analysis.JumpProblemResult.Valid.class,
                new com.ariesninja.skulkpk.client.core.JumpAnalyzer().analyzeProblem(
                        world, player, new BlockPos(5, -1, 0)));
        assertEquals(4, analyzed.problem().approachRegion().size());

        MovementPlan plan = ready(new SearchPlanningSession(new PlanningRequest(world, player,
                analyzed.problem().selectedBlock(), PlanningPolicy.AGGRESSIVE, analyzed.problem())));

        assertEquals(PlanningStage.DIRECT, plan.planningStage());
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        assertFalse(plan.landingZone().isCore(
                plan.predictedTrajectory().stream().filter(sample -> sample.support() == SupportKind.TARGET)
                        .findFirst().orElseThrow().boundingBox(),
                plan.predictedTrajectory().stream().filter(sample -> sample.support() == SupportKind.TARGET)
                        .findFirst().orElseThrow().feetPosition()),
                "Maximum reach should accept the first stable fringe contact without a safety inset.");
    }

    @Test void shiftedFourBlockTargetUsesTheNearestTakeoffAndLandingEdges() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int x = -3; x <= 0; x++) {
            world.floor(x, 0, 0);
            approach.add(surface(x, 0, 0));
        }
        world.floor(5, 1, 0);
        StandableSurface landing = surface(5, 1, 0);
        Vec3d feet = new Vec3d(-1.5, 0, 0.5);
        LandingZone zone = LandingZone.build(List.of(landing), new PlayerSnapshot(feet));
        assertTrue(zone.fringeAnchors().stream().mapToDouble(anchor -> anchor.feet().z).min().orElse(2) <= 0.80,
                "The landing zone must preserve its nearest legal fringe: " + zone.fringeAnchors());
        assertTrue(bruteShiftedReach(world, List.of(landing), feet),
                "Production physics should contain at least one shifted maximum-reach family.");

        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world,
                List.of(surface(0, 0, 0)), approach, List.of(landing), feet, -90,
                PlanningPolicy.AGGRESSIVE)));

        assertEquals(PlanningStage.DIRECT, plan.planningStage());
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        assertTrue(plan.launchLane().takeoffPoint().z > 0.60,
                "The shifted target should use the near lateral edge of the runway.");
        assertTrue(plan.launchLane().landingAnchor().feet().z < 1.50,
                "The shifted target should use its near landing edge instead of its center.");
        assertTrue(plan.launchLane().triggerMinimum() <= -0.90,
                "Only maximum-gap geometry should retain the measured coyote launch window.");
    }

    @Test void partialBlockRiseIsAcceptedOnlyAfterProductionPhysicsFindsAStableRoute() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int x = -3; x <= 0; x++) {
            world.floor(x, 0, 0);
            approach.add(surface(x, 0, 0));
        }
        double targetTop = 1.2522;
        world.floor(2, 0, targetTop);
        StandableSurface landing = new StandableSurface(new BlockPos(2, 0, 0),
                new Box(2, targetTop - 1, 0, 3, targetTop, 1), targetTop);
        Vec3d feet = new Vec3d(-1.5, 0, 0.5);

        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world,
                List.of(surface(0, 0, 0)), approach, List.of(landing), feet, -90,
                PlanningPolicy.AGGRESSIVE)));

        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        assertEquals(targetTop, plan.predictedTrajectory().getLast().feetPosition().y, 1.0E-6);
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

    @Test void threeBlockOneRiseUsesAProductionBudgetMomentumLaunch() {
        MovementPlan plan = ready(new SearchPlanningSession(request(3, 1, -90,
                PlanningPolicy.AGGRESSIVE, false)));

        assertEquals(PlanningStage.DIRECT, plan.planningStage());
        assertTrue(plan.metrics().searchNanos() <= PlanningPolicy.AGGRESSIVE.maximumWallNanos());
        assertTrue(plan.controlFrames().stream().anyMatch(frame -> frame.jump() && frame.sprint()));
        assertTrue(plan.controlFrames().stream().filter(frame -> frame.phase() == ControlPhase.RUN_UP)
                .anyMatch(ControlFrame::sprint));
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
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
        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world, takeoffs, landing,
                feet, -90, PlanningPolicy.AGGRESSIVE)));
        assertTrue(plan.predictedTrajectory().stream().anyMatch(TrajectorySample::verticalCollision));
        assertTrue(plan.controlFrames().stream().anyMatch(frame -> frame.jump() && frame.sprint()));
        assertTrue(plan.controlFrames().stream().anyMatch(frame -> frame.phase() == ControlPhase.RUN_UP
                && frame.sprint()));
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
    }

    @Test void suppliedMapHeadhitterUsesTheLastSprintTickUnderTheCeiling() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int z = 1; z <= 2; z++) {
            world.floor(0, z, 0);
            approach.add(surface(0, z, 0));
        }
        List<StandableSurface> landing = new ArrayList<>();
        for (int z = -3; z <= -1; z++) {
            world.floor(0, z, 0);
            landing.add(surface(0, z, 0));
        }
        world.box(new Box(0, 2, 0, 1, 3, 1));
        Vec3d feet = new Vec3d(0.5, 0, 2.5);
        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world, approach,
                approach, landing, feet, 180, PlanningPolicy.AGGRESSIVE)));

        assertEquals(PlanningStage.DIRECT, plan.planningStage());
        assertTrue(plan.predictedTrajectory().stream().anyMatch(TrajectorySample::verticalCollision));
        assertTrue(plan.controlFrames().stream().anyMatch(frame -> frame.phase() == ControlPhase.RUN_UP
                && frame.sprint()));
        assertTrue(plan.controlFrames().stream().anyMatch(frame -> frame.jump() && frame.sprint()));
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
    }

    @Test void suppliedMapNeoRoutesAroundAFullHeightColumn() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int z = 0; z <= 2; z++) {
            world.floor(0, z, 0);
            approach.add(surface(0, z, 0));
        }
        List<StandableSurface> landing = new ArrayList<>();
        for (int z = -3; z <= -2; z++) {
            world.floor(0, z, 0);
            landing.add(surface(0, z, 0));
        }
        world.floor(0, -1, 0).box(new Box(0, 0, -1, 1, 2, 0));
        Vec3d feet = new Vec3d(0.5, 0, 1.5);
        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world,
                List.of(surface(0, 0, 0)), approach, landing, feet, 180,
                PlanningPolicy.AGGRESSIVE)));

        assertEquals(PlanningStage.OBSTACLE, plan.planningStage());
        assertTrue(plan.metrics().searchNanos() <= PlanningPolicy.AGGRESSIVE.maximumWallNanos());
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        assertTrue(plan.controlFrames().stream().anyMatch(frame -> frame.phase() == ControlPhase.RUN_UP
                && Math.abs(frame.strafe()) > 0.1));
        assertTrue(plan.takeoffPosition().z > 0.60,
                "The neo should jump before reaching the pillar-adjacent exposed edge.");
        assertTrue(plan.predictedTrajectory().stream().noneMatch(TrajectorySample::horizontalCollision));
        Box pillar = new Box(0, 0, -1, 1, 2, 0);
        double clearance = plan.predictedTrajectory().stream()
                .filter(sample -> sample.boundingBox().maxY > pillar.minY
                        && sample.boundingBox().minY < pillar.maxY)
                .mapToDouble(sample -> horizontalGap(sample.boundingBox(), pillar))
                .min().orElse(1);
        assertTrue(clearance >= 0.08, "pillar clearance=" + clearance);
        assertTrue(plan.predictedTrajectory().stream().anyMatch(sample ->
                Math.abs(sample.feetPosition().x - 0.5) > 0.82));
    }

    @Test void oneBlockNeoUsesAnEarlyClearanceLaneInsteadOfThePillarEdge() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int z = 0; z <= 2; z++) {
            world.floor(0, z, 0);
            approach.add(surface(0, z, 0));
        }
        StandableSurface landing = surface(0, -2, 0);
        world.floor(0, -2, 0).floor(0, -1, 0)
                .box(new Box(0, 0, -1, 1, 3, 0));
        Vec3d feet = new Vec3d(0.5, 0, 1.5);

        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world,
                List.of(surface(0, 0, 0)), approach, List.of(landing), feet, 180,
                PlanningPolicy.AGGRESSIVE)));

        assertEquals(PlanningStage.OBSTACLE, plan.planningStage());
        assertTrue(plan.takeoffPosition().z > 0.60);
        assertTrue(plan.launchLane().triggerMinimum() >= -0.121,
                "A neo must commit before walking beyond its early supported launch lane.");
        assertTrue(plan.predictedTrajectory().stream().noneMatch(TrajectorySample::horizontalCollision));
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        Box pillar = new Box(0, 0, -1, 1, 3, 0);
        double clearance = plan.predictedTrajectory().stream()
                .filter(sample -> sample.boundingBox().maxY > pillar.minY
                        && sample.boundingBox().minY < pillar.maxY)
                .mapToDouble(sample -> horizontalGap(sample.boundingBox(), pillar))
                .min().orElse(1);
        assertTrue(clearance >= 0.08, "pillar clearance=" + clearance);
    }

    @Test void cornerObstacleRouteRetainsUsefulSideContactOrLateralFlightWithinProductionBudget() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> takeoffs = new ArrayList<>();
        for (int x = -2; x <= 0; x++) { world.floor(x, 0, 0); takeoffs.add(surface(x, 0)); }
        world.floor(1, 1, 0).box(new Box(1, 0, 0, 2, 2.4, 1));
        StandableSurface target = new StandableSurface(new BlockPos(1, -1, 1),
                new Box(1, -1, 1, 2, 0, 2), 0);
        Vec3d feet = new Vec3d(0.5, 0, 0.5);
        MovementPlan plan = ready(new SearchPlanningSession(customRequest(world, takeoffs,
                List.of(target), feet, -45, PlanningPolicy.AGGRESSIVE)));
        assertTrue(plan.metrics().searchNanos() <= PlanningPolicy.AGGRESSIVE.maximumWallNanos());
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
        return customRequest(world, takeoffs, landing, feet, yaw, TEST_POLICY);
    }

    private PlanningRequest customRequest(InMemoryPhysicsWorld world, List<StandableSurface> takeoffs,
                                          List<StandableSurface> landing, Vec3d feet, float yaw,
                                          PlanningPolicy policy) {
        return customRequest(world, takeoffs, takeoffs, landing, feet, yaw, policy);
    }

    private PlanningRequest customRequest(InMemoryPhysicsWorld world,
                                          List<StandableSurface> legalTakeoffs,
                                          List<StandableSurface> approach,
                                          List<StandableSurface> landing, Vec3d feet, float yaw,
                                          PlanningPolicy policy) {
        PlayerSnapshot player = new PlayerSnapshot(feet, box(feet), Vec3d.ZERO, yaw,
                true, false, false, 0.1, 0.42, 0.6, Map.of());
        JumpProblem problem = new JumpProblem(landing.getFirst().block(), approach.getLast(), landing,
                legalTakeoffs, approach, world.boxes(), player, 11);
        return new PlanningRequest(world, player, landing.getFirst().block(), policy, problem);
    }

    private static StandableSurface surface(int x, int topY) {
        return surface(x, 0, topY);
    }
    private static StandableSurface surface(int x, int z, int topY) {
        return new StandableSurface(new BlockPos(x, topY - 1, z),
                new Box(x, topY - 1, z, x + 1, topY, z + 1), topY);
    }
    private static Box box(Vec3d feet) {
        return new Box(feet.x - 0.3, feet.y, feet.z - 0.3,
                feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
    }
    private static double horizontalGap(Box first, Box second) {
        double dx = Math.max(0, Math.max(second.minX - first.maxX, first.minX - second.maxX));
        double dz = Math.max(0, Math.max(second.minZ - first.maxZ, first.minZ - second.maxZ));
        return Math.hypot(dx, dz);
    }

    private boolean bruteShiftedReach(InMemoryPhysicsWorld world, List<StandableSurface> landing,
                                      Vec3d feet) {
        ParkourPhysics physics = new ParkourPhysics();
        PlayerSnapshot snapshot = new PlayerSnapshot(feet, box(feet), Vec3d.ZERO, -90,
                true, false, false, 0.1, 0.42, 0.6, Map.of());
        for (float yaw = -90; yaw <= -78; yaw += 1) {
            ParkourState ground = ParkourState.at(snapshot, feet, Vec3d.ZERO, yaw, true, false);
            for (int launchTick = 0; launchTick < 20; launchTick++) {
                for (int release = 3; release <= 14; release++) {
                    ParkourState flight = physics.tick(world, ground,
                            new ControlInput(1, 0, true, true, false, yaw));
                    boolean airborne = !flight.onGround();
                    for (int tick = 1; tick < 60; tick++) {
                        if (airborne && flight.onGround()) {
                            if (SupportResolver.targetSupported(flight.boundingBox(),
                                    flight.feetPosition(), true, landing)) {
                                boolean stable = true;
                                for (int settle = 0; settle < 14; settle++) {
                                    flight = physics.tick(world, flight, new ControlInput(
                                            0, 0, false, false, true, yaw));
                                    stable &= SupportResolver.targetSupported(flight.boundingBox(),
                                            flight.feetPosition(), flight.onGround(), landing);
                                }
                                if (stable) return true;
                            }
                            break;
                        }
                        flight = physics.tick(world, flight, new ControlInput(
                                tick < release ? 1 : -1, 0, true, false, false, yaw));
                        airborne |= !flight.onGround();
                    }
                }
                ground = physics.tick(world, ground, new ControlInput(1, 0, true,
                        false, false, yaw));
                if (!ground.onGround()) break;
            }
        }
        return false;
    }
}

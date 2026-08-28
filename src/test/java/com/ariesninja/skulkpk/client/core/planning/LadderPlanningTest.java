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
import static org.junit.jupiter.api.Assertions.*;

class LadderPlanningTest {
    @Test void isolatedRungTopsCanBeStartAndEndOfAnAscendingCornerTransfer() {
        var world = new InMemoryPhysicsWorld();
        for (int y = 98; y <= 106; y++) world.box(new Box(1, y, 0, 2, y + 1, 1));
        world.ladder(new BlockPos(0, 100, 0)).box(new Box(0.8125, 100, 0, 1, 101, 1));
        world.ladder(new BlockPos(1, 101, 1)).box(new Box(1, 101, 1, 2, 102, 1.1875));
        for (double z : new double[]{0.2, 0.8}) {
            var player = new PlayerSnapshot(new Vec3d(0.65, 101, z));
            var result = new com.ariesninja.skulkpk.client.core.JumpAnalyzer().analyzeProblem(
                    world, player, new BlockPos(1, 101, 1));
            var problem = assertInstanceOf(com.ariesninja.skulkpk.client.core.analysis.JumpProblemResult.Valid.class,
                    result).problem();
            var session = new SearchPlanningSession(new PlanningRequest(world, player,
                    problem.selectedBlock(), PlanningPolicy.AGGRESSIVE, problem));
            PlanningTickResult outcome;
            do { outcome = session.tick(10_000_000); } while (outcome instanceof PlanningTickResult.Planning);
            var plan = assertInstanceOf(PlanningTickResult.Ready.class, outcome, outcome.toString()).plan();
            assertEquals(102, plan.predictedTrajectory().getLast().feetPosition().y, 1.0E-7);
            assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        }
    }

    @Test void fiveBlockAndAdjacentAndHiddenFaceAttachmentsReachTheSelectedPlatform() {
        for (String route : List.of("five-block", "side-face", "back-face")) {
            var world = new InMemoryPhysicsWorld();
            var approach = new ArrayList<StandableSurface>();
            for (int x = -4; x <= 0; x++) {
                var surface = NichePlanningTest.surface(x, 100, 0);
                world.box(surface.footprint()); approach.add(surface);
            }
            int endX = route.equals("five-block") ? 6 : route.equals("side-face") ? 4 : 3;
            int endY = route.equals("five-block") ? 100 : 102;
            for (int y = 98; y <= endY; y++) {
                world.box(new Box(endX, y, 0, endX + 1, y + 1, 1));
                if (route.equals("five-block")) world.ladder(new BlockPos(endX - 1, y, 0))
                        .box(new Box(endX - 0.1875, y, 0, endX, y + 1, 1));
                else if (route.equals("side-face")) world.ladder(new BlockPos(endX, y, 1))
                        .box(new Box(endX, y, 1, endX + 1, y + 1, 1.1875));
                else world.ladder(new BlockPos(endX + 1, y, 0))
                        .box(new Box(endX + 1, y, 0, endX + 1.1875, y + 1, 1));
            }
            MovementPlan plan = NichePlanningTest.solve(world, approach,
                    NichePlanningTest.surface(endX, endY, 0), -90);
            assertTrue(plan.predictedTrajectory().stream().anyMatch(sample ->
                    world.isLadder(BlockPos.ofFloored(sample.feetPosition()))));
            assertEquals(8, plan.validatedToleranceVariants(), route);
        }
    }

    @Test void catchesLadderAndClimbsToGroundedPlatformBeyondJumpHeight() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int x = -2; x <= 0; x++) {
            world.floor(x, 0, 0);
            approach.add(new StandableSurface(new BlockPos(x, -1, 0), new Box(x, -1, 0, x + 1, 0, 1), 0));
        }
        for (int y = -1; y <= 1; y++) {
            world.box(new Box(3, y, 0, 4, y + 1, 1));
            world.box(new Box(2.8125, y, 0, 3, y + 1, 1)).ladder(new BlockPos(2, y, 0));
        }
        StandableSurface landing = new StandableSurface(new BlockPos(3, 1, 0), new Box(3, 1, 0, 4, 2, 1), 2);
        PlayerSnapshot player = new PlayerSnapshot(new Vec3d(0.5, 0, 0.5));
        JumpProblem problem = new JumpProblem(landing.block(), approach.getLast(), List.of(landing),
                approach, world.boxes(), player, 11);
        SearchPlanningSession session = new SearchPlanningSession(new PlanningRequest(world, player,
                landing.block(), PlanningPolicy.AGGRESSIVE, problem));
        PlanningTickResult result;
        do { result = session.tick(10_000_000); } while (result instanceof PlanningTickResult.Planning);
        MovementPlan plan = assertInstanceOf(PlanningTickResult.Ready.class, result, result.toString()).plan();
        assertEquals(2, plan.predictedTrajectory().getLast().feetPosition().y, 1.0E-7);
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        assertTrue(plan.predictedTrajectory().stream().anyMatch(sample ->
                world.isLadder(BlockPos.ofFloored(sample.feetPosition())) && sample.velocity().y > 0.1));
        assertEquals(1, plan.controlFrames().stream().filter(ControlFrame::jump).count());
        assertFalse(plan.controlFrames().stream().anyMatch(frame -> frame.sneak() && !frame.phase().isLandingPhase()));
    }
}

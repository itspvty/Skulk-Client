package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.*;
import com.ariesninja.skulkpk.client.core.physics.*;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DifficultObstaclePlanningTest {
    @Test void twoBlockDeepFullAndFloatingObstaclesUseValidatedPrecisionRoutes() {
        for (boolean floating : List.of(false, true)) {
            InMemoryPhysicsWorld grid = new InMemoryPhysicsWorld();
            List<StandableSurface> approach = new ArrayList<>();
            for (int z = -3; z <= 3; z++) {
                grid.floor(0, z, 101);
                if (z >= 0) approach.add(surface(z));
            }
            for (int z = -2; z <= -1; z++) for (int y = floating ? 102 : 101; y <= 103; y++)
                grid.floor(0, z, y + 1);
            Vec3d feet = new Vec3d(0.5, 101, 1.5);
            double radius = 0.6F / 2.0;
            PlayerSnapshot player = new PlayerSnapshot(feet,
                    new Box(feet.x - radius, feet.y, feet.z - radius,
                            feet.x + radius, feet.y + 1.8F, feet.z + radius),
                    new Vec3d(0, -0.0784000015258789, 0), 180,
                    true, false, false, 0.1F, 0.42F, 0.6F, Map.of());
            JumpProblem problem = new JumpProblem(new BlockPos(0, 100, -3), surface(1),
                    List.of(surface(-3)), approach.subList(0, 3), approach, grid.boxes(), player, 11);
            SnapshotWorld.Capture capture = SnapshotWorld.capture(grid, new Box(-3, 97, -6, 4, 107, 7));
            while (!capture.tick(1_000_000)) { /* client-sized capture slices */ }
            SnapshotWorld world = capture.finish();
            // Behavioral test has generous wall time for cold CI machines. Normal-client
            // fixtures independently exercise the shipping 1.5-second policy.
            SearchPlanningSession search = new SearchPlanningSession(new PlanningRequest(world, player,
                    problem.selectedBlock(), new PlanningPolicy(5_000_000_000L, 256, 60, 120, false), problem));
            PlanningTickResult result;
            do { result = search.tick(10_000_000); } while (result instanceof PlanningTickResult.Planning);
            MovementPlan plan = assertInstanceOf(PlanningTickResult.Ready.class, result,
                    "floating=" + floating + " result=" + result).plan();
            assertTrue(plan.routeMode() == RouteMode.AVOID_LEFT || plan.routeMode() == RouteMode.AVOID_RIGHT);
            assertEquals(8, plan.validatedToleranceVariants());
            assertEquals(0.005, plan.launchEnvelope().maximumPositionError(), 1.0E-9);
            assertEquals(0.003, plan.launchEnvelope().maximumVelocityError(), 1.0E-9);
            assertTrue(plan.controlFrames().stream().noneMatch(frame -> frame.sneak()
                    && !frame.phase().isLandingPhase()));
            assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
            assertFalse(plan.predictedTrajectory().stream().anyMatch(TrajectorySample::horizontalCollision));
        }
    }

    private static StandableSurface surface(int z) {
        return new StandableSurface(new BlockPos(0, 100, z), new Box(0, 100, z, 1, 101, z + 1), 101);
    }
}

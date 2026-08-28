package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.*;
import com.ariesninja.skulkpk.client.core.physics.*;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NichePlanningTest {
    @Test void raisedOverhangCanBeSkirtedInBothWorldOrientations() {
        for (boolean north : List.of(false, true)) {
            var world = new InMemoryPhysicsWorld();
            List<StandableSurface> approach = new ArrayList<>();
            for (int x = -2; x <= 0; x++) {
                StandableSurface surface = north ? surface(0, 100, -x) : surface(x, 100, 0);
                world.box(surface.footprint()); approach.add(surface);
            }
            var landing = north ? surface(0, 101, -3) : surface(3, 101, 0);
            world.box(landing.footprint()).box(north ? new Box(0, 102, -2, 1, 103, -1)
                    : new Box(2, 102, 0, 3, 103, 1));
            MovementPlan plan = solve(world, approach, landing, north ? 180 : -90);
            assertEquals(8, plan.validatedToleranceVariants());
            assertTrue(plan.routeMode() == RouteMode.AVOID_LEFT || plan.routeMode() == RouteMode.AVOID_RIGHT);
            assertFalse(plan.predictedTrajectory().stream().anyMatch(TrajectorySample::horizontalCollision));
        }
    }

    @Test void aCeilingReachedOnlyAfterTakeoffStillContributesToTheRoute() {
        var world = new InMemoryPhysicsWorld();
        List<StandableSurface> approach = new ArrayList<>();
        for (int x = -2; x <= 0; x++) { var surface = surface(x, 100, 0); approach.add(surface); world.box(surface.footprint()); }
        var landing = surface(3, 100, 0);
        world.box(landing.footprint()).box(new Box(1, 103, 0, 2, 104, 1));
        solve(world, approach, landing, -90);
    }

    static MovementPlan solve(InMemoryPhysicsWorld world, List<StandableSurface> approach,
                               StandableSurface landing, float yaw) {
        Vec3d feet = new Vec3d(0.5, 101, 0.5);
        double radius = 0.6F / 2.0;
        PlayerSnapshot player = new PlayerSnapshot(feet, new Box(feet.x - radius, feet.y, feet.z - radius,
                feet.x + radius, feet.y + 1.8F, feet.z + radius), new Vec3d(0, -0.0784000015258789, 0),
                yaw, true, false, false, 0.1F, 0.42F, 0.6F, Map.of());
        JumpProblem problem = new JumpProblem(landing.block(), approach.getLast(), List.of(landing),
                approach, world.boxes(), player, 11);
        SnapshotWorld.Capture capture = SnapshotWorld.capture(world, new Box(-6, 96, -6, 10, 110, 8));
        while (!capture.tick(1_000_000)) { /* bounded immutable world capture */ }
        var session = new SearchPlanningSession(new PlanningRequest(capture.finish(), player, landing.block(),
                new PlanningPolicy(5_000_000_000L, 256, 60, 120, false), problem));
        PlanningTickResult result;
        do { result = session.tick(10_000_000); } while (result instanceof PlanningTickResult.Planning);
        var plan = assertInstanceOf(PlanningTickResult.Ready.class, result, result.toString()).plan();
        assertEquals(SupportKind.TARGET, plan.predictedTrajectory().getLast().support());
        assertTrue(plan.controlFrames().stream().noneMatch(frame -> frame.sneak()
                && !frame.phase().isLandingPhase() && frame.phase() != ControlPhase.LADDER));
        return plan;
    }

    static StandableSurface surface(int x, int y, int z) {
        return new StandableSurface(new BlockPos(x, y, z), new Box(x, y, z, x + 1, y + 1, z + 1), y + 1);
    }
}

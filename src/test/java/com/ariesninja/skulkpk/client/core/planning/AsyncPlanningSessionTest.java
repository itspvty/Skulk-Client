package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.JumpProblem;
import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import com.ariesninja.skulkpk.client.core.physics.InMemoryPhysicsWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AsyncPlanningSessionTest {
    @Test void workerUsesOnlyCapturedDataAndHandsOffLiveInvalidation() {
        Thread owner = Thread.currentThread();
        InMemoryPhysicsWorld grid = new InMemoryPhysicsWorld().floor(0, 0, 0).floor(2, 0, 0);
        WorldView guarded = new WorldView() {
            private void check() { assertSame(owner, Thread.currentThread(), "live world read on search worker"); }
            public boolean isSolid(BlockPos p) { check(); return grid.isSolid(p); }
            public boolean isAir(BlockPos p) { check(); return grid.isAir(p); }
            public boolean isLadder(BlockPos p) { check(); return false; }
            public boolean hasCollision(Box b) { check(); return grid.hasCollision(b); }
            public List<Box> collisionBoxes(Box b) { check(); return grid.collisionBoxes(b); }
            public int topY() { check(); return grid.topY(); }
            public long fingerprint(Box b) { check(); return grid.fingerprint(b); }
        };
        PlayerSnapshot player = new PlayerSnapshot(new Vec3d(0.5, 0, 0.5));
        StandableSurface from = new StandableSurface(new BlockPos(0, -1, 0), new Box(0, -1, 0, 1, 0, 1), 0);
        StandableSurface to = new StandableSurface(new BlockPos(2, -1, 0), new Box(2, -1, 0, 3, 0, 1), 0);
        Box bounds = new Box(-3, -4, -3, 6, 5, 4);
        JumpProblem problem = new JumpProblem(to.block(), from, List.of(to), List.of(from), grid.boxes(), player, 11);
        AsyncPlanningSession session = new AsyncPlanningSession(new PlanningRequest(guarded, player,
                to.block(), PlanningPolicy.AGGRESSIVE, problem), bounds);
        try {
            PlanningTickResult result;
            do {
                result = session.tick(1_000_000);
                if (result instanceof PlanningTickResult.Planning)
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            } while (result instanceof PlanningTickResult.Planning);
            assertInstanceOf(PlanningTickResult.Ready.class, result);
            grid.fingerprint(99);
            assertEquals(99, session.executionWorld().fingerprint(bounds));
        } finally { session.cancel(); }
    }

    @Test void cancellationBeforeCaptureCannotPublishAPlan() {
        PlanningRequest request = new PlanningRequest(new InMemoryPhysicsWorld().floor(0, 0, 0).floor(2, 0, 0),
                new PlayerSnapshot(new Vec3d(0.5, 0, 0.5)), new BlockPos(2, -1, 0), PlanningPolicy.AGGRESSIVE);
        AsyncPlanningSession session = new AsyncPlanningSession(request, new Box(-5, -5, -5, 10, 6, 6));
        session.tick(1);
        session.cancel();
        assertInstanceOf(PlanningTickResult.Rejected.class, session.tick(1_000_000));
    }
}

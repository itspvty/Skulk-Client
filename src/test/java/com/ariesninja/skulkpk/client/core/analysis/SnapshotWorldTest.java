package com.ariesninja.skulkpk.client.core.analysis;

import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import com.ariesninja.skulkpk.client.core.physics.InMemoryPhysicsWorld;
import com.ariesninja.skulkpk.client.core.physics.ParkourPhysics;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotWorldTest {
    @Test void thinLadderTopSupportSurvivesWorldCapture() {
        var pos = new BlockPos(0, 0, 0);
        var world = new InMemoryPhysicsWorld().box(new Box(1, -2, 0, 2, 5, 1))
                .box(new Box(0.8125, 0, 0, 1, 1, 1)).ladder(pos);
        var capture = SnapshotWorld.capture(world, new Box(-2, -3, -2, 4, 6, 3));
        while (!capture.tick(1_000_000)) { /* bounded capture slices */ }
        var snapshot = capture.finish();
        assertEquals(world.standableSurfaces(pos), snapshot.standableSurfaces(pos));
        var surface = snapshot.standableSurfaces(pos).getFirst();
        Box body = new PlayerSnapshot(new Vec3d(0.65, 1, 0.5)).boundingBox();
        assertEquals(SupportGeometry.standingRegions(world, surface, body),
                SupportGeometry.standingRegions(snapshot, surface, body));
        assertTrue(snapshot.collisionBoxes(body).isEmpty());
    }

    @Test void indexesPartialAndNeighborExtendedShapesWithoutDuplicates() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld()
                .box(new Box(-0.25, 0, 0, 0.25, 1.5, 1)).box(new Box(1, 0, 0, 2, 0.5, 1));
        SnapshotWorld.Capture capture = SnapshotWorld.capture(world, new Box(-2, -2, -2, 4, 4, 4));
        int slices = 0;
        while (!capture.tick(1)) slices++;
        assertTrue(slices > 1, "capture must yield, not scan all cells in a single tick");
        SnapshotWorld snapshot = capture.finish();
        for (Box query : new Box[]{new Box(-1, 1, 0, 1, 2, 1), new Box(0, 0, 0, 2, 1, 1)}) {
            assertEquals(new HashSet<>(world.collisionBoxes(query)), new HashSet<>(snapshot.collisionBoxes(query)));
            assertEquals(new HashSet<>(snapshot.collisionBoxes(query)).size(), snapshot.collisionBoxes(query).size());
        }
        world.box(new Box(2, 0, 0, 3, 1, 1));
        assertTrue(snapshot.collisionBoxes(new Box(2, 0, 0, 3, 1, 1)).isEmpty(), "snapshot must be immutable");
        world.fingerprint(42);
        assertEquals(42, snapshot.validatedBy(world).fingerprint(new Box(0, 0, 0, 1, 1, 1)));
    }

    @Test void snapshotAndLiveShapesProduceIdenticalPhysicsAndUnknownSpaceIsNotAir() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        for (int x = -4; x <= 10; x++) for (int z = -4; z <= 4; z++) world.floor(x, z, 0);
        SnapshotWorld.Capture capture = SnapshotWorld.capture(world, new Box(-5, -2, -5, 12, 6, 6));
        while (!capture.tick(1_000_000)) { /* bounded capture slices */ }
        SnapshotWorld snapshot = capture.finish();
        ParkourState live = ParkourState.capture(new PlayerSnapshot(new Vec3d(0.5, 0, 0.5)));
        ParkourState frozen = live;
        ParkourPhysics physics = new ParkourPhysics();
        for (int tick = 0; tick < 35; tick++) {
            ControlInput input = new ControlInput(tick < 25 ? 1 : -1, 0, true, tick == 8, false, -90);
            live = physics.tick(world, live, input).state();
            assertEquals(physics.tick(snapshot, frozen, input).state(), physics.tickState(snapshot, frozen, input));
            frozen = physics.tickState(snapshot, frozen, input);
            assertEquals(live, frozen);
        }
        assertFalse(snapshot.contains(new Box(100, 0, 0, 101, 1, 1)));
        assertFalse(snapshot.collisionBoxes(new Box(100, 0, 0, 101, 1, 1)).isEmpty());
        assertThrows(ParkourPhysics.UnsupportedPhysicsStateException.class,
                () -> snapshot.hasFluid(new BlockPos(100, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotWorld.capture(world, new Box(0, 0, 0, 1000, 1000, 1000)));
    }
}

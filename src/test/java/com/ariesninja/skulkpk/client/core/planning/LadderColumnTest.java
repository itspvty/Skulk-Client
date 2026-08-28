package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.physics.InMemoryPhysicsWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LadderColumnTest {
    @Test void attachmentUsesWholeFeetCellsAndDiscoversAllFourFaces() {
        Box[] rungs = {new Box(0.8125, 0, 0, 1, 1, 1), new Box(0, 0, 0, 0.1875, 1, 1),
                new Box(0, 0, 0.8125, 1, 1, 1), new Box(0, 0, 0, 1, 1, 0.1875)};
        Vec3d[] inward = {new Vec3d(1, 0, 0), new Vec3d(-1, 0, 0),
                new Vec3d(0, 0, 1), new Vec3d(0, 0, -1)};
        for (int i = 0; i < rungs.length; i++) {
            var world = new InMemoryPhysicsWorld().box(rungs[i]).ladder(BlockPos.ORIGIN);
            var column = LadderColumn.discover(world, new Box(-1, -1, -1, 2, 2, 2)).getFirst();
            assertEquals(inward[i], column.intoWall());
            assertTrue(column.contains(new Vec3d(0.01, 0.9, 0.01)));
            assertTrue(column.contains(new Vec3d(0.99, 0.9, 0.99)));
            assertFalse(column.contains(new Vec3d(1, 0.9, 0.5)));
            assertFalse(column.contains(new Vec3d(0.5, 1, 0.5)));
        }
    }

    @Test void aChangeOfFacingDoesNotBecomeOneFalseClimbingWall() {
        var world = new InMemoryPhysicsWorld().ladder(BlockPos.ORIGIN).ladder(new BlockPos(0, 1, 0))
                .box(new Box(0.8125, 0, 0, 1, 1, 1)).box(new Box(0, 1, 0, 0.1875, 2, 1));
        var columns = LadderColumn.discover(world, new Box(0, 0, 0, 1, 2, 1));
        assertEquals(2, columns.size());
        assertEquals(1, columns.getFirst().attachment().maxY);
        assertEquals(1, columns.getLast().attachment().minY);
        assertNotEquals(columns.getFirst().intoWall(), columns.getLast().intoWall());
    }

    @Test void ladderControlsCannotAuthorizeSneakOutsideActualAttachment() {
        var hold = new ControlFrame(0, 0, false, false, true, 0, ControlPhase.LADDER);
        assertEquals(FrameGuard.LADDER, hold.guard());
        assertTrue(hold.guard().permits(false, false, true));
        assertFalse(hold.guard().permits(false, false, false));
        assertFalse(hold.guard().permits(true, false, true));
        assertTrue(hold.allowsSneak(false, false, true));
        assertFalse(hold.allowsSneak(false, false, false), "Losing attachment releases Shift immediately.");
        assertFalse(hold.allowsSneak(true, false, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlFrame(0, 0, false, false, true, 0, ControlPhase.AIRBORNE));
    }

    @Test void steppingFromTheActualTopRungIsTransitNotAnExtraJumpOrSneak() {
        var feet = new Vec3d(1.34, 3, 0.5);
        var state = com.ariesninja.skulkpk.client.core.physics.ParkourState.at(
                new com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot(feet), feet,
                Vec3d.ZERO, 90, true, false);
        var column = new LadderColumn(new Box(1, 0, 0, 2, 3, 1), new Vec3d(-1, 0, 0));
        assertTrue(column.supportsExit(state));
        assertFalse(column.contains(feet));
        var frame = LadderContinuation.choose(new InMemoryPhysicsWorld(),
                new com.ariesninja.skulkpk.client.core.physics.ParkourPhysics(), state, column,
                new Vec3d(0.5, 3, 0.5));
        assertEquals(ControlPhase.LADDER_EXIT, frame.phase());
        assertFalse(frame.jump());
        assertFalse(frame.sneak());
    }
}

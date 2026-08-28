package com.ariesninja.skulkpk.client.core.physics;

import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LadderPhysicsTest {
    private final ParkourPhysics physics = new ParkourPhysics();

    @Test void ladderClampsBeforeMovementAndWallContactClimbsAfterMovement() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld()
                .box(new Box(1, -2, 0, 2, 5, 1));
        for (int y = -2; y <= 4; y++) world.ladder(new BlockPos(0, y, 0));
        Vec3d feet = new Vec3d(0.7, 1, 0.5);
        ParkourState initial = ParkourState.at(new PlayerSnapshot(feet), feet,
                new Vec3d(0.4, -0.4, 0), -90, false, false);
        PhysicsStep step = physics.tick(world, initial, new ControlInput(1, 0, false, false, false, -90));
        assertEquals(0.7, step.state().feetPosition().x, 1.0E-8);
        assertEquals(1 - 0.15000000596046448, step.state().feetPosition().y, 1.0E-8);
        assertEquals((0.2 - 0.08) * 0.9800000190734863, step.state().velocity().y, 1.0E-9);
        assertTrue(step.collisions().hasSideContact());
        assertFalse(step.state().onGround());
        assertEquals(step.state(), physics.tickState(world, initial,
                new ControlInput(1, 0, false, false, false, -90)));
    }

    @Test void fallingOnLadderWithoutWallPressureSlidesButDoesNotBecomeGrounded() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().ladder(new BlockPos(0, 0, 0));
        Vec3d feet = new Vec3d(0.5, 0.8, 0.5);
        ParkourState state = ParkourState.at(new PlayerSnapshot(feet), feet,
                new Vec3d(0, -0.7, 0), 0, false, false);
        ParkourState next = physics.tickState(world, state, new ControlInput(0, 0, false, false, false, 0));
        assertEquals(0.8 - 0.15000000596046448, next.feetPosition().y, 1.0E-9);
        assertEquals((-0.15000000596046448 - 0.08) * 0.9800000190734863, next.velocity().y, 1.0E-9);
        assertFalse(next.onGround());
    }

    @Test void sneakHoldsOnlyInsideTheFeetBlockNotWhenTheBodyMerelyTouchesTheLadder() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().ladder(new BlockPos(0, 0, 0));
        Vec3d feet = new Vec3d(0.98, 0.8, 0.5);
        ParkourState attached = ParkourState.at(new PlayerSnapshot(feet), feet,
                new Vec3d(0, -0.7, 0), 0, false, false);
        ControlInput hold = new ControlInput(0, 0, false, false, true, 0);
        ParkourState held = physics.tickState(world, attached, hold);
        assertEquals(feet.y, held.feetPosition().y, 1.0E-9);
        assertEquals(-0.08 * 0.9800000190734863, held.velocity().y, 1.0E-9);
        assertFalse(held.onGround());
        Vec3d outside = feet.add(0.04, 0, 0);
        ParkourState missed = ParkourState.at(new PlayerSnapshot(outside), outside,
                new Vec3d(0, -0.7, 0), 0, false, false);
        assertEquals(outside.y - 0.7, physics.tickState(world, missed, hold).feetPosition().y, 1.0E-9);
    }

    @Test void ladderJumpClimbsAfterTheGroundJumpHasAlreadyBeenUsed() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().ladder(new BlockPos(0, 0, 0));
        ParkourState state = ParkourState.capture(new PlayerSnapshot(new Vec3d(0.5, 0.8, 0.5),
                new Box(0.2, 0.8, 0.2, 0.8, 2.6, 0.8), new Vec3d(0, -0.1, 0),
                0, false, false, false, 0.1, 0.42, 0.6, java.util.Map.of()));
        assertTrue(state.jumpUsed());
        ParkourState climbed = physics.tickState(world, state, new ControlInput(0, 0, false, true, false, 0));
        assertEquals(0.7, climbed.feetPosition().y, 1.0E-9, "Climb must not invent a fresh 0.42 impulse.");
        assertEquals(0.11760000228881837, climbed.velocity().y, 1.0E-9);
        assertTrue(climbed.jumpUsed());
    }

    @Test void crouchScalingAndBodyDimensionsFollowTheClientInputEpoch() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().ladder(new BlockPos(0, 0, 0));
        Vec3d feet = new Vec3d(0.5, 0.8, 0.5);
        ParkourState start = ParkourState.at(new PlayerSnapshot(feet), feet, Vec3d.ZERO, 0, false, false);
        ControlInput holdRight = new ControlInput(0, 1, false, false, true, 0);
        ParkourState first = physics.tickState(world, start, holdRight);
        assertEquals(0.0196, first.feetPosition().x - start.feetPosition().x, 1.0E-9);
        assertEquals(1.5, first.boundingBox().getLengthY(), 1.0E-9);
        ParkourState second = physics.tickState(world, first, holdRight);
        assertEquals(first.velocity().x + 0.0196 * 0.3,
                second.feetPosition().x - first.feetPosition().x, 1.0E-9);
        ControlInput release = new ControlInput(0, 1, false, false, false, 0);
        ParkourState released = physics.tickState(world, second, release);
        assertEquals(second.velocity().x + 0.0196 * 0.3,
                released.feetPosition().x - second.feetPosition().x, 1.0E-9);
        assertEquals(1.8F, released.boundingBox().getLengthY(), 1.0E-7);
        assertFalse(released.previousSneak());
        ParkourState normal = physics.tickState(world, released, release);
        assertEquals(released.velocity().x + 0.0196,
                normal.feetPosition().x - released.feetPosition().x, 1.0E-9);
    }

    @Test void releasedCrouchBoxDoesNotKeepSlowingInputWhenStandingFitsAgain() {
        var world = new InMemoryPhysicsWorld().ladder(new BlockPos(0, 0, 0));
        Vec3d feet = new Vec3d(0.5, 0.5, 0.5);
        var snapshot = new PlayerSnapshot(feet, new Box(0.2, 0.5, 0.2, 0.8, 2, 0.8),
                Vec3d.ZERO, 0, false, false, false, 0.1, 0.42, 0.6, java.util.Map.of());
        var next = physics.tickState(world, ParkourState.capture(snapshot),
                new ControlInput(1, 0, true, false, false, 0));
        assertTrue(next.sprinting());
        assertEquals((double) 0.98F * 0.025999999F, next.feetPosition().z - feet.z, 1.0E-12);
    }
}

package com.ariesninja.skulkpk.client.core.physics;

import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParkourPhysicsTest {
    private final ParkourPhysics physics = new ParkourPhysics();

    @Test void standingUsesGroundSupportGravityAndDrag() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().floor(0, 0, 0);
        ParkourState next = physics.tick(world, state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true),
                new ControlInput(0, 0, false, false, false, 0));
        assertTrue(next.onGround());
        assertEquals(0, next.feetPosition().y, 1.0E-9);
        assertEquals(-0.0784000015, next.velocity().y, 1.0E-7);
    }

    @Test void walkingAndSprintUseCapturedMovementAttribute() {
        InMemoryPhysicsWorld world = runway();
        ParkourState base = state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true);
        ParkourState walk = physics.tick(world, base,
                new ControlInput(1, 0, false, false, false, -90));
        ParkourState sprint = physics.tick(world, base,
                new ControlInput(1, 0, true, false, false, -90));
        assertEquals(0.098, walk.feetPosition().x - base.feetPosition().x, 1.0E-5);
        assertEquals(0.1274, sprint.feetPosition().x - base.feetPosition().x, 1.0E-5);
        assertTrue(sprint.velocity().x > walk.velocity().x);
    }

    @Test void jumpAndSprintJumpHaveExactImpulseAndOneTransition() {
        InMemoryPhysicsWorld world = runway();
        ParkourState base = state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true);
        ParkourState jump = physics.tick(world, base,
                new ControlInput(0, 0, false, true, false, -90));
        ParkourState sprintJump = physics.tick(world, base,
                new ControlInput(0, 0, true, true, false, -90));
        assertEquals(0.42, jump.feetPosition().y, 1.0E-7);
        assertEquals(0.2, sprintJump.feetPosition().x - base.feetPosition().x, 1.0E-7);
        assertTrue(jump.jumpUsed());
        ParkourState second = physics.tick(world, jump,
                new ControlInput(0, 0, false, true, false, -90));
        assertTrue(second.velocity().y < jump.velocity().y);
    }

    @Test void yawRelativeInputAndCollisionClippingAreDeterministic() {
        InMemoryPhysicsWorld world = runway().box(new Box(0.8, 0, 0, 1, 2, 1));
        ParkourState next = physics.tick(world, state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true),
                new ControlInput(1, 0, false, false, false, -90));
        assertEquals(0.5, next.feetPosition().x, 1.0E-7);
        assertTrue(next.horizontalCollision());
    }

    @Test void positiveStrafeUsesTheSameWorldAxisAsPlanningCoordinates() {
        InMemoryPhysicsWorld world = runway();
        ParkourState base = state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true);
        ParkourState strafed = physics.tick(world, base,
                new ControlInput(0, 1, false, false, false, -90));
        Vec3d routeHeading = new Vec3d(1, 0, 0);
        Vec3d expectedStrafeAxis = ControlInput.strafeDirection(routeHeading);

        assertEquals(new Vec3d(0, 0, -1), expectedStrafeAxis);
        assertTrue(strafed.feetPosition().subtract(base.feetPosition())
                .dotProduct(expectedStrafeAxis) > 0);
    }

    @Test void headContactClipsVerticalMovementWithoutRejectingState() {
        InMemoryPhysicsWorld world = runway().box(new Box(0, 2.0, 0, 1, 2.2, 1));
        ParkourState next = physics.tick(world, state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true),
                new ControlInput(0, 0, false, true, false, 0));
        assertTrue(next.verticalCollision());
        assertFalse(next.onGround());
        assertEquals(0.2, next.feetPosition().y, 1.0E-7);
    }

    @Test void stepHeightChoosesTheHigherHorizontalResolution() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().floor(0, 0, 0)
                .box(new Box(1, 0, 0, 2, 0.5, 1));
        ParkourState next = physics.tick(world,
                state(new Vec3d(0.5, 0, 0.5), new Vec3d(0.35, 0, 0), true),
                new ControlInput(1, 0, false, false, false, -90));
        assertTrue(next.feetPosition().x > 0.8);
        assertEquals(0.5, next.feetPosition().y, 1.0E-7);
        assertTrue(next.onGround());
    }

    @Test void statesAreIndependentValuesAndEffectsAdvance() {
        InMemoryPhysicsWorld world = runway();
        PlayerSnapshot snapshot = snapshot(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true,
                Map.of("minecraft:jump_boost", new PlayerSnapshot.EffectSnapshot(0, 2)));
        ParkourState base = ParkourState.capture(snapshot);
        ParkourState one = physics.tick(world, base,
                new ControlInput(0, 0, false, false, false, 0));
        ParkourState two = physics.tick(world, base,
                new ControlInput(1, 0, false, false, false, -90));
        assertEquals(new Vec3d(0.5, 0, 0.5), base.feetPosition());
        assertNotEquals(one.feetPosition(), two.feetPosition());
        assertEquals(1, one.activeEffects().get("minecraft:jump_boost").remainingTicks());
    }

    @Test void capturedGravityAndJumpStrengthDriveTheProductionArc() {
        InMemoryPhysicsWorld world = runway();
        Vec3d feet = new Vec3d(0.5, 0, 0.5);
        PlayerSnapshot snapshot = new PlayerSnapshot(feet,
                new Box(0.2, 0, 0.2, 0.8, 1.8, 0.8), Vec3d.ZERO, -90,
                true, false, false, 0.1, 0.50, 0.6, 0.04, Map.of());
        ParkourState jumped = physics.tick(world, ParkourState.capture(snapshot),
                new ControlInput(0, 0, false, true, false, -90));

        assertEquals(0.50, jumped.feetPosition().y, 1.0E-7);
        assertEquals((0.50 - 0.04) * 0.9800000190734863, jumped.velocity().y, 1.0E-7);
        assertEquals(0.04, jumped.gravity(), 1.0E-9);
    }

    @Test void productionKernelCanReachAOneBlockRiseWithARealSprintPrefix() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        for (int x = -3; x <= 0; x++) world.floor(x, 0, 0);
        world.floor(2, 0, 1);
        ParkourState prefix = state(new Vec3d(-1.5, 0, 0.5), Vec3d.ZERO, true);
        boolean reached = false;
        StringBuilder outcomes = new StringBuilder();
        for (int jumpTick = 0; jumpTick < 24 && prefix.onGround(); jumpTick++) {
            ParkourState flight = physics.tick(world, prefix,
                    new ControlInput(1, 0, true, true, false, -90));
            for (int tick = 0; tick < 40 && !flight.onGround(); tick++) {
                flight = physics.tick(world, flight,
                        new ControlInput(1, 0, true, false, false, -90));
            }
            outcomes.append(jumpTick).append(':').append(String.format("%.2f/%.2f ",
                    flight.feetPosition().x, flight.feetPosition().y));
            if (flight.onGround() && Math.abs(flight.feetPosition().y - 1) < 0.01
                    && flight.boundingBox().maxX > 2 && flight.boundingBox().minX < 3) {
                reached = true;
                break;
            }
            prefix = physics.tick(world, prefix,
                    new ControlInput(1, 0, true, false, false, -90));
        }
        assertTrue(reached, outcomes.toString());
    }

    @Test void productionKernelExposesSingleBlockFourJumpTouchdownEnvelope() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().floor(0, 0, 0).floor(4, 0, 0);
        ParkourState prefix = state(new Vec3d(0.31, 0, 0.5), Vec3d.ZERO, true);
        boolean touched = false;
        StringBuilder outcomes = new StringBuilder();
        for (int jumpTick = 0; jumpTick < 6 && prefix.onGround(); jumpTick++) {
            for (int releaseTick : new int[]{60, 10, 8, 6}) {
                ParkourState flight = physics.tick(world, prefix,
                        new ControlInput(1, 0, true, true, false, -90));
                for (int tick = 0; tick < 40 && !flight.onGround(); tick++) {
                    float forward = tick < releaseTick ? 1 : 0;
                    flight = physics.tick(world, flight,
                            new ControlInput(forward, 0, true, false, false, -90));
                }
                outcomes.append(jumpTick).append('/').append(releaseTick).append(':')
                        .append(String.format("%.3f/%.2f ", flight.feetPosition().x, flight.feetPosition().y));
                touched |= flight.onGround() && flight.feetPosition().y == 0
                        && flight.boundingBox().maxX > 4 && flight.boundingBox().minX < 5;
            }
            prefix = physics.tick(world, prefix,
                    new ControlInput(1, 0, true, false, false, -90));
        }
        assertTrue(touched, outcomes.toString());
    }

    private InMemoryPhysicsWorld runway() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        for (int x = -2; x <= 3; x++) world.floor(x, 0, 0);
        return world;
    }
    private ParkourState state(Vec3d feet, Vec3d velocity, boolean ground) {
        return ParkourState.capture(snapshot(feet, velocity, ground, Map.of()));
    }
    private PlayerSnapshot snapshot(Vec3d feet, Vec3d velocity, boolean ground,
                                    Map<String, PlayerSnapshot.EffectSnapshot> effects) {
        return new PlayerSnapshot(feet, new Box(feet.x - 0.3, feet.y, feet.z - 0.3,
                feet.x + 0.3, feet.y + 1.8, feet.z + 0.3), velocity, -90,
                ground, false, false, 0.1, 0.42, 0.6, effects);
    }
}

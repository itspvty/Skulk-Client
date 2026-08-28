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
                new ControlInput(0, 0, false, false, false, 0)).state();
        assertTrue(next.onGround());
        assertEquals(0, next.feetPosition().y, 1.0E-9);
        assertEquals(-0.0784000015, next.velocity().y, 1.0E-7);
    }

    @Test void walkingAndSprintUseCapturedMovementAttribute() {
        InMemoryPhysicsWorld world = runway();
        ParkourState base = state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true);
        ParkourState walk = physics.tick(world, base,
                new ControlInput(1, 0, false, false, false, -90)).state();
        ParkourState sprint = physics.tick(world, base,
                new ControlInput(1, 0, true, false, false, -90)).state();
        assertEquals(0.098, walk.feetPosition().x - base.feetPosition().x, 1.0E-5);
        assertEquals(0.1274, sprint.feetPosition().x - base.feetPosition().x, 1.0E-5);
        assertTrue(sprint.velocity().x > walk.velocity().x);
    }

    @Test void jumpAndSprintJumpHaveExactImpulseAndOneTransition() {
        InMemoryPhysicsWorld world = runway();
        ParkourState base = state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true);
        ParkourState jump = physics.tick(world, base,
                new ControlInput(0, 0, false, true, false, -90)).state();
        ParkourState sprintJump = physics.tick(world, base,
                new ControlInput(1, 0, true, true, false, -90)).state();
        ParkourState sprintWalk = physics.tick(world, base,
                new ControlInput(1, 0, true, false, false, -90)).state();
        assertEquals(0.42, jump.feetPosition().y, 1.0E-7);
        assertEquals(0.2, sprintJump.feetPosition().x - sprintWalk.feetPosition().x, 1.0E-7);
        assertTrue(jump.jumpUsed());
        ParkourState second = physics.tick(world, jump,
                new ControlInput(0, 0, false, true, false, -90)).state();
        assertTrue(second.velocity().y < jump.velocity().y);
    }

    @Test void yawRelativeInputAndCollisionClippingAreDeterministic() {
        InMemoryPhysicsWorld world = runway().box(new Box(0.8, 0, 0, 1, 2, 1));
        ParkourState next = physics.tick(world, state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true),
                new ControlInput(1, 0, false, false, false, -90)).state();
        assertEquals(0.5, next.feetPosition().x, 1.0E-7);
        assertTrue(next.horizontalCollision());
    }

    @Test void sprintIsALatchedMovementStateNotTheSprintKey() {
        var base = state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true);
        var started = physics.tickState(runway(), base, new ControlInput(1, 0, true, false, false, -90));
        var released = physics.tickState(runway(), started, new ControlInput(1, 0, false, false, false, -90));
        assertTrue(released.sprinting(), "Releasing sprint while moving forward must preserve sprint.");
        assertFalse(physics.tickState(runway(), released,
                new ControlInput(0, 1, true, false, false, -90)).sprinting());
        assertFalse(physics.tickState(runway(), released.withCollisions(true, false, false),
                new ControlInput(1, 0, true, false, false, -90)).sprinting());
        assertTrue(physics.tickState(runway(), released.withCollisions(true, false, true),
                new ControlInput(1, 0, true, false, false, -90)).sprinting());
    }

    @Test void forwardDoubleTapSprintSurvivesNeutralTicksButExpiresAfterSeven() {
        var world = runway();
        var base = state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true);
        var forward = new ControlInput(1, 0, false, false, false, -90);
        var neutral = new ControlInput(0, 0, false, false, false, -90);
        var first = physics.tickState(world, base, forward);
        assertFalse(first.sprinting());
        assertEquals(7, first.sprintTapTicks());
        var released = physics.tickState(world, first, neutral);
        var second = physics.tickState(world, released, new ControlInput(1, 0, false, true, false, -90));
        assertTrue(second.sprinting(), "Second forward press must produce the sprint-jump boost without a sprint key.");
        assertTrue(second.feetPosition().x - released.feetPosition().x > 0.3);
        var expired = first;
        for (int tick = 0; tick < 7; tick++) expired = physics.tickState(world, expired, neutral);
        assertEquals(0, expired.sprintTapTicks());
        assertFalse(physics.tickState(world, expired, forward).sprinting());
        assertEquals(7, first.sprintTapTicks(), "Branches must not mutate their parent's input history.");
        assertEquals(released.sprintTapTicks(), released.withCollisions(true, false).sprintTapTicks());
    }

    @Test void lowHungerCannotCreateSprintAccelerationOrASprintJumpBoost() {
        var feet = new Vec3d(0.5, 0, 0.5);
        var base = snapshot(feet, Vec3d.ZERO, true, Map.of());
        var hungry = new PlayerSnapshot(feet, base.boundingBox(), Vec3d.ZERO, -90,
                true, false, false, base.movementSpeed(), base.jumpStrength(), base.stepHeight(),
                base.gravity(), base.activeEffects(), base.sneakingSpeed(), 6, false, false);
        var captured = ParkourState.capture(hungry);
        var requested = physics.tickState(runway(), captured,
                new ControlInput(1, 0, true, true, false, -90));
        assertFalse(requested.sprinting());
        assertFalse(requested.sprintAllowed());
        assertEquals(0.098, requested.feetPosition().x - feet.x, 1.0E-7);
        assertFalse(ParkourState.at(hungry, feet, Vec3d.ZERO, -90, true, true).sprintAllowed(),
                "Synthetic staging roots must not restore unavailable sprint permission.");
        assertFalse(captured.withCollisions(true, false).sprintAllowed());
    }

    @Test void obliqueGroundPrefixMatchesVanillaFloatOrderAndLookup() {
        var world = new InMemoryPhysicsWorld().box(new Box(-10, -1, -10, 10, 0, 10));
        var state = state(new Vec3d(-1.2, 0, 0.8), Vec3d.ZERO, true);
        float yaw = -79.15969f;
        float acceleration = (float) (0.1 * ParkourState.SPRINT_MULTIPLIER)
                * (0.21600002F / (0.6F * 0.6F * 0.6F));
        double dx = -(double) net.minecraft.util.math.MathHelper.sin(yaw * 0.017453292F)
                * ((double) 0.98F * acceleration);
        double dz = (double) net.minecraft.util.math.MathHelper.cos(yaw * 0.017453292F)
                * ((double) 0.98F * acceleration);
        double x = state.feetPosition().x, z = state.feetPosition().z, vx = 0, vz = 0;
        for (int tick = 0; tick < 11; tick++) {
            if (Math.abs(vx) < 0.003) vx = 0;
            if (Math.abs(vz) < 0.003) vz = 0;
            vx += dx; vz += dz; x += vx; z += vz;
            vx *= (double) (0.6F * 0.91F); vz *= (double) (0.6F * 0.91F);
            state = physics.tickState(world, state, new ControlInput(1, 0, true, false, false, yaw));
            assertEquals(x, state.feetPosition().x, 1.0E-13);
            assertEquals(z, state.feetPosition().z, 1.0E-13);
            assertEquals(vx, state.velocity().x, 1.0E-13);
        }
    }

    @Test void positiveStrafeUsesTheSameWorldAxisAsPlanningCoordinates() {
        InMemoryPhysicsWorld world = runway();
        ParkourState base = state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true);
        ParkourState strafed = physics.tick(world, base,
                new ControlInput(0, 1, false, false, false, -90)).state();
        Vec3d routeHeading = new Vec3d(1, 0, 0);
        Vec3d expectedStrafeAxis = ControlInput.strafeDirection(routeHeading);

        assertEquals(new Vec3d(0, 0, -1), expectedStrafeAxis);
        assertTrue(strafed.feetPosition().subtract(base.feetPosition())
                .dotProduct(expectedStrafeAxis) > 0);
    }

    @Test void searchControlsUseTheSameDigitalAxesAsMinecraftKeyBindings() {
        ControlInput fractional = new ControlInput(0.35f, -0.45f, true, false, false, -90);
        ControlInput digital = new ControlInput(1, -1, true, false, false, -90);
        assertEquals(digital, fractional);
        var frame = new com.ariesninja.skulkpk.client.core.planning.ControlFrame(
                0.35f, -0.45f, true, false, false, -90,
                com.ariesninja.skulkpk.client.core.planning.ControlPhase.AIRBORNE);
        assertEquals(1, frame.forward());
        assertEquals(-1, frame.strafe());
        assertThrows(IllegalArgumentException.class,
                () -> new ControlInput(Float.NaN, 0, false, false, false, 0));
    }

    @Test void pillarCornerResolvesTheLargerHorizontalAxisFirst() {
        // Both coordinates would collide if resolved together. X clears the near corner
        // before Z moves; reversing axis order falsely clips Z at the corner.
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().box(new Box(0, 0, 0, 1, 3, 1));
        ParkourState base = state(new Vec3d(1.15, 0.5, -0.4), new Vec3d(0.30, 0, 0.20), false);
        PhysicsStep step = physics.tick(world, base, new ControlInput(0, 0, false, false, false, 0));
        assertEquals(1.45, step.state().feetPosition().x, 1.0E-9);
        assertEquals(-0.20, step.state().feetPosition().z, 1.0E-9);
        assertFalse(step.collisions().hasSideContact());
    }

    @Test void sneakingClampsEdgeDisplacementButDoesNotEraseResidualVelocity() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().floor(2, 0, 0);
        ParkourState base = state(new Vec3d(2.08, 0, -0.234), new Vec3d(0.25, -0.0784, -0.0713), true);
        PhysicsStep step = physics.tick(world, base, new ControlInput(0, 0, false, false, true, -90));
        assertTrue(step.state().feetPosition().z > base.feetPosition().z + base.velocity().z);
        assertEquals(base.velocity().z * 0.546, step.state().velocity().z, 1.0E-7,
                "The observed vanilla edge guard preserves velocity while limiting movement.");
        assertFalse(step.collisions().hasSideContact());
    }

    @Test void headContactClipsVerticalMovementWithoutRejectingState() {
        InMemoryPhysicsWorld world = runway().box(new Box(0, 2.0, 0, 1, 2.2, 1));
        PhysicsStep step = physics.tick(world, state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true),
                new ControlInput(0, 0, false, true, false, 0));
        ParkourState next = step.state();
        assertTrue(next.verticalCollision());
        assertFalse(next.onGround());
        assertEquals(0.2, next.feetPosition().y, 1.0E-7);
        assertEquals(-0.0784000015, next.velocity().y, 1.0E-7,
                "Vanilla clears upward velocity on the contact tick, then applies gravity/drag.");
        assertTrue(step.collisions().hasHeadContact());
        assertTrue(step.collisions().contacts().stream().allMatch(contact ->
                contact.face() == CollisionFace.DOWN && contact.axis() == CollisionAxis.Y));
    }

    @Test void sideAndSupportContactsExposeStableShapeFeaturesAndNormals() {
        Box wall = new Box(0.8, 0, 0, 1, 2, 1);
        InMemoryPhysicsWorld world = runway().box(wall);
        PhysicsStep side = physics.tick(world,
                state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true),
                new ControlInput(1, 0, false, false, false, -90));
        CollisionContact wallContact = side.collisions().contacts().stream()
                .filter(contact -> contact.face().sideContact()).findFirst().orElseThrow();
        assertEquals(CollisionFace.WEST, wallContact.face());
        assertFalse(wallContact.support());

        PhysicsStep repeated = physics.tick(world,
                state(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true),
                new ControlInput(1, 0, false, false, false, -90));
        assertEquals(wallContact.featureId(), repeated.collisions().contacts().stream()
                .filter(contact -> contact.face().sideContact()).findFirst().orElseThrow().featureId());

        PhysicsStep landing = physics.tick(new InMemoryPhysicsWorld().floor(0, 0, 0),
                state(new Vec3d(0.5, 0.1, 0.5), new Vec3d(0, -0.2, 0), false),
                new ControlInput(0, 0, false, false, false, 0));
        assertTrue(landing.collisions().hasSupportContact());
        assertTrue(landing.collisions().contacts().stream().anyMatch(contact ->
                contact.face() == CollisionFace.UP && contact.support()));
    }

    @Test void floatingObstacleProducesTheSameBodySideContactAsAFullColumn() {
        ParkourState moving = state(new Vec3d(0.5, 0, 0.5), new Vec3d(0.35, 0, 0), true);
        ControlInput input = new ControlInput(1, 0, true, false, false, -90);
        PhysicsStep floating = physics.tick(new InMemoryPhysicsWorld().floor(0, 0, 0)
                .box(new Box(0.8, 1, 0, 1.8, 2, 1)), moving, input);
        PhysicsStep column = physics.tick(new InMemoryPhysicsWorld().floor(0, 0, 0)
                .box(new Box(0.8, 0, 0, 1.8, 2, 1)), moving, input);

        assertTrue(floating.collisions().hasSideContact());
        assertTrue(column.collisions().hasSideContact());
        assertEquals(floating.state().feetPosition().x, column.state().feetPosition().x, 1.0E-9);
    }

    @Test void supportedGroundStrafeBuildsLateralMomentumBeforeANearbyPillar() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        for (int z = 0; z <= 2; z++) world.floor(0, z, 0);
        world.box(new Box(0, 0, -1, 1, 2, 0));
        ParkourState state = state(new Vec3d(0.3, 0, 0.55), Vec3d.ZERO, true);
        for (int tick = 0; tick < 3; tick++) {
            PhysicsStep step = physics.tick(world, state,
                    new ControlInput(0, 1, true, false, false, 180));
            state = step.state();
            assertFalse(step.collisions().hasSideContact(), "tick=" + tick);
            assertTrue(state.onGround(), "tick=" + tick + " feet=" + state.feetPosition());
        }
        assertTrue(Math.abs(state.feetPosition().x - 0.3) > 0.32, state.feetPosition().toString());
    }

    @Test void stepHeightChoosesTheHigherHorizontalResolution() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld().floor(0, 0, 0)
                .box(new Box(1, 0, 0, 2, 0.5, 1));
        ParkourState next = physics.tick(world,
                state(new Vec3d(0.5, 0, 0.5), new Vec3d(0.35, 0, 0), true),
                new ControlInput(1, 0, false, false, false, -90)).state();
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
                new ControlInput(0, 0, false, false, false, 0)).state();
        ParkourState two = physics.tick(world, base,
                new ControlInput(1, 0, false, false, false, -90)).state();
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
                new ControlInput(0, 0, false, true, false, -90)).state();

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
                    new ControlInput(1, 0, true, true, false, -90)).state();
            for (int tick = 0; tick < 40 && !flight.onGround(); tick++) {
                flight = physics.tick(world, flight,
                        new ControlInput(1, 0, true, false, false, -90)).state();
            }
            outcomes.append(jumpTick).append(':').append(String.format("%.2f/%.2f ",
                    flight.feetPosition().x, flight.feetPosition().y));
            if (flight.onGround() && Math.abs(flight.feetPosition().y - 1) < 0.01
                    && flight.boundingBox().maxX > 2 && flight.boundingBox().minX < 3) {
                reached = true;
                break;
            }
            prefix = physics.tick(world, prefix,
                    new ControlInput(1, 0, true, false, false, -90)).state();
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
                        new ControlInput(1, 0, true, true, false, -90)).state();
                for (int tick = 0; tick < 40 && !flight.onGround(); tick++) {
                    float forward = tick < releaseTick ? 1 : 0;
                    flight = physics.tick(world, flight,
                            new ControlInput(forward, 0, true, false, false, -90)).state();
                }
                outcomes.append(jumpTick).append('/').append(releaseTick).append(':')
                        .append(String.format("%.3f/%.2f ", flight.feetPosition().x, flight.feetPosition().y));
                touched |= flight.onGround() && flight.feetPosition().y == 0
                        && flight.boundingBox().maxX > 4 && flight.boundingBox().minX < 5;
            }
            prefix = physics.tick(world, prefix,
                    new ControlInput(1, 0, true, false, false, -90)).state();
        }
        assertTrue(touched, outcomes.toString());
    }

    @Test void productionKernelCanStablyClearATrueFourBlockGapFromAFullSprintRunway() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        for (int x = -3; x <= 0; x++) world.floor(x, 0, 0);
        world.floor(5, 0, 0);
        ParkourState prefix = state(new Vec3d(-2.5, 0, 0.5), Vec3d.ZERO, true);
        boolean stable = false;
        StringBuilder outcomes = new StringBuilder();
        for (int jumpTick = 0; jumpTick < 24 && prefix.onGround(); jumpTick++) {
            for (int releaseTick : new int[]{60, 3, 5, 7, 9, 11, 14}) {
                ParkourState flight = physics.tick(world, prefix,
                        new ControlInput(1, 0, true, true, false, -90)).state();
                for (int tick = 0; tick < 40 && !flight.onGround(); tick++) {
                    flight = physics.tick(world, flight,
                            new ControlInput(tick < releaseTick ? 1 : 0, 0,
                                    true, false, false, -90)).state();
                }
                int supportedTicks = 0;
                for (int tick = 0; tick < 24 && targetSupported(flight, 5); tick++) {
                    float counter = flight.velocity().horizontalLength() > 0.04 ? -1 : 0;
                    flight = physics.tick(world, flight,
                            new ControlInput(counter, 0, false, false, false, -90)).state();
                    if (targetSupported(flight, 5)) supportedTicks++;
                }
                outcomes.append(jumpTick).append('/').append(releaseTick).append(':')
                        .append(String.format("%.3f/%.2f/%d ", flight.feetPosition().x,
                                flight.feetPosition().y, supportedTicks));
                stable |= supportedTicks >= 14;
            }
            prefix = physics.tick(world, prefix,
                    new ControlInput(1, 0, true, false, false, -90)).state();
        }
        assertTrue(stable, outcomes.toString());
    }

    private InMemoryPhysicsWorld runway() {
        InMemoryPhysicsWorld world = new InMemoryPhysicsWorld();
        for (int x = -2; x <= 3; x++) world.floor(x, 0, 0);
        return world;
    }
    private ParkourState state(Vec3d feet, Vec3d velocity, boolean ground) {
        return ParkourState.capture(snapshot(feet, velocity, ground, Map.of()));
    }
    private boolean targetSupported(ParkourState state, int x) {
        return state.onGround() && Math.abs(state.feetPosition().y) < 0.01
                && state.boundingBox().maxX > x && state.boundingBox().minX < x + 1;
    }
    private PlayerSnapshot snapshot(Vec3d feet, Vec3d velocity, boolean ground,
                                    Map<String, PlayerSnapshot.EffectSnapshot> effects) {
        return new PlayerSnapshot(feet, new Box(feet.x - 0.3, feet.y, feet.z - 0.3,
                feet.x + 0.3, feet.y + 1.8, feet.z + 0.3), velocity, -90,
                ground, false, false, 0.1, 0.42, 0.6, effects);
    }
}

/*
 * This file contains movement logic derived from LiquidBounce's Minecraft movement
 * simulation. LiquidBounce is licensed under GPL-3.0; see the repository notices.
 */
package com.ariesninja.skulkpk.client.core.physics;

import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Authoritative immutable dry-land Minecraft movement kernel used by planning and recovery. */
public final class ParkourPhysics {
    private static final double VERTICAL_DRAG = 0.9800000190734863;
    private static final double AIR_FRICTION = 0.91;
    private static final double INPUT_SCALE = 0.98;
    private static final double EPSILON = 1.0E-7;
    private static final double SUPPORT_PROBE = 1.0E-4;

    public ParkourState tick(PhysicsWorld world, ParkourState state, ControlInput input) {
        BlockPos feetBlock = BlockPos.ofFloored(state.feetPosition());
        if (world.hasFluid(feetBlock) || world.isClimbable(feetBlock)) {
            throw new UnsupportedPhysicsStateException("fluid_or_climbable");
        }

        Vec3d velocity = zeroTiny(state.velocity());
        boolean jumped = input.jump() && state.onGround() && !state.jumpUsed();
        boolean jumpUsed = state.jumpUsed() || jumped;
        boolean sprinting = input.sprint();
        if (jumped) {
            double jumpVelocity = state.jumpStrength() * jumpMultiplier(world, state)
                    + jumpBoost(state.activeEffects());
            double radians = Math.toRadians(input.yaw());
            velocity = new Vec3d(velocity.x - Math.sin(radians) * (sprinting ? 0.2 : 0),
                    Math.max(jumpVelocity, velocity.y),
                    velocity.z + Math.cos(radians) * (sprinting ? 0.2 : 0));
        }

        BlockPos affecting = BlockPos.ofFloored(state.feetPosition().x,
                state.boundingBox().minY - 0.5000001, state.feetPosition().z);
        double slipperiness = world.slipperiness(affecting);
        double acceleration = state.onGround()
                ? state.baseMovementSpeed() * (sprinting ? 1.3 : 1.0)
                    * (0.21600002 / (slipperiness * slipperiness * slipperiness))
                : sprinting ? 0.026 : 0.02;
        velocity = velocity.add(movementVector(input, acceleration));

        Vec3d requested = input.sneak() && state.onGround()
                ? clampSneaking(world, state.boundingBox(), velocity) : velocity;
        CollisionResult collision = move(world, state.boundingBox(), requested,
                state.onGround(), state.stepHeight());
        Box movedBox = state.boundingBox().offset(collision.movement());
        Vec3d feet = state.feetPosition().add(collision.movement());
        boolean onGround = collision.onGround() || (state.onGround()
                && Math.abs(requested.y) < EPSILON && hasSupport(world, movedBox));

        double movedX = collision.xCollision() ? 0 : requested.x;
        double movedY = collision.onGround() ? 0 : requested.y;
        double movedZ = collision.zCollision() ? 0 : requested.z;
        double gravity = hasEffect(state.activeEffects(), "minecraft:slow_falling") && movedY <= 0
                ? Math.min(state.gravity(), 0.01) : state.gravity();
        PlayerSnapshot.EffectSnapshot levitation = state.activeEffects().get("minecraft:levitation");
        double vertical = levitation == null
                ? movedY - gravity
                : movedY + (0.05 * (levitation.amplifier() + 1) - movedY) * 0.2;
        // Minecraft selects horizontal friction before movement/collision resolution. A jump
        // therefore keeps ground friction for its takeoff tick; a landing keeps air friction.
        double friction = state.onGround() ? slipperiness * AIR_FRICTION : AIR_FRICTION;
        Vec3d nextVelocity = new Vec3d(movedX * friction, vertical * VERTICAL_DRAG,
                movedZ * friction);

        return new ParkourState(feet, nextVelocity, movedBox, input.yaw(), onGround,
                sprinting, jumpUsed, collision.xCollision() || collision.zCollision(),
                collision.yCollision(), state.elapsedTicks() + 1, state.baseMovementSpeed(),
                state.jumpStrength(), state.stepHeight(), state.gravity(), tickEffects(state.activeEffects()));
    }

    private Vec3d movementVector(ControlInput input, double acceleration) {
        double side = input.strafe() * INPUT_SCALE;
        double forward = input.forward() * INPUT_SCALE;
        double lengthSquared = side * side + forward * forward;
        if (lengthSquared < 1.0E-7) return Vec3d.ZERO;
        double scale = lengthSquared > 1 ? acceleration / Math.sqrt(lengthSquared) : acceleration;
        side *= scale;
        forward *= scale;
        double radians = Math.toRadians(input.yaw());
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return new Vec3d(side * cos - forward * sin, 0, forward * cos + side * sin);
    }

    private CollisionResult move(PhysicsWorld world, Box box, Vec3d requested,
                                 boolean wasOnGround, double stepHeight) {
        Vec3d clipped = clip(world, box, requested);
        boolean xCollision = different(requested.x, clipped.x);
        boolean yCollision = different(requested.y, clipped.y);
        boolean zCollision = different(requested.z, clipped.z);
        boolean canStep = stepHeight > 0 && (wasOnGround || yCollision && requested.y < 0)
                && (xCollision || zCollision);
        if (canStep) {
            Vec3d raised = clip(world, box, new Vec3d(requested.x, stepHeight, requested.z));
            if (raised.horizontalLengthSquared() > clipped.horizontalLengthSquared()) {
                Vec3d descend = clip(world, box.offset(raised),
                        new Vec3d(0, requested.y - raised.y, 0));
                Vec3d stepped = raised.add(descend);
                if (stepped.horizontalLengthSquared() > clipped.horizontalLengthSquared()) clipped = stepped;
            }
        }
        xCollision = different(requested.x, clipped.x);
        yCollision = different(requested.y, clipped.y);
        zCollision = different(requested.z, clipped.z);
        return new CollisionResult(clipped, xCollision, yCollision, zCollision,
                yCollision && requested.y < 0);
    }

    private Vec3d clip(PhysicsWorld world, Box box, Vec3d movement) {
        if (movement.lengthSquared() < EPSILON) return movement;
        Box swept = sweptBox(box, movement).expand(1.0E-7);
        List<Box> obstacles = world.collisionBoxes(swept);
        double y = clipY(box, obstacles, movement.y);
        Box shifted = box.offset(0, y, 0);
        double x = movement.x;
        double z = movement.z;
        if (Math.abs(x) < Math.abs(z)) {
            x = clipX(shifted, obstacles, x);
            shifted = shifted.offset(x, 0, 0);
            z = clipZ(shifted, obstacles, z);
        } else {
            z = clipZ(shifted, obstacles, z);
            shifted = shifted.offset(0, 0, z);
            x = clipX(shifted, obstacles, x);
        }
        return new Vec3d(x, y, z);
    }

    private double clipX(Box box, List<Box> obstacles, double amount) {
        for (Box obstacle : obstacles) {
            if (!overlap(box.minY, box.maxY, obstacle.minY, obstacle.maxY)
                    || !overlap(box.minZ, box.maxZ, obstacle.minZ, obstacle.maxZ)) continue;
            if (amount > 0 && box.maxX <= obstacle.minX) amount = Math.min(amount, obstacle.minX - box.maxX);
            else if (amount < 0 && box.minX >= obstacle.maxX) amount = Math.max(amount, obstacle.maxX - box.minX);
        }
        return amount;
    }

    private double clipY(Box box, List<Box> obstacles, double amount) {
        for (Box obstacle : obstacles) {
            if (!overlap(box.minX, box.maxX, obstacle.minX, obstacle.maxX)
                    || !overlap(box.minZ, box.maxZ, obstacle.minZ, obstacle.maxZ)) continue;
            if (amount > 0 && box.maxY <= obstacle.minY) amount = Math.min(amount, obstacle.minY - box.maxY);
            else if (amount < 0 && box.minY >= obstacle.maxY) amount = Math.max(amount, obstacle.maxY - box.minY);
        }
        return amount;
    }

    private double clipZ(Box box, List<Box> obstacles, double amount) {
        for (Box obstacle : obstacles) {
            if (!overlap(box.minX, box.maxX, obstacle.minX, obstacle.maxX)
                    || !overlap(box.minY, box.maxY, obstacle.minY, obstacle.maxY)) continue;
            if (amount > 0 && box.maxZ <= obstacle.minZ) amount = Math.min(amount, obstacle.minZ - box.maxZ);
            else if (amount < 0 && box.minZ >= obstacle.maxZ) amount = Math.max(amount, obstacle.maxZ - box.minZ);
        }
        return amount;
    }

    private Vec3d clampSneaking(PhysicsWorld world, Box box, Vec3d movement) {
        double x = movement.x;
        double z = movement.z;
        while (Math.abs(x) > 1.0E-7 && !hasSupport(world, box.offset(x, 0, 0))) x = reduce(x, 0.05);
        while (Math.abs(z) > 1.0E-7 && !hasSupport(world, box.offset(0, 0, z))) z = reduce(z, 0.05);
        while (Math.abs(x) > 1.0E-7 && Math.abs(z) > 1.0E-7
                && !hasSupport(world, box.offset(x, 0, z))) {
            x = reduce(x, 0.05);
            z = reduce(z, 0.05);
        }
        return new Vec3d(x, movement.y, z);
    }

    private boolean hasSupport(PhysicsWorld world, Box box) {
        Box probe = new Box(box.minX + 1.0E-5, box.minY - SUPPORT_PROBE, box.minZ + 1.0E-5,
                box.maxX - 1.0E-5, box.minY, box.maxZ - 1.0E-5);
        return !world.collisionBoxes(probe).isEmpty();
    }

    private double jumpMultiplier(PhysicsWorld world, ParkourState state) {
        double atFeet = world.jumpMultiplier(BlockPos.ofFloored(state.feetPosition()));
        if (Math.abs(atFeet - 1) > 1.0E-6) return atFeet;
        return world.jumpMultiplier(BlockPos.ofFloored(state.feetPosition().x,
                state.boundingBox().minY - 0.5000001, state.feetPosition().z));
    }

    private double jumpBoost(Map<String, PlayerSnapshot.EffectSnapshot> effects) {
        PlayerSnapshot.EffectSnapshot effect = effects.get("minecraft:jump_boost");
        return effect == null ? 0 : 0.1 * (effect.amplifier() + 1);
    }

    private boolean hasEffect(Map<String, PlayerSnapshot.EffectSnapshot> effects, String id) {
        PlayerSnapshot.EffectSnapshot effect = effects.get(id);
        return effect != null && effect.remainingTicks() > 0;
    }

    private Map<String, PlayerSnapshot.EffectSnapshot> tickEffects(
            Map<String, PlayerSnapshot.EffectSnapshot> effects) {
        if (effects.isEmpty()) return Map.of();
        Map<String, PlayerSnapshot.EffectSnapshot> next = new HashMap<>();
        effects.forEach((id, effect) -> {
            if (effect.remainingTicks() > 1) next.put(id,
                    new PlayerSnapshot.EffectSnapshot(effect.amplifier(), effect.remainingTicks() - 1));
        });
        return Map.copyOf(next);
    }

    private Vec3d zeroTiny(Vec3d velocity) {
        return new Vec3d(Math.abs(velocity.x) < 0.003 ? 0 : velocity.x,
                Math.abs(velocity.y) < 0.003 ? 0 : velocity.y,
                Math.abs(velocity.z) < 0.003 ? 0 : velocity.z);
    }

    private Box sweptBox(Box box, Vec3d movement) {
        return new Box(Math.min(box.minX, box.minX + movement.x),
                Math.min(box.minY, box.minY + movement.y), Math.min(box.minZ, box.minZ + movement.z),
                Math.max(box.maxX, box.maxX + movement.x), Math.max(box.maxY, box.maxY + movement.y),
                Math.max(box.maxZ, box.maxZ + movement.z));
    }

    private boolean overlap(double aMin, double aMax, double bMin, double bMax) {
        return aMax > bMin + 1.0E-9 && aMin < bMax - 1.0E-9;
    }

    private boolean different(double expected, double actual) {
        return Math.abs(expected - actual) > 1.0E-7;
    }

    private double reduce(double value, double step) {
        if (Math.abs(value) <= step) return 0;
        return value > 0 ? value - step : value + step;
    }

    private record CollisionResult(Vec3d movement, boolean xCollision, boolean yCollision,
                                   boolean zCollision, boolean onGround) {}

    public static final class UnsupportedPhysicsStateException extends RuntimeException {
        public UnsupportedPhysicsStateException(String reason) { super(reason); }
    }
}

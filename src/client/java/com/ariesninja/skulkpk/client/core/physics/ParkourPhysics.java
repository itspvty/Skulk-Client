/*
 * This file contains movement logic derived from LiquidBounce's Minecraft movement
 * simulation. LiquidBounce is licensed under GPL-3.0; see the repository notices.
 */
package com.ariesninja.skulkpk.client.core.physics;

import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Authoritative immutable dry-land Minecraft movement kernel used by planning and recovery. */
public final class ParkourPhysics {
    private static final double VERTICAL_DRAG = 0.9800000190734863;
    private static final float AIR_FRICTION = 0.91F;
    private static final float INPUT_SCALE = 0.98F;
    private static final double EPSILON = 1.0E-7;
    private static final double SUPPORT_PROBE = 1.0E-4;

    public PhysicsStep tick(PhysicsWorld world, ParkourState state, ControlInput input) {
        return tick(world, state, input, true);
    }

    /** Identical mechanics without allocating contact diagnostics for discarded MPC nodes. */
    public ParkourState tickState(PhysicsWorld world, ParkourState state, ControlInput input) {
        return tick(world, state, input, false).state();
    }

    private PhysicsStep tick(PhysicsWorld world, ParkourState state, ControlInput input, boolean recordContacts) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        BlockPos feetBlock = BlockPos.ofFloored(state.feetPosition());
        if (world.hasFluid(feetBlock) || world.isClimbable(feetBlock) && !world.isLadder(feetBlock)) {
            throw new UnsupportedPhysicsStateException("fluid_or_unsupported_climbable");
        }

        Vec3d velocity = zeroTiny(state.velocity());
        // jumpUsed belongs to one airborne episode.  Keeping it latched forever made the
        // otherwise-authoritative kernel incapable of modelling a momentum jump which lands
        // and commits the actual gap jump on the next command epoch.
        boolean jumpWasUsed = !state.onGround() && state.jumpUsed();
        boolean jumped = input.jump() && state.onGround() && !jumpWasUsed;
        // ClientPlayerEntity computes shouldSlowDown before KeyboardInput.tick: the
        // preceding sneak command controls input scaling, but the new command holds ladders.
        // The client recomputes its input pose before movement. The physical box can remain
        // crouched for an extra epoch after release; its height alone is not the slow-input flag.
        Box standingBox = new Box(state.boundingBox().minX, state.boundingBox().minY,
                state.boundingBox().minZ, state.boundingBox().maxX,
                state.boundingBox().minY + 1.8F, state.boundingBox().maxZ);
        boolean forcedCrouch = state.boundingBox().getLengthY() < 1.6
                && !world.collisionBoxes(standingBox.contract(EPSILON)).isEmpty();
        boolean slowInput = state.previousSneak() || forcedCrouch;
        // Sprint key requests a state transition; releasing it does not stop a forward sprint.
        // A sideways/braking command or a hard collision does, even with the sprint key held.
        int sprintTapTicks = state.previousSneak() ? 0 : Math.max(0, state.sprintTapTicks() - 1);
        boolean walking = input.forward() > 0 && (!slowInput || state.sneakingSpeed() >= 0.8F);
        boolean canStartSprint = state.sprintAllowed() && !state.sprinting() && walking && !slowInput
                && !hasEffect(state.activeEffects(), "minecraft:blindness");
        boolean doubleTapSprint = false;
        if (state.onGround() && !state.previousSneak() && !state.previousForward() && canStartSprint) {
            if (sprintTapTicks > 0 || input.sprint()) doubleTapSprint = true;
            else sprintTapTicks = 7;
        }
        boolean sprinting = state.sprintAllowed() && (input.sprint() || state.sprinting() || doubleTapSprint)
                && input.forward() > 0
                && !slowInput && !hasEffect(state.activeEffects(), "minecraft:blindness")
                && (!state.horizontalCollision() || state.collidedSoftly());
        if (jumped) {
            float jumpVelocity = (float) state.jumpStrength() * (float) jumpMultiplier(world, state)
                    + (float) jumpBoost(state.activeEffects());
            float radians = input.yaw() * 0.017453292F;
            velocity = new Vec3d(velocity.x - MathHelper.sin(radians) * (sprinting ? 0.2 : 0),
                    Math.max(jumpVelocity, velocity.y),
                    velocity.z + MathHelper.cos(radians) * (sprinting ? 0.2 : 0));
        }

        BlockPos affecting = BlockPos.ofFloored(state.feetPosition().x,
                state.boundingBox().minY - 0.5000001, state.feetPosition().z);
        float slipperiness = (float) world.slipperiness(affecting);
        float acceleration = state.onGround()
                ? (float) (state.baseMovementSpeed() * (sprinting ? ParkourState.SPRINT_MULTIPLIER : 1.0))
                    * (0.21600002F / (slipperiness * slipperiness * slipperiness))
                : sprinting ? 0.025999999F : 0.02F;
        velocity = velocity.add(movementVector(input, acceleration, slowInput ? state.sneakingSpeed() : 1));
        // LivingEntity.applyClimbingSpeed (1.21.4): clamp before collision movement.
        // Ladder attachment uses the feet block, not body-box overlap with the thin shape.
        if (world.isLadder(feetBlock)) {
            double vertical = Math.max(velocity.y, -0.15000000596046448);
            if (input.sneak() && vertical < 0) vertical = 0;
            velocity = new Vec3d(Math.clamp(velocity.x, -0.15000000596046448, 0.15000000596046448),
                    vertical, Math.clamp(velocity.z, -0.15000000596046448, 0.15000000596046448));
        }
        Vec3d preCollisionVelocity = velocity;

        Vec3d requested = input.sneak() && state.onGround()
                ? clampSneaking(world, state.boundingBox(), velocity) : velocity;
        if (!world.contains(sweptBox(state.boundingBox(), requested).expand(0, state.stepHeight() + 0.01, 0))) {
            throw new UnsupportedPhysicsStateException("outside_captured_world");
        }
        CollisionResult collision = move(world, state.boundingBox(), requested,
                state.onGround(), state.stepHeight(), recordContacts);
        Box movedBox = state.boundingBox().offset(collision.movement());
        Vec3d feet = state.feetPosition().add(collision.movement());
        boolean onGround = collision.onGround() || (state.onGround()
                && Math.abs(requested.y) < EPSILON && hasSupport(world, movedBox));

        // Sneak's edge guard reduces displacement, not velocity. Only an actual shape
        // collision clears momentum; otherwise the unclamped velocity receives friction.
        double movedX = collision.xCollision() ? 0 : velocity.x;
        // Entity.move clears vertical velocity at either end of the axis: a ceiling is
        // not ground, but it still consumes the upward impulse before gravity/drag.
        double movedY = collision.yCollision() ? 0 : requested.y;
        double movedZ = collision.zCollision() ? 0 : velocity.z;
        // Vanilla checks climbing again AFTER moving, so catching a ladder can start the
        // ascent immediately. This is climb motion, not a second ground-jump impulse.
        if ((collision.xCollision() || collision.zCollision() || input.jump())
                && world.isLadder(BlockPos.ofFloored(feet))) movedY = 0.2;
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

        // PlayerEntity.updatePose runs after movement. Keep this tick's collision body,
        // then update dimensions for the next tick; never shrink before the contact occurs.
        Box nextBox = movedBox;
        double poseHeight = input.sneak() ? 1.5F : 1.8F;
        if (Math.abs(movedBox.getLengthY() - poseHeight) > EPSILON) {
            Box resized = new Box(movedBox.minX, movedBox.minY, movedBox.minZ,
                    movedBox.maxX, movedBox.minY + poseHeight, movedBox.maxZ);
            if (world.collisionBoxes(resized.contract(EPSILON)).isEmpty()) nextBox = resized;
        }
        boolean nextJumpUsed = !onGround && (jumpWasUsed || jumped);
        ParkourState next = new ParkourState(feet, nextVelocity, nextBox, input.yaw(), onGround,
                sprinting, nextJumpUsed, collision.xCollision() || collision.zCollision(),
                collision.yCollision(), state.elapsedTicks() + 1, state.baseMovementSpeed(),
                state.jumpStrength(), state.stepHeight(), state.gravity(), tickEffects(state.activeEffects()),
                input.sneak(), state.sneakingSpeed(), collision.xCollision() || collision.zCollision()
                    ? collidedSoftly(input, collision.movement(), slowInput ? state.sneakingSpeed() : 1) : false,
                sprintTapTicks, walking, state.sprintAllowed());
        List<CollisionContact> contacts = collision.contacts().stream().map(contact ->
                new CollisionContact(CollisionContact.featureId(contact.obstacle(), contact.face()),
                        contact.obstacle(), contact.face(), contact.axis(), contact.support(),
                        preCollisionVelocity, nextVelocity)).toList();
        return new PhysicsStep(next, new CollisionManifold(contacts, requested, collision.movement()));
    }

    private Vec3d movementVector(ControlInput input, float acceleration, double inputMultiplier) {
        // Preserve vanilla's float operations and sine lookup, including their order.
        // Double trigonometry looks more accurate but drifts across tight trigger boundaries.
        double side = (input.strafe() * (float) inputMultiplier) * INPUT_SCALE;
        double forward = (input.forward() * (float) inputMultiplier) * INPUT_SCALE;
        double lengthSquared = side * side + forward * forward;
        if (lengthSquared < 1.0E-7) return Vec3d.ZERO;
        if (lengthSquared > 1) {
            double inverseLength = 1 / Math.sqrt(lengthSquared);
            side *= inverseLength;
            forward *= inverseLength;
        }
        side *= acceleration;
        forward *= acceleration;
        float radians = input.yaw() * 0.017453292F;
        float sin = MathHelper.sin(radians);
        float cos = MathHelper.cos(radians);
        return new Vec3d(side * cos - forward * sin, 0, forward * cos + side * sin);
    }

    private boolean collidedSoftly(ControlInput input, Vec3d moved, double multiplier) {
        Vec3d intent = movementVector(input, 1, multiplier);
        double intentLength = intent.horizontalLengthSquared(), actualLength = moved.horizontalLengthSquared();
        if (intentLength < 9.999999747378752E-6 || actualLength < 9.999999747378752E-6) return false;
        double cosine = (intent.x * moved.x + intent.z * moved.z) / Math.sqrt(intentLength * actualLength);
        return Math.acos(cosine) < 0.13962633907794952;
    }

    private CollisionResult move(PhysicsWorld world, Box box, Vec3d requested,
                                 boolean wasOnGround, double stepHeight, boolean recordContacts) {
        ResolvedMovement clippedResult = clip(world, box, requested, recordContacts);
        Vec3d clipped = clippedResult.movement();
        boolean xCollision = different(requested.x, clipped.x);
        boolean yCollision = different(requested.y, clipped.y);
        boolean zCollision = different(requested.z, clipped.z);
        boolean canStep = stepHeight > 0 && (wasOnGround || yCollision && requested.y < 0)
                && (xCollision || zCollision);
        if (canStep) {
            ResolvedMovement raisedResult = clip(world, box,
                    new Vec3d(requested.x, stepHeight, requested.z), recordContacts);
            Vec3d raised = raisedResult.movement();
            if (raised.horizontalLengthSquared() > clipped.horizontalLengthSquared()) {
                ResolvedMovement descendResult = clip(world, box.offset(raised),
                        new Vec3d(0, requested.y - raised.y, 0), recordContacts);
                Vec3d descend = descendResult.movement();
                Vec3d stepped = raised.add(descend);
                if (stepped.horizontalLengthSquared() > clipped.horizontalLengthSquared()) {
                    List<ContactDraft> contacts = new ArrayList<>(raisedResult.contacts());
                    contacts.addAll(descendResult.contacts());
                    clippedResult = new ResolvedMovement(stepped, List.copyOf(contacts));
                    clipped = stepped;
                }
            }
        }
        xCollision = different(requested.x, clipped.x);
        yCollision = different(requested.y, clipped.y);
        zCollision = different(requested.z, clipped.z);
        return new CollisionResult(clipped, xCollision, yCollision, zCollision,
                yCollision && requested.y < 0, clippedResult.contacts());
    }

    private ResolvedMovement clip(PhysicsWorld world, Box box, Vec3d movement, boolean recordContacts) {
        if (movement.lengthSquared() < EPSILON) return new ResolvedMovement(movement, List.of());
        Box swept = sweptBox(box, movement).expand(1.0E-7);
        List<Box> obstacles = world.collisionBoxes(swept);
        List<ContactDraft> contacts = new ArrayList<>();
        AxisClip yClip = clipY(box, obstacles, movement.y, recordContacts);
        double y = yClip.amount();
        contacts.addAll(yClip.contacts());
        Box shifted = box.offset(0, y, 0);
        double x = movement.x;
        double z = movement.z;
        // Vanilla resolves the larger horizontal displacement first. Reversing this
        // order changes which face wins at a pillar corner even when both endpoints fit.
        if (Math.abs(x) >= Math.abs(z)) {
            AxisClip xClip = clipX(shifted, obstacles, x, recordContacts);
            x = xClip.amount();
            contacts.addAll(xClip.contacts());
            shifted = shifted.offset(x, 0, 0);
            AxisClip zClip = clipZ(shifted, obstacles, z, recordContacts);
            z = zClip.amount();
            contacts.addAll(zClip.contacts());
        } else {
            AxisClip zClip = clipZ(shifted, obstacles, z, recordContacts);
            z = zClip.amount();
            contacts.addAll(zClip.contacts());
            shifted = shifted.offset(0, 0, z);
            AxisClip xClip = clipX(shifted, obstacles, x, recordContacts);
            x = xClip.amount();
            contacts.addAll(xClip.contacts());
        }
        return new ResolvedMovement(new Vec3d(x, y, z), List.copyOf(contacts));
    }

    private AxisClip clipX(Box box, List<Box> obstacles, double amount, boolean recordContacts) {
        double requested = amount;
        for (Box obstacle : obstacles) {
            if (!overlap(box.minY, box.maxY, obstacle.minY, obstacle.maxY)
                    || !overlap(box.minZ, box.maxZ, obstacle.minZ, obstacle.maxZ)) continue;
            if (amount > 0 && box.maxX <= obstacle.minX) amount = Math.min(amount, obstacle.minX - box.maxX);
            else if (amount < 0 && box.minX >= obstacle.maxX) amount = Math.max(amount, obstacle.maxX - box.minX);
        }
        return new AxisClip(amount, recordContacts ? contactsForAxis(box, obstacles, requested, amount, CollisionAxis.X) : List.of());
    }

    private AxisClip clipY(Box box, List<Box> obstacles, double amount, boolean recordContacts) {
        double requested = amount;
        for (Box obstacle : obstacles) {
            if (!overlap(box.minX, box.maxX, obstacle.minX, obstacle.maxX)
                    || !overlap(box.minZ, box.maxZ, obstacle.minZ, obstacle.maxZ)) continue;
            if (amount > 0 && box.maxY <= obstacle.minY) amount = Math.min(amount, obstacle.minY - box.maxY);
            else if (amount < 0 && box.minY >= obstacle.maxY) amount = Math.max(amount, obstacle.maxY - box.minY);
        }
        return new AxisClip(amount, recordContacts ? contactsForAxis(box, obstacles, requested, amount, CollisionAxis.Y) : List.of());
    }

    private AxisClip clipZ(Box box, List<Box> obstacles, double amount, boolean recordContacts) {
        double requested = amount;
        for (Box obstacle : obstacles) {
            if (!overlap(box.minX, box.maxX, obstacle.minX, obstacle.maxX)
                    || !overlap(box.minY, box.maxY, obstacle.minY, obstacle.maxY)) continue;
            if (amount > 0 && box.maxZ <= obstacle.minZ) amount = Math.min(amount, obstacle.minZ - box.maxZ);
            else if (amount < 0 && box.minZ >= obstacle.maxZ) amount = Math.max(amount, obstacle.maxZ - box.minZ);
        }
        return new AxisClip(amount, recordContacts ? contactsForAxis(box, obstacles, requested, amount, CollisionAxis.Z) : List.of());
    }

    private List<ContactDraft> contactsForAxis(Box box, List<Box> obstacles, double requested,
                                               double resolved, CollisionAxis axis) {
        if (!different(requested, resolved)) return List.of();
        List<ContactDraft> result = new ArrayList<>();
        for (Box obstacle : obstacles) {
            if (!overlapsOtherAxes(box, obstacle, axis)) continue;
            double distance = axisDistance(box, obstacle, requested, axis);
            if (Double.isFinite(distance) && Math.abs(distance - resolved) <= 1.0E-7) {
                CollisionFace face = collisionFace(axis, requested);
                result.add(new ContactDraft(obstacle, face, axis,
                        axis == CollisionAxis.Y && requested < 0));
            }
        }
        return List.copyOf(result);
    }

    private boolean overlapsOtherAxes(Box box, Box obstacle, CollisionAxis axis) {
        return switch (axis) {
            case X -> overlap(box.minY, box.maxY, obstacle.minY, obstacle.maxY)
                    && overlap(box.minZ, box.maxZ, obstacle.minZ, obstacle.maxZ);
            case Y -> overlap(box.minX, box.maxX, obstacle.minX, obstacle.maxX)
                    && overlap(box.minZ, box.maxZ, obstacle.minZ, obstacle.maxZ);
            case Z -> overlap(box.minX, box.maxX, obstacle.minX, obstacle.maxX)
                    && overlap(box.minY, box.maxY, obstacle.minY, obstacle.maxY);
        };
    }

    private double axisDistance(Box box, Box obstacle, double requested, CollisionAxis axis) {
        if (requested > 0) return switch (axis) {
            case X -> box.maxX <= obstacle.minX ? obstacle.minX - box.maxX : Double.NaN;
            case Y -> box.maxY <= obstacle.minY ? obstacle.minY - box.maxY : Double.NaN;
            case Z -> box.maxZ <= obstacle.minZ ? obstacle.minZ - box.maxZ : Double.NaN;
        };
        if (requested < 0) return switch (axis) {
            case X -> box.minX >= obstacle.maxX ? obstacle.maxX - box.minX : Double.NaN;
            case Y -> box.minY >= obstacle.maxY ? obstacle.maxY - box.minY : Double.NaN;
            case Z -> box.minZ >= obstacle.maxZ ? obstacle.maxZ - box.minZ : Double.NaN;
        };
        return Double.NaN;
    }

    private CollisionFace collisionFace(CollisionAxis axis, double requested) {
        return switch (axis) {
            case X -> requested > 0 ? CollisionFace.WEST : CollisionFace.EAST;
            case Y -> requested > 0 ? CollisionFace.DOWN : CollisionFace.UP;
            case Z -> requested > 0 ? CollisionFace.NORTH : CollisionFace.SOUTH;
        };
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

    private record AxisClip(double amount, List<ContactDraft> contacts) {}
    private record ResolvedMovement(Vec3d movement, List<ContactDraft> contacts) {}
    private record ContactDraft(Box obstacle, CollisionFace face, CollisionAxis axis,
                                boolean support) {}
    private record CollisionResult(Vec3d movement, boolean xCollision, boolean yCollision,
                                   boolean zCollision, boolean onGround,
                                   List<ContactDraft> contacts) {}

    public static final class UnsupportedPhysicsStateException extends RuntimeException {
        public UnsupportedPhysicsStateException(String reason) { super(reason); }
    }
}

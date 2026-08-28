package com.ariesninja.skulkpk.client.core.analysis;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.Objects;

public record PlayerSnapshot(
        Vec3d feetPosition,
        Box boundingBox,
        Vec3d velocity,
        float yaw,
        boolean onGround,
        boolean sprinting,
        boolean sneaking,
        double movementSpeed,
        double jumpStrength,
        double stepHeight,
        double gravity,
        Map<String, EffectSnapshot> activeEffects,
        double sneakingSpeed,
        int sprintTapTicks,
        boolean previousForward,
        boolean sprintAllowed
) {
    public record EffectSnapshot(int amplifier, int remainingTicks) {}
    public PlayerSnapshot {
        feetPosition = Objects.requireNonNull(feetPosition);
        boundingBox = Objects.requireNonNull(boundingBox);
        velocity = Objects.requireNonNull(velocity);
        activeEffects = Map.copyOf(activeEffects);
    }

    /** Compatibility constructor for geometry fixtures. */
    public PlayerSnapshot(Vec3d feetPosition) {
        this(feetPosition, new Box(feetPosition.x - 0.3, feetPosition.y, feetPosition.z - 0.3,
                        feetPosition.x + 0.3, feetPosition.y + 1.8, feetPosition.z + 0.3),
                Vec3d.ZERO, 0, true, false, false, 0.1, 0.42, 0.6, Map.of());
    }

    public PlayerSnapshot(Vec3d feetPosition, Box boundingBox, Vec3d velocity, float yaw,
                          boolean onGround, boolean sprinting, boolean sneaking,
                          double movementSpeed, double jumpStrength, double stepHeight,
                          Map<String, EffectSnapshot> activeEffects) {
        this(feetPosition, boundingBox, velocity, yaw, onGround, sprinting, sneaking,
                movementSpeed, jumpStrength, stepHeight, 0.08, activeEffects);
    }

    public static PlayerSnapshot capture(PlayerEntity player) {
        java.util.HashMap<String, EffectSnapshot> effects = new java.util.HashMap<>();
        player.getStatusEffects().forEach(instance -> effects.put(
                Registries.STATUS_EFFECT.getId(instance.getEffectType().value()).toString(),
                new EffectSnapshot(instance.getAmplifier(), instance.getDuration())));
        return new PlayerSnapshot(
                player.getPos(), player.getBoundingBox(), player.getVelocity(), player.getYaw(),
                player.isOnGround(), player.isSprinting(), player.isSneaking(),
                player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED),
                player.getAttributeValue(EntityAttributes.JUMP_STRENGTH),
                player.getStepHeight(), player.getAttributeValue(EntityAttributes.GRAVITY), effects,
                player.getAttributeValue(EntityAttributes.SNEAKING_SPEED),
                player instanceof net.minecraft.client.network.ClientPlayerEntity client
                        ? client.ticksLeftToDoubleTapSprint : 0,
                player instanceof net.minecraft.client.network.ClientPlayerEntity client
                        && client.input.movementForward >= 0.8F,
                player.getHungerManager().getFoodLevel() > 6 || player.getAbilities().allowFlying);
    }

    public PlayerSnapshot(Vec3d feetPosition, Box boundingBox, Vec3d velocity, float yaw,
                          boolean onGround, boolean sprinting, boolean sneaking, double movementSpeed,
                          double jumpStrength, double stepHeight, double gravity,
                          Map<String, EffectSnapshot> activeEffects, double sneakingSpeed,
                          int sprintTapTicks, boolean previousForward) {
        this(feetPosition, boundingBox, velocity, yaw, onGround, sprinting, sneaking,
                movementSpeed, jumpStrength, stepHeight, gravity, activeEffects, sneakingSpeed,
                sprintTapTicks, previousForward, true);
    }

    public PlayerSnapshot(Vec3d feetPosition, Box boundingBox, Vec3d velocity, float yaw,
                          boolean onGround, boolean sprinting, boolean sneaking, double movementSpeed,
                          double jumpStrength, double stepHeight, double gravity,
                          Map<String, EffectSnapshot> activeEffects, double sneakingSpeed) {
        this(feetPosition, boundingBox, velocity, yaw, onGround, sprinting, sneaking,
                movementSpeed, jumpStrength, stepHeight, gravity, activeEffects, sneakingSpeed, 0, false);
    }

    public PlayerSnapshot(Vec3d feetPosition, Box boundingBox, Vec3d velocity, float yaw,
                          boolean onGround, boolean sprinting, boolean sneaking, double movementSpeed,
                          double jumpStrength, double stepHeight, double gravity,
                          Map<String, EffectSnapshot> activeEffects) {
        this(feetPosition, boundingBox, velocity, yaw, onGround, sprinting, sneaking,
                movementSpeed, jumpStrength, stepHeight, gravity, activeEffects, 0.3);
    }

    public Vec3d position() { return feetPosition; }
}

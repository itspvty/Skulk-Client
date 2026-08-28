package com.ariesninja.skulkpk.client.core.physics;

import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.Objects;

/** Complete immutable state for the supported dry-land movement kernel. */
public record ParkourState(
        Vec3d feetPosition,
        Vec3d velocity,
        Box boundingBox,
        float yaw,
        boolean onGround,
        boolean sprinting,
        boolean jumpUsed,
        boolean horizontalCollision,
        boolean verticalCollision,
        int elapsedTicks,
        double baseMovementSpeed,
        double jumpStrength,
        double stepHeight,
        double gravity,
        Map<String, PlayerSnapshot.EffectSnapshot> activeEffects,
        boolean previousSneak,
        double sneakingSpeed,
        boolean collidedSoftly,
        int sprintTapTicks,
        boolean previousForward,
        boolean sprintAllowed
) {
    // LivingEntity's ADD_MULTIPLIED_TOTAL sprint modifier is the widened float 0.3F.
    public static final double SPRINT_MULTIPLIER = 1.0 + 0.30000001192092896;
    public ParkourState {
        feetPosition = Objects.requireNonNull(feetPosition);
        velocity = Objects.requireNonNull(velocity);
        boundingBox = Objects.requireNonNull(boundingBox);
        activeEffects = Map.copyOf(activeEffects);
        if (elapsedTicks < 0) throw new IllegalArgumentException("Elapsed ticks cannot be negative.");
        if (baseMovementSpeed <= 0 || jumpStrength <= 0 || stepHeight < 0) {
            throw new IllegalArgumentException("Invalid player movement attributes.");
        }
    }

    public static ParkourState capture(PlayerSnapshot snapshot) {
        double baseSpeed = snapshot.sprinting()
                ? snapshot.movementSpeed() / SPRINT_MULTIPLIER : snapshot.movementSpeed();
        return new ParkourState(snapshot.feetPosition(), snapshot.velocity(), snapshot.boundingBox(),
                snapshot.yaw(), snapshot.onGround(), snapshot.sprinting(), !snapshot.onGround(),
                false, false, 0, baseSpeed, snapshot.jumpStrength(), snapshot.stepHeight(),
                snapshot.gravity(), snapshot.activeEffects(), snapshot.sneaking(), snapshot.sneakingSpeed(),
                false, snapshot.sprintTapTicks(), snapshot.previousForward(), snapshot.sprintAllowed());
    }

    public static ParkourState at(PlayerSnapshot snapshot, Vec3d feet, Vec3d velocity,
                                  float yaw, boolean onGround, boolean sprinting) {
        ParkourState captured = capture(snapshot);
        Box box = snapshot.boundingBox().offset(feet.subtract(snapshot.feetPosition()));
        return new ParkourState(feet, velocity, box, yaw, onGround, sprinting && captured.sprintAllowed, false,
                false, false, 0, captured.baseMovementSpeed, captured.jumpStrength,
                captured.stepHeight, captured.gravity, captured.activeEffects, false, captured.sneakingSpeed,
                false, 0, false, captured.sprintAllowed);
    }

    public ParkourState withCollisions(boolean horizontal, boolean vertical) {
        return withCollisions(horizontal, vertical, false);
    }

    public ParkourState withCollisions(boolean horizontal, boolean vertical, boolean soft) {
        return new ParkourState(feetPosition, velocity, boundingBox, yaw, onGround, sprinting,
                jumpUsed, horizontal, vertical, elapsedTicks, baseMovementSpeed, jumpStrength,
                stepHeight, gravity, activeEffects, previousSneak, sneakingSpeed, soft,
                sprintTapTicks, previousForward, sprintAllowed);
    }

    public ParkourState(Vec3d feetPosition, Vec3d velocity, Box boundingBox, float yaw,
                        boolean onGround, boolean sprinting, boolean jumpUsed,
                        boolean horizontalCollision, boolean verticalCollision, int elapsedTicks,
                        double baseMovementSpeed, double jumpStrength, double stepHeight, double gravity,
                        Map<String, PlayerSnapshot.EffectSnapshot> activeEffects,
                        boolean previousSneak, double sneakingSpeed, boolean collidedSoftly,
                        int sprintTapTicks, boolean previousForward) {
        this(feetPosition, velocity, boundingBox, yaw, onGround, sprinting, jumpUsed,
                horizontalCollision, verticalCollision, elapsedTicks, baseMovementSpeed, jumpStrength,
                stepHeight, gravity, activeEffects, previousSneak, sneakingSpeed, collidedSoftly,
                sprintTapTicks, previousForward, true);
    }

    public ParkourState(Vec3d feetPosition, Vec3d velocity, Box boundingBox, float yaw,
                        boolean onGround, boolean sprinting, boolean jumpUsed,
                        boolean horizontalCollision, boolean verticalCollision, int elapsedTicks,
                        double baseMovementSpeed, double jumpStrength, double stepHeight, double gravity,
                        Map<String, PlayerSnapshot.EffectSnapshot> activeEffects,
                        boolean previousSneak, double sneakingSpeed, boolean collidedSoftly) {
        this(feetPosition, velocity, boundingBox, yaw, onGround, sprinting, jumpUsed,
                horizontalCollision, verticalCollision, elapsedTicks, baseMovementSpeed, jumpStrength,
                stepHeight, gravity, activeEffects, previousSneak, sneakingSpeed, collidedSoftly, 0, false);
    }

    public ParkourState(Vec3d feetPosition, Vec3d velocity, Box boundingBox, float yaw,
                        boolean onGround, boolean sprinting, boolean jumpUsed,
                        boolean horizontalCollision, boolean verticalCollision, int elapsedTicks,
                        double baseMovementSpeed, double jumpStrength, double stepHeight, double gravity,
                        Map<String, PlayerSnapshot.EffectSnapshot> activeEffects,
                        boolean previousSneak, double sneakingSpeed) {
        this(feetPosition, velocity, boundingBox, yaw, onGround, sprinting, jumpUsed,
                horizontalCollision, verticalCollision, elapsedTicks, baseMovementSpeed, jumpStrength,
                stepHeight, gravity, activeEffects, previousSneak, sneakingSpeed, false);
    }

    public ParkourState(Vec3d feetPosition, Vec3d velocity, Box boundingBox, float yaw,
                        boolean onGround, boolean sprinting, boolean jumpUsed,
                        boolean horizontalCollision, boolean verticalCollision, int elapsedTicks,
                        double baseMovementSpeed, double jumpStrength, double stepHeight, double gravity,
                        Map<String, PlayerSnapshot.EffectSnapshot> activeEffects) {
        this(feetPosition, velocity, boundingBox, yaw, onGround, sprinting, jumpUsed,
                horizontalCollision, verticalCollision, elapsedTicks, baseMovementSpeed,
                jumpStrength, stepHeight, gravity, activeEffects, false, 0.3);
    }

    public ParkourState(Vec3d feetPosition, Vec3d velocity, Box boundingBox, float yaw,
                        boolean onGround, boolean sprinting, boolean jumpUsed,
                        boolean horizontalCollision, boolean verticalCollision, int elapsedTicks,
                        double baseMovementSpeed, double jumpStrength, double stepHeight,
                        Map<String, PlayerSnapshot.EffectSnapshot> activeEffects) {
        this(feetPosition, velocity, boundingBox, yaw, onGround, sprinting, jumpUsed,
                horizontalCollision, verticalCollision, elapsedTicks, baseMovementSpeed,
                jumpStrength, stepHeight, 0.08, activeEffects);
    }
}

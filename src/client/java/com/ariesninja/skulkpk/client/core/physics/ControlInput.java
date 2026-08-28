package com.ariesninja.skulkpk.client.core.physics;

import net.minecraft.util.math.Vec3d;

/** One immutable client input frame consumed by {@link ParkourPhysics}. */
public record ControlInput(
        float forward,
        float strafe,
        boolean sprint,
        boolean jump,
        boolean sneak,
        float yaw
) {
    public ControlInput {
        forward = keyAxis(forward);
        strafe = keyAxis(strafe);
    }

    /** KeyboardInput has three states per axis; fractional steering is not executable. */
    public static float keyAxis(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Movement input must be finite.");
        return value > 0.01f ? 1 : value < -0.01f ? -1 : 0;
    }

    public ControlInput withoutJump() {
        return jump ? new ControlInput(forward, strafe, sprint, false, sneak, yaw) : this;
    }

    /** World-space direction produced by positive Minecraft strafe for a route heading. */
    public static Vec3d strafeDirection(Vec3d heading) {
        Vec3d normalized = heading.normalize();
        return new Vec3d(normalized.z, 0, -normalized.x);
    }
}

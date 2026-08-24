package com.ariesninja.skulkpk.client.core.physics;

import net.minecraft.util.math.MathHelper;
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
        forward = MathHelper.clamp(forward, -1, 1);
        strafe = MathHelper.clamp(strafe, -1, 1);
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

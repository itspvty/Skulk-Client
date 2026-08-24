package com.ariesninja.skulkpk.client.core.planning;

import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;

import java.util.Objects;

public record TrajectorySample(
        int tick,
        Vec3d feetPosition,
        Vec3d velocity,
        Box boundingBox,
        boolean onGround,
        boolean horizontalCollision,
        boolean verticalCollision,
        ControlPhase phase,
        SupportKind support,
        double targetOverlap
) {
    public TrajectorySample {
        if (tick < 0) throw new IllegalArgumentException("Tick cannot be negative.");
        feetPosition = Objects.requireNonNull(feetPosition);
        velocity = Objects.requireNonNull(velocity);
        boundingBox = Objects.requireNonNull(boundingBox);
        phase = Objects.requireNonNull(phase);
        support = Objects.requireNonNull(support);
        targetOverlap = Math.max(0, targetOverlap);
    }

    public TrajectorySample(int tick, Vec3d feetPosition, Vec3d velocity, boolean onGround) {
        this(tick, feetPosition, velocity,
                new Box(feetPosition.x - 0.3, feetPosition.y, feetPosition.z - 0.3,
                        feetPosition.x + 0.3, feetPosition.y + 1.8, feetPosition.z + 0.3),
                onGround, false, false, onGround ? ControlPhase.RUN_UP : ControlPhase.AIRBORNE,
                SupportKind.NONE, 0);
    }
}

package com.ariesninja.skulkpk.client.core.physics;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

/** One resolved player/shape contact produced by an authoritative physics step. */
public record CollisionContact(
        String featureId,
        Box obstacle,
        CollisionFace face,
        CollisionAxis axis,
        boolean support,
        Vec3d preVelocity,
        Vec3d postVelocity
) {
    public CollisionContact {
        featureId = Objects.requireNonNull(featureId);
        obstacle = Objects.requireNonNull(obstacle);
        face = Objects.requireNonNull(face);
        axis = Objects.requireNonNull(axis);
        preVelocity = Objects.requireNonNull(preVelocity);
        postVelocity = Objects.requireNonNull(postVelocity);
    }

    static String featureId(Box box, CollisionFace face) {
        return Long.toUnsignedString(mix(Double.doubleToLongBits(box.minX),
                Double.doubleToLongBits(box.minY), Double.doubleToLongBits(box.minZ),
                Double.doubleToLongBits(box.maxX), Double.doubleToLongBits(box.maxY),
                Double.doubleToLongBits(box.maxZ), face.ordinal()), 36);
    }

    private static long mix(long... values) {
        long hash = 0xcbf29ce484222325L;
        for (long value : values) {
            hash ^= value;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}

package com.ariesninja.skulkpk.client.core.analysis;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

/** A collision-shape top which can support a standing player. */
public record StandableSurface(BlockPos block, Box footprint, double topY) {
    public StandableSurface {
        block = Objects.requireNonNull(block).toImmutable();
        footprint = Objects.requireNonNull(footprint);
        if (footprint.getLengthX() <= 0 || footprint.getLengthZ() <= 0) {
            throw new IllegalArgumentException("A standable surface needs a non-empty footprint.");
        }
    }

    public Vec3d centerFeet() {
        return new Vec3d(
                (footprint.minX + footprint.maxX) * 0.5,
                topY,
                (footprint.minZ + footprint.maxZ) * 0.5);
    }

    public boolean containsFeet(Vec3d feet, double inset) {
        return feet.y >= topY - 0.08 && feet.y <= topY + 0.18
                && feet.x >= footprint.minX + inset && feet.x <= footprint.maxX - inset
                && feet.z >= footprint.minZ + inset && feet.z <= footprint.maxZ - inset;
    }
}

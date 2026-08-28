package com.ariesninja.skulkpk.client.core.analysis;

import com.ariesninja.skulkpk.client.core.physics.PhysicsWorld;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

public interface WorldView extends PhysicsWorld {
    record Cell(List<Box> collisions, boolean solid, boolean air, boolean ladder,
                boolean climbable, boolean fluid, double slipperiness, double jumpMultiplier) {
        public Cell { collisions = List.copyOf(collisions); }
    }

    /** Captured on the client thread; contains no live world/block-state references. */
    default Cell captureCell(BlockPos pos) {
        return new Cell(collisionBoxes(new Box(pos)), isSolid(pos), isAir(pos), isLadder(pos),
                isClimbable(pos), hasFluid(pos), slipperiness(pos), jumpMultiplier(pos));
    }
    boolean isSolid(BlockPos pos);
    boolean isAir(BlockPos pos);
    boolean isLadder(BlockPos pos);
    boolean hasCollision(Box box);
    int topY();

    /** World-space collision boxes intersecting {@code region}. */
    default List<Box> collisionBoxes(Box region) {
        List<Box> boxes = new ArrayList<>();
        int minX = (int) Math.floor(region.minX);
        int maxX = (int) Math.ceil(region.maxX);
        int minY = (int) Math.floor(region.minY);
        int maxY = (int) Math.ceil(region.maxY);
        int minZ = (int) Math.floor(region.minZ);
        int maxZ = (int) Math.ceil(region.maxZ);
        for (int x = minX; x < maxX; x++) for (int y = minY; y < maxY; y++)
            for (int z = minZ; z < maxZ; z++) {
                BlockPos pos = new BlockPos(x, y, z);
                Box block = new Box(x, y, z, x + 1, y + 1, z + 1);
                if (isSolid(pos) && block.intersects(region)) boxes.add(block);
            }
        return List.copyOf(boxes);
    }

    default List<StandableSurface> standableSurfaces(BlockPos pos) {
        return SupportGeometry.surfaces(this, pos, captureCell(pos).collisions());
    }

    default double slipperiness(BlockPos pos) { return 0.6; }
    default double jumpMultiplier(BlockPos pos) { return 1.0; }
    default boolean hasFluid(BlockPos pos) { return false; }
    default boolean isClimbable(BlockPos pos) { return isLadder(pos); }
    default Object identityToken() { return this; }

    /** Cheap deterministic invalidation token for the geometry relevant to a plan. */
    default long fingerprint(Box region) {
        long hash = 0xcbf29ce484222325L;
        for (Box box : collisionBoxes(region)) {
            hash = (hash ^ Double.doubleToLongBits(box.minX)) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(box.minY)) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(box.minZ)) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(box.maxX)) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(box.maxY)) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(box.maxZ)) * 0x100000001b3L;
        }
        return hash;
    }
}

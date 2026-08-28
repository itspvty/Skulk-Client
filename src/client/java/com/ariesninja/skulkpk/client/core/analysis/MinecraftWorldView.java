package com.ariesninja.skulkpk.client.core.analysis;

import net.minecraft.block.BlockState;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftWorldView implements WorldView {
    private static final Cell AIR = new Cell(List.of(), false, true, false, false, false, 0.6F, 1);
    private final World world;

    public MinecraftWorldView(World world) { this.world = world; }
    @Override public Cell captureCell(BlockPos pos) {
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4))
            throw new IllegalArgumentException("Planning region includes unloaded chunks.");
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return AIR;
        return new Cell(state.getCollisionShape(world, pos, ShapeContext.absent())
                .getBoundingBoxes().stream().map(box -> box.offset(pos)).toList(),
                state.isSolidBlock(world, pos), state.isAir(), state.getBlock() instanceof LadderBlock,
                state.isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE), !state.getFluidState().isEmpty(),
                state.getBlock().getSlipperiness(), state.getBlock().getJumpVelocityMultiplier());
    }

    /** Includes movement properties/fluid/ladder changes, not only visible collision boxes. */
    @Override public long fingerprint(Box region) {
        region = region.expand(1); // Includes the captured property/neighbor-shape halo.
        // Keep shape hashing as well: moving-piston/block-entity collision can change
        // without the block-state id changing.
        long hash = WorldView.super.fingerprint(region);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = (int) Math.floor(region.minX); x < Math.ceil(region.maxX); x++)
            for (int y = (int) Math.floor(region.minY); y < Math.ceil(region.maxY); y++)
                for (int z = (int) Math.floor(region.minZ); z < Math.ceil(region.maxZ); z++) {
                    hash = (hash ^ net.minecraft.block.Block.getRawIdFromState(
                            world.getBlockState(pos.set(x, y, z)))) * 0x100000001b3L;
                }
        return hash;
    }
    @Override public boolean isSolid(BlockPos pos) { return world.getBlockState(pos).isSolidBlock(world, pos); }
    @Override public boolean isAir(BlockPos pos) { return world.getBlockState(pos).isAir(); }
    @Override public boolean isLadder(BlockPos pos) { return world.getBlockState(pos).getBlock() instanceof LadderBlock; }
    @Override public boolean hasCollision(Box box) { return world.getBlockCollisions(null, box).iterator().hasNext(); }
    @Override public int topY() { return world.getTopYInclusive(); }

    @Override
    public List<Box> collisionBoxes(Box region) {
        List<Box> boxes = new ArrayList<>();
        for (VoxelShape shape : world.getBlockCollisions(null, region)) boxes.addAll(shape.getBoundingBoxes());
        return List.copyOf(boxes);
    }

    @Override
    public List<StandableSurface> standableSurfaces(BlockPos pos) {
        return SupportGeometry.surfaces(this, pos, captureCell(pos).collisions());
    }

    @Override public double slipperiness(BlockPos pos) { return world.getBlockState(pos).getBlock().getSlipperiness(); }
    @Override public double jumpMultiplier(BlockPos pos) { return world.getBlockState(pos).getBlock().getJumpVelocityMultiplier(); }
    @Override public boolean hasFluid(BlockPos pos) {
        FluidState fluid = world.getFluidState(pos);
        return !fluid.isEmpty();
    }
    @Override public boolean isClimbable(BlockPos pos) { return world.getBlockState(pos).isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE); }
    @Override public Object identityToken() { return world; }
}

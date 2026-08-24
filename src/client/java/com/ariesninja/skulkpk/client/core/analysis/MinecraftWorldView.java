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
    private final World world;

    public MinecraftWorldView(World world) { this.world = world; }
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
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return List.of();
        VoxelShape shape = state.getCollisionShape(world, pos, ShapeContext.absent());
        List<StandableSurface> surfaces = new ArrayList<>();
        for (Box local : shape.getBoundingBoxes()) {
            Box worldBox = local.offset(pos);
            double top = worldBox.maxY;
            if (worldBox.getLengthX() < 0.2 || worldBox.getLengthZ() < 0.2) continue;
            Box body = new Box(
                    (worldBox.minX + worldBox.maxX) * 0.5 - 0.3, top,
                    (worldBox.minZ + worldBox.maxZ) * 0.5 - 0.3,
                    (worldBox.minX + worldBox.maxX) * 0.5 + 0.3, top + 1.8,
                    (worldBox.minZ + worldBox.maxZ) * 0.5 + 0.3);
            if (!hasCollision(body)) surfaces.add(new StandableSurface(pos, worldBox, top));
        }
        return List.copyOf(surfaces);
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

package com.ariesninja.skulkpk.client.core.physics;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;

/** Read-only movement properties required by the deterministic parkour kernel. */
public interface PhysicsWorld {
    List<Box> collisionBoxes(Box region);
    double slipperiness(BlockPos pos);
    double jumpMultiplier(BlockPos pos);
    boolean hasFluid(BlockPos pos);
    boolean isClimbable(BlockPos pos);
    int topY();
}

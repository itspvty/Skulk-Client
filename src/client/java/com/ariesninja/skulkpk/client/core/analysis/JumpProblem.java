package com.ariesninja.skulkpk.client.core.analysis;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.Objects;

/** Immutable geometry and state captured when a target is selected. */
public record JumpProblem(
        BlockPos selectedBlock,
        StandableSurface standingSurface,
        List<StandableSurface> landingRegion,
        List<StandableSurface> reachableTakeoffs,
        List<StandableSurface> approachRegion,
        List<Box> nearbyCollision,
        PlayerSnapshot player,
        long worldFingerprint
) {
    public JumpProblem {
        selectedBlock = Objects.requireNonNull(selectedBlock).toImmutable();
        standingSurface = Objects.requireNonNull(standingSurface);
        landingRegion = List.copyOf(landingRegion);
        reachableTakeoffs = List.copyOf(reachableTakeoffs);
        approachRegion = List.copyOf(approachRegion);
        nearbyCollision = List.copyOf(nearbyCollision);
        player = Objects.requireNonNull(player);
        if (landingRegion.isEmpty()) throw new IllegalArgumentException("Landing region cannot be empty.");
        if (reachableTakeoffs.isEmpty()) throw new IllegalArgumentException("Takeoff set cannot be empty.");
        if (approachRegion.isEmpty()) throw new IllegalArgumentException("Approach region cannot be empty.");
    }

    public JumpProblem(BlockPos selectedBlock, StandableSurface standingSurface,
                       List<StandableSurface> landingRegion,
                       List<StandableSurface> reachableTakeoffs, List<Box> nearbyCollision,
                       PlayerSnapshot player, long worldFingerprint) {
        this(selectedBlock, standingSurface, landingRegion, reachableTakeoffs,
                reachableTakeoffs, nearbyCollision, player, worldFingerprint);
    }
}

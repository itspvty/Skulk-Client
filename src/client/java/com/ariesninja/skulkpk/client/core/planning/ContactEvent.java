package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.physics.CollisionFace;
import net.minecraft.util.math.Box;

import java.util.Objects;

/** Contact feature and state tube expected during a planned route. */
public record ContactEvent(
        String featureId,
        Box obstacle,
        ContactRequirement requirement,
        CollisionFace face,
        Box entryFeetBounds,
        Box exitFeetBounds,
        int earliestTick,
        int latestTick
) {
    public ContactEvent {
        featureId = Objects.requireNonNull(featureId);
        obstacle = Objects.requireNonNull(obstacle);
        requirement = Objects.requireNonNull(requirement);
        face = Objects.requireNonNull(face);
        entryFeetBounds = Objects.requireNonNull(entryFeetBounds);
        exitFeetBounds = Objects.requireNonNull(exitFeetBounds);
        if (earliestTick < 0 || latestTick < earliestTick) {
            throw new IllegalArgumentException("Invalid contact event window.");
        }
    }
}

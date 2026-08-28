package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.Objects;

/** Safe staging region, exact ground reference, and the committed jump-input window. */
public record ApproachPlan(
        Box stagingRegion,
        List<StandableSurface> supportRegion,
        List<ParkourState> referenceStates,
        Box launchWindow,
        int commitIndex,
        int lastSupportedIndex
) {
    public ApproachPlan {
        stagingRegion = Objects.requireNonNull(stagingRegion);
        supportRegion = List.copyOf(supportRegion);
        referenceStates = List.copyOf(referenceStates);
        launchWindow = Objects.requireNonNull(launchWindow);
        if (commitIndex < 0 || lastSupportedIndex < commitIndex) {
            throw new IllegalArgumentException("Invalid takeoff commitment bounds.");
        }
    }
}

package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

/** Fixed route coordinates for one takeoff-edge sample and one settle anchor. */
public record LaunchLane(
        int id,
        StandableSurface takeoffSurface,
        Vec3d edgeStart,
        Vec3d edgeEnd,
        Vec3d takeoffPoint,
        Box approachCorridor,
        Vec3d heading,
        float yaw,
        double triggerMinimum,
        double triggerMaximum,
        double availableRunUp,
        LandingZone.LandingAnchor landingAnchor
) {
    public LaunchLane {
        takeoffSurface = Objects.requireNonNull(takeoffSurface);
        edgeStart = Objects.requireNonNull(edgeStart);
        edgeEnd = Objects.requireNonNull(edgeEnd);
        takeoffPoint = Objects.requireNonNull(takeoffPoint);
        approachCorridor = Objects.requireNonNull(approachCorridor);
        heading = Objects.requireNonNull(heading).normalize();
        landingAnchor = Objects.requireNonNull(landingAnchor);
        if (triggerMaximum < triggerMinimum || availableRunUp < 0) {
            throw new IllegalArgumentException("Invalid launch lane interval.");
        }
    }

    /** Positive while the player's feet are still behind the safe edge. */
    public double distanceBeforeEdge(Vec3d feet) {
        return takeoffPoint.subtract(feet).dotProduct(heading);
    }

    public double lateralError(Vec3d feet) {
        Vec3d side = ControlInput.strafeDirection(heading);
        return feet.subtract(takeoffPoint).dotProduct(side);
    }

    public boolean inTriggerInterval(Vec3d feet) {
        double remaining = distanceBeforeEdge(feet);
        return remaining >= triggerMinimum && remaining <= triggerMaximum;
    }
}

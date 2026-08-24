package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

public record LaunchEnvelope(
        Box positionBounds,
        Vec3d expectedVelocity,
        double maximumPositionError,
        double maximumVelocityError,
        float desiredYaw,
        float yawTolerance,
        Vec3d routeOrigin,
        Vec3d routeHeading,
        double minimumLongitudinal,
        double maximumLongitudinal,
        double minimumLateral,
        double maximumLateral,
        double minimumForwardSpeed,
        double maximumForwardSpeed
) {
    public LaunchEnvelope {
        positionBounds = Objects.requireNonNull(positionBounds);
        expectedVelocity = Objects.requireNonNull(expectedVelocity);
        routeOrigin = Objects.requireNonNull(routeOrigin);
        routeHeading = Objects.requireNonNull(routeHeading).normalize();
        if (maximumPositionError <= 0 || maximumVelocityError <= 0 || yawTolerance <= 0) {
            throw new IllegalArgumentException("Launch tolerances must be positive.");
        }
        if (maximumLongitudinal < minimumLongitudinal || maximumLateral < minimumLateral
                || maximumForwardSpeed < minimumForwardSpeed) {
            throw new IllegalArgumentException("Launch envelope ranges are inverted.");
        }
    }

    public LaunchEnvelope(Box positionBounds, Vec3d expectedVelocity,
                          double maximumPositionError, double maximumVelocityError,
                          float desiredYaw, float yawTolerance) {
        this(positionBounds, expectedVelocity, maximumPositionError, maximumVelocityError,
                desiredYaw, yawTolerance,
                new Vec3d((positionBounds.minX + positionBounds.maxX) * 0.5,
                        positionBounds.minY,
                        (positionBounds.minZ + positionBounds.maxZ) * 0.5),
                direction(desiredYaw), -positionBounds.getLengthX() * 0.5,
                positionBounds.getLengthX() * 0.5, -positionBounds.getLengthZ() * 0.5,
                positionBounds.getLengthZ() * 0.5, 0,
                Math.max(0.01, expectedVelocity.horizontalLength() + maximumVelocityError));
    }

    public boolean containsPosition(Vec3d feet) {
        Vec3d relative = feet.subtract(routeOrigin);
        Vec3d side = ControlInput.strafeDirection(routeHeading);
        double longitudinal = relative.dotProduct(routeHeading);
        double lateral = relative.dotProduct(side);
        return longitudinal >= minimumLongitudinal && longitudinal <= maximumLongitudinal
                && lateral >= minimumLateral && lateral <= maximumLateral
                && feet.y >= positionBounds.minY && feet.y <= positionBounds.maxY;
    }

    public boolean containsVelocity(Vec3d velocity) {
        double forward = velocity.dotProduct(routeHeading);
        return forward >= minimumForwardSpeed && forward <= maximumForwardSpeed
                && velocity.subtract(routeHeading.multiply(forward)).horizontalLength()
                    <= maximumVelocityError;
    }

    private static Vec3d direction(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(radians), 0, Math.cos(radians));
    }
}

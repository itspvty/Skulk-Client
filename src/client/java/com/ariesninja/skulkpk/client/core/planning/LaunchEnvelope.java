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
    // Live stair-launch traces differ by up to 8e-6 from double-precision prefixes.
    // Match the measured numeric precision, not a gameplay-sized safety allowance.
    private static final double NUMERIC_EPSILON = 1.0E-5;
    // Live float-based input/friction and a decaying staging velocity differed from the
    // nominal boundary by ~3e-6 blocks/tick. Do not treat that as lost launch support.
    // This is a comparison precision, not a wider physical velocity envelope.
    private static final double VELOCITY_EPSILON = 1.0E-5;
    public LaunchEnvelope {
        positionBounds = Objects.requireNonNull(positionBounds);
        expectedVelocity = Objects.requireNonNull(expectedVelocity);
        routeOrigin = Objects.requireNonNull(routeOrigin);
        routeHeading = Objects.requireNonNull(routeHeading).normalize();
        if (maximumPositionError <= 0 || maximumVelocityError <= 0 || yawTolerance < 0) {
            throw new IllegalArgumentException("Launch error bounds must be positive and yaw tolerance non-negative.");
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
        return longitudinal >= minimumLongitudinal - NUMERIC_EPSILON
                && longitudinal <= maximumLongitudinal + NUMERIC_EPSILON
                && lateral >= minimumLateral - NUMERIC_EPSILON
                && lateral <= maximumLateral + NUMERIC_EPSILON
                && feet.y >= positionBounds.minY - NUMERIC_EPSILON
                && feet.y <= positionBounds.maxY + NUMERIC_EPSILON;
    }

    public boolean containsVelocity(Vec3d velocity) {
        double forward = velocity.dotProduct(routeHeading);
        return forward >= minimumForwardSpeed - VELOCITY_EPSILON
                && forward <= maximumForwardSpeed + VELOCITY_EPSILON
                && velocity.subtract(expectedVelocity).horizontalLength()
                    <= maximumVelocityError + VELOCITY_EPSILON;
    }

    private static Vec3d direction(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(radians), 0, Math.cos(radians));
    }
}

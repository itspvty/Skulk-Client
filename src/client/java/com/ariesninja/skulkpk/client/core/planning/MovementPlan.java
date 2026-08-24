package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.Objects;

public record MovementPlan(
        List<Vec3d> positioningPath,
        List<ControlFrame> controlFrames,
        List<TrajectorySample> predictedTrajectory,
        List<StandableSurface> landingRegion,
        Vec3d stagingPosition,
        Vec3d takeoffPosition,
        boolean immediateLaunch,
        boolean currentStateLaunch,
        LaunchEnvelope launchEnvelope,
        PlanMetrics metrics,
        long worldFingerprint,
        Box fingerprintRegion,
        LandingZone landingZone,
        LaunchLane launchLane,
        Vec3d settleAnchor,
        PlanningStage planningStage
) {
    public MovementPlan {
        positioningPath = List.copyOf(positioningPath);
        controlFrames = List.copyOf(controlFrames);
        predictedTrajectory = List.copyOf(predictedTrajectory);
        landingRegion = List.copyOf(landingRegion);
        stagingPosition = Objects.requireNonNull(stagingPosition);
        takeoffPosition = Objects.requireNonNull(takeoffPosition);
        launchEnvelope = Objects.requireNonNull(launchEnvelope);
        metrics = Objects.requireNonNull(metrics);
        fingerprintRegion = Objects.requireNonNull(fingerprintRegion);
        landingZone = Objects.requireNonNull(landingZone);
        settleAnchor = Objects.requireNonNull(settleAnchor);
        planningStage = Objects.requireNonNull(planningStage);
        if (controlFrames.isEmpty() || predictedTrajectory.isEmpty() || landingRegion.isEmpty()) {
            throw new IllegalArgumentException("A movement plan needs controls, predictions, and a landing region.");
        }
    }

    public MovementPlan(List<Vec3d> positioningPath, List<ControlFrame> controlFrames,
                        List<TrajectorySample> predictedTrajectory, List<StandableSurface> landingRegion,
                        Vec3d stagingPosition, Vec3d takeoffPosition, boolean immediateLaunch,
                        PlanMetrics metrics, long worldFingerprint, Box fingerprintRegion) {
        this(positioningPath, controlFrames, predictedTrajectory, landingRegion, stagingPosition,
                takeoffPosition, immediateLaunch,
                false,
                new LaunchEnvelope(new Box(stagingPosition.x - 0.08, stagingPosition.y - 0.08,
                        stagingPosition.z - 0.08, stagingPosition.x + 0.08, stagingPosition.y + 0.12,
                        stagingPosition.z + 0.08), Vec3d.ZERO, 0.25, 0.12, 0, 2),
                metrics, worldFingerprint, fingerprintRegion,
                LandingZone.build(landingRegion, snapshotFor(stagingPosition)), null,
                predictedTrajectory.getLast().feetPosition(), PlanningStage.DIRECT);
    }

    public MovementPlan(List<Vec3d> positioningPath, List<ControlFrame> controlFrames,
                        List<TrajectorySample> predictedTrajectory, List<StandableSurface> landingRegion,
                        Vec3d stagingPosition, Vec3d takeoffPosition, boolean immediateLaunch,
                        LaunchEnvelope launchEnvelope, PlanMetrics metrics, long worldFingerprint,
                        Box fingerprintRegion) {
        this(positioningPath, controlFrames, predictedTrajectory, landingRegion, stagingPosition,
                takeoffPosition, immediateLaunch, false, launchEnvelope, metrics, worldFingerprint,
                fingerprintRegion, LandingZone.build(landingRegion, snapshotFor(stagingPosition)),
                null, predictedTrajectory.getLast().feetPosition(), PlanningStage.DIRECT);
    }

    private static com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot snapshotFor(Vec3d feet) {
        return new com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot(feet);
    }
}

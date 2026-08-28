package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Shared collision-shape support classification for planning and execution. */
public final class SupportResolver {
    private static final double VERTICAL_TOLERANCE = 0.13;
    private static final double MINIMUM_OVERLAP = 1.0E-4;

    private SupportResolver() {}

    public record Contact(SupportKind kind, double overlapArea) {
        public boolean targetSupported() { return kind == SupportKind.TARGET; }
    }

    public static Contact resolve(Box playerBox, Vec3d feet, boolean onGround,
                                  List<StandableSurface> target,
                                  List<StandableSurface> takeoff,
                                  WorldView world) {
        if (!onGround) return new Contact(SupportKind.NONE, 0);
        double targetOverlap = overlapArea(playerBox, feet.y, target);
        if (targetOverlap > MINIMUM_OVERLAP) {
            return new Contact(SupportKind.TARGET, targetOverlap);
        }
        double takeoffOverlap = overlapArea(playerBox, feet.y, takeoff);
        if (takeoffOverlap > MINIMUM_OVERLAP) {
            return new Contact(SupportKind.TAKEOFF, takeoffOverlap);
        }
        Box below = new Box(playerBox.minX, playerBox.minY - 0.08, playerBox.minZ,
                playerBox.maxX, playerBox.minY + 0.02, playerBox.maxZ);
        return world != null && !world.collisionBoxes(below).isEmpty()
                ? new Contact(SupportKind.OTHER, 0)
                : new Contact(SupportKind.NONE, 0);
    }

    public static double overlapArea(Box playerBox, double feetY, List<StandableSurface> region) {
        double total = 0;
        for (StandableSurface surface : region) {
            if (Math.abs(feetY - surface.topY()) > VERTICAL_TOLERANCE) continue;
            double overlapX = Math.max(0, Math.min(playerBox.maxX, surface.footprint().maxX)
                    - Math.max(playerBox.minX, surface.footprint().minX));
            double overlapZ = Math.max(0, Math.min(playerBox.maxZ, surface.footprint().maxZ)
                    - Math.max(playerBox.minZ, surface.footprint().minZ));
            total += overlapX * overlapZ;
        }
        return total;
    }

    public static boolean targetSupported(Box playerBox, Vec3d feet, boolean onGround,
                                          List<StandableSurface> region) {
        return onGround && overlapArea(playerBox, feet.y, region) > MINIMUM_OVERLAP;
    }

    public static double distanceToRegion(Vec3d feet, List<StandableSurface> region) {
        double best = Double.MAX_VALUE;
        for (StandableSurface surface : region) {
            double dx = Math.max(0, Math.max(surface.footprint().minX - feet.x,
                    feet.x - surface.footprint().maxX));
            double dz = Math.max(0, Math.max(surface.footprint().minZ - feet.z,
                    feet.z - surface.footprint().maxZ));
            best = Math.min(best, Math.hypot(dx, dz));
        }
        return best;
    }

    public static double edgeMargin(Box playerBox, double feetY, List<StandableSurface> region) {
        Vec3d center = new Vec3d((playerBox.minX + playerBox.maxX) * 0.5, feetY,
                (playerBox.minZ + playerBox.maxZ) * 0.5);
        double margin = Double.MAX_VALUE;
        for (double angle = 0; angle < Math.PI * 2; angle += Math.PI / 16) {
            double ray = 0;
            while (ray <= 6 && pointOnRegion(center.x + Math.cos(angle) * ray,
                    center.z + Math.sin(angle) * ray, feetY, region)) ray += 0.04;
            margin = Math.min(margin, ray - Math.max(playerBox.getLengthX(), playerBox.getLengthZ()) * 0.5);
        }
        return Math.max(0, margin);
    }

    private static boolean pointOnRegion(double x, double z, double feetY,
                                         List<StandableSurface> region) {
        for (StandableSurface surface : region) {
            if (Math.abs(feetY - surface.topY()) <= VERTICAL_TOLERANCE
                    && x >= surface.footprint().minX - 0.01 && x <= surface.footprint().maxX + 0.01
                    && z >= surface.footprint().minZ - 0.01 && z <= surface.footprint().maxZ + 0.01) return true;
        }
        return false;
    }
}

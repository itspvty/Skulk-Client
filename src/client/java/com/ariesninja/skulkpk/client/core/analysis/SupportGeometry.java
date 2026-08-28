package com.ariesninja.skulkpk.client.core.analysis;

import com.ariesninja.skulkpk.client.core.physics.PhysicsWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Collision-top support expressed in player-feet configuration space, including thin ledges. */
public final class SupportGeometry {
    private static final double EPS = 1.0E-6;
    private SupportGeometry() { }

    public static List<StandableSurface> surfaces(WorldView world, BlockPos pos, List<Box> shapes) {
        List<StandableSurface> result = new ArrayList<>();
        Box body = new Box(-0.3, 0, -0.3, 0.3, 1.8F, 0.3);
        for (Box shape : shapes) {
            if (shape.getLengthX() <= EPS || shape.getLengthZ() <= EPS) continue;
            StandableSurface surface = new StandableSurface(pos, shape, shape.maxY);
            if (!standingRegions(world, surface, body).isEmpty()) result.add(surface);
        }
        return List.copyOf(result);
    }

    /**
     * Any positive footprint overlap is support. Subtract expanded body obstacles from that
     * region, rather than requiring the feet to lie over the shape's center or full footprint.
     * The tiny epsilon excludes zero-area contact; it is not a gameplay safety inset.
     */
    public static List<Box> standingRegions(PhysicsWorld world, StandableSurface surface, Box body) {
        Box top = surface.footprint();
        double rx = body.getLengthX() / 2, rz = body.getLengthZ() / 2;
        double y = surface.topY();
        Box allowed = new Box(top.minX - rx + EPS, y, top.minZ - rz + EPS,
                top.maxX + rx - EPS, y + EPS, top.maxZ + rz - EPS);
        List<Box> regions = List.of(allowed);
        Box query = new Box(allowed.minX - rx, y + EPS, allowed.minZ - rz,
                allowed.maxX + rx, y + body.getLengthY() - EPS, allowed.maxZ + rz);
        for (Box obstacle : world.collisionBoxes(query)) {
            if (obstacle.maxY <= y + EPS || obstacle.minY >= y + body.getLengthY() - EPS) continue;
            Box forbidden = new Box(obstacle.minX - rx, y, obstacle.minZ - rz,
                    obstacle.maxX + rx, y + EPS, obstacle.maxZ + rz);
            List<Box> next = new ArrayList<>();
            for (Box region : regions) subtract(region, forbidden, next);
            regions = next;
            if (regions.isEmpty()) break;
        }
        return List.copyOf(regions);
    }

    public static Vec3d nearest(Box region, Vec3d point) {
        return new Vec3d(Math.clamp(point.x, region.minX, region.maxX), region.minY,
                Math.clamp(point.z, region.minZ, region.maxZ));
    }

    public static double overlap(Box body, StandableSurface surface) {
        Box top = surface.footprint();
        return Math.max(0, Math.min(body.maxX, top.maxX) - Math.max(body.minX, top.minX))
                * Math.max(0, Math.min(body.maxZ, top.maxZ) - Math.max(body.minZ, top.minZ));
    }

    private static void subtract(Box source, Box cut, List<Box> output) {
        double x0 = Math.max(source.minX, cut.minX), x1 = Math.min(source.maxX, cut.maxX);
        double z0 = Math.max(source.minZ, cut.minZ), z1 = Math.min(source.maxZ, cut.maxZ);
        if (x1 <= x0 || z1 <= z0) { output.add(source); return; }
        add(output, source.minX, source.minZ, x0, source.maxZ, source.minY);
        add(output, x1, source.minZ, source.maxX, source.maxZ, source.minY);
        add(output, x0, source.minZ, x1, z0, source.minY);
        add(output, x0, z1, x1, source.maxZ, source.minY);
    }

    private static void add(List<Box> output, double x0, double z0, double x1, double z1, double y) {
        if (x1 - x0 > EPS && z1 - z0 > EPS)
            output.add(new Box(x0, y, z0, x1, y + EPS, z1));
    }
}

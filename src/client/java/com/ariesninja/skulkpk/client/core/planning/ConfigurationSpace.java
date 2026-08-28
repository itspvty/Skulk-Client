package com.ariesninja.skulkpk.client.core.planning;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Player-sized free-space model used for staging and obstacle topology. */
public final class ConfigurationSpace {
    private static final double EPSILON = 1.0E-7;
    private final List<ConfigurationObstacle> obstacles;

    private ConfigurationSpace(List<ConfigurationObstacle> obstacles) {
        this.obstacles = List.copyOf(obstacles);
    }

    public static ConfigurationSpace compile(List<Box> collisionShapes, Box playerBounds) {
        double halfX = playerBounds.getLengthX() * 0.5;
        double halfZ = playerBounds.getLengthZ() * 0.5;
        double height = playerBounds.getLengthY();
        List<ConfigurationObstacle> result = new ArrayList<>();
        collisionShapes.stream().sorted(Comparator.comparingDouble((Box box) -> box.minY)
                .thenComparingDouble(box -> box.minX).thenComparingDouble(box -> box.minZ)
                .thenComparingDouble(box -> box.maxY).thenComparingDouble(box -> box.maxX)
                .thenComparingDouble(box -> box.maxZ)).forEach(shape -> {
            Box forbidden = new Box(shape.minX - halfX, shape.minY - height,
                    shape.minZ - halfZ, shape.maxX + halfX, shape.maxY, shape.maxZ + halfZ);
            result.add(new ConfigurationObstacle(shapeId(shape), shape, forbidden));
        });
        return new ConfigurationSpace(result);
    }

    public List<ConfigurationObstacle> obstacles() { return obstacles; }

    /** Obstacles that the player's full body would intersect along a feet-position segment. */
    public List<ConfigurationObstacle> intersecting(Vec3d fromFeet, Vec3d toFeet) {
        Box segment = new Box(Math.min(fromFeet.x, toFeet.x), Math.min(fromFeet.y, toFeet.y),
                Math.min(fromFeet.z, toFeet.z), Math.max(fromFeet.x, toFeet.x),
                Math.max(fromFeet.y, toFeet.y), Math.max(fromFeet.z, toFeet.z)).expand(EPSILON);
        return obstacles.stream().filter(obstacle -> intersectsOpen(segment, obstacle.forbiddenFeet()))
                .toList();
    }

    public boolean bodyClear(Vec3d feet) {
        return obstacles.stream().noneMatch(obstacle -> containsOpen(obstacle.forbiddenFeet(), feet));
    }

    public List<ConfigurationObstacle> routeObstacles(Vec3d takeoff, Vec3d landing) {
        return intersecting(takeoff.add(0, 0.01, 0), landing.add(0, 0.01, 0)).stream()
                // A support top exactly touching the feet plane is not body obstruction.
                .filter(obstacle -> obstacle.collisionShape().maxY > Math.min(takeoff.y, landing.y) + 0.01
                        || obstacle.collisionShape().minY < Math.max(takeoff.y, landing.y) - 0.01)
                .toList();
    }

    private static boolean containsOpen(Box box, Vec3d point) {
        return point.x > box.minX + EPSILON && point.x < box.maxX - EPSILON
                && point.y > box.minY + EPSILON && point.y < box.maxY - EPSILON
                && point.z > box.minZ + EPSILON && point.z < box.maxZ - EPSILON;
    }

    private static boolean intersectsOpen(Box first, Box second) {
        return first.maxX > second.minX + EPSILON && first.minX < second.maxX - EPSILON
                && first.maxY > second.minY + EPSILON && first.minY < second.maxY - EPSILON
                && first.maxZ > second.minZ + EPSILON && first.minZ < second.maxZ - EPSILON;
    }

    private static String shapeId(Box box) {
        long hash = 0xcbf29ce484222325L;
        for (double value : new double[]{box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ}) {
            hash ^= Double.doubleToLongBits(value);
            hash *= 0x100000001b3L;
        }
        return Long.toUnsignedString(hash, 36);
    }
}

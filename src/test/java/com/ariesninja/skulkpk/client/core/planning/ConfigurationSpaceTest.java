package com.ariesninja.skulkpk.client.core.planning;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationSpaceTest {
    private static final Box PLAYER = new Box(0.2, 0, 0.2, 0.8, 1.8, 0.8);

    @Test void floatingAndFullPillarsBlockTheSameFeetPath() {
        Box floating = new Box(1, 1, 0, 2, 2, 1);
        Box column = new Box(1, 0, 0, 2, 2, 1);
        Vec3d from = new Vec3d(0.5, 0, 0.5);
        Vec3d to = new Vec3d(2.5, 0, 0.5);

        assertEquals(1, ConfigurationSpace.compile(List.of(floating), PLAYER)
                .routeObstacles(from, to).size());
        assertEquals(1, ConfigurationSpace.compile(List.of(column), PLAYER)
                .routeObstacles(from, to).size());
    }

    @Test void supportTopTouchingFeetPlaneIsNotABodyObstacle() {
        Box floor = new Box(0, -1, 0, 1, 0, 1);
        ConfigurationSpace space = ConfigurationSpace.compile(List.of(floor), PLAYER);
        assertTrue(space.bodyClear(new Vec3d(0.5, 0, 0.5)));
        assertTrue(space.routeObstacles(new Vec3d(0.3, 0, 0.5),
                new Vec3d(0.7, 0, 0.5)).isEmpty());
    }

    @Test void forbiddenFeetSpaceUsesActualPlayerDimensions() {
        Box obstacle = new Box(1, 1.7, 0, 2, 2.7, 1);
        ConfigurationObstacle compiled = ConfigurationSpace.compile(List.of(obstacle), PLAYER)
                .obstacles().getFirst();
        assertEquals(0.7, compiled.forbiddenFeet().minX, 1.0E-9);
        assertEquals(-0.1, compiled.forbiddenFeet().minY, 1.0E-9);
        assertEquals(-0.3, compiled.forbiddenFeet().minZ, 1.0E-9);
    }
}

package com.ariesninja.skulkpk.client.core.analysis;

import com.ariesninja.skulkpk.client.core.JumpAnalyzer;
import com.ariesninja.skulkpk.client.core.physics.InMemoryPhysicsWorld;
import com.ariesninja.skulkpk.client.core.planning.LandingZone;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SupportGeometryTest {
    private InMemoryPhysicsWorld rungWorld() {
        return new InMemoryPhysicsWorld().box(new Box(1, -3, 0, 2, 5, 1))
                .box(new Box(0.8125, -1, 0, 1, 0, 1)).ladder(new BlockPos(0, -1, 0));
    }

    @Test void ladderTopHasLegalOutsideStancesEvenThoughItsCenterIsBlocked() {
        var world = rungWorld();
        var player = new PlayerSnapshot(new Vec3d(0.65, 0, 0.5));
        var surface = world.standableSurfaces(new BlockPos(0, -1, 0)).getFirst();
        assertEquals(0.1875, surface.footprint().getLengthX());
        assertTrue(world.collisionBoxes(player.boundingBox()).isEmpty(),
                "The fixture body starts exactly at the top: its bottom must not collide with the rung.");
        assertFalse(world.collisionBoxes(player.boundingBox()
                .offset(surface.centerFeet().subtract(player.feetPosition()))).isEmpty());
        assertTrue(SupportGeometry.standingRegions(world, surface, player.boundingBox()).stream()
                .anyMatch(region -> region.minX <= 0.65 && region.maxX >= 0.65
                        && region.minZ <= 0.5 && region.maxZ >= 0.5));
    }

    @Test void landingAnchorsDoNotPointInsideTheBackingWall() {
        var world = rungWorld();
        var player = new PlayerSnapshot(new Vec3d(0.65, 0, 0.5));
        var surfaces = world.standableSurfaces(new BlockPos(0, -1, 0));
        var zone = LandingZone.build(surfaces, player, world);
        assertTrue(zone.coreAnchors().isEmpty());
        assertFalse(zone.fringeAnchors().isEmpty());
        for (var anchor : zone.fringeAnchors()) {
            Box body = player.boundingBox().offset(anchor.feet().subtract(player.feetPosition()));
            assertTrue(world.collisionBoxes(body.contract(1.0E-7)).isEmpty());
            assertTrue(SupportGeometry.overlap(body, surfaces.getFirst()) > 0);
        }
    }

    @Test void selectionAcceptsARealRungTopAsEitherEndpoint() {
        var world = rungWorld().floor(0, -3, 0);
        var fromRung = new JumpAnalyzer().analyzeProblem(world,
                new PlayerSnapshot(new Vec3d(0.65, 0, 0.5)), new BlockPos(0, -1, -3));
        assertInstanceOf(JumpProblemResult.Valid.class, fromRung);
        var ontoRung = new JumpAnalyzer().analyzeProblem(world,
                new PlayerSnapshot(new Vec3d(0.5, 0, -2.5)), new BlockPos(0, -1, 0));
        assertInstanceOf(JumpProblemResult.Valid.class, ontoRung);
    }

    @Test void floatingNearASurfaceDoesNotCountAsStandingOnIt() {
        var world = new InMemoryPhysicsWorld().floor(0, 0, 0).floor(3, 0, 0);
        var result = new JumpAnalyzer().analyzeProblem(world,
                new PlayerSnapshot(new Vec3d(0.5, 0.5, 0.5)), new BlockPos(3, -1, 0));
        assertEquals(JumpRejectionReason.NO_TAKEOFF,
                assertInstanceOf(JumpProblemResult.Rejected.class, result).reason());
    }
}

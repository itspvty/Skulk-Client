package com.ariesninja.skulkpk.client.core;

import com.ariesninja.skulkpk.client.core.analysis.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JumpProblemAnalyzerTest {
    private final JumpAnalyzer analyzer = new JumpAnalyzer();
    private final PlayerSnapshot player = new PlayerSnapshot(new Vec3d(-1.5, 0, 0.5));

    @Test void buildsConnectedLandingAndAlternateTakeoffSurfaces() {
        ShapeWorld world = runwayAndGap();
        world.support(2, -1, 0, 1);
        world.support(3, -1, 0, 1);
        JumpProblem problem = valid(world, new BlockPos(2, -1, 0));
        assertEquals(2, problem.landingRegion().size());
        assertTrue(problem.reachableTakeoffs().size() >= 3);
        assertEquals(new Vec3d(-1.5, 0, 0.5), problem.player().feetPosition());
    }

    @Test void acceptsSlabStairsAndDiagonalCollisionTopGeometry() {
        ShapeWorld slab = runwayAndGap();
        slab.support(2, -1, 0, 0.5);
        assertEquals(-0.5, valid(slab, new BlockPos(2, -1, 0)).landingRegion().getFirst().topY(), 0.001);

        ShapeWorld stairLike = runwayAndGap();
        stairLike.surface(new BlockPos(2, -1, 1), new Box(2, -1, 1, 3, -0.5, 2));
        stairLike.surface(new BlockPos(2, -1, 1), new Box(2, -0.5, 1.5, 3, 0, 2));
        JumpProblem diagonal = valid(stairLike, new BlockPos(2, -1, 1));
        assertEquals(0, diagonal.landingRegion().getFirst().topY(), 0.001);
    }

    @Test void preservesOneBlockRiseAndEdgeDistanceBounds() {
        ShapeWorld rise = runwayAndGap();
        rise.support(2, 0, 0, 1);
        assertInstanceOf(JumpProblemResult.Valid.class,
                analyzer.analyzeProblem(rise, player, new BlockPos(2, 0, 0)));

        ShapeWorld high = runwayAndGap();
        high.support(2, 1, 0, 1);
        JumpProblemResult.Rejected tooHigh = assertInstanceOf(JumpProblemResult.Rejected.class,
                analyzer.analyzeProblem(high, player, new BlockPos(2, 1, 0)));
        assertEquals(JumpRejectionReason.TOO_HIGH, tooHigh.reason());

        ShapeWorld far = runwayAndGap();
        far.support(7, -1, 0, 1);
        JumpProblemResult.Rejected tooFar = assertInstanceOf(JumpProblemResult.Rejected.class,
                analyzer.analyzeProblem(far, player, new BlockPos(7, -1, 0)));
        assertEquals(JumpRejectionReason.TOO_FAR, tooFar.reason());
    }

    @Test void rejectsWalkOnlyFluidClimbableAndBlockedStandingRoutes() {
        ShapeWorld walking = runwayAndGap();
        walking.support(1, -1, 0, 1);
        walking.support(2, -1, 0, 1);
        assertEquals(JumpRejectionReason.NO_JUMP_REQUIRED, rejected(walking, new BlockPos(2, -1, 0)).reason());

        ShapeWorld fluid = runwayAndGap();
        fluid.support(2, -1, 0, 1);
        fluid.fluids.add(new BlockPos(2, -1, 0));
        assertEquals(JumpRejectionReason.UNSUPPORTED, rejected(fluid, new BlockPos(2, -1, 0)).reason());

        ShapeWorld climb = runwayAndGap();
        climb.support(2, -1, 0, 1);
        climb.climbables.add(new BlockPos(2, 0, 0));
        assertEquals(JumpRejectionReason.UNSUPPORTED, rejected(climb, new BlockPos(2, -1, 0)).reason());
    }

    @Test void rejectedProblemAtomicallyClearsAFormerSelection() {
        ShapeWorld validWorld = runwayAndGap();
        validWorld.support(2, -1, 0, 1);
        JumpProblemResult.Valid valid = assertInstanceOf(JumpProblemResult.Valid.class,
                analyzer.analyzeProblem(validWorld, player, new BlockPos(2, -1, 0)));
        BlockSelector.storeProblemResult(valid);
        assertTrue(BlockSelector.getCurrentProblem().isPresent());

        JumpProblemResult.Rejected rejected = assertInstanceOf(JumpProblemResult.Rejected.class,
                analyzer.analyzeProblem(new ShapeWorld(), player, new BlockPos(2, -1, 0)));
        BlockSelector.storeProblemResult(rejected);
        assertTrue(BlockSelector.getCurrentProblem().isEmpty());
    }

    private JumpProblem valid(ShapeWorld world, BlockPos selected) {
        return assertInstanceOf(JumpProblemResult.Valid.class,
                analyzer.analyzeProblem(world, player, selected)).problem();
    }
    private JumpProblemResult.Rejected rejected(ShapeWorld world, BlockPos selected) {
        return assertInstanceOf(JumpProblemResult.Rejected.class,
                analyzer.analyzeProblem(world, player, selected));
    }
    private ShapeWorld runwayAndGap() {
        ShapeWorld world = new ShapeWorld();
        for (int x = -3; x <= 0; x++) world.support(x, -1, 0, 1);
        return world;
    }

    private static final class ShapeWorld implements WorldView {
        private final Map<BlockPos, List<Box>> surfaces = new HashMap<>();
        private final Set<BlockPos> fluids = new HashSet<>();
        private final Set<BlockPos> climbables = new HashSet<>();
        void support(int x, int y, int z, double height) {
            surface(new BlockPos(x, y, z), new Box(x, y, z, x + 1, y + height, z + 1));
        }
        void surface(BlockPos pos, Box box) { surfaces.computeIfAbsent(pos, ignored -> new ArrayList<>()).add(box); }
        @Override public boolean isSolid(BlockPos pos) { return surfaces.containsKey(pos); }
        @Override public boolean isAir(BlockPos pos) { return !isSolid(pos); }
        @Override public boolean isLadder(BlockPos pos) { return climbables.contains(pos); }
        @Override public boolean hasFluid(BlockPos pos) { return fluids.contains(pos); }
        @Override public boolean isClimbable(BlockPos pos) { return climbables.contains(pos); }
        @Override public int topY() { return 64; }
        @Override public boolean hasCollision(Box box) {
            return surfaces.values().stream().flatMap(List::stream).anyMatch(box::intersects);
        }
        @Override public List<Box> collisionBoxes(Box region) {
            return surfaces.values().stream().flatMap(List::stream).filter(region::intersects).toList();
        }
        @Override public List<StandableSurface> standableSurfaces(BlockPos pos) {
            return surfaces.getOrDefault(pos, List.of()).stream()
                    .map(box -> new StandableSurface(pos, box, box.maxY)).toList();
        }
    }
}

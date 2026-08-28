package com.ariesninja.skulkpk.client.core;

import com.ariesninja.skulkpk.client.core.analysis.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/** Converts a selected collision surface and exact player state into bounded search geometry. */
public final class JumpAnalyzer {
    static final double MAX_LEVEL_OR_UPWARD_EDGE_DISTANCE = 4.3;

    public JumpProblemResult analyzeProblem(WorldView world, PlayerSnapshot player, BlockPos selectedBlock) {
        StandableSurface standing = findStandingSurface(world, player);
        if (standing == null) return rejected(JumpRejectionReason.NO_TAKEOFF,
                "No supported takeoff surface was found under the player.");
        StandableSurface selectedLanding = findSelectedSurface(world, selectedBlock);
        if (selectedLanding == null) return rejected(JumpRejectionReason.NO_LANDING,
                "No standable collision surface was found on the selected block.");
        if (world.hasFluid(standing.block()) || world.hasFluid(selectedLanding.block())
                || world.isClimbable(standing.block().up()) || world.isClimbable(selectedLanding.block().up())) {
            return rejected(JumpRejectionReason.UNSUPPORTED,
                    "Routes beginning or ending in liquid or climbable blocks are not supported.");
        }

        List<StandableSurface> landingRegion = connectedSameHeightRegion(world, selectedLanding, 12, 256);
        List<StandableSurface> walkingRegion = reachableWalkingRegion(world, standing, player.stepHeight(), 10, 256);
        // A ladder is an intermediate attachment goal. Applying a ballistic endpoint
        // distance bound to the final platform excludes valid catch-and-climb routes.
        Box assistBounds = regionBounds(player.boundingBox(), List.of(standing), landingRegion).expand(2, 3, 2);
        boolean ladderAssist = !com.ariesninja.skulkpk.client.core.planning.LadderColumn
                .discover(world, assistBounds).isEmpty();
        if (walkingRegion.stream().anyMatch(surface -> sameSurface(surface, selectedLanding))) {
            return rejected(JumpRejectionReason.NO_JUMP_REQUIRED,
                    "The selected target is reachable without a jump.");
        }
        List<StandableSurface> takeoffs = walkingRegion.stream()
                .filter(surface -> landingRegion.stream().noneMatch(landing -> sameSurface(surface, landing)))
                // The current planner generates flat supported approach prefixes. A reachable
                // floor below the player is collision context, not another runway root.
                .filter(surface -> Math.abs(surface.topY() - standing.topY()) <= 0.01)
                .filter(surface -> landingRegion.stream().anyMatch(landing ->
                        ladderAssist || landing.topY() < surface.topY() - 0.01
                                || edgeDistance(surface.footprint(), landing.footprint())
                                <= MAX_LEVEL_OR_UPWARD_EDGE_DISTANCE))
                .sorted(Comparator.comparingDouble(surface -> landingRegion.stream()
                        .mapToDouble(landing -> edgeDistance(surface.footprint(), landing.footprint()))
                        .min().orElse(Double.MAX_VALUE)))
                .toList();
        if (takeoffs.isEmpty()) return rejected(JumpRejectionReason.TOO_FAR,
                "No reachable takeoff is within the supported level/upward edge distance; "
                        + "vertical reach is evaluated by physics after analysis.");

        List<StandableSurface> approachRegion = connectedApproachRegion(walkingRegion, takeoffs);
        Box bounds = regionBounds(player.boundingBox(), approachRegion, landingRegion).expand(2, 3, 2);
        return new JumpProblemResult.Valid(new JumpProblem(selectedBlock, standing, landingRegion, takeoffs,
                approachRegion, world.collisionBoxes(bounds), player, world.fingerprint(bounds)));
    }

    /**
     * Planning prefixes are flat runway motion. Keep only the same-height walking components
     * connected to a legal takeoff instead of handing the solver every reachable drop below
     * the course (which can otherwise fill the 256-surface analysis bound).
     */
    private List<StandableSurface> connectedApproachRegion(
            List<StandableSurface> walkingRegion, List<StandableSurface> takeoffs) {
        Queue<StandableSurface> queue = new ArrayDeque<>(takeoffs);
        List<StandableSurface> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            StandableSurface current = queue.remove();
            if (!visited.add(surfaceKey(current))) continue;
            result.add(current);
            for (StandableSurface candidate : walkingRegion) {
                if (!visited.contains(surfaceKey(candidate))
                        && Math.abs(candidate.topY() - current.topY()) <= 0.01
                        && footprintsTouch(current.footprint(), candidate.footprint())) {
                    queue.add(candidate);
                }
            }
        }
        return List.copyOf(result);
    }

    private StandableSurface findStandingSurface(WorldView world, PlayerSnapshot player) {
        BlockPos feet = BlockPos.ofFloored(player.feetPosition().x, player.feetPosition().y - 0.01,
                player.feetPosition().z);
        StandableSurface best = null;
        double bestDelta = Double.MAX_VALUE;
        for (int dy = -2; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            for (StandableSurface surface : world.standableSurfaces(feet.add(dx, dy, dz))) {
                double horizontal = distanceToFootprint(player.feetPosition(), surface.footprint());
                double delta = Math.abs(player.feetPosition().y - surface.topY()) + horizontal * 2;
                if (Math.abs(player.feetPosition().y - surface.topY()) <= 0.02
                        && SupportGeometry.overlap(player.boundingBox(), surface) > 1.0E-6
                        && delta < bestDelta) { best = surface; bestDelta = delta; }
            }
        }
        return best;
    }

    private StandableSurface findSelectedSurface(WorldView world, BlockPos selected) {
        for (int dy = 0; dy <= 3; dy++) {
            List<StandableSurface> surfaces = world.standableSurfaces(selected.up(dy));
            if (!surfaces.isEmpty()) return surfaces.stream()
                    .max(Comparator.comparingDouble(StandableSurface::topY)).orElseThrow();
        }
        return null;
    }

    private List<StandableSurface> connectedSameHeightRegion(
            WorldView world, StandableSurface start, int radius, int maximum) {
        Queue<StandableSurface> queue = new ArrayDeque<>();
        List<StandableSurface> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty() && found.size() < maximum) {
            StandableSurface current = queue.remove();
            if (!visited.add(surfaceKey(current))) continue;
            found.add(current);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos nextPos = current.block().add(dx, dy, dz);
                    if (horizontalDistance(start.block(), nextPos) > radius) continue;
                    for (StandableSurface next : world.standableSurfaces(nextPos)) {
                        if (Math.abs(next.topY() - start.topY()) <= 0.01
                                && footprintsTouch(current.footprint(), next.footprint())) queue.add(next);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    private List<StandableSurface> reachableWalkingRegion(
            WorldView world, StandableSurface start, double stepHeight, int radius, int maximum) {
        Queue<StandableSurface> queue = new ArrayDeque<>();
        List<StandableSurface> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty() && found.size() < maximum) {
            StandableSurface current = queue.remove();
            if (!visited.add(surfaceKey(current))) continue;
            found.add(current);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 1; dy >= -3; dy--) {
                    BlockPos pos = current.block().add(dx, dy, dz);
                    if (horizontalDistance(start.block(), pos) > radius) continue;
                    for (StandableSurface next : world.standableSurfaces(pos)) {
                        double rise = next.topY() - current.topY();
                        if (rise <= stepHeight + 0.01 && rise >= -3.01
                                && footprintsTouch(current.footprint(), next.footprint())) queue.add(next);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    private boolean sameSurface(StandableSurface a, StandableSurface b) {
        return a.block().equals(b.block()) && a.footprint().equals(b.footprint());
    }
    private String surfaceKey(StandableSurface surface) {
        return surface.block().asLong() + ":" + surface.footprint();
    }
    private boolean footprintsTouch(Box a, Box b) {
        return a.maxX >= b.minX - 0.01 && b.maxX >= a.minX - 0.01
                && a.maxZ >= b.minZ - 0.01 && b.maxZ >= a.minZ - 0.01;
    }
    private double edgeDistance(Box a, Box b) {
        double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return Math.hypot(dx, dz);
    }
    private double distanceToFootprint(Vec3d point, Box box) {
        double dx = Math.max(0, Math.max(box.minX - point.x, point.x - box.maxX));
        double dz = Math.max(0, Math.max(box.minZ - point.z, point.z - box.maxZ));
        return Math.hypot(dx, dz);
    }
    private Box regionBounds(Box player, List<StandableSurface> takeoffs, List<StandableSurface> landings) {
        Box bounds = player;
        for (StandableSurface surface : takeoffs) bounds = bounds.union(surface.footprint());
        for (StandableSurface surface : landings) bounds = bounds.union(surface.footprint());
        return bounds;
    }
    private int horizontalDistance(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
    }
    private JumpProblemResult.Rejected rejected(JumpRejectionReason reason, String message) {
        return new JumpProblemResult.Rejected(reason, message);
    }
}

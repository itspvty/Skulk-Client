package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed, search-independent landing geometry and settle anchors. */
public record LandingZone(
        List<StandableSurface> surfaces,
        List<LandingAnchor> coreAnchors,
        List<LandingAnchor> fringeAnchors
) {
    public static final double CORE_SAFETY_MARGIN = 0.05;

    public LandingZone {
        surfaces = List.copyOf(surfaces);
        coreAnchors = List.copyOf(coreAnchors);
        fringeAnchors = List.copyOf(fringeAnchors);
        if (surfaces.isEmpty() || coreAnchors.isEmpty() && fringeAnchors.isEmpty()) {
            throw new IllegalArgumentException("A landing zone needs surfaces and at least one anchor.");
        }
    }

    public static LandingZone build(List<StandableSurface> surfaces, PlayerSnapshot player) {
        double width = player.boundingBox().getLengthX();
        double depth = player.boundingBox().getLengthZ();
        double height = player.boundingBox().getLengthY();
        List<LandingAnchor> all = new ArrayList<>();
        for (StandableSurface surface : surfaces) {
            Box footprint = surface.footprint();
            addAnchor(all, surfaces, surface.centerFeet(), width, depth, height);
            double spacing = 0.10;
            for (double x = footprint.minX + width * 0.5; x <= footprint.maxX - width * 0.5 + 1.0E-6; x += spacing) {
                for (double z = footprint.minZ + depth * 0.5; z <= footprint.maxZ - depth * 0.5 + 1.0E-6; z += spacing) {
                    addAnchor(all, surfaces, new Vec3d(x, surface.topY(), z), width, depth, height);
                }
            }
            // Legal partial-support anchors are sampled only as a fallback.
            for (double x = footprint.minX - width * 0.35; x <= footprint.maxX + width * 0.35; x += 0.20) {
                for (double z = footprint.minZ - depth * 0.35; z <= footprint.maxZ + depth * 0.35; z += 0.20) {
                    addAnchor(all, surfaces, new Vec3d(x, surface.topY(), z), width, depth, height);
                }
            }
        }

        Map<String, LandingAnchor> unique = new LinkedHashMap<>();
        all.stream().sorted(ANCHOR_ORDER).forEach(anchor -> unique.putIfAbsent(key(anchor.feet()), anchor));
        List<LandingAnchor> core = unique.values().stream().filter(LandingAnchor::core).limit(24).toList();
        List<LandingAnchor> fringe = unique.values().stream().filter(anchor -> !anchor.core()).limit(24).toList();
        return new LandingZone(surfaces, core, fringe);
    }

    public List<LandingAnchor> preferredAnchors() {
        return coreAnchors.isEmpty() ? fringeAnchors : coreAnchors;
    }

    public boolean isCore(Box playerBox, Vec3d feet) {
        double area = playerBox.getLengthX() * playerBox.getLengthZ();
        double overlap = Math.min(area, SupportResolver.overlapArea(playerBox, feet.y, surfaces));
        return overlap >= area - 1.0E-4
                && SupportResolver.edgeMargin(playerBox, feet.y, surfaces) >= CORE_SAFETY_MARGIN;
    }

    private static void addAnchor(List<LandingAnchor> anchors, List<StandableSurface> surfaces,
                                  Vec3d feet, double width, double depth, double height) {
        Box box = new Box(feet.x - width * 0.5, feet.y, feet.z - depth * 0.5,
                feet.x + width * 0.5, feet.y + height, feet.z + depth * 0.5);
        double area = width * depth;
        double overlap = Math.min(area, SupportResolver.overlapArea(box, feet.y, surfaces));
        if (overlap <= 1.0E-4) return;
        double margin = SupportResolver.edgeMargin(box, feet.y, surfaces);
        anchors.add(new LandingAnchor(feet, margin, overlap >= area - 1.0E-4
                && margin >= CORE_SAFETY_MARGIN));
    }

    private static String key(Vec3d feet) {
        return Math.round(feet.x * 20) + ":" + Math.round(feet.y * 20) + ":" + Math.round(feet.z * 20);
    }

    private static final Comparator<LandingAnchor> ANCHOR_ORDER = Comparator
            .comparing(LandingAnchor::core).reversed()
            .thenComparing(Comparator.comparingDouble(LandingAnchor::supportMargin).reversed())
            .thenComparingDouble(anchor -> anchor.feet().x)
            .thenComparingDouble(anchor -> anchor.feet().z);

    public record LandingAnchor(Vec3d feet, double supportMargin, boolean core) {}
}

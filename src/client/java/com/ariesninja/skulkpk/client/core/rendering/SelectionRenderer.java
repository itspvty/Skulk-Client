package com.ariesninja.skulkpk.client.core.rendering;

import com.ariesninja.skulkpk.client.core.BlockSelector;
import com.ariesninja.skulkpk.client.core.analysis.JumpProblem;
import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.planning.ContactRequirement;
import com.ariesninja.skulkpk.client.core.planning.ControlPhase;
import com.ariesninja.skulkpk.client.core.planning.MovementPlan;
import me.x150.renderer.render.Renderer3d;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.Objects;

public final class SelectionRenderer {
    private static final Color LANDING_FILL = new Color(255, 80, 80, 80);
    private static final Color LANDING_EDGE = new Color(255, 30, 30);
    private static final Color TAKEOFF_FILL = new Color(80, 120, 255, 100);
    private static final Color TAKEOFF_EDGE = new Color(30, 80, 255);
    private static final Color CORRIDOR_FILL = new Color(90, 120, 210, 35);
    private static final Color CORRIDOR_EDGE = new Color(110, 145, 230, 150);
    private static final Color CORE_FILL = new Color(255, 25, 25, 105);
    private static final Color CORE_EDGE = new Color(255, 175, 175);
    private static final Color SETTLE_FILL = new Color(255, 45, 45, 110);
    private static final Color SETTLE_EDGE = new Color(255, 180, 180);
    private static final Color POSITIONING = new Color(160, 160, 160);
    private static final Color MOMENTUM = new Color(0, 230, 255);
    private static final Color FLIGHT = new Color(40, 255, 90);
    private static final Color STAGING_FILL = new Color(150, 150, 150, 50);
    private static final Color STAGING_EDGE = new Color(180, 180, 180, 190);
    private static final Color COMMIT_FILL = new Color(40, 110, 255, 55);
    private static final Color COMMIT_EDGE = new Color(80, 150, 255, 220);
    private static final Color OBSTACLE_FILL = new Color(175, 80, 255, 25);
    private static final Color OBSTACLE_EDGE = new Color(195, 110, 255, 150);
    private static final Color CONTACT_FILL = new Color(255, 65, 210, 115);
    private static final Color CONTACT_EDGE = new Color(255, 155, 235);

    private static boolean highlightsVisible = true;
    private static MovementPlan movementPlan;

    static { Renderer3d.renderThroughWalls(); }
    private SelectionRenderer() {}

    public static void hideAllHighlights() { highlightsVisible = false; movementPlan = null; }
    public static void showHighlights() { highlightsVisible = true; }
    public static void setMovementPlan(MovementPlan plan) { movementPlan = plan; highlightsVisible = true; }
    public static void clearMovementPlan() { movementPlan = null; }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (!highlightsVisible || !(context.consumers() instanceof VertexConsumerProvider.Immediate)) return;
            JumpProblem problem = BlockSelector.getCurrentProblem().orElse(null);
            if (problem == null) return;
            MatrixStack matrices = Objects.requireNonNull(context.matrixStack());

            for (StandableSurface surface : problem.landingRegion()) renderSurface(matrices, surface,
                    LANDING_FILL, LANDING_EDGE, 0.055);
            MovementPlan plan = movementPlan;
            if (plan == null) return;

            for (var anchor : plan.landingZone().coreAnchors()) {
                Vec3d corner = anchor.feet().add(-0.04, 0.02, -0.04);
                Renderer3d.renderEdged(matrices, CORE_FILL, CORE_EDGE, corner,
                        new Vec3d(0.08, 0.025, 0.08));
            }

            renderBox(matrices, plan.approachPlan().stagingRegion(), STAGING_FILL, STAGING_EDGE, 0.018);
            renderBox(matrices, plan.approachPlan().launchWindow(), COMMIT_FILL, COMMIT_EDGE, 0.025);
            for (var obstacle : plan.configurationObstacles()) {
                renderBox(matrices, obstacle.forbiddenFeet(), OBSTACLE_FILL, OBSTACLE_EDGE, 0);
            }
            plan.contactEvents().stream()
                    .filter(event -> event.requirement() == ContactRequirement.REQUIRED)
                    .forEach(event -> renderBox(matrices, contactFace(event.obstacle(), event.face()),
                            CONTACT_FILL, CONTACT_EDGE, 0));

            if (plan.launchLane() != null) {
                Box corridor = plan.launchLane().approachCorridor();
                Renderer3d.renderEdged(matrices, CORRIDOR_FILL, CORRIDOR_EDGE,
                        new Vec3d(corridor.minX, plan.stagingPosition().y + 0.015, corridor.minZ),
                        new Vec3d(corridor.getLengthX(), 0.025, corridor.getLengthZ()));
                Renderer3d.renderLine(matrices, TAKEOFF_EDGE,
                        plan.launchLane().edgeStart().add(0, 0.09, 0),
                        plan.launchLane().edgeEnd().add(0, 0.09, 0));
            }

            Vec3d takeoffCorner = plan.takeoffPosition().add(-0.3, 0.01, -0.3);
            Renderer3d.renderEdged(matrices, TAKEOFF_FILL, TAKEOFF_EDGE, takeoffCorner, new Vec3d(0.6, 0.08, 0.6));

            Vec3d settle = plan.settleAnchor();
            Renderer3d.renderEdged(matrices, SETTLE_FILL, SETTLE_EDGE,
                    settle.add(-0.12, 0.055, -0.12), new Vec3d(0.24, 0.055, 0.24));

            if (!plan.positioningPath().isEmpty()) {
                Vec3d previous = problem.player().feetPosition().add(0, 0.04, 0);
                for (Vec3d waypoint : plan.positioningPath()) {
                    Renderer3d.renderLine(matrices, POSITIONING, previous, waypoint.add(0, 0.04, 0));
                    previous = waypoint.add(0, 0.04, 0);
                }
            }
            if (plan.metrics().runUpLength() > 0.01) {
                Renderer3d.renderLine(matrices, MOMENTUM, plan.stagingPosition().add(0, 0.07, 0),
                        plan.takeoffPosition().add(0, 0.07, 0));
            }

            int takeoffFrame = 0;
            while (takeoffFrame < plan.controlFrames().size()
                    && plan.controlFrames().get(takeoffFrame).phase().ordinal() < ControlPhase.TAKEOFF.ordinal()) {
                takeoffFrame++;
            }
            int sample = Math.min(takeoffFrame, plan.predictedTrajectory().size() - 1);
            for (int index = sample + 1; index < plan.predictedTrajectory().size(); index++) {
                Vec3d from = plan.predictedTrajectory().get(index - 1).feetPosition().add(0, 0.08, 0);
                Vec3d to = plan.predictedTrajectory().get(index).feetPosition().add(0, 0.08, 0);
                Renderer3d.renderLine(matrices, FLIGHT, from, to);
            }
        });
    }

    private static void renderSurface(MatrixStack matrices, StandableSurface surface,
                                      Color fill, Color edge, double height) {
        Box box = surface.footprint();
        Renderer3d.renderEdged(matrices, fill, edge,
                new Vec3d(box.minX, surface.topY() + 0.01, box.minZ),
                new Vec3d(box.getLengthX(), height, box.getLengthZ()));
    }

    private static void renderBox(MatrixStack matrices, Box box, Color fill, Color edge,
                                  double yOffset) {
        Renderer3d.renderEdged(matrices, fill, edge,
                new Vec3d(box.minX, box.minY + yOffset, box.minZ),
                new Vec3d(Math.max(0.015, box.getLengthX()), Math.max(0.015, box.getLengthY()),
                        Math.max(0.015, box.getLengthZ())));
    }

    private static Box contactFace(Box obstacle,
                                   com.ariesninja.skulkpk.client.core.physics.CollisionFace face) {
        double thickness = 0.025;
        return switch (face) {
            case WEST -> new Box(obstacle.minX - thickness, obstacle.minY, obstacle.minZ,
                    obstacle.minX, obstacle.maxY, obstacle.maxZ);
            case EAST -> new Box(obstacle.maxX, obstacle.minY, obstacle.minZ,
                    obstacle.maxX + thickness, obstacle.maxY, obstacle.maxZ);
            case DOWN -> new Box(obstacle.minX, obstacle.minY - thickness, obstacle.minZ,
                    obstacle.maxX, obstacle.minY, obstacle.maxZ);
            case UP -> new Box(obstacle.minX, obstacle.maxY, obstacle.minZ,
                    obstacle.maxX, obstacle.maxY + thickness, obstacle.maxZ);
            case NORTH -> new Box(obstacle.minX, obstacle.minY, obstacle.minZ - thickness,
                    obstacle.maxX, obstacle.maxY, obstacle.minZ);
            case SOUTH -> new Box(obstacle.minX, obstacle.minY, obstacle.maxZ,
                    obstacle.maxX, obstacle.maxY, obstacle.maxZ + thickness);
        };
    }
}

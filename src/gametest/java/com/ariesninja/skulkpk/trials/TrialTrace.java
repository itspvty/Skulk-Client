package com.ariesninja.skulkpk.trials;

import com.ariesninja.skulkpk.client.core.StepExecutor;
import com.ariesninja.skulkpk.client.core.analysis.MinecraftWorldView;
import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import com.ariesninja.skulkpk.client.core.physics.ParkourPhysics;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import com.ariesninja.skulkpk.client.core.planning.ControlFrame;
import com.ariesninja.skulkpk.client.core.planning.MovementPlan;
import com.ariesninja.skulkpk.client.core.planning.PlanningRequest;
import com.ariesninja.skulkpk.client.core.planning.LaunchState;
import com.google.gson.Gson;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent real-player trace. Prediction is diagnostic only, never the success oracle. */
public final class TrialTrace {
    private static final Gson JSON = new Gson();
    private static final ParkourPhysics PHYSICS = new ParkourPhysics();
    private static final List<Map<String, Object>> ROWS = new ArrayList<>();
    private static ParkourState before;
    private static boolean enabled;
    private static double maximumPositionError;
    private static int airSneakTicks;
    private static int headContacts;
    private static int ladderTicks;
    private static int minimumFoodLevel;
    private static ControlFrame command;
    private static long commandEpoch;
    private static double maximumVelocityError;
    private static final List<MovementPlan> PLANS = new ArrayList<>();
    private static final List<Object> SEARCHES = new ArrayList<>();
    private static final List<Object> CANDIDATES = new ArrayList<>();
    private static final List<Object> BEAMS = new ArrayList<>();
    private static final Map<String, List<Long>> TIMINGS = new java.util.concurrent.ConcurrentHashMap<>();

    static void start() {
        ROWS.clear();
        before = null;
        maximumPositionError = 0;
        airSneakTicks = 0;
        headContacts = 0;
        ladderTicks = 0;
        minimumFoodLevel = 20;
        command = null;
        PLANS.clear();
        SEARCHES.clear();
        CANDIDATES.clear();
        BEAMS.clear();
        TIMINGS.clear();
        maximumVelocityError = 0;
        enabled = true;
    }

    public static void before(ClientPlayerEntity player) {
        if (enabled) before = ParkourState.capture(PlayerSnapshot.capture(player))
                .withCollisions(player.horizontalCollision, player.verticalCollision, player.collidedSoftly);
    }

    public static void command(ControlFrame frame, long epoch) {
        if (enabled) {
            command = frame;
            commandEpoch = epoch;
        }
    }

    public static void released() { command = null; }

    public static void plan(MovementPlan plan) {
        if (enabled) PLANS.add(plan);
    }

    public static void search(PlanningRequest request, List<LaunchState> launches) {
        if (enabled) SEARCHES.add(Map.of("player", request.player(), "problem", request.problem(),
                "launches", List.copyOf(launches)));
    }

    public static void candidate(Object candidate, int variants) {
        if (enabled && CANDIDATES.size() < 8) CANDIDATES.add(Map.of("candidate", candidate, "variants", variants));
    }

    public static void timing(String operation, long nanos) {
        if (enabled) TIMINGS.computeIfAbsent(operation,
                key -> java.util.Collections.synchronizedList(new ArrayList<>())).add(nanos);
    }

    public static void beam(List<?> nodes) {
        if (!enabled || !Boolean.getBoolean("skulk.trials.beams")) return;
        List<Object> states = new ArrayList<>();
        for (Object node : nodes) {
            try {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String field : List.of("state", "guide", "guideIndex", "score")) {
                    var reflected = node.getClass().getDeclaredField(field);
                    reflected.setAccessible(true);
                    row.put(field, reflected.get(node));
                }
                var reflected = node.getClass().getDeclaredField("launch");
                reflected.setAccessible(true);
                LaunchState launch = (LaunchState) reflected.get(node);
                row.put("launchState", launch.state());
                row.put("lane", launch.lane().id());
                states.add(row);
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
        }
        BEAMS.add(states);
    }

    public static void after(ClientPlayerEntity player) {
        if (!enabled || before == null) return;
        var raw = player.input.playerInput;
        var row = new LinkedHashMap<String, Object>();
        row.put("tick", ROWS.size());
        row.put("before", vector(before.feetPosition()));
        row.put("beforeVelocity", vector(before.velocity()));
        row.put("after", vector(player.getPos()));
        row.put("velocity", vector(player.getVelocity()));
        row.put("yaw", player.getYaw());
        row.put("groundBefore", before.onGround());
        row.put("groundAfter", player.isOnGround());
        row.put("sprint", player.isSprinting());
        row.put("forward", raw.forward());
        row.put("back", raw.backward());
        row.put("left", raw.left());
        row.put("right", raw.right());
        row.put("jump", raw.jump());
        row.put("sneak", raw.sneak());
        row.put("horizontalCollision", player.horizontalCollision);
        row.put("verticalCollision", player.verticalCollision);
        row.put("climbing", player.isClimbing());
        if (player.isClimbing()) ladderTicks++;
        row.put("executor", StepExecutor.getInstance().getStatus().state().name());
        if (command != null) {
            row.put("commandEpoch", commandEpoch);
            row.put("command", command);
        }
        boolean attachedBefore = new MinecraftWorldView(player.getWorld()).isLadder(
                net.minecraft.util.math.BlockPos.ofFloored(before.feetPosition()));
        row.put("ladderBefore", attachedBefore);
        row.put("beforeSneak", before.previousSneak());
        row.put("beforeHeight", before.boundingBox().getLengthY());
        row.put("sprintTapTicks", before.sprintTapTicks());
        row.put("previousForward", before.previousForward());
        row.put("sprintAllowedBefore", before.sprintAllowed());
        row.put("foodLevel", player.getHungerManager().getFoodLevel());
        minimumFoodLevel = Math.min(minimumFoodLevel, player.getHungerManager().getFoodLevel());
        if (raw.sneak() && !before.onGround() && !attachedBefore) airSneakTicks++;
        if (player.verticalCollision && (before.velocity().y > 0 || raw.jump()) && !player.isOnGround()) headContacts++;
        try {
            var input = new ControlInput((raw.forward() ? 1 : 0) - (raw.backward() ? 1 : 0),
                    (raw.left() ? 1 : 0) - (raw.right() ? 1 : 0), raw.sprint(),
                    raw.jump(), raw.sneak(), player.getYaw());
            var prediction = PHYSICS.tick(new MinecraftWorldView(player.getWorld()), before, input).state();
            double error = prediction.feetPosition().distanceTo(player.getPos());
            maximumPositionError = Math.max(maximumPositionError, error);
            maximumVelocityError = Math.max(maximumVelocityError,
                    prediction.velocity().distanceTo(player.getVelocity()));
            row.put("predicted", vector(prediction.feetPosition()));
            row.put("predictedVelocity", vector(prediction.velocity()));
            row.put("positionError", error);
            row.put("velocityError", prediction.velocity().distanceTo(player.getVelocity()));
            if (command != null) {
                var requested = new ControlInput(command.forward(), command.strafe(), command.sprint(),
                        command.jump(), command.sneak(), command.desiredYaw());
                var requestedPrediction = PHYSICS.tick(new MinecraftWorldView(player.getWorld()), before, requested).state();
                maximumPositionError = Math.max(maximumPositionError,
                        requestedPrediction.feetPosition().distanceTo(player.getPos()));
                maximumVelocityError = Math.max(maximumVelocityError,
                        requestedPrediction.velocity().distanceTo(player.getVelocity()));
                row.put("commandPositionError", requestedPrediction.feetPosition().distanceTo(player.getPos()));
                row.put("commandVelocityError", requestedPrediction.velocity().distanceTo(player.getVelocity()));
            }
        } catch (RuntimeException error) {
            row.put("predictionError", error.toString());
        }
        ROWS.add(row);
        before = null;
    }

    static void save(Path path) throws IOException {
        enabled = false;
        Files.createDirectories(path.getParent());
        Files.write(path, ROWS.stream().map(JSON::toJson).toList());
        Files.writeString(path.resolveSibling(path.getFileName() + ".plans.json"), JSON.toJson(PLANS));
        Files.writeString(path.resolveSibling(path.getFileName() + ".search.json"), JSON.toJson(SEARCHES));
        Files.writeString(path.resolveSibling(path.getFileName() + ".candidates.json"), JSON.toJson(CANDIDATES));
        Files.writeString(path.resolveSibling(path.getFileName() + ".beams.json"), JSON.toJson(BEAMS));
        Map<String, Object> timings = new java.util.TreeMap<>();
        TIMINGS.forEach((operation, values) -> {
            List<Long> sorted;
            synchronized (values) { sorted = values.stream().sorted().toList(); }
            if (!sorted.isEmpty()) timings.put(operation, Map.of("count", sorted.size(),
                    "medianMs", sorted.get(sorted.size() / 2) / 1_000_000.0,
                    "p95Ms", sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95))) / 1_000_000.0,
                    "maxMs", sorted.getLast() / 1_000_000.0));
        });
        Files.writeString(path.resolveSibling(path.getFileName() + ".timings.json"), JSON.toJson(timings));
        if (ROWS.stream().anyMatch(row -> row.containsKey("predictionError"))) {
            throw new IOException("Physics diagnostics failed; inspect the saved predictionError rows in " + path);
        }
    }

    static int ladderTicks() { return ladderTicks; }
    static int minimumFoodLevel() { return minimumFoodLevel; }

    static double maximumPositionError() { return maximumPositionError; }
    static double maximumVelocityError() { return maximumVelocityError; }
    static int airSneakTicks() { return airSneakTicks; }
    static int headContacts() { return headContacts; }
    private static List<Double> vector(Vec3d v) { return List.of(v.x, v.y, v.z); }
}

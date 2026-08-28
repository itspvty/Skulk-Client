package com.ariesninja.skulkpk.trials;

import com.ariesninja.skulkpk.client.core.BlockSelector;
import com.ariesninja.skulkpk.client.core.Keybinds;
import com.ariesninja.skulkpk.client.core.StepExecutor;
import com.ariesninja.skulkpk.client.core.execution.ExecutionState;
import com.ariesninja.skulkpk.client.core.execution.MinecraftMovementIO;
import com.ariesninja.skulkpk.client.core.planning.ControlFrame;
import com.ariesninja.skulkpk.client.core.planning.ControlPhase;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Runs the shipping selection/execution path against vanilla movement in a disposable world. */
public final class ParkourGameTest implements FabricClientGameTest {
    private static final int FEET_Y = 101;
    private static final int MAX_TICKS = 500;
    private static final int POST_RELEASE_TICKS = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var singleplayer = context.worldBuilder().create()) {
            runTrials(new TrialDriver() {
                public void runOnClient(java.util.function.Consumer<MinecraftClient> action) {
                    context.runOnClient(action::accept);
                }
                public <T> T computeOnClient(java.util.function.Function<MinecraftClient, T> action) {
                    return context.computeOnClient(action::apply);
                }
                public void waitTicks(int ticks) { context.waitTicks(ticks); }
                public void command(String command) { singleplayer.getServer().runCommand(command); }
                public void executeKey() { context.getInput().pressKey(Keybinds.EXECUTE_KEY); }
                public void takeScreenshot(String name) { context.takeScreenshot(name); }
            }, "gametest");
        }
    }

    void runTrials(TrialDriver context, String mode) {
        Path output = Path.of(System.getProperty("skulk.trials.output", "build/parkour-results"))
                .resolve(Instant.now().toString().replace(':', '-') + "-" + mode);
        List<Result> results = new ArrayList<>();
        try {
            context.command("gamerule doDaylightCycle false");
            context.command("gamerule doMobSpawning false");
            context.command("gamerule fallDamage false");
            context.command("time set noon");
            context.runOnClient(client -> {
                client.options.getAutoJump().setValue(false);
                client.options.pauseOnLostFocus = false;
            });
            int repeats = Integer.parseInt(System.getProperty("skulk.trials.repeats", "1"));
            String filter = System.getProperty("skulk.trials.cases", "all");
            if (repeats < 1 || repeats > 20) throw new IllegalArgumentException("trialRepeats must be 1..20.");
            if (!filter.equals("all")) {
                List<String> available = cases().stream().map(Case::name).toList();
                for (String name : filter.split(",")) {
                    if (!available.contains(name)) throw new IllegalArgumentException("Unknown trial: " + name
                            + "; available: " + available);
                }
            }
            for (Case fixture : cases()) {
                if (!filter.equals("all") && !List.of(filter.split(",")).contains(fixture.name())) continue;
                for (int repeat = 0; repeat < repeats; repeat++) {
                    String name = fixture.name() + "-" + repeat;
                    try {
                        setup(context, fixture);
                        var preflight = context.computeOnClient(client -> verifyFixture(client, fixture));
                        Files.createDirectories(output);
                        Files.writeString(output.resolve(name + ".preflight.json"),
                                new GsonBuilder().setPrettyPrinting().create().toJson(preflight));
                        Result result = run(context, fixture, name, output);
                        results.add(result);
                        System.out.println("SKULK_TRIAL " + new GsonBuilder().create().toJson(result));
                    } finally {
                        context.runOnClient(client -> {
                            StepExecutor.getInstance().cancel(client, "Trial cleanup.");
                            new MinecraftMovementIO().release(client);
                            BlockSelector.clearSelectionSilent();
                        });
                    }
                    Files.createDirectories(output);
                    Files.writeString(output.resolve("summary.json"),
                            new GsonBuilder().setPrettyPrinting().create().toJson(results));
                }
            }
        } catch (Exception failure) {
            throw new RuntimeException("Live trial harness failed; artifacts: " + output, failure);
        }
        long failures = results.stream().filter(result -> !result.passed()).count();
        if (results.isEmpty() || failures > 0) {
            throw new AssertionError(failures + "/" + results.size() + " real-client trials failed: " + output);
        }
    }

    private void setup(TrialDriver context, Case fixture) {
        context.runOnClient(client -> {
            StepExecutor.getInstance().cancel(client, "Resetting disposable trial.");
            BlockSelector.clearSelectionSilent();
        });
        context.command("fill -10 98 -10 16 108 10 air");
        context.command("gamemode survival @p");
        for (String command : fixture.geometry()) context.command(command);
        context.command("tp @p " + fixture.start().x + " " + fixture.start().y + " " + fixture.start().z
                + " " + fixture.yaw() + " 0");
        // A persistent survival test world accumulates exhaustion across trials. Refill only
        // during setup, then remove the effect before measurement; never feed mid-execution.
        context.command("effect give @p minecraft:saturation 1 255 true");
        context.waitTicks(2);
        context.command("effect clear @p minecraft:saturation");
        context.waitTicks(5);
        context.runOnClient(client -> {
            client.player.setVelocity(Vec3d.ZERO);
            client.player.setSprinting(false);
            client.player.setYaw(fixture.yaw());
            client.player.setPitch(0);
        });
        context.waitTicks(5);
        if (!context.computeOnClient(client -> client.player.isOnGround())) {
            throw new IllegalStateException("Fixture did not settle on its starting platform.");
        }
        for (int tick = 0; tick < fixture.approachTicks(); tick++) {
            context.runOnClient(client -> new MinecraftMovementIO().apply(client,
                    new ControlFrame(1, 0, true, false, false, fixture.yaw(), ControlPhase.RUN_UP), false));
            context.waitTick();
        }
    }

    private java.util.Map<String, Object> verifyFixture(MinecraftClient client, Case fixture) {
        int food = client.player.getHungerManager().getFoodLevel();
        float saturation = client.player.getHungerManager().getSaturationLevel();
        if (food != 20 || saturation < 5) throw new IllegalStateException(
                "INVALID TRIAL SETUP: hunger was not restored (food=" + food + ", saturation=" + saturation + ").");
        List<java.util.Map<String, Object>> ladders = new ArrayList<>();
        for (String command : fixture.geometry()) {
            if (!command.contains("ladder")) continue;
            String[] parts = command.split(" ");
            boolean fill = parts[0].equals("fill");
            int blockIndex = fill ? 7 : 4;
            var facing = java.util.regex.Pattern.compile("(?:minecraft:)?ladder\\[facing=(north|south|east|west)\\]")
                    .matcher(parts[blockIndex]);
            if ((!fill && !parts[0].equals("setblock")) || !facing.matches())
                throw new IllegalStateException("Every fixture ladder needs an explicit audited orientation: " + command);
            var direction = net.minecraft.util.math.Direction.byName(facing.group(1));
            BlockPos from = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            BlockPos to = fill ? new BlockPos(Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), Integer.parseInt(parts[6])) : from;
            for (BlockPos mutable : BlockPos.iterate(from, to)) {
                BlockPos pos = mutable.toImmutable();
                var state = client.world.getBlockState(pos);
                if (!state.isOf(net.minecraft.block.Blocks.LADDER)
                        || state.get(net.minecraft.block.LadderBlock.FACING) != direction
                        || !state.canPlaceAt(client.world, pos)) {
                    throw new IllegalStateException("INVALID TRIAL SETUP: missing/unsupported/wrong-facing ladder at "
                            + pos.toShortString() + ", expected " + direction + ", actual " + state);
                }
                ladders.add(java.util.Map.of("position", pos.toShortString(), "facing", direction.asString(),
                        "survivedNeighborUpdates", true));
            }
        }
        return java.util.Map.of("foodLevel", food, "saturation", saturation,
                "ladderCount", ladders.size(), "ladders", ladders, "supportedStart", client.player.isOnGround());
    }

    private Result run(TrialDriver context, Case fixture, String name, Path output) throws Exception {
        context.runOnClient(client -> TrialTrace.start());
        if (fixture.name().equals("coordinate-selection")) {
            verifyCoordinateSelection(context, fixture);
            context.takeScreenshot(name + "-pass");
            context.runOnClient(client -> {
                try { TrialTrace.save(output.resolve(name + ".jsonl")); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            return new Result(name, true, "coordinate selection, unchanged camera, invalid-target cleanup, planning cancellation",
                    0, 0, 0, 0, 0);
        }
        if (fixture.calibration()) {
            MinecraftMovementIO io = new MinecraftMovementIO();
            for (int tick = 0; tick < 35; tick++) {
                int frame = tick;
                boolean ladderHold = fixture.name().equals("ladder-hold-calibration");
                boolean sprintTap = fixture.name().equals("sprint-tap-calibration");
                context.runOnClient(client -> io.apply(client, sprintTap ? new ControlFrame(
                        frame == 0 || frame >= 3 && frame < 12 || frame >= 25 ? 1 : 0,
                        0, false, frame == 3 || frame == 25, false, fixture.yaw(), ControlPhase.RUN_UP)
                        : ladderHold ? new ControlFrame(
                        frame >= 8 && frame < 14 ? 0 : 1, frame >= 14 && frame < 18 ? 1 : 0,
                        false, frame < 8 || frame >= 19,
                        frame >= 8 && frame < 19, -90,
                        frame >= 8 ? ControlPhase.LADDER : ControlPhase.RUN_UP) : new ControlFrame(1,
                        fixture.name().equals("fractional-strafe") ? 0.35f : 0,
                        frame >= 8, frame == 12, false, fixture.yaw(), ControlPhase.RUN_UP), false));
                context.waitTick();
                if (sprintTap && (frame == 3 || frame == 25)) {
                    boolean actualSprint = context.computeOnClient(client -> client.player.isSprinting());
                    if (actualSprint != (frame == 3)) throw new AssertionError("Unexpected vanilla double-tap sprint state.");
                }
            }
            context.runOnClient(io::release);
            context.waitTicks(10);
            double error = context.computeOnClient(client -> TrialTrace.maximumPositionError());
            double velocityError = context.computeOnClient(client -> TrialTrace.maximumVelocityError());
            context.runOnClient(client -> {
                try { TrialTrace.save(output.resolve(name + ".jsonl")); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            double tolerance = fixture.name().equals("oblique-calibration")
                    || fixture.name().equals("sprint-tap-calibration") ? 1.0E-7 : 0.002;
            return new Result(name, error < tolerance && velocityError < tolerance,
                    "physics_calibration", 45, error, velocityError, 0, 0);
        }
        List<BlockPos> targets = new ArrayList<>();
        targets.add(fixture.target());
        targets.addAll(fixture.followupTargets());
        int ticks = 0, legs = 0;
        boolean supported = true;
        for (BlockPos target : targets) {
        context.runOnClient(client -> selectWithChat(client, target));
        if (!context.computeOnClient(client -> target.equals(BlockSelector.getSelectedBlock()))) {
            context.runOnClient(client -> {
                try { TrialTrace.save(output.resolve(name + ".jsonl")); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            return new Result(name, false, "Selection rejected before planning at leg " + (legs + 1), ticks, 0, 0, 0, 0);
        }
        context.executeKey();
        int legTicks = 0;
        while (context.computeOnClient(client -> StepExecutor.getInstance().isExecuting()) && legTicks++ < MAX_TICKS) {
            context.waitTick();
        }
        ticks += legTicks;
        for (int tick = 0; tick < POST_RELEASE_TICKS; tick++) {
            context.waitTick();
            supported &= context.computeOnClient(client -> supportedOnTarget(client, target));
        }
        legs++;
        if (!supported || context.computeOnClient(client -> StepExecutor.getInstance().getStatus().state())
                != ExecutionState.SUCCEEDED) break;
        // Only coordinate selection + H from here: no pose/velocity/camera resets between rungs.
        }
        var status = context.computeOnClient(client -> StepExecutor.getInstance().getStatus());
        boolean keysReleased = context.computeOnClient(this::keysReleased);
        int sneak = context.computeOnClient(client -> TrialTrace.airSneakTicks());
        int contacts = context.computeOnClient(client -> TrialTrace.headContacts());
        double error = context.computeOnClient(client -> TrialTrace.maximumPositionError());
        double velocityError = context.computeOnClient(client -> TrialTrace.maximumVelocityError());
        boolean expectedContact = !fixture.name().startsWith("headhitter") || contacts > 0;
        boolean expectedLadder = !fixture.name().startsWith("ladder-catch")
                || context.computeOnClient(client -> TrialTrace.ladderTicks()) > 0;
        boolean passed = status.state() == ExecutionState.SUCCEEDED && legs == targets.size() && supported && keysReleased
                && sneak == 0 && expectedContact && expectedLadder && error < 0.002 && velocityError < 0.002;
        context.takeScreenshot(name + (passed ? "-pass" : "-fail"));
        context.runOnClient(client -> {
            try { TrialTrace.save(output.resolve(name + ".jsonl")); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        return new Result(name, passed, status.reason() + "; sustainedSupport=" + supported
                + "; keysReleased=" + keysReleased + "; legs=" + legs + "/" + targets.size(),
                ticks, error, velocityError, sneak, contacts);
    }

    /** Uses actual vanilla collision shapes and observed grounded state, not Skulk's simulator/resolver. */
    private boolean supportedOnTarget(MinecraftClient client, BlockPos target) {
        if (client.player == null || client.world == null || !client.player.isOnGround()
                || client.player.getVelocity().horizontalLength() >= 0.04) return false;
        Box feet = client.player.getBoundingBox();
        for (Box shape : client.world.getBlockState(target).getCollisionShape(client.world, target).getBoundingBoxes()) {
            Box box = shape.offset(target);
            if (Math.abs(feet.minY - box.maxY) < 1.0E-5 && feet.maxX > box.minX && feet.minX < box.maxX
                    && feet.maxZ > box.minZ && feet.minZ < box.maxZ) return true;
        }
        return false;
    }

    private boolean keysReleased(MinecraftClient client) {
        var o = client.options;
        return !o.forwardKey.isPressed() && !o.backKey.isPressed() && !o.leftKey.isPressed()
                && !o.rightKey.isPressed() && !o.sprintKey.isPressed() && !o.jumpKey.isPressed()
                && !o.sneakKey.isPressed();
    }

    private void selectWithChat(MinecraftClient client, BlockPos target) {
        client.player.networkHandler.sendChatCommand("skulk select " + target.getX() + " "
                + target.getY() + " " + target.getZ());
        if (!target.equals(BlockSelector.getSelectedBlock())) {
            throw new AssertionError("Coordinate chat command did not select " + target);
        }
    }

    private void verifyCoordinateSelection(TrialDriver context, Case fixture) {
        context.runOnClient(client -> {
            float yaw = client.player.getYaw();
            float pitch = client.player.getPitch();
            Vec3d position = client.player.getPos();
            selectWithChat(client, fixture.target());
            if (client.player.getYaw() != yaw || client.player.getPitch() != pitch
                    || !client.player.getPos().equals(position) || StepExecutor.getInstance().isExecuting()) {
                throw new AssertionError("Selecting coordinates changed camera, position, or started movement.");
            }
            for (String invalid : List.of("skulk select -1 2147483647 -2",
                    "skulk select 500000 100 500000", "skulk select 5 105 5")) {
                selectWithChat(client, fixture.target());
                client.player.networkHandler.sendChatCommand(invalid);
                if (BlockSelector.getSelectedBlock() != null) {
                    throw new AssertionError("Invalid target retained a stale selection: " + invalid);
                }
            }
            selectWithChat(client, fixture.target());
            StepExecutor.getInstance().executeSequence(client);
            if (!StepExecutor.getInstance().isPlanning()) throw new AssertionError("Planning did not start.");
            selectWithChat(client, fixture.target());
            if (StepExecutor.getInstance().isExecuting() || !keysReleased(client)) {
                throw new AssertionError("Coordinate reselection did not cancel planning and release inputs.");
            }
        });
    }

    private List<Case> cases() {
        List<Case> result = new ArrayList<>();
        result.add(new Case("coordinate-selection", List.of("setblock -3 100 -2 stone",
                "setblock -1 100 -2 stone"), new Vec3d(-2.5, FEET_Y, -1.5), 90,
                new BlockPos(-1, 100, -2), false));
        for (String name : List.of("movement", "fractional-strafe")) result.add(new Case(name,
                List.of("fill -4 100 -6 16 100 6 stone"), new Vec3d(0.5, FEET_Y, 0.5), -90,
                new BlockPos(5, 100, 0), true));
        result.add(new Case("oblique-calibration", List.of("fill -4 100 -6 16 100 6 stone"),
                new Vec3d(0.5, FEET_Y, 0.5), -79.15969f, new BlockPos(5, 100, 0), true));
        result.add(new Case("sprint-tap-calibration", List.of("fill -4 100 -6 16 100 6 stone"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(5, 100, 0), true));
        for (int gap = 1; gap <= 4; gap++) {
            result.add(new Case("gap-" + gap, List.of("fill -3 100 0 0 100 0 stone",
                    "setblock " + (gap + 1) + " 100 0 stone"), new Vec3d(0.5, FEET_Y, 0.5),
                    -90, new BlockPos(gap + 1, 100, 0), false));
        }
        result.add(new Case("wrong-yaw", List.of("fill -3 100 0 0 100 0 stone", "setblock 3 100 0 stone"),
                new Vec3d(-1.5, FEET_Y, 0.5), 90, new BlockPos(3, 100, 0), false));
        result.add(new Case("headhitter", List.of("fill -2 100 0 0 100 0 stone", "setblock 2 100 0 stone",
                "setblock 0 103 0 stone"), new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(2, 100, 0), false));
        for (boolean floating : List.of(false, true)) result.add(new Case(floating ? "floating-neo" : "neo",
                List.of("fill 0 100 -2 0 100 2 stone", "fill 0 " + (floating ? 102 : 101) + " -1 0 103 -1 stone"),
                new Vec3d(0.5, FEET_Y, 1.5), 180, new BlockPos(0, 100, -2), false));
        Case neo = result.stream().filter(fixture -> fixture.name().equals("neo")).findFirst().orElseThrow();
        result.add(new Case("neo-left-start", neo.geometry(), new Vec3d(0.32, FEET_Y, 1.5), 180, neo.target(), false));
        result.add(new Case("neo-right-start", neo.geometry(), new Vec3d(0.68, FEET_Y, 1.5), 180, neo.target(), false));
        result.add(new Case("neo-back", neo.geometry(), new Vec3d(0.5, FEET_Y, 2.4), 90, neo.target(), false));
        Case head = result.stream().filter(fixture -> fixture.name().equals("headhitter")).findFirst().orElseThrow();
        result.add(new Case("headhitter-back", head.geometry(), new Vec3d(-0.5, FEET_Y, 0.5), 90, head.target(), false));
        for (boolean floating : List.of(false, true)) result.add(new Case(
                floating ? "floating-double-neo" : "double-neo",
                List.of("fill 0 100 -3 0 100 3 stone", "fill 0 " + (floating ? 102 : 101) + " -2 0 103 -1 stone"),
                new Vec3d(0.5, FEET_Y, 1.5), 180, new BlockPos(0, 100, -3), false));
        Case doubleObstacle = result.stream().filter(fixture -> fixture.name().equals("double-neo")).findFirst().orElseThrow();
        result.add(new Case("double-neo-right-start", doubleObstacle.geometry(),
                new Vec3d(0.68, FEET_Y, 1.5), 180, doubleObstacle.target(), false));
        result.add(new Case("double-neo-back", doubleObstacle.geometry(),
                new Vec3d(0.5, FEET_Y, 2.4), 90, doubleObstacle.target(), false));
        result.add(new Case("ladder-calibration", List.of("fill 2 100 0 3 100 0 stone",
                "fill 3 101 0 3 106 0 stone", "fill 2 101 0 2 106 0 ladder[facing=west]"),
                new Vec3d(2.5, FEET_Y, 0.5), -90, new BlockPos(3, 106, 0), true));
        result.add(new Case("ladder-hold-calibration", List.of("fill 2 100 0 3 100 0 stone",
                "fill 3 101 0 3 107 0 stone", "fill 2 101 0 2 107 0 ladder[facing=west]"),
                new Vec3d(2.5, FEET_Y, 0.5), -90, new BlockPos(3, 107, 0), true));
        result.add(new Case("ladder-catch", List.of("fill -2 100 0 0 100 0 stone",
                "fill 3 100 0 3 102 0 stone", "fill 2 100 0 2 102 0 ladder[facing=west]"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(3, 102, 0), false));
        result.add(new Case("ladder-catch-north", List.of("fill 0 100 0 0 100 3 stone",
                "fill 0 100 -3 0 102 -3 stone", "fill 0 100 -2 0 102 -2 ladder[facing=south]"),
                new Vec3d(0.5, FEET_Y, 0.5), 180, new BlockPos(0, 102, -3), false));
        Case ladder = result.stream().filter(fixture -> fixture.name().equals("ladder-catch")).findFirst().orElseThrow();
        result.add(new Case("ladder-catch-wrong-yaw", ladder.geometry(), new Vec3d(-0.5, FEET_Y, 0.5),
                90, ladder.target(), false));
        // Capability probes deliberately vary geometry, orientation, landing shape and contact height.
        result.add(new Case("overhang-rise", List.of("fill -2 100 0 0 100 0 stone",
                "setblock 3 101 0 stone", "setblock 2 102 0 stone"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(3, 101, 0), false));
        result.add(new Case("overhang-rise-north", List.of("fill 0 100 0 0 100 2 stone",
                "setblock 0 101 -3 stone", "setblock 0 102 -2 stone"),
                new Vec3d(0.5, FEET_Y, 0.5), 180, new BlockPos(0, 101, -3), false));
        result.add(new Case("offset-gap-4", List.of("fill -3 100 0 0 100 0 stone",
                "setblock 5 100 1 stone"), new Vec3d(0.5, FEET_Y, 0.5), -90,
                new BlockPos(5, 100, 1), false));
        result.add(new Case("trapdoor-rise", List.of("fill -2 100 0 0 100 0 stone",
                "setblock 3 101 0 stone", "setblock 3 102 0 iron_trapdoor[half=bottom,open=false]"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(3, 102, 0), false));
        result.add(new Case("slab-drop", List.of("fill -2 100 0 0 100 0 stone",
                "setblock 4 100 0 stone_slab[type=bottom]"), new Vec3d(0.5, FEET_Y, 0.5),
                -90, new BlockPos(4, 100, 0), false));
        result.add(new Case("fence-landing", List.of("fill -2 100 0 0 100 0 stone",
                "setblock 3 100 0 oak_fence"), new Vec3d(0.5, FEET_Y, 0.5),
                -90, new BlockPos(3, 100, 0), false));
        result.add(new Case("stair-rise", List.of("fill -2 100 0 0 100 0 stone",
                "setblock 3 101 0 stone_stairs[facing=east]"), new Vec3d(0.5, FEET_Y, 0.5),
                -90, new BlockPos(3, 101, 0), false));
        result.add(new Case("late-ceiling", List.of("fill -2 100 0 0 100 0 stone",
                "setblock 3 100 0 stone", "setblock 1 103 0 stone"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(3, 100, 0), false));
        result.add(new Case("ladder-catch-gap-5", List.of("fill -4 100 0 0 100 0 stone",
                "fill 6 98 0 6 100 0 stone", "fill 5 98 0 5 100 0 ladder[facing=west]"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(6, 100, 0), false));
        result.add(new Case("ladder-catch-offset", List.of("fill -4 100 0 0 100 0 stone",
                "fill 6 98 1 6 100 1 stone", "fill 5 98 1 5 100 1 ladder[facing=west]"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(6, 100, 1), false));
        result.add(new Case("ladder-catch-side", List.of("fill -2 100 0 0 100 0 stone",
                "fill 4 98 0 4 102 0 stone", "fill 4 98 1 4 102 1 ladder[facing=south]"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(4, 102, 0), false));
        result.add(new Case("ladder-catch-back", List.of("fill -2 100 0 0 100 0 stone",
                "fill 3 98 0 3 102 0 stone", "fill 4 98 0 4 102 0 ladder[facing=east]"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(3, 102, 0), false));
        result.add(new Case("ladder-catch-side-north", List.of("fill 0 100 0 0 100 2 stone",
                "fill 0 98 -4 0 102 -4 stone", "fill 1 98 -4 1 102 -4 ladder[facing=east]"),
                new Vec3d(0.5, FEET_Y, 0.5), 180, new BlockPos(0, 102, -4), false));
        result.add(new Case("ladder-catch-back-north", List.of("fill 0 100 0 0 100 2 stone",
                "fill 0 98 -3 0 102 -3 stone", "fill 0 98 -4 0 102 -4 ladder[facing=north]"),
                new Vec3d(0.5, FEET_Y, 0.5), 180, new BlockPos(0, 102, -3), false));
        result.add(new Case("overhang-rise-wrong-yaw", List.of("fill -2 100 0 0 100 0 stone",
                "setblock 3 101 0 stone", "setblock 2 102 0 stone"),
                new Vec3d(-1.25, FEET_Y, 0.6), 90, new BlockPos(3, 101, 0), false));
        result.add(new Case("ladder-catch-gap-5-single-rung", List.of("fill -4 100 0 0 100 0 stone",
                "setblock 6 100 0 stone", "setblock 5 100 0 ladder[facing=west]"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(6, 100, 0), false));
        List<String> offset = List.of("fill -3 100 0 0 100 0 stone", "setblock 5 100 1 stone");
        double[][] starts = {{0.75, 0.65, -90}, {-0.35, 0.35, 90}, {-1.2, 0.8, -65}, {0.25, 0.25, -90}};
        for (int i = 0; i < starts.length; i++) {
            result.add(new Case("tight-offset-" + i, offset,
                    new Vec3d(starts[i][0], FEET_Y, starts[i][1]), (float) starts[i][2],
                    new BlockPos(5, 100, 1), false));
        }
        result.add(new Case("tight-offset-moving", offset, new Vec3d(-1.4, FEET_Y, 0.6),
                -90, new BlockPos(5, 100, 1), false, 4));
        for (int i = 0; i < 3; i++) result.add(new Case("tight-one-runway-" + i,
                List.of("setblock 0 100 0 stone", "setblock 5 100 0 stone"),
                new Vec3d(0.3 + i * 0.2, FEET_Y, 0.5), i == 1 ? 90 : -90,
                new BlockPos(5, 100, 0), false));
        result.add(new Case("tight-two-runway", List.of("fill -1 100 0 0 100 0 stone", "setblock 5 100 0 stone"),
                new Vec3d(-0.4, FEET_Y, 0.65), -80, new BlockPos(5, 100, 0), false));
        result.add(new Case("ladder-top-land", List.of("fill -2 100 0 0 100 0 stone",
                "fill 3 98 0 3 105 0 stone", "setblock 2 101 0 ladder[facing=west]"),
                new Vec3d(0.5, FEET_Y, 0.5), -90, new BlockPos(2, 101, 0), false));
        result.add(new Case("ladder-top-start", List.of("fill 1 98 0 1 105 0 stone",
                "setblock 0 100 0 ladder[facing=west]", "setblock 0 100 -3 stone"),
                new Vec3d(0.65, FEET_Y, 0.5), 180, new BlockPos(0, 100, -3), false));
        result.add(new Case("ladder-top-rise", List.of("fill 1 98 0 1 106 2 stone",
                "setblock 0 100 0 ladder[facing=west]", "setblock 0 101 2 ladder[facing=west]"),
                new Vec3d(0.65, FEET_Y, 0.5), 0, new BlockPos(0, 101, 2), false));
        result.add(new Case("ladder-top-corner", List.of("fill 1 98 0 1 106 0 stone",
                "setblock 0 100 0 ladder[facing=west]", "setblock 1 101 1 ladder[facing=south]"),
                new Vec3d(0.65, FEET_Y, 0.5), 0, new BlockPos(1, 101, 1), false));
        result.add(new Case("ladder-top-back", List.of("fill 1 98 0 1 106 0 stone",
                "setblock 0 100 0 ladder[facing=west]", "setblock 2 101 0 ladder[facing=east]"),
                new Vec3d(0.65, FEET_Y, 0.5), 0, new BlockPos(2, 101, 0), false));
        for (boolean clockwise : List.of(true, false)) {
            List<String> tower = new ArrayList<>(List.of("fill 1 98 0 1 108 0 stone",
                    "setblock 0 100 0 ladder[facing=west]", "setblock 2 102 0 ladder[facing=east]",
                    "setblock 0 104 0 ladder[facing=west]"));
            tower.add("setblock 1 " + (clockwise ? 101 : 103) + " 1 ladder[facing=south]");
            tower.add("setblock 1 " + (clockwise ? 103 : 101) + " -1 ladder[facing=north]");
            result.add(new Case(clockwise ? "ladder-tower-clockwise" : "ladder-tower-counterclockwise", tower,
                    new Vec3d(0.65, FEET_Y, 0.5), clockwise ? 0 : 180,
                    new BlockPos(1, 101, clockwise ? 1 : -1), false, 0,
                    List.of(new BlockPos(2, 102, 0), new BlockPos(1, 103, clockwise ? -1 : 1),
                            new BlockPos(0, 104, 0))));
        }
        result.add(new Case("ladder-top-north-start", List.of("fill 0 98 1 0 106 1 stone",
                "setblock 0 100 0 ladder[facing=north]", "setblock 3 100 0 stone"),
                new Vec3d(0.5, FEET_Y, 0.65), -90, new BlockPos(3, 100, 0), false));
        return result;
    }

    private record Case(String name, List<String> geometry, Vec3d start, float yaw,
                        BlockPos target, boolean calibration, int approachTicks, List<BlockPos> followupTargets) {
        private Case(String name, List<String> geometry, Vec3d start, float yaw,
                     BlockPos target, boolean calibration, int approachTicks) {
            this(name, geometry, start, yaw, target, calibration, approachTicks, List.of());
        }
        private Case(String name, List<String> geometry, Vec3d start, float yaw,
                     BlockPos target, boolean calibration) {
            this(name, geometry, start, yaw, target, calibration, 0);
        }
    }
    private record Result(String name, boolean passed, String reason, int ticks,
                          double maxOneTickPositionError, double maxOneTickVelocityError,
                          int airSneakTicks, int headContacts, int minimumFoodLevel) {
        private Result(String name, boolean passed, String reason, int ticks,
                       double maxOneTickPositionError, double maxOneTickVelocityError,
                       int airSneakTicks, int headContacts) {
            this(name, passed && TrialTrace.minimumFoodLevel() > 6,
                    TrialTrace.minimumFoodLevel() <= 6 ? "INVALID_TRIAL_HUNGER: " + reason : reason,
                    ticks, maxOneTickPositionError, maxOneTickVelocityError, airSneakTicks, headContacts,
                    TrialTrace.minimumFoodLevel());
        }
    }
}

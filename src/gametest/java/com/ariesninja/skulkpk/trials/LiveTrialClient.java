package com.ariesninja.skulkpk.trials;

import com.ariesninja.skulkpk.client.core.Keybinds;
import com.ariesninja.skulkpk.client.core.StepExecutor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.ScreenshotRecorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** Opt-in development runner. No GameTest tick/input/network overrides are enabled here. */
public final class LiveTrialClient implements ClientModInitializer {
    private final Object tickMonitor = new Object();
    private long ticks;
    private volatile boolean started;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile long lastTickNanos = System.nanoTime();

    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("skulk.trials.live")) return;
        if (System.getProperty("fabric.client.gametest") != null) {
            throw new IllegalStateException("Real-time trials cannot run with GameTest timing overrides.");
        }
        Path marker = MinecraftClient.getInstance().runDirectory.toPath()
                .resolve("saves/Skulk Trials/.skulk-disposable-trial");
        if (!Files.isRegularFile(marker)) throw new IllegalStateException("Disposable trial marker missing.");
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            lastTickNanos = System.nanoTime();
            synchronized (tickMonitor) { ticks++; tickMonitor.notifyAll(); }
            if (!started && client.player != null && client.getServer() != null && client.currentScreen == null) {
                started = true;
                Thread worker = new Thread(() -> run(client), "Skulk real-time trials");
                worker.setDaemon(true);
                worker.start();
            }
        });
        long startup = System.nanoTime();
        Thread watchdog = new Thread(() -> {
            while (!stopping.get()) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));
                long now = System.nanoTime();
                if ((!started && now - startup > TimeUnit.SECONDS.toNanos(120))
                        || (started && now - lastTickNanos > TimeUnit.SECONDS.toNanos(5))) {
                    stopFailedClient(MinecraftClient.getInstance(),
                            new IllegalStateException("Trial startup or render-thread heartbeat stalled."));
                    return;
                }
            }
        }, "Skulk trial watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void run(MinecraftClient client) {
        RealTimeDriver driver = new RealTimeDriver(client);
        try {
            driver.waitTicks(20);
            if (Boolean.getBoolean("skulk.trials.testFreeze")) {
                driver.runOnClient(c -> {
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    // The client task queue unparks its render thread. One park call can
                    // return immediately, so use a deadline to inject a real heartbeat stall.
                    while (System.nanoTime() < until) {
                        java.util.concurrent.locks.LockSupport.parkNanos(until - System.nanoTime());
                    }
                });
                throw new AssertionError("Injected freeze was not detected.");
            }
            new ParkourGameTest().runTrials(driver, "realtime");
            Files.writeString(client.runDirectory.toPath().resolve("trial-status.txt"), "passed");
            driver.runOnClient(c -> { StepExecutor.getInstance().cancel(c, "Trial suite complete."); c.scheduleStop(); });
            stopping.set(true);
        } catch (Throwable failure) {
            stopFailedClient(client, failure);
        }
    }

    private void stopFailedClient(MinecraftClient client, Throwable failure) {
        if (!stopping.compareAndSet(false, true)) return;
        // Independent test thread still makes a render-thread freeze diagnosable. Only
        // this explicitly launched disposable client exits; no other game process is touched.
        StringBuilder dump = new StringBuilder(failure.toString()).append('\n');
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            dump.append(thread.getName()).append('\n');
            for (StackTraceElement element : stack) dump.append("  ").append(element).append('\n');
        });
        try {
            Files.writeString(client.runDirectory.toPath().resolve("trial-status.txt"), "failed");
            Path directory = Path.of(System.getProperty("skulk.trials.output"));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("realtime-failure-"
                    + Instant.now().toEpochMilli() + ".txt"), dump);
        } catch (Exception reportFailure) { failure.addSuppressed(reportFailure); }
        failure.printStackTrace();
        client.execute(() -> { StepExecutor.getInstance().cancel(client, "Trial failed."); client.scheduleStop(); });
        // The test worker is independent of the render thread. Give normal shutdown
        // five seconds, then terminate only this opt-in disposable JVM if it is frozen.
        Thread shutdownGuard = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            }
            Runtime.getRuntime().halt(2);
        }, "Skulk trial shutdown guard");
        shutdownGuard.setDaemon(true);
        shutdownGuard.start();
    }

    private final class RealTimeDriver implements TrialDriver {
        private final MinecraftClient client;
        RealTimeDriver(MinecraftClient client) { this.client = client; }
        public void runOnClient(Consumer<MinecraftClient> action) {
            computeOnClient(c -> { action.accept(c); return null; });
        }
        public <T> T computeOnClient(Function<MinecraftClient, T> action) {
            return await(client.submit(() -> action.apply(client)));
        }
        public void waitTicks(int count) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5 + count / 10);
            synchronized (tickMonitor) {
                long end = ticks + count;
                while (ticks < end) {
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("Minecraft client tick heartbeat stalled.");
                    try { tickMonitor.wait(250); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
            }
        }
        public void command(String command) {
            var server = computeOnClient(MinecraftClient::getServer);
            if (server == null) throw new IllegalStateException("Trial integrated world unavailable.");
            Path expected = client.runDirectory.toPath().resolve("saves/Skulk Trials").toAbsolutePath().normalize();
            if (!server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath().normalize().equals(expected))
                throw new IllegalStateException("Refusing fixture commands outside the disposable trial world.");
            await(server.submit(() -> server.getCommandManager().executeWithPrefix(server.getCommandSource(), command)));
        }
        public void executeKey() {
            runOnClient(c -> KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(Keybinds.EXECUTE_KEY)));
            waitTick();
        }
        public void takeScreenshot(String name) {
            CompletableFuture<Void> saved = new CompletableFuture<>();
            runOnClient(c -> ScreenshotRecorder.saveScreenshot(c.runDirectory, name + ".png",
                    c.getFramebuffer(), message -> saved.complete(null)));
            await(saved);
        }
        private <T> T await(CompletableFuture<T> future) {
            try { return future.get(5, TimeUnit.SECONDS); }
            catch (Exception e) { throw new IllegalStateException("Trial command/observation timed out or failed.", e); }
        }
    }
}

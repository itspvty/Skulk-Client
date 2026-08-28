package com.ariesninja.skulkpk.trials;

import net.minecraft.client.MinecraftClient;
import java.util.function.Consumer;
import java.util.function.Function;

/** Same trial body under deterministic GameTest or unmodified real-time client scheduling. */
interface TrialDriver {
    void runOnClient(Consumer<MinecraftClient> action);
    <T> T computeOnClient(Function<MinecraftClient, T> action);
    void waitTicks(int ticks);
    default void waitTick() { waitTicks(1); }
    void command(String command);
    void executeKey();
    void takeScreenshot(String name);
}

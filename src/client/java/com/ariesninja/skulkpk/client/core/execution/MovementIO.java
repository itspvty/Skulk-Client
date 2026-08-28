package com.ariesninja.skulkpk.client.core.execution;

import com.ariesninja.skulkpk.client.core.planning.ControlFrame;
import net.minecraft.client.MinecraftClient;

/** Injectable END-client-tick observation/command boundary. */
public interface MovementIO {
    MovementObservation observe(MinecraftClient client);
    long apply(MinecraftClient client, ControlFrame frame, boolean targetSupported);
    void release(MinecraftClient client);
}

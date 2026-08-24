package com.ariesninja.skulkpk.client.core.execution;

import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import com.ariesninja.skulkpk.client.core.planning.MovementPlan;
import net.minecraft.client.MinecraftClient;

public interface StepController {
    void start(MovementPlan plan, WorldView world);
    StepTickResult tick(MinecraftClient client);
    void stop(MinecraftClient client);
    default String reason() { return "Movement controller rejected the plan."; }
}

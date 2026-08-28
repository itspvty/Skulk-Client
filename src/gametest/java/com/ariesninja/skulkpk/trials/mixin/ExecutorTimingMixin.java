package com.ariesninja.skulkpk.trials.mixin;

import com.ariesninja.skulkpk.client.core.StepExecutor;
import com.ariesninja.skulkpk.trials.TrialTrace;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = StepExecutor.class, remap = false)
abstract class ExecutorTimingMixin {
    @Unique private long skulk$started;
    @Unique private String skulk$operation;

    @Inject(method = "tick", at = @At("HEAD"))
    private void started(MinecraftClient client, CallbackInfo ci) {
        StepExecutor executor = (StepExecutor) (Object) this;
        skulk$operation = executor.isPlanning() ? "client.planning"
                : executor.isExecuting() ? "client.execution" : "client.idle";
        skulk$started = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void finished(MinecraftClient client, CallbackInfo ci) {
        TrialTrace.timing(skulk$operation, System.nanoTime() - skulk$started);
    }
}

package com.ariesninja.skulkpk.trials.mixin;

import com.ariesninja.skulkpk.client.core.execution.MinecraftMovementIO;
import com.ariesninja.skulkpk.client.core.planning.ControlFrame;
import com.ariesninja.skulkpk.trials.TrialTrace;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MinecraftMovementIO.class, remap = false)
abstract class MovementTraceMixin {
    @Inject(method = "apply", at = @At("RETURN"))
    private void commandApplied(MinecraftClient client, ControlFrame frame, boolean targetSupported,
                                CallbackInfoReturnable<Long> ci) {
        TrialTrace.command(frame, ci.getReturnValue());
    }

    @Inject(method = "release", at = @At("RETURN"))
    private void commandReleased(MinecraftClient client, CallbackInfo ci) {
        TrialTrace.released();
    }
}

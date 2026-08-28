package com.ariesninja.skulkpk.trials.mixin;

import com.ariesninja.skulkpk.trials.TrialTrace;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only observation: never replaces input, movement, or collision resolution. */
@Mixin(ClientPlayerEntity.class)
abstract class PlayerTraceMixin {
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void beforeMovement(CallbackInfo ci) {
        TrialTrace.before((ClientPlayerEntity) (Object) this);
    }

    @Inject(method = "tickMovement", at = @At("RETURN"))
    private void afterMovement(CallbackInfo ci) {
        TrialTrace.after((ClientPlayerEntity) (Object) this);
    }
}

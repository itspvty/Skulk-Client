package com.ariesninja.skulkpk.trials.mixin;

import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import com.ariesninja.skulkpk.client.core.execution.TrajectoryStepController;
import com.ariesninja.skulkpk.client.core.planning.MovementPlan;
import com.ariesninja.skulkpk.trials.TrialTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TrajectoryStepController.class, remap = false)
abstract class PlanTraceMixin {
    @Inject(method = "start", at = @At("HEAD"))
    private void planStarted(MovementPlan plan, WorldView world, CallbackInfo ci) {
        TrialTrace.plan(plan);
    }
}

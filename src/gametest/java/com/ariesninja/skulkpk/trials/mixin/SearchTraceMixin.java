package com.ariesninja.skulkpk.trials.mixin;

import com.ariesninja.skulkpk.client.core.planning.LaunchState;
import com.ariesninja.skulkpk.client.core.planning.PlanningRequest;
import com.ariesninja.skulkpk.client.core.planning.SearchPlanningSession;
import com.ariesninja.skulkpk.trials.TrialTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Coerce;
import java.util.List;

@Mixin(value = SearchPlanningSession.class, remap = false)
abstract class SearchTraceMixin {
    @Shadow @Final private PlanningRequest request;
    @Shadow private List<LaunchState> launchStates;
    @Unique private long skulk$prepareStart;
    @Unique private long skulk$obstacleStart;

    @Inject(method = "prepare", at = @At("HEAD"))
    private void prepareStarted(CallbackInfo ci) { skulk$prepareStart = System.nanoTime(); }

    @Inject(method = "prepare", at = @At("RETURN"))
    private void prepared(CallbackInfo ci) {
        TrialTrace.timing("search.prepare", System.nanoTime() - skulk$prepareStart);
        TrialTrace.search(request, launchStates);
    }

    @Inject(method = "beginObstacle", at = @At("HEAD"))
    private void obstacleStarted(CallbackInfo ci) { skulk$obstacleStart = System.nanoTime(); }

    @Inject(method = "beginObstacle", at = @At("RETURN"))
    private void obstaclePrepared(CallbackInfo ci) {
        TrialTrace.timing("search.obstaclePrepare", System.nanoTime() - skulk$obstacleStart);
    }

    @Inject(method = "validateRouteTube", at = @At("RETURN"))
    private void validated(@Coerce Object candidate, CallbackInfoReturnable<Integer> ci) {
        TrialTrace.candidate(candidate, ci.getReturnValue());
    }

    @Inject(method = "retainDiverse", at = @At("RETURN"))
    private void beamRetained(List<?> nodes, CallbackInfoReturnable<List<?>> ci) {
        TrialTrace.beam(ci.getReturnValue());
    }
}

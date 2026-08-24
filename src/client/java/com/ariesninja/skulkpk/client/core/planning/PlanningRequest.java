package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.JumpProblem;
import com.ariesninja.skulkpk.client.core.analysis.JumpProblemResult;
import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import com.ariesninja.skulkpk.client.core.JumpAnalyzer;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;

public record PlanningRequest(
        WorldView world,
        PlayerSnapshot player,
        BlockPos target,
        PlanningPolicy policy,
        JumpProblem problem
) {
    public PlanningRequest {
        world = Objects.requireNonNull(world);
        player = Objects.requireNonNull(player);
        target = Objects.requireNonNull(target).toImmutable();
        policy = Objects.requireNonNull(policy);
        problem = Objects.requireNonNull(problem);
    }

    public PlanningRequest(WorldView world, PlayerSnapshot player, BlockPos target, PlanningPolicy policy) {
        this(world, player, target, policy, analyze(world, player, target));
    }

    private static JumpProblem analyze(WorldView world, PlayerSnapshot player, BlockPos target) {
        JumpProblemResult result = new JumpAnalyzer().analyzeProblem(world, player, target);
        if (result instanceof JumpProblemResult.Valid valid) return valid.problem();
        JumpProblemResult.Rejected rejected = (JumpProblemResult.Rejected) result;
        throw new IllegalArgumentException(rejected.message());
    }
}

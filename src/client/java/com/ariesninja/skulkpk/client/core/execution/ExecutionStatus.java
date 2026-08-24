package com.ariesninja.skulkpk.client.core.execution;

public record ExecutionStatus(
        ExecutionState state,
        int stepIndex,
        int stepCount,
        String reason
) {}

package com.ariesninja.skulkpk.client.core.analysis;

public sealed interface JumpProblemResult {
    record Valid(JumpProblem problem) implements JumpProblemResult {}
    record Rejected(JumpRejectionReason reason, String message) implements JumpProblemResult {}
}

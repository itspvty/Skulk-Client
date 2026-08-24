package com.ariesninja.skulkpk.client.core.planning;

public sealed interface PlanningTickResult {
    record Planning(int candidatesEvaluated) implements PlanningTickResult {}
    record Ready(MovementPlan plan) implements PlanningTickResult {}
    record Rejected(PlanRejectionReason reason, String message, int candidatesEvaluated) implements PlanningTickResult {}
}

package com.ariesninja.skulkpk.client.core.planning;

public interface PlanningSession {
    PlanningTickResult tick(long budgetNanos);
    void cancel();
}

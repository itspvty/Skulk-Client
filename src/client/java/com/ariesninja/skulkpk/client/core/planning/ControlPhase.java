package com.ariesninja.skulkpk.client.core.planning;

public enum ControlPhase {
    POSITIONING,
    ALIGNING,
    RUN_UP,
    TAKEOFF,
    AIRBORNE,
    LADDER,
    LADDER_EXIT,
    LANDED_BRAKING,
    SETTLING;

    public boolean isLandingPhase() {
        return this == LANDED_BRAKING || this == SETTLING;
    }

    public boolean isTransitPhase() { return this == AIRBORNE || this == LADDER || this == LADDER_EXIT; }
}

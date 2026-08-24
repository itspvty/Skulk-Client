package com.ariesninja.skulkpk.client.core.planning;

public enum ControlPhase {
    POSITIONING,
    ALIGNING,
    RUN_UP,
    TAKEOFF,
    AIRBORNE,
    LANDED_BRAKING,
    SETTLING;

    public boolean isLandingPhase() {
        return this == LANDED_BRAKING || this == SETTLING;
    }
}

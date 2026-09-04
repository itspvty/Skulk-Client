package com.ariesninja.skulkpk.client.core.planning;

public enum ControlPhase {
    POSITIONING,
    ALIGNING,
    RUN_UP,
    PREPARATORY_TAKEOFF,
    APPROACH_AIRBORNE,
    TAKEOFF,
    AIRBORNE,
    LADDER,
    LADDER_EXIT,
    LANDED_BRAKING,
    SETTLING;

    public boolean isLandingPhase() {
        return this == LANDED_BRAKING || this == SETTLING;
    }

    public boolean isTransitPhase() {
        return this == AIRBORNE || this == LADDER || this == LADDER_EXIT;
    }

    public boolean isPreparatoryPhase() {
        return this == PREPARATORY_TAKEOFF || this == APPROACH_AIRBORNE;
    }
}

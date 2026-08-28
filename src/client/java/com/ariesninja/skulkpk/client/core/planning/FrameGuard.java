package com.ariesninja.skulkpk.client.core.planning;

/** Runtime state which must be observed before a planned input can be applied. */
public enum FrameGuard {
    GROUNDED,
    AIRBORNE,
    LADDER,
    TARGET_GROUNDED;

    public boolean permits(boolean onGround, boolean targetSupported) {
        return permits(onGround, targetSupported, false);
    }

    public boolean permits(boolean onGround, boolean targetSupported, boolean ladderAttached) {
        return switch (this) {
            case GROUNDED -> onGround;
            case AIRBORNE -> !onGround;
            case LADDER -> ladderAttached && !onGround;
            case TARGET_GROUNDED -> onGround && targetSupported;
        };
    }
}

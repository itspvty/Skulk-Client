package com.ariesninja.skulkpk.client.core.planning;

public record ControlFrame(
        float forward,
        float strafe,
        boolean sprint,
        boolean jump,
        boolean sneak,
        float desiredYaw,
        ControlPhase phase,
        FrameGuard guard
) {
    public ControlFrame {
        forward = clamp(forward);
        strafe = clamp(strafe);
        if (phase == null) throw new IllegalArgumentException("Control phase is required.");
        if (guard == null) throw new IllegalArgumentException("A frame guard is required.");
        if (sneak && !phase.isLandingPhase() && phase != ControlPhase.LADDER) {
            throw new IllegalArgumentException("Sneak requires target grounding or guarded ladder attachment.");
        }
    }

    public ControlFrame(float forward, float strafe, boolean sprint, boolean jump, boolean sneak,
                        float desiredYaw, ControlPhase phase) {
        this(forward, strafe, sprint, jump, sneak, desiredYaw, phase, defaultGuard(phase));
    }

    private static float clamp(float value) {
        return com.ariesninja.skulkpk.client.core.physics.ControlInput.keyAxis(value);
    }

    public static ControlFrame neutral(float yaw, ControlPhase phase) {
        return new ControlFrame(0, 0, false, false, false, yaw, phase);
    }

    /** A requested hold is never sufficient authority to crouch in ordinary flight. */
    public boolean allowsSneak(boolean onGround, boolean targetSupported, boolean ladderAttached) {
        return sneak && (phase.isLandingPhase() && onGround && targetSupported
                || phase == ControlPhase.LADDER && !onGround && ladderAttached);
    }

    private static FrameGuard defaultGuard(ControlPhase phase) {
        return switch (phase) {
            case AIRBORNE, APPROACH_AIRBORNE -> FrameGuard.AIRBORNE;
            case LADDER -> FrameGuard.LADDER;
            case LANDED_BRAKING, SETTLING -> FrameGuard.TARGET_GROUNDED;
            default -> FrameGuard.GROUNDED;
        };
    }
}

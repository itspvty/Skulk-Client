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
        if (sneak && !phase.isLandingPhase()) {
            throw new IllegalArgumentException("Sneak is only legal in a landed control phase.");
        }
    }

    public ControlFrame(float forward, float strafe, boolean sprint, boolean jump, boolean sneak,
                        float desiredYaw, ControlPhase phase) {
        this(forward, strafe, sprint, jump, sneak, desiredYaw, phase, defaultGuard(phase));
    }

    private static float clamp(float value) { return Math.max(-1, Math.min(1, value)); }

    public static ControlFrame neutral(float yaw, ControlPhase phase) {
        return new ControlFrame(0, 0, false, false, false, yaw, phase);
    }

    private static FrameGuard defaultGuard(ControlPhase phase) {
        return switch (phase) {
            case AIRBORNE -> FrameGuard.AIRBORNE;
            case LANDED_BRAKING, SETTLING -> FrameGuard.TARGET_GROUNDED;
            default -> FrameGuard.GROUNDED;
        };
    }
}

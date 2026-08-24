package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;

import java.util.List;

/** Stateful landing criterion shared by simulation tests and runtime planning. */
public final class LandingStabilityTracker {
    public enum State { SEARCHING, BRAKING, SETTLING, STABLE, FAILED }
    public static final int REQUIRED_GROUNDED_TICKS = 8;
    public static final int REQUIRED_SETTLE_TICKS = 6;
    public static final double MAX_FINAL_SPEED = 0.04;

    private final List<StandableSurface> region;
    private int groundedTicks;
    private int settleTicks;
    private boolean touchedRegion;
    private double landingSpeed = Double.MAX_VALUE;
    private double edgeMargin = -1;
    private String reason = "";

    public LandingStabilityTracker(List<StandableSurface> region) { this.region = List.copyOf(region); }

    public State observe(Vec3d feet, Vec3d velocity, boolean onGround, boolean hasBeenAirborne) {
        Box box = new Box(feet.x - 0.3, feet.y, feet.z - 0.3,
                feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        return observe(box, feet, velocity, onGround, hasBeenAirborne);
    }

    public State observe(Box playerBox, Vec3d feet, Vec3d velocity,
                         boolean onGround, boolean hasBeenAirborne) {
        boolean supported = SupportResolver.targetSupported(playerBox, feet, onGround, region);
        if (!supported) {
            groundedTicks = 0;
            settleTicks = 0;
            if (touchedRegion || (hasBeenAirborne && onGround)) {
                reason = touchedRegion
                        ? "The player slid or fell off after touching the landing region."
                        : "Landed outside the connected target region.";
                return State.FAILED;
            }
            return State.SEARCHING;
        }

        touchedRegion = true;
        landingSpeed = velocity.horizontalLength();
        edgeMargin = SupportResolver.edgeMargin(playerBox, feet.y, region);
        if (landingSpeed > MAX_FINAL_SPEED) {
            groundedTicks = 0;
            settleTicks = 0;
            return State.BRAKING;
        }
        if (groundedTicks < REQUIRED_GROUNDED_TICKS) {
            groundedTicks++;
            return State.SETTLING;
        }
        settleTicks++;
        return settleTicks >= REQUIRED_SETTLE_TICKS ? State.STABLE : State.SETTLING;
    }

    public double landingSpeed() { return landingSpeed; }
    public double edgeMargin() { return edgeMargin; }
    public String reason() { return reason; }

    public boolean isSupported(Vec3d feet) {
        Box box = new Box(feet.x - 0.3, feet.y, feet.z - 0.3,
                feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        return SupportResolver.targetSupported(box, feet, true, region);
    }

    /** Minecraft only needs part of the horizontal player box to remain supported. */
    public static boolean overlapsRegion(Vec3d feet, List<StandableSurface> region) {
        Box box = new Box(feet.x - 0.3, feet.y, feet.z - 0.3,
                feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        return SupportResolver.targetSupported(box, feet, true, region);
    }
}

package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LandingStabilityTrackerTest {
    @Test void requiresEightGroundedTicksThenSixSettleTicks() {
        LandingStabilityTracker tracker = tracker();
        for (int tick = 0; tick < 13; tick++) {
            assertNotEquals(LandingStabilityTracker.State.STABLE,
                    tracker.observe(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true, true));
        }
        assertEquals(LandingStabilityTracker.State.STABLE,
                tracker.observe(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true, true));
    }

    @Test void fastLandingBrakesBeforeStabilityCanAccumulate() {
        LandingStabilityTracker tracker = tracker();
        assertEquals(LandingStabilityTracker.State.BRAKING,
                tracker.observe(new Vec3d(0.5, 0, 0.5), new Vec3d(0.08, 0, 0), true, true));
        assertEquals(0.08, tracker.landingSpeed(), 0.001);
        assertEquals(LandingStabilityTracker.State.SETTLING,
                tracker.observe(new Vec3d(0.5, 0, 0.5), new Vec3d(0.03, 0, 0), true, true));
    }

    @Test void momentaryTouchFollowedByFallingOffFails() {
        LandingStabilityTracker tracker = tracker();
        tracker.observe(new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, true, true);
        assertEquals(LandingStabilityTracker.State.FAILED,
                tracker.observe(new Vec3d(1.2, -0.1, 0.5), new Vec3d(0.1, -0.1, 0), false, true));
        assertTrue(tracker.reason().contains("fell off"));
    }

    @Test void connectedPlatformSupportsAPlayerAcrossTheSeam() {
        LandingStabilityTracker tracker = new LandingStabilityTracker(List.of(surface(0), surface(1)));
        assertTrue(tracker.isSupported(new Vec3d(1.0, 0, 0.5)));
        assertTrue(tracker.isSupported(new Vec3d(1.85, 0, 0.5)));
        assertFalse(tracker.isSupported(new Vec3d(2.31, 0, 0.5)));
    }

    @Test void stableEdgeOverlapDoesNotRequireAllFourCornersOnTheBlock() {
        LandingStabilityTracker tracker = tracker();
        assertTrue(tracker.isSupported(new Vec3d(1.25, 0, 0.5)));
        for (int tick = 0; tick < 14; tick++) {
            LandingStabilityTracker.State state = tracker.observe(
                    new Vec3d(1.25, 0, 0.5), Vec3d.ZERO, true, true);
            if (tick == 13) assertEquals(LandingStabilityTracker.State.STABLE, state);
            else assertNotEquals(LandingStabilityTracker.State.FAILED, state);
        }
    }

    private LandingStabilityTracker tracker() { return new LandingStabilityTracker(List.of(surface(0))); }
    private StandableSurface surface(int x) {
        return new StandableSurface(new BlockPos(x, -1, 0), new Box(x, -1, 0, x + 1, 0, 1), 0);
    }
}

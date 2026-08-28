package com.ariesninja.skulkpk.client.core.planning;

public record PlanningPolicy(
        long maximumWallNanos,
        int beamWidth,
        int normalHorizonTicks,
        int dropHorizonTicks,
        boolean requireRobustVariants
) {
    public static final PlanningPolicy AGGRESSIVE = new PlanningPolicy(
            1_500_000_000L, 256, 60, 120, false);

    public long softWallNanos() { return Math.min(maximumWallNanos, 750_000_000L); }

    public long validationReserveNanos() {
        return Math.min(250_000_000L, Math.max(1, maximumWallNanos / 5));
    }

    public PlanningPolicy {
        if (maximumWallNanos <= 0 || beamWidth <= 0 || normalHorizonTicks <= 0
                || dropHorizonTicks < normalHorizonTicks) {
            throw new IllegalArgumentException("Invalid planning bounds.");
        }
    }
}

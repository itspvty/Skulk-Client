package com.ariesninja.skulkpk.client.core.planning;

public record PlanMetrics(
        long searchNanos,
        int candidatesEvaluated,
        double runUpLength,
        double landingSpeed,
        int robustnessScore,
        double edgeMargin,
        int statesDeduplicated,
        int launchSeeds,
        StoppingMethod stoppingMethod,
        boolean usesSneak,
        String terminationReason,
        long directNanos,
        long obstacleNanos,
        long landingValidationNanos,
        int flightStatesExpanded,
        int diversityBuckets,
        int coreTouchdowns,
        int fringeTouchdowns,
        PlanningStage planningStage
) {
    public PlanMetrics(long searchNanos, int candidatesEvaluated, double runUpLength,
                       double landingSpeed, int robustnessScore, double edgeMargin) {
        this(searchNanos, candidatesEvaluated, runUpLength, landingSpeed, robustnessScore,
                edgeMargin, 0, 0, StoppingMethod.NONE, false, "complete",
                searchNanos, 0, 0, 0, 0, 0, 0, PlanningStage.DIRECT);
    }

    public PlanMetrics(long searchNanos, int candidatesEvaluated, double runUpLength,
                       double landingSpeed, int robustnessScore, double edgeMargin,
                       int statesDeduplicated, int launchSeeds, StoppingMethod stoppingMethod,
                       boolean usesSneak, String terminationReason) {
        this(searchNanos, candidatesEvaluated, runUpLength, landingSpeed, robustnessScore,
                edgeMargin, statesDeduplicated, launchSeeds, stoppingMethod, usesSneak,
                terminationReason, searchNanos, 0, 0, 0, 0, 0, 0, PlanningStage.DIRECT);
    }
}

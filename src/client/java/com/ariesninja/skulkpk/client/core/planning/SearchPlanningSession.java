package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import com.ariesninja.skulkpk.client.core.physics.CollisionContact;
import com.ariesninja.skulkpk.client.core.physics.CollisionFace;
import com.ariesninja.skulkpk.client.core.physics.CollisionManifold;
import com.ariesninja.skulkpk.client.core.physics.ParkourPhysics;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import com.ariesninja.skulkpk.client.core.physics.PhysicsStep;
import com.ariesninja.skulkpk.client.core.physics.PhysicsWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic launch generation, direct schedules, then a diverse flight-only obstacle beam. */
public final class SearchPlanningSession implements PlanningSession {
    private static final long DIRECT_BUDGET_NANOS = 175_000_000L;
    private static final double PLAYER_RADIUS = 0.3;
    private static final double MAXIMUM_REACH_TRIGGER_MINIMUM = -(PLAYER_RADIUS * 2 + 0.35);
    // The configuration obstacle already includes the player radius. This additional clearance
    // is the tracking tube reserved for launch-position and command-latency variation.
    private static final double TANGENT_CLEARANCE = 0.25;
    private static final double RUN_UP_INCREMENT = 0.25;
    private static final int MAX_LANES = 36;
    private static final int MAX_LANE_CANDIDATES = MAX_LANES * 4;
    private static final int MAX_LAUNCH_STATES = 192;
    private static final int MAX_GROUND_TICKS = 48;
    private static final int MAX_GROUND_FRONTIER = 24;
    private static final int MAX_MANIFOLD_STATES_PER_STRATUM = 24;
    private static final int MAX_OBSTACLE_ROOTS = 96;
    private static final int MAX_STOPPING_TICKS = 40;
    private static final long PLAN_CONSTRUCTION_RESERVE_NANOS = 75_000_000L;

    private final PlanningRequest request;
    private final ParkourPhysics physics;
    private final long startedNanos;
    private final long wallDeadline;
    private final long softDeadline;
    private final long finalizationDeadline;
    private long directDeadline;
    private final long obstacleDeadline;
    private final Map<String, Integer> rejectionCounts = new LinkedHashMap<>();
    private final Map<ObstacleStateKey, ObstacleNode> obstacleNext = new HashMap<>();
    private final List<Candidate> nominalObstacleCandidates = new ArrayList<>();
    private final List<Candidate> fragileObstacleCandidates = new ArrayList<>();

    private LandingZone landingZone;
    private ConfigurationSpace configurationSpace;
    private List<LadderColumn> ladders = List.of();
    private final java.util.Set<Integer> perimeterLanes = new LinkedHashSet<>();
    private List<LaunchLane> lanes = List.of();
    private List<LaunchState> launchStates = List.of();
    private List<DirectTrial> directTrials = List.of();
    private Map<Integer, List<ObstacleGuide>> obstacleGuides = Map.of();
    private PlanningStage stage = PlanningStage.DIRECT;
    private int directTrialIndex;
    private int directEvaluations;
    private int flightStatesExpanded;
    private int statesDeduplicated;
    private int diversityBuckets;
    private int coreTouchdowns;
    private int fringeTouchdowns;
    private long directFinishedNanos;
    private long directStartedNanos;
    private long obstacleStartedNanos;
    private boolean prepared;
    private volatile boolean cancelled;
    private boolean collisionRelevant;
    private Candidate best;
    private List<ObstacleNode> obstacleFrontier = List.of();
    private int obstacleIndex;
    private int obstacleDepth;
    private int bestObstacleDepth = -1;
    private boolean exploratoryObstacleSearch;
    private boolean refinedLaunchSearch;
    private boolean refinedRouteSearch;
    private boolean skirtSearch;
    private boolean ladderShooting;

    public SearchPlanningSession(PlanningRequest request) { this(request, new ParkourPhysics()); }

    SearchPlanningSession(PlanningRequest request, ParkourPhysics physics) {
        this.request = Objects.requireNonNull(request);
        this.physics = Objects.requireNonNull(physics);
        startedNanos = System.nanoTime();
        wallDeadline = startedNanos + request.policy().maximumWallNanos();
        softDeadline = startedNanos + request.policy().softWallNanos();
        long constructionReserve = Math.min(PLAN_CONSTRUCTION_RESERVE_NANOS,
                Math.max(1, request.policy().maximumWallNanos() / 10));
        finalizationDeadline = wallDeadline - constructionReserve;
        long reserve = request.policy().validationReserveNanos();
        directDeadline = 0;
        obstacleDeadline = wallDeadline - reserve;
    }

    @Override
    public PlanningTickResult tick(long budgetNanos) {
        if (cancelled) return rejected(PlanRejectionReason.UNREACHABLE, "Planning was cancelled.");
        long now = System.nanoTime();
        if (now >= wallDeadline) return finishAtWallLimit(now);
        long sliceDeadline = Math.min(wallDeadline, now + Math.max(1, budgetNanos));
        if (!prepared) {
            prepare();
            if (launchStates.isEmpty()) return rejected(PlanRejectionReason.UNREACHABLE,
                    "No supported launch lane reached a jump-trigger interval. " + rejectionSummary());
        }
        while (!cancelled && !Thread.currentThread().isInterrupted() && System.nanoTime() < sliceDeadline) {
            if (stage == PlanningStage.DIRECT) {
                PlanningTickResult result = tickDirect(System.nanoTime());
                if (result != null) return result;
            } else if (stage == PlanningStage.OBSTACLE) {
                PlanningTickResult result = tickObstacle(System.nanoTime());
                if (result != null) return result;
            } else return ready(best, System.nanoTime());
        }
        return System.nanoTime() >= wallDeadline ? finishAtWallLimit(System.nanoTime())
                : new PlanningTickResult.Planning(directEvaluations + flightStatesExpanded);
    }

    @Override public void cancel() { cancelled = true; }

    private void prepare() {
        landingZone = LandingZone.build(request.problem().landingRegion(), request.player(), request.world());
        configurationSpace = ConfigurationSpace.compile(request.problem().nearbyCollision(),
                request.player().boundingBox());
        ladders = LadderColumn.discover(request.world(), problemBounds().contract(0.01)).stream()
                .filter(column -> landingZone.surfaces().stream().anyMatch(surface ->
                        horizontalDistance(column.entry(surface.topY()), surface.centerFeet()) <= 1.6
                                && column.attachment().maxY >= surface.topY() - 0.5))
                .sorted(Comparator.comparingDouble(column -> landingZone.surfaces().stream()
                        .mapToDouble(surface -> horizontalDistance(column.entry(surface.topY()), surface.centerFeet()))
                        .min().orElse(Double.MAX_VALUE))).toList();
        lanes = buildLaunchLanes();
        if (!ladders.isEmpty()) lanes = withPerimeterLanes(lanes);
        obstacleGuides = buildObstacleGuides();
        collisionRelevant = !ladders.isEmpty() || obstacleGuides.values().stream().flatMap(List::stream)
                .anyMatch(guide -> guide.side() != 0);
        launchStates = buildLaunchStates();
        directTrials = buildDirectTrials();
        directStartedNanos = System.nanoTime();
        // Once configuration geometry proves an obstacle route is relevant, Stage A owns the
        // first 200 ms of the complete request, including launch-manifold preparation. An
        // unobstructed route still receives its full direct trial window so ordinary momentum
        // jumps cannot be displaced into the obstacle beam by cold-start preparation cost.
        long directWindowStart = collisionRelevant ? startedNanos : directStartedNanos;
        directDeadline = Math.min(finalizationDeadline - request.policy().validationReserveNanos(),
                directWindowStart + DIRECT_BUDGET_NANOS);
        prepared = true;
        if (lanes.isEmpty()) recordRejection("No fixed takeoff edge had a supported approach corridor.");
        if (launchStates.isEmpty()) recordRejection("Insufficient runway: no prefix reached its trigger interval.");
    }

    private PlanningTickResult tickDirect(long now) {
        if (now >= directDeadline || directTrialIndex >= directTrials.size()) {
            directFinishedNanos = now;
            if (best != null) stage = PlanningStage.LANDING_VALIDATION;
            else beginObstacle();
            return null;
        }
        DirectTrial trial = directTrials.get(directTrialIndex++);
        LaunchState launch = trial.launch();
        List<AirSchedule> schedules = directSchedules(launch);
        Candidate candidate = simulateDirect(launch, schedules.get(trial.scheduleIndex()));
        directEvaluations++;
        keep(candidate);
        // Trials are wave-ordered across geometry; keep the direct stage open for its bounded
        // budget so later waves can replace a synthetic route with a current-state/core route.
        return null;
    }

    private List<DirectTrial> buildDirectTrials() {
        if (launchStates.isEmpty()) return List.of();
        Map<Integer, Integer> nextRank = new HashMap<>();
        Map<LaunchState, Integer> stateRanks = new LinkedHashMap<>();
        int maximumRank = 0;
        for (LaunchState launch : launchStates) {
            int rank = nextRank.getOrDefault(launch.lane().id(), 0);
            stateRanks.put(launch, rank);
            nextRank.put(launch.lane().id(), rank + 1);
            maximumRank = Math.max(maximumRank, rank);
        }
        int scheduleCount = directSchedules(launchStates.getFirst()).size();
        List<Integer> scheduleOrder = new ArrayList<>();
        for (int preferred : new int[]{0, 4, 3, 5, 2, 6, 1}) {
            if (preferred < scheduleCount) scheduleOrder.add(preferred);
        }
        for (int index = 0; index < scheduleCount; index++) {
            if (!scheduleOrder.contains(index)) scheduleOrder.add(index);
        }
        List<DirectTrial> result = new ArrayList<>();
        for (int stateRank = 0; stateRank <= maximumRank; stateRank++) {
            for (int scheduleRank = 0; scheduleRank < scheduleOrder.size(); scheduleRank++) {
                int schedule = scheduleOrder.get(scheduleRank);
                for (LaunchState launch : launchStates) {
                    if (stateRanks.get(launch) == stateRank) {
                        result.add(new DirectTrial(launch, schedule));
                    }
                }
            }
        }
        // Preserve the wave order: sorting every schedule by prefix length starves
        // velocity-bearing launches behind thousands of impossible short prefixes.
        return List.copyOf(result);
    }

    private void beginObstacle() {
        stage = PlanningStage.OBSTACLE;
        if (obstacleStartedNanos == 0) obstacleStartedNanos = System.nanoTime();
        if (exploratoryObstacleSearch) obstacleGuides = buildObstacleGuides();
        // The lateral launch manifold belongs to Stage B. Building it during prepare() made
        // direct collision routes spend their fast-path window on ground alternatives they did
        // not need, and left less time for the flight search that does need them.
        List<LaunchState> expandedLaunches = new ArrayList<>(launchStates);
        int searchedObstacleLanes = 0;
        // Canonical geometry must not change when the player stands a little farther left.
        // Fringe anchors serve direct maximum reach; they cannot consume all four expensive
        // ground-manifold slots while the flight stage admits only core-anchor lanes.
        List<LaunchLane> obstacleLanes = lanes.stream()
                .filter(lane -> lane.landingAnchor().core() || landingZone.coreAnchors().isEmpty())
                .sorted(Comparator.comparingInt(LaunchLane::id)).toList();
        for (LaunchLane lane : exploratoryObstacleSearch ? List.<LaunchLane>of() : obstacleLanes) {
            if (System.nanoTime() >= obstacleDeadline - 150_000_000L) break;
            if (obstacleGuides.getOrDefault(lane.id(), List.of()).stream()
                    .anyMatch(guide -> guide.side() != 0) && searchedObstacleLanes++ < 4) {
                collectKinodynamicLaunchStates(expandedLaunches, lane);
            }
        }
        launchStates = deduplicateAndPrioritize(expandedLaunches);
        // Direct schedules have already been exhausted by Stage A.  Replaying hundreds of
        // whole-flight macros here consumed the entire contact budget before the first beam
        // depth.  Stage B starts immediately from diverse launch/topology roots and spends its
        // budget on one-tick mechanics transitions.
        List<ObstacleNode> roots = new ArrayList<>();
        for (LaunchState launch : obstacleLaunchStates()) {
            for (ObstacleGuide guide : obstacleGuides.getOrDefault(launch.lane().id(), List.of())) {
                if (!ladders.isEmpty() && guide.ladder == null) continue;
                if (!guideMatches(launch.approachMode(), guide.side())) continue;
                int guideIndex = guide.advance(launch.state().feetPosition(), 0);
                float desiredMovementYaw = guide.desiredYaw(
                        launch.state().feetPosition(), guideIndex, launch.lane());
                float guidedStrafe = guide.side() == 0 ? 0 : guide.side() * 0.35f;
                float approachStrafe = launch.approachMode() == RouteMode.AVOID_LEFT ? -1
                        : launch.approachMode() == RouteMode.AVOID_RIGHT ? 1 : 0;
                LinkedHashSet<Float> rootStrafes = new LinkedHashSet<>(List.of(
                        0f, guidedStrafe, (float) guide.side(), approachStrafe));
                for (float strafe : rootStrafes) {
                    LinkedHashSet<Float> rootYaws = new LinkedHashSet<>(List.of(
                            commandYaw(launch.state().yaw(), desiredMovementYaw, 1, strafe),
                            launch.state().yaw(), launch.state().yaw() - 12,
                            launch.state().yaw() + 12));
                    for (float rootYaw : rootYaws) {
                        ControlFrame jump = frame(1, strafe, launch.state().sprinting(), true,
                                rootYaw, ControlPhase.TAKEOFF, FrameGuard.GROUNDED);
                        if (!hasCommandLead(launch, jump)) continue;
                        PhysicsStep step = advanceStep(launch.state(), jump);
                        ParkourState next = step == null ? null : step.state();
                        if (next != null && !next.onGround()) {
                            int nextGuideIndex = guide.advance(next.feetPosition(), guideIndex);
                            roots.add(new ObstacleNode(next, launch, guide, nextGuideIndex,
                                    null, jump, step.collisions(), 1,
                                    collisionSignature(step.collisions()),
                                    obstacleScore(next, launch.lane(), guide, nextGuideIndex)));
                        }
                    }
                }
            }
        }
        obstacleFrontier = retainRootDiverse(roots);
        if (obstacleFrontier.isEmpty()) recordRejection("No physically viable flight root left takeoff support.");
    }

    private List<ObstacleNode> retainRootDiverse(List<ObstacleNode> roots) {
        Map<String, List<ObstacleNode>> families = new LinkedHashMap<>();
        for (ObstacleNode root : roots) {
            String key = root.launch.lane().id() + ":" + root.launch.approachMode()
                    + ":" + root.guide.side() + ":" + root.guide.waypoints().size();
            families.computeIfAbsent(key, ignored -> new ArrayList<>()).add(root);
        }
        families.values().forEach(family -> family.sort(OBSTACLE_ORDER));
        int rootLimit = Math.min(exploratoryObstacleSearch || !ladders.isEmpty()
                ? 256 : MAX_OBSTACLE_ROOTS, request.policy().beamWidth());
        List<ObstacleNode> retained = new ArrayList<>();
        for (int rank = 0; retained.size() < rootLimit; rank++) {
            boolean added = false;
            for (List<ObstacleNode> family : families.values()) {
                if (rank >= family.size()) continue;
                retained.add(family.get(rank));
                added = true;
                if (retained.size() >= rootLimit) break;
            }
            if (!added) break;
        }
        return List.copyOf(retained);
    }

    private List<LaunchState> obstacleLaunchStates() {
        List<LaunchState> eligible = new ArrayList<>();
        for (LaunchState launch : launchStates) {
            // Near-side fringe anchors expand direct maximum-reach geometry. They must not
            // multiply obstacle homotopies when a stable core exists, or the 256-state beam is
            // diluted across duplicate obstacle routes before it clears the feature.
            if (ladders.isEmpty() && !launch.lane().landingAnchor().core() && !landingZone.coreAnchors().isEmpty()) continue;
            // A straight jump is not a proof that a diagonal/yawed jump is impossible.
            // The root expansion validates EACH actual jump command below. Rejecting the
            // entire launch here erased late corner launches which need lateral impulse.
            if (!launch.state().onGround() || !supportsLaunchTube(launch)) continue;
            if (!refinedLaunchSearch && !hasCommandLead(launch, frame(1, 0,
                    launch.state().sprinting(), true, launch.state().yaw(),
                    ControlPhase.TAKEOFF, FrameGuard.GROUNDED))) continue;
            eligible.add(launch);
        }
        boolean hasAvoidance = eligible.stream().anyMatch(launch ->
                launch.approachMode() == RouteMode.AVOID_LEFT
                        || launch.approachMode() == RouteMode.AVOID_RIGHT);
        Map<String, List<LaunchState>> byFamily = new LinkedHashMap<>();
        for (LaunchState launch : eligible) {
            if (hasAvoidance && ladders.isEmpty() && launch.approachMode() == RouteMode.DIRECT) continue;
            String family = launch.lane().id() + ":" + launch.approachMode();
            byFamily.computeIfAbsent(family, ignored -> new ArrayList<>()).add(launch);
        }
        byFamily.values().forEach(states -> {
            Comparator<LaunchState> order = Comparator
                    .comparingDouble(this::tangentLaunchScore)
                    .thenComparingDouble(this::ballisticTouchdownMiss)
                    .thenComparing((LaunchState state) -> !state.state().sprinting())
                    .thenComparing(Comparator.comparingDouble((LaunchState state) ->
                            Math.abs(state.lane().lateralError(state.state().feetPosition()))).reversed())
                    .thenComparing(Comparator.comparingDouble(this::forwardSpeed).reversed())
                    .thenComparingDouble(this::launchCost);
            states.sort(order);
            LinkedHashSet<LaunchState> prioritized = new LinkedHashSet<>();
            // Preserve the strongest balanced state that is still before the nominal edge.
            // It is the mechanics-derived counterpart of committing a lateral route while both
            // forward and lateral momentum are available, and prevents tangent proximity from
            // selecting only late, pillar-adjacent launch states.
            states.stream().filter(state -> state.lane().distanceBeforeEdge(
                            state.state().feetPosition()) >= 0)
                    .max(Comparator.comparingDouble(state -> {
                        Vec3d side = ControlInput.strafeDirection(state.lane().heading());
                        return Math.min(Math.abs(state.state().velocity().dotProduct(side)),
                                Math.max(0, forwardSpeed(state)));
                    })).ifPresent(prioritized::add);
            if (!states.isEmpty()) prioritized.add(states.getFirst());
            prioritized.addAll(states);
            states.clear();
            states.addAll(prioritized);
        });
        List<LaunchState> selected = new ArrayList<>();
        if (!ladders.isEmpty()) byFamily.values().stream()
                .filter(states -> !states.isEmpty() && perimeterLanes.contains(states.getFirst().lane().id()))
                .limit(12).forEach(states -> selected.add(states.getFirst()));
        int launchLimit = refinedLaunchSearch ? 96 : 32;
        for (int rank = 0; selected.size() < launchLimit; rank++) {
            boolean added = false;
            for (List<LaunchState> states : byFamily.values()) {
                if (rank >= states.size()) continue;
                if (selected.contains(states.get(rank))) continue;
                selected.add(states.get(rank));
                added = true;
                if (selected.size() >= launchLimit) break;
            }
            if (!added) break;
        }
        return List.copyOf(selected);
    }

    /** Reject impossible launch perturbations before they can occupy the flight beam.
     * This is the contact route's measured launch tolerance, not a landing safety inset.
     * A nominal launch hanging by 0.007 blocks cannot support a +/-0.025 position tube.
     */
    private boolean supportsLaunchTube(LaunchState launch) {
        Vec3d heading = launch.lane().heading();
        Vec3d side = ControlInput.strafeDirection(heading);
        for (Vec3d axis : List.of(heading, side)) {
            for (double offset : new double[]{-0.025, 0.025}) {
                Vec3d feet = launch.state().feetPosition().add(axis.multiply(offset));
                if (!bodyClear(feet) || !SupportResolver.targetSupported(playerBox(feet), feet,
                        true, request.problem().approachRegion())) return false;
            }
        }
        return true;
    }

    private double tangentLaunchScore(LaunchState launch) {
        int sideSign = launch.approachMode() == RouteMode.AVOID_LEFT ? -1
                : launch.approachMode() == RouteMode.AVOID_RIGHT ? 1 : 0;
        ObstacleGuide guide = obstacleGuides.getOrDefault(launch.lane().id(), List.of()).stream()
                .filter(value -> value.side() == sideSign && !value.waypoints().isEmpty())
                .findFirst().orElse(null);
        if (guide == null) return ballisticTouchdownMiss(launch) + launchCost(launch);
        Vec3d side = ControlInput.strafeDirection(launch.lane().heading());
        double lateral = launch.lane().lateralError(launch.state().feetPosition());
        double lateralSpeed = launch.state().velocity().dotProduct(side);
        double forward = Math.max(0.02, forwardSpeed(launch));
        double distance = Math.max(0, -launch.state().feetPosition()
                .subtract(launch.lane().takeoffPoint()).dotProduct(launch.lane().heading()));
        double ticks = MathHelper.clamp(distance / (forward + (launch.state().sprinting() ? 0.10 : 0.04)),
                1, 8);
        double projectedLateral = lateral + lateralSpeed * ticks;
        double tangentLateral = guide.waypoints().getFirst()
                .subtract(launch.lane().takeoffPoint()).dotProduct(side);
        double balancedMomentum = Math.min(Math.abs(lateralSpeed), forward);
        double beforeEdge = launch.lane().distanceBeforeEdge(launch.state().feetPosition());
        double commitPenalty = Math.max(0, -beforeEdge) * 120
                + Math.abs(beforeEdge - 0.10) * 4;
        return Math.abs(projectedLateral - tangentLateral) * 12
                - balancedMomentum * 8 + launchCost(launch) * 0.15 + commitPenalty;
    }

    private double ballisticTouchdownMiss(LaunchState launch) {
        double supportCeiling = Math.max(launch.lane().takeoffSurface().topY(),
                launch.lane().landingAnchor().feet().y) + 0.02;
        PhysicsWorld ballisticWorld = new SupportOnlyPhysicsWorld(request.world(), supportCeiling);
        ParkourState state = launch.state();
        boolean airborne = false;
        double best = SupportResolver.distanceToRegion(state.feetPosition(),
                request.problem().landingRegion());
        for (int tick = 0; tick < Math.min(36, maximumHorizon()); tick++) {
            float strafe = tick < 5 ? switch (launch.approachMode()) {
                case AVOID_LEFT -> -0.5f;
                case AVOID_RIGHT -> 0.5f;
                default -> 0;
            } : 0;
            ControlInput input = new ControlInput(1, strafe, launch.state().sprinting(),
                    tick == 0, false, launch.lane().yaw());
            try { state = physics.tick(ballisticWorld, state, input).state(); }
            catch (RuntimeException exception) { return Double.MAX_VALUE; }
            airborne |= !state.onGround();
            best = Math.min(best, SupportResolver.distanceToRegion(state.feetPosition(),
                    request.problem().landingRegion()));
            if (airborne && state.onGround()) break;
        }
        return best + Math.abs(launch.lane().lateralError(state.feetPosition())) * 0.05;
    }

    private boolean requiresMaximumReach(LaunchLane lane) {
        // Anchor distance becomes misleading for a legal fringe landing: the mutually nearest
        // player positions can be only ~4.1 apart even though the actual platform-edge gap is
        // the full four blocks. Use platform geometry as the primary reach classification so
        // edge-departure momentum is retained for shifted/diagonal maximum jumps as well.
        double platformGap = landingZone.surfaces().stream()
                .mapToDouble(surface -> edgeDistance(lane.takeoffSurface().footprint(), surface.footprint()))
                .min().orElse(Double.MAX_VALUE);
        return platformGap >= 3.75
                || horizontalDistance(lane.takeoffPoint(), lane.landingAnchor().feet()) >= 4.20;
    }

    private PlanningTickResult tickObstacle(long now) {
        if (now >= softDeadline && !collisionRelevant && best == null) {
            return rejected(PlanRejectionReason.UNREACHABLE,
                    "No stable ordinary route was found before the 750 ms soft limit. "
                            + rejectionSummary());
        }
        if (now >= obstacleDeadline) {
            promoteNominalObstacleCandidate();
            tryPrecisionCandidates();
            if (best != null) { stage = PlanningStage.LANDING_VALIDATION; return null; }
            return rejected(PlanRejectionReason.SEARCH_TIMEOUT,
                    "Trajectory search reached its obstacle-stage limit before a stable route was validated. "
                            + rejectionSummary());
        }
        if (obstacleFrontier.isEmpty()) {
            promoteNominalObstacleCandidate();
            if (best != null) { stage = PlanningStage.LANDING_VALIDATION; return null; }
            if (!ladderShooting && !ladders.isEmpty() && now + 150_000_000L < obstacleDeadline) {
                ladderShooting = true;
                shootAttachmentRoutes();
                if (best != null) { stage = PlanningStage.LANDING_VALIDATION; return null; }
            }
            if (!skirtSearch && collisionRelevant && ladders.isEmpty()
                    && now + 300_000_000L < obstacleDeadline) {
                skirtSearch = true;
                collectSkirtLaunches();
                if (best != null) { stage = PlanningStage.LANDING_VALIDATION; return null; }
            }
            if (!refinedRouteSearch && !fragileObstacleCandidates.isEmpty()
                    && now + 100_000_000L < finalizationDeadline) {
                refinedRouteSearch = true;
                refineNearMissRoutes();
                if (best != null) { stage = PlanningStage.LANDING_VALIDATION; return null; }
            }
            if (!exploratoryObstacleSearch && collisionRelevant && now + 200_000_000L < obstacleDeadline) {
                exploratoryObstacleSearch = true;
                obstacleIndex = 0;
                obstacleDepth = 0;
                obstacleNext.clear();
                beginObstacle();
                return null;
            }
            if (exploratoryObstacleSearch && !refinedLaunchSearch && !fragileObstacleCandidates.isEmpty()
                    && now + 150_000_000L < obstacleDeadline) {
                List<LaunchState> refined = refineNearMissLaunches();
                refinedLaunchSearch = true;
                if (!refined.isEmpty()) {
                    launchStates = refined;
                    nominalObstacleCandidates.clear();
                    obstacleIndex = 0;
                    obstacleDepth = 0;
                    obstacleNext.clear();
                    beginObstacle();
                    return null;
                }
            }
            tryPrecisionCandidates();
            if (best != null) { stage = PlanningStage.LANDING_VALIDATION; return null; }
            return rejected(PlanRejectionReason.UNREACHABLE, "No stable route was found. "
                    + rejectionSummary());
        }
        if (obstacleDepth >= maximumHorizon()) {
            obstacleFrontier.forEach(node -> recordRejection(classifyMiss(node.state, node.launch.lane())));
            obstacleFrontier = List.of();
            return null;
        }
        if (obstacleIndex >= obstacleFrontier.size()) {
            promoteNominalObstacleCandidate();
            if (best == null && exploratoryObstacleSearch && !refinedRouteSearch
                    && !fragileObstacleCandidates.isEmpty()
                    && System.nanoTime() + 100_000_000L < finalizationDeadline) {
                // Refine the first demonstrated landing family now, while the reserved
                // validation budget remains. Do not spend it following already-missed falls.
                refinedRouteSearch = true;
                refineNearMissRoutes();
                if (best != null) { stage = PlanningStage.LANDING_VALIDATION; return null; }
            }
            if (best != null && bestObstacleDepth >= 0 && obstacleDepth > bestObstacleDepth) {
                stage = PlanningStage.LANDING_VALIDATION;
                return null;
            }
            obstacleFrontier = retainDiverse(new ArrayList<>(obstacleNext.values()));
            obstacleNext.clear();
            obstacleIndex = 0;
            obstacleDepth++;
            return null;
        }
        expandObstacle(obstacleFrontier.get(obstacleIndex++));
        return null;
    }

    /** Refine reachable staging poses and jump direction around a demonstrated route.
     * Every prefix is re-simulated; translating an airborne state alone is never a launch. */
    private void refineNearMissRoutes() {
        List<Candidate> refined = new ArrayList<>();
        for (Candidate seed : fragileObstacleCandidates.stream().limit(4).toList()) {
            boolean attachment = seed.routeMode == RouteMode.LADDER_ASSIST;
            for (double forward : attachment ? new double[]{0, -0.05, 0.05, -0.10, 0.10, 0.15}
                    : new double[]{0, 0.05, 0.10, 0.15, -0.05}) {
                for (double lateral : new double[]{0, -0.015, 0.015}) {
                    for (float yawOffset : new float[]{0, -3, 3, -6, 6}) {
                        if (System.nanoTime() >= obstacleDeadline) break;
                        Vec3d offset = seed.launch.lane().heading().multiply(forward).add(
                                ControlInput.strafeDirection(seed.launch.lane().heading()).multiply(lateral));
                        Candidate candidate = shootRefinedRoute(seed, offset, yawOffset);
                        if (candidate != null) {
                            if (attachment) {
                                // Validate a demonstrated catch immediately. Generating every
                                // variant first spent the hard deadline before proving the first
                                // viable attachment, especially with a cold JVM.
                                for (Tube tube : new Tube[]{Tube.STANDARD, Tube.PRECISE}) {
                                    Candidate tested = candidate.withTube(tube, false);
                                    if (validateRouteTube(tested) == 8) {
                                        best = tested.withTube(tube, true);
                                        return;
                                    }
                                }
                            } else refined.add(candidate);
                        }
                    }
                }
            }
        }
        refined.sort(NOMINAL_VALIDATION_ORDER);
        for (Candidate candidate : refined) {
            if (System.nanoTime() >= finalizationDeadline) break;
            if (validateRouteTube(candidate) == 8) {
                best = candidate.withTube(Tube.STANDARD, true);
                return;
            }
        }
        for (Candidate candidate : refined) {
            if (System.nanoTime() >= finalizationDeadline) break;
            Candidate precise = candidate.withTube(Tube.PRECISE, false);
            if (validateRouteTube(precise) == 8) {
                best = precise.withTube(Tube.PRECISE, true);
                return;
            }
        }
    }

    private Candidate shootRefinedRoute(Candidate seed, Vec3d offset, float jumpYawOffset) {
        LaunchState old = seed.launch;
        ParkourState initial = pose(old.initialState(), old.initialState().feetPosition().add(offset),
                old.initialState().velocity(), old.initialState().yaw(), true,
                old.initialState().sprinting(), false);
        if (!bodyClear(initial.feetPosition()) || support(initial).kind() != SupportKind.TAKEOFF) return null;
        ParkourState state = initial;
        List<ControlFrame> frames = new ArrayList<>();
        List<ParkourState> states = new ArrayList<>(List.of(initial));
        List<CollisionManifold> contacts = new ArrayList<>();
        for (ControlInput input : old.groundPrefix()) {
            PhysicsStep step;
            try { step = physics.tick(request.world(), state, input); }
            catch (RuntimeException exception) { return null; }
            state = step.state();
            if (!state.onGround() || support(state).kind() != SupportKind.TAKEOFF
                    || step.collisions().contacts().stream().anyMatch(contact -> !contact.support())) return null;
            frames.add(frame(input.forward(), input.strafe(), input.sprint(), false,
                    input.yaw(), ControlPhase.RUN_UP, FrameGuard.GROUNDED));
            states.add(state);
            contacts.add(step.collisions());
        }
        LaunchState launch = new LaunchState(initial, state, old.lane(), initial.feetPosition(),
                old.runUpLength(), old.jumpTick(), old.groundPrefix(), false, old.approachMode());
        if (!supportsLaunchTube(launch)) return null;
        LadderColumn attachment = seed.routeMode == RouteMode.LADDER_ASSIST ? ladders.stream()
                .filter(column -> seed.states.stream().anyMatch(sample -> column.contains(sample.feetPosition())))
                .findFirst().orElse(null) : null;
        boolean attached = false;
        for (int i = old.groundPrefix().size(); i < seed.frames.size(); i++) {
            ControlFrame oldFrame = seed.frames.get(i);
            if (oldFrame.phase().isLandingPhase() && attachment == null) break;
            ControlFrame action = frame(oldFrame.forward(), oldFrame.strafe(), oldFrame.sprint(),
                    oldFrame.jump(), boundedYaw(state.yaw(), oldFrame.desiredYaw()
                    + (oldFrame.jump() ? jumpYawOffset : 0)), oldFrame.phase(), oldFrame.guard());
            attached |= attachment != null && attachment.contains(state.feetPosition());
            if (attached) action = LadderContinuation.choose(request.world(), physics, state,
                    attachment, attachment.exit(landingZone));
            PhysicsStep step = advanceStep(state, action);
            if (step == null || attachment == null && step.collisions().contacts().stream()
                    .anyMatch(contact -> !contact.support())) return null;
            state = step.state();
            frames.add(action);
            states.add(state);
            contacts.add(step.collisions());
            if (state.onGround()) {
                if (support(state).targetSupported()) return stopAndValidate(launch, frames, states,
                        contacts, PlanningStage.OBSTACLE, seed.routeMode);
                if (attachment == null || !attachment.supportsExit(state)) return null;
            }
        }
        return null;
    }

    /** Local shooting refinement around physically demonstrated near-misses. All changes
     * are actual ground commands replayed from the same reachable staging state. */
    private List<LaunchState> refineNearMissLaunches() {
        List<LaunchState> result = new ArrayList<>();
        LinkedHashSet<LaunchState> seeds = new LinkedHashSet<>();
        fragileObstacleCandidates.stream().sorted(NOMINAL_VALIDATION_ORDER)
                .map(candidate -> candidate.launch).forEach(seeds::add);
        int seedCount = 0;
        for (LaunchState seed : seeds) {
            if (seedCount++ >= 4 || System.nanoTime() >= obstacleDeadline - 100_000_000L) break;
            for (int tail : new int[]{1, 2, 3}) for (float yawOffset : new float[]{-12, -6, -3, 0, 3, 6, 12}) {
                ParkourState state = seed.initialState();
                List<ControlInput> prefix = new ArrayList<>();
                boolean valid = true;
                for (int index = 0; index < seed.groundPrefix().size(); index++) {
                    ControlInput old = seed.groundPrefix().get(index);
                    float yaw = old.yaw() + (index >= seed.groundPrefix().size() - tail ? yawOffset : 0);
                    ControlInput input = new ControlInput(old.forward(), old.strafe(), old.sprint(), false,
                            false, boundedYaw(state.yaw(), yaw));
                    try { state = physics.tickState(request.world(), state, input); }
                    catch (RuntimeException exception) { valid = false; break; }
                    if (!state.onGround() || state.horizontalCollision() || support(state).kind() != SupportKind.TAKEOFF) {
                        valid = false; break;
                    }
                    prefix.add(input);
                }
                if (!valid) continue;
                addRefinedLaunch(result, seed, state, prefix);
                for (ControlInput input : groundActions(state, seed.lane())) {
                    ParkourState next;
                    try { next = physics.tickState(request.world(), state, input); }
                    catch (RuntimeException exception) { continue; }
                    if (!next.onGround() || next.horizontalCollision() || support(next).kind() != SupportKind.TAKEOFF) continue;
                    List<ControlInput> extended = new ArrayList<>(prefix);
                    extended.add(input);
                    addRefinedLaunch(result, seed, next, extended);
                }
            }
        }
        Map<String, LaunchState> unique = new LinkedHashMap<>();
        result.stream().sorted(Comparator.comparingDouble(this::tangentLaunchScore))
                .forEach(launch -> unique.putIfAbsent(launchKey(launch), launch));
        return unique.values().stream().limit(MAX_LAUNCH_STATES).toList();
    }

    private void addRefinedLaunch(List<LaunchState> output, LaunchState seed, ParkourState state,
                                  List<ControlInput> prefix) {
        if (!seed.lane().inTriggerInterval(state.feetPosition())) return;
        LaunchState launch = new LaunchState(seed.initialState(), state, seed.lane(), seed.stagingPosition(),
                horizontalDistance(seed.stagingPosition(), state.feetPosition()), prefix.size(), prefix,
                false, seed.approachMode());
        if (supportsLaunchTube(launch)) output.add(launch);
    }

    private void expandObstacle(ObstacleNode node) {
        if (node.guide.ladder != null && node.guide.ladder.contains(node.state.feetPosition())) {
            completeLadderRoute(node);
            return;
        }
        for (ControlFrame action : obstacleActions(node)) {
            PhysicsStep step = advanceStep(node.state, action);
            if (step == null) continue;
            ParkourState next = step.state();
            flightStatesExpanded++;
            if (exploratoryObstacleSearch && node.guide.ladder == null && node.guide.side() != 0 && step.collisions().contacts().stream()
                    .anyMatch(contact -> !contact.support())) {
                // Avoidance is a hard route contract, not a late score penalty. Keeping
                // already-clipped states crowded intact routes out of the bounded beam.
                // Contact-mode (side == 0) still retains head/wall/corner transitions.
                recordRejection("Forbidden obstacle contact in an avoidance route.");
                continue;
            }
            double recoveryFloor = node.guide.ladder == null ? minimumLandingY() - 4
                    : node.guide.ladder.attachment().minY;
            if (next.feetPosition().y < recoveryFloor) {
                recordRejection("Undershot: trajectory fell below the landing region.");
                continue;
            }
            if (next.onGround()) {
                if (!support(next).targetSupported()) {
                    recordRejection("Wrong support: first grounded contact was outside the connected target.");
                    continue;
                }
                RouteMode routeMode = node.guide.side() < 0 ? RouteMode.AVOID_LEFT
                        : node.guide.side() > 0 ? RouteMode.AVOID_RIGHT : RouteMode.DIRECT;
                keep(stopAndValidate(node.launch, reconstructObstacleFrames(node, action),
                        reconstructObstacleStates(node, next),
                        reconstructObstacleContacts(node, step.collisions()),
                        PlanningStage.OBSTACLE, routeMode));
                continue;
            }
            int guideIndex = node.guide.advance(next.feetPosition(), node.guideIndex);
            ObstacleNode child = new ObstacleNode(next, node.launch, node.guide, guideIndex,
                    node, action, step.collisions(), node.airTicks + 1,
                    mergeCollisionSignature(node.collisionSignature, step.collisions()),
                    obstacleScore(next, node.launch.lane(), node.guide, guideIndex));
            ObstacleStateKey key = ObstacleStateKey.from(child);
            ObstacleNode existing = obstacleNext.get(key);
            if (existing == null || child.score < existing.score) obstacleNext.put(key, child);
            else statesDeduplicated++;
        }
    }

    private void completeLadderRoute(ObstacleNode node) {
        List<ControlFrame> frames = reconstructObstacleFrames(node, null);
        List<ParkourState> states = reconstructObstacleStates(node, null);
        List<CollisionManifold> contacts = reconstructObstacleContacts(node, null);
        frames.removeLast(); states.removeLast(); contacts.removeLast();
        ParkourState state = node.state;
        Vec3d exit = node.guide.ladder.exit(landingZone);
        for (int tick = 0; tick < 100 && System.nanoTime() < obstacleDeadline; tick++) {
            ControlFrame action = LadderContinuation.choose(request.world(), physics, state, node.guide.ladder, exit);
            PhysicsStep step = advanceStep(state, action);
            if (step == null) return;
            state = step.state();
            frames.add(action); states.add(state); contacts.add(step.collisions());
            flightStatesExpanded++;
            if (state.onGround()) {
                if (support(state).targetSupported()) {
                    keep(stopAndValidate(node.launch, frames, states, contacts,
                            PlanningStage.OBSTACLE, RouteMode.LADDER_ASSIST));
                    return;
                }
                if (!node.guide.ladder.supportsExit(state)) return;
            }
            if (state.feetPosition().y < node.guide.ladder.attachment().minY) return;
        }
    }

    private Candidate simulateDirect(LaunchState launch, AirSchedule schedule) {
        return simulateSchedule(launch, schedule, PlanningStage.DIRECT, RouteMode.DIRECT);
    }

    private Candidate simulateSchedule(LaunchState launch, AirSchedule schedule,
                                       PlanningStage sourceStage, RouteMode routeMode) {
        List<ControlFrame> frames = groundFrames(launch);
        List<ParkourState> states = replayGroundStates(launch);
        List<CollisionManifold> contacts = replayGroundContacts(launch);
        ParkourState state = launch.state();
        boolean airborne = false;
        for (int tick = 0; tick < maximumHorizon(); tick++) {
            ControlFrame action = schedule.frame(tick, launch.lane(), !airborne);
            PhysicsStep step = advanceStep(state, action);
            if (step == null) return null;
            state = step.state();
            frames.add(action);
            states.add(state);
            contacts.add(step.collisions());
            airborne |= !state.onGround();
            if (airborne && state.onGround()) {
                if (!support(state).targetSupported()) {
                    recordRejection(classifyMiss(state, launch.lane()));
                    return null;
                }
                return stopAndValidate(launch, frames, states, contacts, sourceStage, routeMode);
            }
        }
        recordRejection(classifyMiss(state, launch.lane()));
        return null;
    }

    private Candidate stopAndValidate(LaunchState launch, List<ControlFrame> baseFrames,
                                      List<ParkourState> baseStates,
                                      List<CollisionManifold> transitContacts,
                                      PlanningStage sourceStage, RouteMode requestedMode) {
        if (sourceStage == PlanningStage.OBSTACLE && !hasCommandLead(launch, baseFrames)) {
            recordRejection("Unsafe commit: jump command was not issued one supported transition before the edge.");
            return null;
        }
        ParkourState touchdown = baseStates.getLast();
        if (landingZone.isCore(touchdown.boundingBox(), touchdown.feetPosition())) coreTouchdowns++;
        else fringeTouchdowns++;
        for (StoppingMethod method : List.of(StoppingMethod.NATURAL,
                StoppingMethod.COUNTER_INPUT, StoppingMethod.SNEAK)) {
            StoppingOutcome stopped = simulateStopping(touchdown, launch.lane(), method);
            if (stopped == null) continue;
            List<ControlFrame> frames = new ArrayList<>(baseFrames);
            frames.addAll(stopped.frames);
            List<ParkourState> states = new ArrayList<>(baseStates);
            states.addAll(stopped.states);
            ParkourState end = states.getLast();
            double margin = SupportResolver.edgeMargin(end.boundingBox(), end.feetPosition().y,
                    request.problem().landingRegion());
            int horizontalContacts = (int) states.stream().filter(ParkourState::horizontalCollision).count();
            double obstacleClearance = trajectoryObstacleClearance(states);
            ContactProfile contactProfile = contactProfile(transitContacts, baseStates, requestedMode);
            collisionRelevant |= !contactProfile.events().isEmpty();
            boolean avoidance = requestedMode == RouteMode.AVOID_LEFT
                    || requestedMode == RouteMode.AVOID_RIGHT;
            if (avoidance && contactProfile.unplannedContacts() > 0) {
                recordRejection("Forbidden contact: avoidance route clipped its configuration obstacle.");
                continue;
            }
            boolean momentumReady = (!requiresPreparedMomentum(launch.lane())
                    || launch.state().sprinting())
                    && (contactProfile.mode() != RouteMode.CONTACT_HEAD
                        || launch.state().sprinting());
            return new Candidate(launch, List.copyOf(frames), List.copyOf(states), method,
                    landingZone.isCore(end.boundingBox(), end.feetPosition()), margin,
                    end.velocity().horizontalLength(), 1, inputChurn(frames), launchCost(launch),
                    horizontalContacts, obstacleClearance,
                    momentumReady, sourceStage,
                    contactProfile.mode(), contactProfile.events(),
                    contactProfile.unplannedContacts(), requestedMode != RouteMode.LADDER_ASSIST
                        && !avoidance && contactProfile.events().isEmpty(), Tube.STANDARD);
        }
        recordRejection("Unstable landing: target contact could not remain supported through settling.");
        return null;
    }

    private boolean hasCommandLead(LaunchState launch, List<ControlFrame> frames) {
        int jumpIndex = launch.groundPrefix().size();
        if (jumpIndex >= frames.size()) return false;
        return hasCommandLead(launch, frames.get(jumpIndex));
    }

    private boolean hasCommandLead(LaunchState launch, ControlFrame jump) {
        if (!launch.state().onGround() || support(launch.state()).kind() != SupportKind.TAKEOFF
                || !bodyClear(launch.state().feetPosition())) return false;
        PhysicsStep step;
        try { step = physics.tick(request.world(), launch.state(), new ControlInput(
                jump.forward(), jump.strafe(), jump.sprint(), true, false, jump.desiredYaw())); }
        catch (RuntimeException exception) { return false; }
        ParkourState observed = step.state();
        // END_CLIENT_TICK writes jump before Minecraft computes this transition. Requiring an
        // additional no-jump state here delayed commitment by one whole mechanics tick and
        // discarded the last safe supported launch. The committed transition itself is the
        // authoritative latency check.
        boolean takeoffAcknowledged = !observed.onGround()
                || observed.velocity().y > 0.05 || step.collisions().hasHeadContact();
        return takeoffAcknowledged && !step.collisions().hasSideContact();
    }

    private StoppingOutcome simulateStopping(ParkourState touchdown, LaunchLane lane, StoppingMethod method) {
        ParkourState state = touchdown;
        List<ControlFrame> frames = new ArrayList<>();
        List<ParkourState> states = new ArrayList<>();
        int slowTicks = 0;
        int settleTicks = 0;
        for (int tick = 0; tick < MAX_STOPPING_TICKS; tick++) {
            if (!support(state).targetSupported()) return null;
            double speed = state.velocity().horizontalLength();
            if (speed <= LandingStabilityTracker.MAX_FINAL_SPEED) {
                slowTicks++;
                if (slowTicks > LandingStabilityTracker.REQUIRED_GROUNDED_TICKS) settleTicks++;
            } else { slowTicks = 0; settleTicks = 0; }
            if (slowTicks >= LandingStabilityTracker.REQUIRED_GROUNDED_TICKS
                    && settleTicks >= LandingStabilityTracker.REQUIRED_SETTLE_TICKS) {
                return new StoppingOutcome(List.copyOf(frames), List.copyOf(states));
            }
            ControlFrame action = stoppingFrame(state, lane, method, speed);
            state = advance(state, action);
            if (state == null || !state.onGround()) return null;
            frames.add(action);
            states.add(state);
        }
        return null;
    }

    private ControlFrame stoppingFrame(ParkourState state, LaunchLane lane,
                                       StoppingMethod method, double speed) {
        if (speed <= LandingStabilityTracker.MAX_FINAL_SPEED || method == StoppingMethod.NATURAL) {
            return frame(0, 0, false, false, lane.yaw(), ControlPhase.SETTLING, FrameGuard.TARGET_GROUNDED);
        }
        if (method == StoppingMethod.SNEAK) return new ControlFrame(0, 0, false, false,
                true, lane.yaw(), ControlPhase.LANDED_BRAKING, FrameGuard.TARGET_GROUNDED);
        Vec3d opposite = new Vec3d(state.velocity().x, 0, state.velocity().z).normalize().multiply(-1);
        Vec3d side = ControlInput.strafeDirection(lane.heading());
        return frame((float) MathHelper.clamp(opposite.dotProduct(lane.heading()), -1, 1),
                (float) MathHelper.clamp(opposite.dotProduct(side), -1, 1), false, false,
                lane.yaw(), ControlPhase.LANDED_BRAKING, FrameGuard.TARGET_GROUNDED);
    }

    private List<LaunchLane> buildLaunchLanes() {
        List<LaunchLane> result = new ArrayList<>();
        int id = 0;
        List<StandableSurface> orderedTakeoffs = request.problem().reachableTakeoffs().stream()
                .sorted(Comparator.comparingDouble(surface -> horizontalDistance(
                        request.player().feetPosition(), surface.centerFeet())))
                .toList();
        outer:
        for (StandableSurface surface : orderedTakeoffs) {
            if (surface.footprint().getLengthX() < request.player().boundingBox().getLengthX()
                    || surface.footprint().getLengthZ() < request.player().boundingBox().getLengthZ()
                    || !bodyClear(surface.centerFeet())) {
                // Thin/obstructed tops have legal partial-support feet regions outside the
                // shape center (for example a rung beside its backing wall).
                for (Box region : com.ariesninja.skulkpk.client.core.analysis.SupportGeometry
                        .standingRegions(request.world(), surface, request.player().boundingBox())) {
                    List<Vec3d> positions = new ArrayList<>();
                    positions.add(new Vec3d((region.minX + region.maxX) / 2, surface.topY(),
                            (region.minZ + region.maxZ) / 2));
                    positions.add(com.ariesninja.skulkpk.client.core.analysis.SupportGeometry
                            .nearest(region, request.player().feetPosition()));
                    Vec3d center = positions.getFirst();
                    double insetX = Math.min(0.04, region.getLengthX() / 4);
                    double insetZ = Math.min(0.04, region.getLengthZ() / 4);
                    positions.add(new Vec3d(center.x, surface.topY(), region.minZ + insetZ));
                    positions.add(new Vec3d(center.x, surface.topY(), region.maxZ - insetZ));
                    positions.add(new Vec3d(region.minX + insetX, surface.topY(), center.z));
                    positions.add(new Vec3d(region.maxX - insetX, surface.topY(), center.z));
                    for (LandingZone.LandingAnchor anchor : landingAnchorsFor(surface)) {
                        for (Vec3d takeoff : positions) {
                            if (!supportedSkirt(takeoff)) continue;
                            Vec3d heading = horizontalDirection(takeoff, anchor.feet());
                            double runUp = 0;
                            for (double d = 0.05; d <= 10; d += 0.05) {
                                if (!supportedSkirt(takeoff.subtract(heading.multiply(d)))) break;
                                runUp = d;
                            }
                            Vec3d side = ControlInput.strafeDirection(heading);
                            LaunchLane lane = new LaunchLane(id++, surface,
                                    takeoff.subtract(side.multiply(0.05)), takeoff.add(side.multiply(0.05)),
                                    takeoff, playerBox(takeoff).union(playerBox(takeoff.subtract(heading.multiply(runUp)))),
                                    heading, yaw(heading), -request.player().boundingBox().getLengthX(),
                                    Math.max(0.42, runUp), runUp, anchor);
                            result.add(lane);
                            perimeterLanes.add(lane.id());
                            if (result.size() >= MAX_LANE_CANDIDATES) break outer;
                        }
                    }
                }
                continue;
            }
            for (LandingZone.LandingAnchor anchor : landingAnchorsFor(surface)) {
                Vec3d roughDirection = horizontalDirection(surface.centerFeet(), anchor.feet());
                Vec3d roughEdge = edgePoint(surface, roughDirection, 0);
                if (!isExposedEdge(surface, roughEdge, roughDirection)) continue;
                for (Vec3d sample : edgeSamples(surface, roughDirection)) {
                    Vec3d heading = horizontalDirection(sample, anchor.feet());
                    Vec3d takeoff = edgePoint(surface, heading, lateralCoordinate(surface, sample, heading));
                    if (!bodyClear(takeoff)) continue;
                    double runUp = traceRunUp(takeoff, heading, surface.topY());
                    Box corridor = playerBox(takeoff).union(playerBox(takeoff.subtract(heading.multiply(runUp))));
                    Vec3d side = ControlInput.strafeDirection(heading);
                    boolean maximumReach = landingZone.surfaces().stream()
                            .mapToDouble(landing -> edgeDistance(surface.footprint(), landing.footprint()))
                            .min().orElse(Double.MAX_VALUE) >= 3.75;
                    int laneId = id++;
                    LaunchLane provisional = new LaunchLane(laneId, surface,
                            takeoff.subtract(side.multiply(0.20)),
                            takeoff.add(side.multiply(0.20)), takeoff, corridor, heading, yaw(heading),
                            maximumReach ? MAXIMUM_REACH_TRIGGER_MINIMUM
                                    : -request.player().boundingBox().getLengthX(),
                            0.42, runUp, anchor);
                    // A route obstruction widens the launch manifold over the supported runway;
                    // exact ground physics decides where lateral momentum and jump commitment
                    // should begin. No named obstacle pattern or fixed setback is introduced.
                    double triggerMaximum = routeObstacleGeometry(provisional).isEmpty()
                            ? 0.42 : Math.max(0.42, runUp);
                    result.add(new LaunchLane(laneId, surface, provisional.edgeStart(),
                            provisional.edgeEnd(), takeoff, corridor, heading, yaw(heading),
                            provisional.triggerMinimum(), triggerMaximum, runUp, anchor));
                    if (result.size() >= MAX_LANE_CANDIDATES) break outer;
                }
            }
        }
        List<LaunchLane> sorted = sortLanes(result);
        if (perimeterLanes.isEmpty()) return sorted.stream().limit(MAX_LANES).toList();
        // Several target anchors can produce the same stance. Nearest-first truncation used
        // every slot on the player's end of a narrow rung, deleting its opposite end entirely.
        // Reserve a lane per distinct supported stance before adding alternate target headings.
        Map<String, List<LaunchLane>> stances = new LinkedHashMap<>();
        for (LaunchLane lane : sorted) {
            Vec3d feet = lane.takeoffPoint();
            String key = q(feet.x, 0.01) + ":" + q(feet.y, 0.01) + ":" + q(feet.z, 0.01);
            stances.computeIfAbsent(key, ignored -> new ArrayList<>()).add(lane);
        }
        List<LaunchLane> diverse = new ArrayList<>();
        for (int rank = 0; diverse.size() < MAX_LANES; rank++) {
            boolean added = false;
            for (List<LaunchLane> family : stances.values()) {
                if (rank >= family.size()) continue;
                diverse.add(family.get(rank));
                added = true;
                if (diverse.size() == MAX_LANES) break;
            }
            if (!added) break;
        }
        return List.copyOf(diverse);
    }

    /**
     * A maximum-distance diagonal is often possible only from the mutually nearest edges.
     * Keep those geometric anchors alongside interior/core anchors instead of globally
     * discarding every fringe anchor whenever the target happens to have a core.
     */
    private List<LandingZone.LandingAnchor> landingAnchorsFor(StandableSurface takeoff) {
        LinkedHashSet<LandingZone.LandingAnchor> selected = new LinkedHashSet<>();
        Comparator<LandingZone.LandingAnchor> nearest = Comparator
                .comparingDouble((LandingZone.LandingAnchor anchor) ->
                        distanceToFootprint(anchor.feet(), takeoff.footprint()))
                .thenComparing(Comparator.comparingDouble(
                        LandingZone.LandingAnchor::supportMargin).reversed());
        // Put one safest interior family first, then the mutually nearest legal fringes.
        // Direct evaluation consumes launch roots in lane-id order, so this deliberately gives
        // shifted maximum jumps early budget without regressing centered ordinary routes.
        landingZone.preferredAnchors().stream().limit(1).forEach(selected::add);
        for (LadderColumn column : ladders) {
            selected.add(new LandingZone.LandingAnchor(column.entry(takeoff.topY()), 0, false));
        }
        landingZone.fringeAnchors().stream().sorted(nearest).limit(2).forEach(selected::add);
        landingZone.preferredAnchors().stream().skip(1).limit(7).forEach(selected::add);
        if (selected.isEmpty()) landingZone.preferredAnchors().stream().limit(8).forEach(selected::add);
        return List.copyOf(selected);
    }

    private StandableSurface supportingApproachSurface(Vec3d feet, double topY) {
        return request.problem().approachRegion().stream()
                .filter(surface -> Math.abs(surface.topY() - topY) <= 0.02)
                .filter(surface -> pointOnSurface(feet, surface, 0.01))
                .findFirst().orElse(null);
    }

    private List<LaunchLane> sortLanes(List<LaunchLane> value) {
        Vec3d player = request.player().feetPosition();
        return value.stream().sorted(Comparator
                .comparingDouble((LaunchLane lane) -> horizontalDistance(player, lane.takeoffPoint()))
                .thenComparing((LaunchLane lane) -> !lane.landingAnchor().core())
                .thenComparingDouble(lane -> -lane.landingAnchor().supportMargin())
                .thenComparingInt(LaunchLane::id)).toList();
    }


    /** Builds left/right configuration-space tangent homotopies around every route obstacle. */
    private Map<Integer, List<ObstacleGuide>> buildObstacleGuides() {
        Map<Integer, List<ObstacleGuide>> result = new LinkedHashMap<>();
        int guideId = 0;
        for (LaunchLane lane : lanes) {
            List<ObstacleGuide> guides = new ArrayList<>();
            guides.add(new ObstacleGuide(guideId++, 0, List.of()));
            List<RouteObstacleGeometry> obstacles = routeObstacleGeometry(lane);
            if (!obstacles.isEmpty()) {
                double tangentClearance = !ladders.isEmpty() ? 0.025
                        : exploratoryObstacleSearch ? 0.04 : TANGENT_CLEARANCE;
                for (int sideSign : new int[]{-1, 1}) {
                    Vec3d side = ControlInput.strafeDirection(lane.heading());
                    List<Vec3d> tangents = new ArrayList<>();
                    List<TangentSpan> silhouette = new ArrayList<>();
                    for (RouteObstacleGeometry obstacle : obstacles) {
                        double lateral = sideSign < 0
                                ? obstacle.minimumLateral - tangentClearance
                                : obstacle.maximumLateral + tangentClearance;
                        double entry = Math.max(0.01,
                                obstacle.minimumLongitudinal - tangentClearance);
                        double exit = Math.min(obstacle.routeLength - 0.01,
                                obstacle.maximumLongitudinal + tangentClearance);
                        if (exit <= entry) continue;
                        // Project the union silhouette, not one turn per block/height layer.
                        // Adjacent shapes overlap in configuration space: visiting each exit
                        // then the next entry sent the guide backwards inside thick obstacles.
                        if (!silhouette.isEmpty() && entry <= silhouette.getLast().exit + 1.0E-6) {
                            TangentSpan previous = silhouette.removeLast();
                            silhouette.add(new TangentSpan(previous.entry, Math.max(previous.exit, exit),
                                    sideSign < 0 ? Math.min(previous.lateral, lateral)
                                            : Math.max(previous.lateral, lateral)));
                        } else silhouette.add(new TangentSpan(entry, exit, lateral));
                    }
                    for (TangentSpan span : silhouette) {
                        tangents.add(lane.takeoffPoint().add(lane.heading().multiply(span.entry))
                                .add(side.multiply(span.lateral)));
                        tangents.add(lane.takeoffPoint().add(lane.heading().multiply(span.exit))
                                .add(side.multiply(span.lateral)));
                    }
                    if (!tangents.isEmpty()) {
                        // The entry tangent preserves ballistic routes that clear the far corner
                        // through inertia; the complete chain supports multiple obstructions.
                        guides.add(new ObstacleGuide(guideId++, sideSign,
                                List.of(tangents.getFirst())));
                        guides.add(new ObstacleGuide(guideId++, sideSign, tangents));
                    }
                }
            }
            if (!ladders.isEmpty()) {
                List<ObstacleGuide> ballistic = List.copyOf(guides);
                for (LadderColumn column : ladders) {
                    Vec3d entry = column.entry(lane.takeoffPoint().y);
                    guides.add(new ObstacleGuide(guideId++, 0, List.of(), column));
                    for (ObstacleGuide guide : ballistic) {
                        if (guide.side() == 0) continue;
                        List<Vec3d> path = new ArrayList<>(guide.waypoints());
                        // Continue around the backing pillar to the ladder's actual face.
                        // These are collision-volume tangents, independent of block patterns.
                        Vec3d side = ControlInput.strafeDirection(lane.heading());
                        Vec3d last = path.getLast();
                        double along = entry.subtract(last).dotProduct(lane.heading());
                        if (along > 0) path.add(last.add(lane.heading().multiply(along)));
                        guides.add(new ObstacleGuide(guideId++, guide.side(), path, column));
                    }
                }
            }
            result.put(lane.id(), List.copyOf(guides));
        }
        return Map.copyOf(result);
    }

    private List<RouteObstacleGeometry> routeObstacleGeometry(LaunchLane lane) {
        double routeLength = horizontalDistance(lane.takeoffPoint(), lane.landingAnchor().feet());
        Vec3d side = ControlInput.strafeDirection(lane.heading());
        List<RouteObstacleGeometry> result = new ArrayList<>();
        // Broad-phase over the entire airborne height range, not only the straight feet
        // segment. A ceiling can be irrelevant while walking yet decisive during a jump.
        double rise = 0, velocity = request.player().jumpStrength()
                * request.world().jumpMultiplier(lane.takeoffSurface().block());
        PlayerSnapshot.EffectSnapshot boost = request.player().activeEffects().get("minecraft:jump_boost");
        if (boost != null) velocity += (boost.amplifier() + 1) * 0.1;
        for (int tick = 0; tick < 120 && velocity > 0; tick++) {
            rise += velocity;
            velocity = (velocity - request.player().gravity()) * 0.9800000190734863;
        }
        for (ConfigurationObstacle obstacle : configurationSpace.intersecting(
                lane.takeoffPoint().add(0, 0.01, 0), lane.landingAnchor().feet().add(0, rise, 0))) {
            if (isPlannedSupportShape(obstacle.collisionShape())) continue;
            Box expanded = obstacle.forbiddenFeet();
            double minLong = Double.POSITIVE_INFINITY, maxLong = Double.NEGATIVE_INFINITY;
            double minSide = Double.POSITIVE_INFINITY, maxSide = Double.NEGATIVE_INFINITY;
            for (Vec3d corner : horizontalCorners(expanded, lane.takeoffPoint().y)) {
                Vec3d relative = corner.subtract(lane.takeoffPoint());
                double longitudinal = relative.dotProduct(lane.heading());
                double lateral = relative.dotProduct(side);
                minLong = Math.min(minLong, longitudinal);
                maxLong = Math.max(maxLong, longitudinal);
                minSide = Math.min(minSide, lateral);
                maxSide = Math.max(maxSide, lateral);
            }
            if (maxLong <= 0.05 || minLong >= routeLength - 0.05
                    || minSide > 0 || maxSide < 0) continue;
            result.add(new RouteObstacleGeometry(obstacle, minLong, maxLong,
                    minSide, maxSide, routeLength));
        }
        return result.stream().sorted(Comparator.comparingDouble(
                RouteObstacleGeometry::minimumLongitudinal)).toList();
    }

    private boolean isPlannedSupportShape(Box shape) {
        return java.util.stream.Stream.concat(request.problem().approachRegion().stream(),
                        request.problem().landingRegion().stream())
                .anyMatch(surface -> Math.abs(shape.maxY - surface.topY()) <= 1.0E-6
                        && Math.abs(shape.minX - surface.footprint().minX) <= 1.0E-6
                        && Math.abs(shape.maxX - surface.footprint().maxX) <= 1.0E-6
                        && Math.abs(shape.minZ - surface.footprint().minZ) <= 1.0E-6
                        && Math.abs(shape.maxZ - surface.footprint().maxZ) <= 1.0E-6);
    }

    private List<Vec3d> horizontalCorners(Box box, double y) {
        return List.of(new Vec3d(box.minX, y, box.minZ), new Vec3d(box.minX, y, box.maxZ),
                new Vec3d(box.maxX, y, box.minZ), new Vec3d(box.maxX, y, box.maxZ));
    }

    private List<LaunchState> buildLaunchStates() {
        List<LaunchState> result = new ArrayList<>();
        PlayerSnapshot player = request.player();
        for (LaunchLane lane : lanes) {
            double remaining = lane.distanceBeforeEdge(player.feetPosition());
            boolean supported = SupportResolver.overlapArea(player.boundingBox(), player.feetPosition().y,
                    request.problem().approachRegion()) > 1.0E-4;
            if (supported && Math.abs(lane.lateralError(player.feetPosition())) <= 0.55
                    && remaining >= lane.triggerMinimum() && remaining <= lane.availableRunUp() + 0.75) {
                ParkourState current = pose(ParkourState.capture(player), player.feetPosition(),
                        player.velocity(), lane.yaw(), player.onGround(), player.sprinting(), false);
                collectLaunchStates(result, current, lane, current.feetPosition(), true,
                        player.sprinting(), Math.max(0, remaining));
                collectLaunchStates(result, current, lane, current.feetPosition(), true,
                        !player.sprinting(), Math.max(0, remaining));
            }
        }
        for (LaunchLane lane : lanes) {
            int laneStart = result.size();
            for (double runUp = 0; runUp <= lane.availableRunUp() + 1.0E-6; runUp += RUN_UP_INCREMENT) {
                // A zero-prefix launch does not need to balance at the safe edge. Stage it at
                // the fully supported interior of a normal block, then jump from rest. This is
                // still a zero-run-up route, but it is safe for closed-loop positioning.
                double stagingOffset = runUp == 0
                        ? Math.min(0.20, lane.availableRunUp()) : runUp;
                Vec3d staging = lane.takeoffPoint().subtract(lane.heading().multiply(stagingOffset));
                if (!(perimeterLanes.contains(lane.id()) ? supportedSkirt(staging)
                        : fullSupport(staging, lane.takeoffSurface().topY())) || !bodyClear(staging)) continue;
                ParkourState start = ParkourState.at(player, staging, Vec3d.ZERO, lane.yaw(), true, false);
                collectLaunchStates(result, start, lane, staging, false, false, runUp);
                collectLaunchStates(result, start, lane, staging, false, true, runUp);
                if (result.size() - laneStart >= MAX_LAUNCH_STATES * 2) break;
            }
        }
        return deduplicateAndPrioritize(result);
    }

    private List<LaunchState> deduplicateAndPrioritize(List<LaunchState> states) {
        Map<String, LaunchState> unique = new LinkedHashMap<>();
        states.stream().sorted(Comparator.comparing(LaunchState::startsFromCurrentState).reversed()
                .thenComparingDouble(this::launchCost).thenComparingInt(state -> state.lane().id())
                .thenComparingInt(LaunchState::jumpTick))
                .forEach(state -> unique.putIfAbsent(launchKey(state), state));
        return prioritizeLaunchStates(new ArrayList<>(unique.values()));
    }

    private void collectKinodynamicLaunchStates(List<LaunchState> output, LaunchLane lane) {
        double maximum = Math.min(lane.availableRunUp(), 2.5);
        List<Double> runUps = new ArrayList<>();
        for (double runUp = RUN_UP_INCREMENT; runUp <= maximum + 1.0E-6;
             runUp += RUN_UP_INCREMENT) runUps.add(runUp);
        // The physical end of a runway is often not a multiple of 0.25. A lateral route can need the
        // final few centimetres to establish enough lateral velocity, so always evaluate the
        // exact supported length as its own cost stratum.
        if (maximum >= RUN_UP_INCREMENT && runUps.stream()
                .noneMatch(value -> Math.abs(value - maximum) < 1.0E-6)) runUps.add(maximum);

        Map<String, LaunchState> manifold = new LinkedHashMap<>();
        for (double runUp : runUps) {
            if (System.nanoTime() >= obstacleDeadline - 150_000_000L) break;
            Vec3d lateralAxis = ControlInput.strafeDirection(lane.heading());
            // Edge sampling already creates separate lateral lanes. Ground physics expands
            // each canonical lane into lateral position/velocity cells without multiplying
            // the expensive launch search by redundant staging roots.
            for (double stagingLateral : new double[]{0}) {
                Vec3d staging = lane.takeoffPoint().subtract(lane.heading().multiply(runUp))
                        .add(lateralAxis.multiply(stagingLateral));
                if (!(perimeterLanes.contains(lane.id()) ? supportedSkirt(staging)
                        : fullSupport(staging, lane.takeoffSurface().topY())) || !bodyClear(staging)) continue;
                ParkourState initial = ParkourState.at(request.player(), staging, Vec3d.ZERO,
                        lane.yaw(), true, false);
                List<GroundNode> frontier = List.of(new GroundNode(initial, List.of(), 0));
                Map<String, Integer> dominance = new HashMap<>();
                for (int depth = 0; depth < Math.min(MAX_GROUND_TICKS, 12)
                        && !frontier.isEmpty(); depth++) {
                    List<GroundNode> nextFrontier = new ArrayList<>();
                    for (GroundNode node : frontier) {
                    ParkourState state = node.state();
                    if (lane.inTriggerInterval(state.feetPosition()) && bodyClear(state.feetPosition())
                            && !node.prefix().isEmpty()) {
                        double lateralVelocity = state.velocity().dotProduct(
                                ControlInput.strafeDirection(lane.heading()));
                        double lateralPosition = lane.lateralError(state.feetPosition());
                        if (Math.abs(lateralPosition) >= 0.32) {
                            // Side is geometry, not velocity sign. A launch already outside
                            // the right corner can be braking left; it must retain the right
                            // homotopy instead of being told to cross through the pillar.
                            RouteMode mode = lateralPosition < 0 ? RouteMode.AVOID_LEFT : RouteMode.AVOID_RIGHT;
                            LaunchState launch = new LaunchState(initial, state, lane, staging,
                                    horizontalDistance(staging, state.feetPosition()), depth,
                                    node.prefix(), false, mode);
                            // Preserve distinct lateral/velocity cells within every runway cost
                            // stratum.  Replacing within a cell is deterministic and prevents the
                            // first barely-lateral states from exhausting the launch budget.
                            String cell = mode + ":" + q(runUp, RUN_UP_INCREMENT) + ":"
                                    + q(Math.abs(lateralPosition), 0.08) + ":"
                                    + q(Math.abs(lateralVelocity), 0.025) + ":"
                                    + q(forwardSpeed(launch), 0.04);
                            LaunchState existing = manifold.get(cell);
                            if (existing == null || launchManifoldOrder(launch, existing) < 0)
                                manifold.put(cell, launch);
                        }
                    }
                    if (lane.distanceBeforeEdge(state.feetPosition()) < lane.triggerMinimum()) continue;
                    for (ControlInput input : groundActions(state, lane)) {
                        PhysicsStep step;
                        try { step = physics.tick(request.world(), state, input); }
                        catch (ParkourPhysics.UnsupportedPhysicsStateException exception) { continue; }
                        ParkourState next = step.state();
                        SupportResolver.Contact support = support(next);
                        if (!next.onGround() || support.kind() != SupportKind.TAKEOFF) continue;
                        if (step.collisions().hasSideContact() || !bodyClear(next.feetPosition())) continue;
                        List<ControlInput> prefix = new ArrayList<>(node.prefix());
                        prefix.add(input);
                        String key = groundKey(next, lane, prefix);
                        int churn = node.churn() + (node.prefix().isEmpty()
                                || sameGroundInput(node.prefix().getLast(), input) ? 0 : 1);
                        Integer existing = dominance.get(key);
                        if (existing != null && existing <= churn) continue;
                        dominance.put(key, churn);
                        nextFrontier.add(new GroundNode(next, List.copyOf(prefix), churn));
                    }
                    }
                    frontier = retainGroundDiverse(nextFrontier, lane);
                }
            }
        }
        // Keep every run-up stratum represented, then favor states which have already created
        // the lateral offset and velocity needed to cross a configuration-space tangent.  The
        // route validator—not this heuristic—still decides whether that family is executable.
        Map<Long, List<LaunchState>> byCost = new LinkedHashMap<>();
        manifold.values().forEach(state -> byCost.computeIfAbsent(
                q(state.runUpLength(), RUN_UP_INCREMENT), ignored -> new ArrayList<>()).add(state));
        for (List<LaunchState> stratum : byCost.values()) {
            Map<String, List<LaunchState>> lateralFamilies = new LinkedHashMap<>();
            for (LaunchState state : stratum) {
                String key = state.approachMode() + ":" + q(Math.abs(
                        state.lane().lateralError(state.state().feetPosition())), 0.10);
                lateralFamilies.computeIfAbsent(key, ignored -> new ArrayList<>()).add(state);
            }
            lateralFamilies.values().forEach(family -> family.sort(this::launchManifoldOrder));
            int stratumLimit = MAX_MANIFOLD_STATES_PER_STRATUM;
            for (int rank = 0, retained = 0; retained < stratumLimit; rank++) {
                boolean added = false;
                for (List<LaunchState> family : lateralFamilies.values()) {
                    if (rank >= family.size()) continue;
                    output.add(family.get(rank));
                    retained++;
                    added = true;
                    if (retained >= stratumLimit) break;
                }
                if (!added) break;
            }
        }
    }

    private int launchManifoldOrder(LaunchState first, LaunchState second) {
        double firstLateral = Math.abs(first.lane().lateralError(first.state().feetPosition()));
        double secondLateral = Math.abs(second.lane().lateralError(second.state().feetPosition()));
        double firstSpeed = Math.abs(first.state().velocity().dotProduct(
                ControlInput.strafeDirection(first.lane().heading())));
        double secondSpeed = Math.abs(second.state().velocity().dotProduct(
                ControlInput.strafeDirection(second.lane().heading())));
        double firstForward = Math.max(0, forwardSpeed(first));
        double secondForward = Math.max(0, forwardSpeed(second));
        int balanced = -Double.compare(Math.min(firstSpeed, firstForward),
                Math.min(secondSpeed, secondForward));
        if (balanced != 0) return balanced;
        int combined = -Double.compare(firstSpeed + firstForward, secondSpeed + secondForward);
        if (combined != 0) return combined;
        int forward = -Double.compare(firstForward, secondForward);
        if (forward != 0) return forward;
        int offset = -Double.compare(firstLateral, secondLateral);
        return offset != 0 ? offset : Integer.compare(first.jumpTick(), second.jumpTick());
    }

    private List<GroundNode> retainGroundDiverse(List<GroundNode> nodes, LaunchLane lane) {
        Comparator<GroundNode> order = Comparator.comparingDouble((GroundNode node) ->
                        groundScore(node.state(), lane)).thenComparingInt(GroundNode::churn);
        nodes.sort(order);
        Map<String, GroundNode> buckets = new LinkedHashMap<>();
        for (GroundNode node : nodes) {
            double lateral = lane.lateralError(node.state().feetPosition());
            double forward = node.state().velocity().dotProduct(lane.heading());
            double lateralSpeed = node.state().velocity().dotProduct(
                    ControlInput.strafeDirection(lane.heading()));
            String key = (int) Math.signum(lateral) + ":" + q(Math.abs(lateral), 0.08) + ":"
                    + q(lane.distanceBeforeEdge(node.state().feetPosition()), 0.15) + ":"
                    + q(forward, 0.04) + ":" + q(lateralSpeed, 0.025) + ":"
                    + node.state().sprinting() + ":" + groundHistory(node.prefix());
            buckets.putIfAbsent(key, node);
        }
        LinkedHashSet<GroundNode> retained = new LinkedHashSet<>();
        int frontierLimit = MAX_GROUND_FRONTIER;
        buckets.values().stream().sorted(order).limit(frontierLimit).forEach(retained::add);
        for (GroundNode node : nodes) {
            if (retained.size() >= frontierLimit) break;
            retained.add(node);
        }
        return List.copyOf(retained);
    }

    private List<ControlInput> groundActions(ParkourState state, LaunchLane lane) {
        List<ControlInput> result = new ArrayList<>();
        result.add(new ControlInput(1, 0, true, false, false, lane.yaw()));
        result.add(new ControlInput(1, 0, false, false, false, lane.yaw()));
        for (int side : new int[]{-1, 1}) {
            result.add(new ControlInput(1, side, true, false, false,
                    lane.yaw()));
        }
        result.add(new ControlInput(0, 0, false, false, false, lane.yaw()));
        result.add(new ControlInput(-1, 0, false, false, false, lane.yaw()));
        if (exploratoryObstacleSearch) {
            // Binary strafe over-corrects a narrow supported edge. Small actual ground-yaw
            // changes can cancel lateral drift while preserving forward momentum, allowing
            // a later supported jump without a hand-authored turn/setback profile.
            for (float offset : new float[]{-12, -6, 6, 12})
                result.add(new ControlInput(1, 0, true, false, false,
                        boundedYaw(state.yaw(), lane.yaw() + offset)));
        }
        return List.copyOf(result);
    }

    private String groundKey(ParkourState state, LaunchLane lane, List<ControlInput> prefix) {
        return q(lane.distanceBeforeEdge(state.feetPosition()), 0.04) + ":"
                + q(lane.lateralError(state.feetPosition()), 0.04) + ":"
                + q(state.velocity().dotProduct(lane.heading()), 0.015) + ":"
                + q(state.velocity().dotProduct(ControlInput.strafeDirection(lane.heading())), 0.015)
                + ":" + q(MathHelper.wrapDegrees(state.yaw() - lane.yaw()), 3)
                + ":" + state.sprinting() + ":" + state.sprintTapTicks() + ":" + state.previousForward()
                + ":" + groundHistory(prefix);
    }

    private String groundHistory(List<ControlInput> prefix) {
        int firstLateral = -1;
        int side = 0;
        for (int index = 0; index < prefix.size(); index++) {
            if (Math.abs(prefix.get(index).strafe()) < 0.1) continue;
            firstLateral = index;
            side = (int) Math.signum(prefix.get(index).strafe());
            break;
        }
        ControlInput last = prefix.isEmpty() ? null : prefix.getLast();
        int current = last == null ? 0 : (int) Math.signum(last.strafe());
        return side + ":" + firstLateral + ":" + current;
    }

    private double groundScore(ParkourState state, LaunchLane lane) {
        double beforeWindow = Math.max(0,
                lane.distanceBeforeEdge(state.feetPosition()) - lane.triggerMaximum());
        double lateralSpeed = Math.abs(state.velocity().dotProduct(
                ControlInput.strafeDirection(lane.heading())));
        return beforeWindow * 5 - Math.abs(lane.lateralError(state.feetPosition())) * 2.0
                - lateralSpeed * 3.0
                - Math.max(0, state.velocity().dotProduct(lane.heading())) * 4.0;
    }

    private boolean sameGroundInput(ControlInput first, ControlInput second) {
        return first.forward() == second.forward() && first.strafe() == second.strafe()
                && first.sprint() == second.sprint()
                && Math.abs(MathHelper.wrapDegrees(first.yaw() - second.yaw())) < 0.1;
    }

    private List<LaunchState> prioritizeLaunchStates(List<LaunchState> states) {
        Map<String, List<LaunchState>> byLane = new LinkedHashMap<>();
        for (LaunchState state : states) {
            String family = state.lane().id() + ":" + state.approachMode();
            if (state.approachMode() == RouteMode.AVOID_LEFT
                    || state.approachMode() == RouteMode.AVOID_RIGHT) {
                family += ":" + q(Math.abs(state.lane().lateralError(
                        state.state().feetPosition())), 0.12) + ":"
                        + q(state.lane().distanceBeforeEdge(state.state().feetPosition()), 0.20);
            }
            byLane.computeIfAbsent(family, ignored -> new ArrayList<>()).add(state);
        }
        for (List<LaunchState> laneStates : byLane.values()) laneStates.sort((first, second) -> {
            if (requiresPreparedMomentum(first.lane())) {
                if (requiresMaximumReach(first.lane())) {
                    int departure = -Boolean.compare(isEdgeDeparture(first), isEdgeDeparture(second));
                    if (departure != 0) return departure;
                }
                int sprint = -Boolean.compare(first.state().sprinting(), second.state().sprinting());
                if (sprint != 0) return sprint;
                int speed = -Double.compare(forwardSpeed(first), forwardSpeed(second));
                if (speed != 0) return speed;
            }
            int current = -Boolean.compare(first.startsFromCurrentState(), second.startsFromCurrentState());
            if (current != 0) return current;
            int momentum = Double.compare(momentumRank(first), momentumRank(second));
            return momentum != 0 ? momentum : Integer.compare(first.jumpTick(), second.jumpTick());
        });

        // Each lane gets both short/current and momentum-bearing late launches. A short
        // horizontal gap under a ceiling can need more speed than an unobstructed long gap;
        // geometry distance is not a sufficient filter for retaining sprint prefixes.
        for (List<LaunchState> family : byLane.values()) {
            LinkedHashSet<LaunchState> representatives = new LinkedHashSet<>();
            if (!family.isEmpty()) representatives.add(family.getFirst());
            family.stream().filter(state -> state.state().sprinting())
                    .max(Comparator.comparingDouble(this::forwardSpeed)).ifPresent(representatives::add);
            family.stream().filter(state -> state.state().sprinting())
                    .min(Comparator.comparingDouble((LaunchState state) ->
                            state.lane().distanceBeforeEdge(state.state().feetPosition()))
                            .thenComparing(Comparator.comparingDouble(this::forwardSpeed).reversed()))
                    .ifPresent(representatives::add);
            representatives.addAll(family);
            family.clear();
            family.addAll(representatives);
        }
        List<LaunchState> prioritized = new ArrayList<>();
        for (int rank = 0; prioritized.size() < MAX_LAUNCH_STATES; rank++) {
            boolean added = false;
            for (List<LaunchState> laneStates : byLane.values()) {
                if (rank >= laneStates.size()) continue;
                prioritized.add(laneStates.get(rank));
                added = true;
                if (prioritized.size() >= MAX_LAUNCH_STATES) break;
            }
            if (!added) break;
        }
        return List.copyOf(prioritized);
    }

    private boolean isEdgeDeparture(LaunchState launch) {
        return launch.state().onGround() && SupportResolver.overlapArea(
                launch.state().boundingBox(), launch.state().feetPosition().y,
                request.problem().approachRegion()) <= 1.0E-5;
    }

    private boolean requiresPreparedMomentum(LaunchLane lane) {
        double distance = horizontalDistance(lane.takeoffPoint(), lane.landingAnchor().feet());
        double rise = lane.landingAnchor().feet().y - lane.takeoffPoint().y;
        return distance >= 2.45 || rise >= 0.45;
    }

    private double forwardSpeed(LaunchState state) {
        return state.state().velocity().dotProduct(state.lane().heading());
    }

    private void collectLaunchStates(List<LaunchState> output, ParkourState initial, LaunchLane lane,
                                     Vec3d staging, boolean current, boolean sprint, double nominalRunUp) {
        ParkourState state = initial;
        List<ControlInput> prefix = new ArrayList<>();
        for (int tick = 0; tick <= MAX_GROUND_TICKS; tick++) {
            if (lane.inTriggerInterval(state.feetPosition()) && bodyClear(state.feetPosition())
                    && (current || nominalRunUp == 0 || tick > 0)) {
                output.add(new LaunchState(initial, state, lane, staging,
                        Math.min(nominalRunUp, horizontalDistance(staging, state.feetPosition())),
                        tick, List.copyOf(prefix), current));
            }
            if (lane.distanceBeforeEdge(state.feetPosition()) < lane.triggerMinimum()) break;
            // Ground direction and camera heading are distinct: a diagonal key pair can
            // build tangential momentum while pointing the sprint-jump boost around a corner.
            ControlInput input = new ControlInput(1, 0, sprint, false, false, lane.yaw());
            ParkourState next;
            try { next = physics.tickState(request.world(), state, input); }
            catch (ParkourPhysics.UnsupportedPhysicsStateException exception) { return; }
            SupportResolver.Contact contact = SupportResolver.resolve(next.boundingBox(), next.feetPosition(),
                    next.onGround(), request.problem().landingRegion(),
                    request.problem().approachRegion(), request.world());
            if (!next.onGround() || next.horizontalCollision()) break;
            prefix.add(input);
            if (contact.kind() != SupportKind.TAKEOFF) {
                // Minecraft can retain onGround for the horizontal edge-departure tick because
                // vertical collision is resolved from the previously supported box. That final
                // state is essential to maximum-distance jumps. Keep exactly that one state,
                // only inside the bounded trigger interval, and never continue ground motion
                // after support has actually gone.
                if (contact.kind() == SupportKind.NONE && lane.inTriggerInterval(next.feetPosition())
                        && bodyClear(next.feetPosition())) {
                    output.add(new LaunchState(initial, next, lane, staging,
                            Math.min(nominalRunUp, horizontalDistance(staging, next.feetPosition())),
                            tick + 1, List.copyOf(prefix), current));
                }
                break;
            }
            state = next;
        }
    }

    private List<AirSchedule> directSchedules(LaunchState launch) {
        // Sprint-jump impulse is a takeoff mechanic, not a property inferred from route length.
        // Momentum/head-contact routes are therefore evaluated from a state that has already
        // entered sprint; short routes preserve their naturally generated sprint state.
        boolean sprint = launch.state().sprinting();
        List<AirSchedule> schedules = new ArrayList<>();
        schedules.add(new AirSchedule(sprint, 120, false, 0, 0, 0));
        for (int release : new int[]{3, 5, 7, 9, 11, 14})
            schedules.add(new AirSchedule(sprint, release, false, 0, 0, 0));
        for (int brake : new int[]{4, 6, 8, 10, 13})
            schedules.add(new AirSchedule(sprint, brake, true, 0, 0, 0));
        for (float side : new float[]{-1, 1}) {
            schedules.add(new AirSchedule(sprint, 120, false, side, 8, 0));
            schedules.add(new AirSchedule(sprint, 7, false, side, 5, side * 5));
        }
        return List.copyOf(schedules);
    }

    /** Search the legal supported perimeter as well as the full-footprint interior.
     * A low overhang may be avoidable from a parallel side lane without spending the
     * short rising phase acquiring all lateral displacement after takeoff. */
    private void collectSkirtLaunches() {
        int id = 100_000;
        for (LaunchLane base : lanes) {
            if (!base.landingAnchor().core()) continue;
            Vec3d side = ControlInput.strafeDirection(base.heading());
            for (double offset : new double[]{-0.70, 0.70, -0.75, 0.75, -0.55, 0.55}) {
                if (System.nanoTime() >= obstacleDeadline - 100_000_000L) return;
                Vec3d takeoff = base.takeoffPoint().add(side.multiply(offset));
                if (!supportedSkirt(takeoff)) continue;
                double available = 0;
                for (double d = 0; d <= 5; d += 0.1) {
                    if (!supportedSkirt(takeoff.subtract(base.heading().multiply(d)))) break;
                    available = d;
                }
                Vec3d anchor = base.landingAnchor().feet().add(side.multiply(offset));
                if (SupportResolver.overlapArea(playerBox(anchor), anchor.y, request.problem().landingRegion()) <= 1.0E-5) continue;
                LaunchLane lane = new LaunchLane(id++, base.takeoffSurface(), takeoff.subtract(side.multiply(0.02)),
                        takeoff.add(side.multiply(0.02)), takeoff,
                        playerBox(takeoff).union(playerBox(takeoff.subtract(base.heading().multiply(available)))),
                        base.heading(), base.yaw(), base.triggerMinimum(), base.triggerMaximum(), available,
                        new LandingZone.LandingAnchor(anchor, 0, false));
                for (double runUp = 0; runUp <= available + 1.0E-6; runUp += 0.25) {
                    Vec3d staging = takeoff.subtract(lane.heading().multiply(runUp));
                    List<LaunchState> roots = new ArrayList<>();
                    ParkourState state = ParkourState.at(request.player(), staging, Vec3d.ZERO, lane.yaw(), true, false);
                    collectLaunchStates(roots, state, lane, staging, false, true, runUp);
                    for (LaunchState root : roots) {
                        if (System.nanoTime() >= obstacleDeadline - 100_000_000L) return;
                        // Small outward steering followed by a return is parameterized by
                        // trajectory time, not a named obstacle pattern. Physics validates all.
                        for (float outwardAngle : new float[]{6, 9, 12})
                            for (int turn : new int[]{1, 2, 3})
                                for (float inwardAngle : new float[]{12, 24, 36, 48}) {
                                    Candidate candidate = simulateSkirt(root, (float) Math.signum(offset),
                                            outwardAngle, turn, inwardAngle);
                                    directEvaluations++;
                                    if (candidate != null) {
                                        for (Tube tube : new Tube[]{Tube.STANDARD, Tube.PRECISE}) {
                                            Candidate tested = candidate.withTube(tube, false);
                                            if (validateRouteTube(tested) == 8) {
                                                keep(tested.withTube(tube, true));
                                                break;
                                            }
                                        }
                                    }
                                }
                    }
                    if (best != null) return;
                }
            }
        }
    }

    /** Attachment routes may need lateral displacement before takeoff. Sample legal
     * footprint support along the same platform, rather than requiring all steering in air. */
    private List<LaunchLane> withPerimeterLanes(List<LaunchLane> original) {
        List<LaunchLane> result = new ArrayList<>(original);
        int id = 100_000;
        for (LaunchLane base : original.stream().filter(lane -> lane.landingAnchor().core()).limit(4).toList()) {
            Vec3d side = ControlInput.strafeDirection(base.heading());
            for (double offset : new double[]{-0.55, 0.55, -0.70, 0.70}) {
                Vec3d takeoff = base.takeoffPoint().add(side.multiply(offset));
                if (!supportedSkirt(takeoff)) continue;
                double available = 0;
                for (double d = 0; d <= 5; d += 0.1) {
                    if (!supportedSkirt(takeoff.subtract(base.heading().multiply(d)))) break;
                    available = d;
                }
                LaunchLane lane = new LaunchLane(id++, base.takeoffSurface(),
                        takeoff.subtract(side.multiply(0.02)), takeoff.add(side.multiply(0.02)), takeoff,
                        playerBox(takeoff).union(playerBox(takeoff.subtract(base.heading().multiply(available)))),
                        base.heading(), base.yaw(), base.triggerMinimum(), base.triggerMaximum(), available,
                        base.landingAnchor());
                perimeterLanes.add(lane.id());
                result.add(lane);
            }
        }
        return List.copyOf(result);
    }

    private boolean supportedSkirt(Vec3d feet) {
        if (!bodyClear(feet)) return false;
        for (double dx : new double[]{-0.025, 0.025}) for (double dz : new double[]{-0.025, 0.025}) {
            Box box = playerBox(feet.add(dx, 0, dz));
            if (SupportResolver.overlapArea(box, feet.y, request.problem().approachRegion()) <= 1.0E-5) return false;
        }
        return true;
    }

    /** Coarse-to-fine control-knot shooting complements the local beam near a hidden
     * attachment face. Momentum can require initiating a turn several ticks before the
     * nearest tangent; each complete command sequence is still checked by real physics. */
    private void shootAttachmentRoutes() {
        long deadline = Math.min(obstacleDeadline - 100_000_000L, System.nanoTime() + 300_000_000L);
        List<LaunchState> prefixes = new ArrayList<>();
        for (LaunchLane lane : lanes) {
            if (!perimeterLanes.contains(lane.id())) continue;
            for (double runUp = 0.25; runUp <= lane.availableRunUp() + 1.0E-6; runUp += 0.25) {
                Vec3d staging = lane.takeoffPoint().subtract(lane.heading().multiply(runUp));
                ParkourState initial = ParkourState.at(request.player(), staging, Vec3d.ZERO, lane.yaw(), true, false);
                collectLaunchStates(prefixes, initial, lane, staging, false, true, runUp);
            }
        }
        List<LaunchState> seeds = prefixes.stream().filter(launch -> launch.state().sprinting()
                        && launch.state().velocity().horizontalLength() > 0.08 && supportsLaunchTube(launch))
                .sorted(Comparator.comparingDouble(LaunchState::runUpLength)
                        .thenComparingInt(launch -> launch.lane().id())).toList();
        for (LaunchState launch : seeds) for (LadderColumn column : ladders) {
            float side = (float) Math.signum(launch.state().feetPosition()
                    .subtract(launch.lane().takeoffSurface().centerFeet())
                    .dotProduct(ControlInput.strafeDirection(launch.lane().heading())));
            if (side == 0) continue;
            ObstacleGuide guide = new ObstacleGuide(-1, (int) side, List.of(), column);
            for (float outward : new float[]{12, 9, 6, 0}) for (int turn : new int[]{8, 7, 9, 6, 10, 4, 12})
                for (float inward : new float[]{96, 72, 48, 84, 60}) for (float strafe : new float[]{1, 0}) {
                    if (System.nanoTime() >= deadline) return;
                    ParkourState state = launch.state();
                    ObstacleNode node = null;
                    for (int tick = 0; tick < maximumHorizon(); tick++) {
                        float desired = launch.lane().yaw() + (tick < 3 ? -side * outward
                                : tick < turn ? 0 : side * inward);
                        ControlFrame frame = frame(1, tick < turn ? 0 : -side * strafe, true,
                                tick == 0, boundedYaw(state.yaw(), desired),
                                tick == 0 ? ControlPhase.TAKEOFF : ControlPhase.AIRBORNE,
                                tick == 0 ? FrameGuard.GROUNDED : FrameGuard.AIRBORNE);
                        PhysicsStep step = advanceStep(state, frame);
                        if (step == null) break;
                        state = step.state();
                        flightStatesExpanded++;
                        node = new ObstacleNode(state, launch, guide, 0, node, frame,
                                step.collisions(), tick + 1, "attachment_shooting", 0);
                        if (column.contains(state.feetPosition())) {
                            completeLadderRoute(node);
                            if (best != null || fragileObstacleCandidates.size() >= 2) return;
                            break;
                        }
                        if (state.onGround() || state.feetPosition().y < column.attachment().minY) break;
                    }
                }
        }
    }

    private Candidate simulateSkirt(LaunchState launch, float outward, float outwardAngle,
                                    int turn, float inwardAngle) {
        List<ControlFrame> frames = groundFrames(launch);
        List<ParkourState> states = replayGroundStates(launch);
        List<CollisionManifold> contacts = replayGroundContacts(launch);
        ParkourState state = launch.state();
        for (int tick = 0; tick < maximumHorizon(); tick++) {
            float yaw = boundedYaw(state.yaw(), launch.lane().yaw()
                    + (tick < turn ? -outward * outwardAngle : outward * inwardAngle));
            ControlFrame action = frame(1, 0, true, tick == 0, yaw,
                    tick == 0 ? ControlPhase.TAKEOFF : ControlPhase.AIRBORNE,
                    tick == 0 ? FrameGuard.GROUNDED : FrameGuard.AIRBORNE);
            PhysicsStep step = advanceStep(state, action);
            if (step == null) return null;
            state = step.state(); frames.add(action); states.add(state); contacts.add(step.collisions());
            if (state.onGround() && tick > 0) return support(state).targetSupported()
                    ? stopAndValidate(launch, frames, states, contacts, PlanningStage.OBSTACLE,
                        outward < 0 ? RouteMode.AVOID_LEFT : RouteMode.AVOID_RIGHT) : null;
            if (state.feetPosition().y < minimumLandingY() - 1) return null;
        }
        return null;
    }

    private List<ControlFrame> obstacleActions(ObstacleNode node) {
        int steeringIndex = node.guide.ladder == null ? node.guideIndex
                : node.guide.advance(node.state.feetPosition().add(node.state.velocity().multiply(2)), node.guideIndex);
        float targetYaw = node.guide.desiredYaw(node.state.feetPosition(),
                steeringIndex, node.launch.lane());
        boolean sprint = node.state.sprinting();
        LinkedHashSet<ControlFrame> actions = new LinkedHashSet<>();
        float guidedYaw = boundedYaw(node.state.yaw(), targetYaw);
        float inward = node.guide.side() == 0 ? 0 : node.guide.side() * 0.45f;
        actions.add(frame(1, 0, sprint, false, guidedYaw,
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE));
        actions.add(frame(1, inward, sprint, false,
                commandYaw(node.state.yaw(), targetYaw, 1, inward),
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE));
        actions.add(frame(1, -1, sprint, false,
                commandYaw(node.state.yaw(), targetYaw, 1, -1),
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE));
        actions.add(frame(1, 1, sprint, false,
                commandYaw(node.state.yaw(), targetYaw, 1, 1),
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE));
        // These two bounded alternatives preserve useful inertia when immediately steering at
        // the tangent would over-correct. Together with both strafes, neutral, and brake they
        // span the useful dry-land air controls without spending 20+ transitions per node on
        // near-duplicate camera/input pairs.
        actions.add(frame(1, 0, sprint, false, node.state.yaw() - 12,
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE));
        actions.add(frame(1, 0, sprint, false, node.state.yaw() + 12,
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE));
        actions.add(frame(0, 0, false, false, boundedYaw(node.state.yaw(), targetYaw),
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE));
        actions.add(frame(-1, 0, false, false, boundedYaw(node.state.yaw(), targetYaw),
                ControlPhase.AIRBORNE, FrameGuard.AIRBORNE));
        if (node.guide.ladder != null) {
            // Corner catches need to brake along one axis while still accelerating along
            // the other. Forward diagonals and a straight brake do not span those controls.
            for (float forward : new float[]{0, -1}) for (float strafe : new float[]{-1, 1}) {
                actions.add(frame(forward, strafe, false, false,
                        commandYaw(node.state.yaw(), targetYaw, forward, strafe),
                        ControlPhase.AIRBORNE, FrameGuard.AIRBORNE));
            }
        }
        return List.copyOf(actions);
    }

    /**
     * Converts a desired world-space movement yaw into the camera yaw which produces it for
     * the requested Minecraft forward/strafe pair.  Without this inverse transform, adding a
     * diagonal strafe rotates acceleration by 45 degrees away from the tangent being tracked.
     */
    private float commandYaw(float currentYaw, float desiredMovementYaw,
                             float forward, float strafe) {
        if (Math.abs(forward) + Math.abs(strafe) < 1.0E-5) return boundedYaw(currentYaw, desiredMovementYaw);
        float inputOffset = (float) Math.toDegrees(Math.atan2(
                ControlInput.keyAxis(strafe), ControlInput.keyAxis(forward)));
        return boundedYaw(currentYaw, desiredMovementYaw + inputOffset);
    }

    private List<ObstacleNode> retainDiverse(List<ObstacleNode> nodes) {
        if (nodes.isEmpty()) return List.of();
        nodes.sort(OBSTACLE_ORDER);
        Map<String, List<ObstacleNode>> coarseFamilies = new LinkedHashMap<>();
        for (ObstacleNode node : nodes) {
            String key = node.launch.lane().id() + ":" + node.launch.approachMode()
                    + ":" + node.guide.side() + ":" + node.guide.waypoints().size();
            coarseFamilies.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
        }
        LinkedHashSet<ObstacleNode> retained = new LinkedHashSet<>();
        // Within each topology, reserve different touchdown/velocity/yaw outcomes before
        // spending slots on near-duplicates. Previously the first loop filled all 256 slots,
        // making the diversity-bucket loop below unreachable in normal full frontiers.
        for (List<ObstacleNode> family : exploratoryObstacleSearch ? coarseFamilies.values()
                : List.<List<ObstacleNode>>of()) {
            Map<DiversityKey, ObstacleNode> cells = new LinkedHashMap<>();
            for (ObstacleNode node : family) cells.putIfAbsent(DiversityKey.from(node), node);
            LinkedHashSet<ObstacleNode> ordered = new LinkedHashSet<>(cells.values());
            ordered.addAll(family);
            family.clear();
            family.addAll(ordered);
        }
        for (int rank = 0; retained.size() < request.policy().beamWidth(); rank++) {
            boolean added = false;
            for (List<ObstacleNode> family : coarseFamilies.values()) {
                if (rank >= family.size()) continue;
                retained.add(family.get(rank));
                added = true;
                if (retained.size() >= request.policy().beamWidth()) break;
            }
            if (!added) break;
        }
        Map<DiversityKey, ObstacleNode> buckets = new LinkedHashMap<>();
        for (ObstacleNode node : nodes) buckets.putIfAbsent(DiversityKey.from(node), node);
        diversityBuckets = Math.max(diversityBuckets, buckets.size());
        for (ObstacleNode node : buckets.values().stream().sorted(OBSTACLE_ORDER).toList()) {
            if (retained.size() >= request.policy().beamWidth()) break;
            retained.add(node);
        }
        for (ObstacleNode node : nodes) {
            if (retained.size() >= request.policy().beamWidth()) break;
            retained.add(node);
        }
        return List.copyOf(retained);
    }

    private double obstacleScore(ParkourState state, LaunchLane lane,
                                 ObstacleGuide guide, int guideIndex) {
        if (guide.ladder != null) {
            Vec3d destination = guide.target(guideIndex, lane);
            double distance = horizontalDistance(state.feetPosition(), destination);
            double below = Math.max(0, guide.ladder.attachment().minY - state.feetPosition().y);
            Vec3d projected = state.feetPosition().add(state.velocity().multiply(3));
            double stoppingDistance = horizontalDistance(projected, destination);
            return distance * 6 + stoppingDistance * 4 + guide.remainingDistance(guideIndex, lane) * 8
                    + guide.crossTrackError(state.feetPosition(), guideIndex, lane) * 4
                    + below * 100 + (state.horizontalCollision() ? 2 : 0);
        }
        Vec3d predicted = predictedTouchdown(state);
        Vec3d delta = lane.landingAnchor().feet().subtract(predicted);
        Vec3d side = ControlInput.strafeDirection(lane.heading());
        Vec3d guideTarget = guide.target(guideIndex, lane);
        double guideDistance = horizontalDistance(state.feetPosition(), guideTarget)
                + guide.remainingDistance(guideIndex, lane);
        double guideCrossTrack = guide.crossTrackError(state.feetPosition(), guideIndex, lane);
        double clearance = bodyObstacleClearance(state);
        return Math.abs(delta.dotProduct(lane.heading())) * 8
                + Math.abs(delta.dotProduct(side)) * 14
                + Math.abs(predicted.y - lane.landingAnchor().feet().y) * 4
                + guideDistance * 1.5 + guideCrossTrack * 5
                + state.velocity().horizontalLength() * 0.3
                + (state.horizontalCollision() ? 12 : 0)
                + Math.max(0, (exploratoryObstacleSearch ? 0.025 : 0.18) - clearance) * 30;
    }

    private Vec3d predictedTouchdown(ParkourState state) {
        double x = state.feetPosition().x, y = state.feetPosition().y, z = state.feetPosition().z;
        double vx = state.velocity().x, vy = state.velocity().y, vz = state.velocity().z;
        for (int tick = 0; tick < maximumHorizon(); tick++) {
            x += vx; y += vy; z += vz;
            if (y <= minimumLandingY() && vy < 0) break;
            vy = (vy - 0.08) * 0.98; vx *= 0.91; vz *= 0.91;
        }
        return new Vec3d(x, y, z);
    }

    private PlanningTickResult finishAtWallLimit(long now) {
        return best != null ? ready(best, now) : rejected(PlanRejectionReason.SEARCH_TIMEOUT,
                String.format("Trajectory search reached its %.0f ms limit. %s",
                        request.policy().maximumWallNanos() / 1_000_000.0, rejectionSummary()));
    }

    private PlanningTickResult.Ready ready(Candidate candidate, long now) {
        stage = PlanningStage.LANDING_VALIDATION;
        long validationStart = System.nanoTime();
        EnvelopeResult envelope = deriveEnvelope(candidate, Math.min(finalizationDeadline,
                now + request.policy().validationReserveNanos()));
        List<Vec3d> positioning = candidate.launch.startsFromCurrentState()
                ? List.of() : positioningPath(candidate.launch.stagingPosition());
        boolean immediate = candidate.launch.startsFromCurrentState()
                && envelope.envelope.containsPosition(request.player().feetPosition())
                && Math.abs(MathHelper.wrapDegrees(request.player().yaw() - envelope.envelope.desiredYaw()))
                <= envelope.envelope.yawTolerance();
        long directNanos = Math.max(0, (directFinishedNanos == 0 ? now : directFinishedNanos)
                - (directStartedNanos == 0 ? startedNanos : directStartedNanos));
        long obstacleNanos = obstacleStartedNanos == 0 ? 0 : Math.max(0, now - obstacleStartedNanos);
        long finished = System.nanoTime();
        PlanMetrics metrics = new PlanMetrics(finished - startedNanos,
                directEvaluations + flightStatesExpanded, candidate.launch.runUpLength(), candidate.landingSpeed,
                envelope.robustness, candidate.edgeMargin, statesDeduplicated, launchStates.size(),
                candidate.stoppingMethod, candidate.frames.stream().anyMatch(ControlFrame::sneak),
                candidate.tube == Tube.PRECISE ? "precision_stable"
                        : candidate.sourceStage == PlanningStage.DIRECT ? "direct_stable" : "obstacle_stable",
                directNanos, obstacleNanos, Math.max(0, finished - validationStart),
                flightStatesExpanded, diversityBuckets, coreTouchdowns, fringeTouchdowns, candidate.sourceStage);
        int commitIndex = candidate.launch.groundPrefix().size();
        List<ParkourState> approachStates = candidate.states.subList(0,
                Math.min(candidate.states.size(), commitIndex + 1));
        Vec3d stagingPoint = candidate.launch.stagingPosition();
        Box stagingRegion = boundsForEnvelope(stagingPoint, candidate.launch.lane().heading(),
                envelope.envelope.minimumLongitudinal(), envelope.envelope.maximumLongitudinal(),
                envelope.envelope.minimumLateral(), envelope.envelope.maximumLateral(),
                request.player().boundingBox().getLengthY());
        ApproachPlan approachPlan = new ApproachPlan(stagingRegion,
                request.problem().approachRegion(), approachStates,
                envelope.envelope.positionBounds(), commitIndex, commitIndex);
        return new PlanningTickResult.Ready(new MovementPlan(positioning, candidate.frames,
                samples(candidate.states), request.problem().landingRegion(), candidate.launch.stagingPosition(),
                candidate.launch.state().feetPosition(), immediate, candidate.launch.startsFromCurrentState(),
                envelope.envelope, metrics,
                request.problem().worldFingerprint(), problemBounds(), landingZone, candidate.launch.lane(),
                candidate.routeMode == RouteMode.LADDER_ASSIST ? candidate.states.getLast().feetPosition()
                        : candidate.launch.lane().landingAnchor().feet(), candidate.sourceStage,
                approachPlan, candidate.contactEvents, candidate.routeMode, 1,
                configurationSpace.routeObstacles(candidate.launch.state().feetPosition(),
                        candidate.launch.lane().landingAnchor().feet()), candidate.robustness));
    }

    private EnvelopeResult deriveEnvelope(Candidate candidate, long deadline) {
        LaunchState launch = candidate.launch;
        ParkourState baseline = launch.state();
        List<ControlFrame> launchSuffix = candidate.frames.subList(
                launch.groundPrefix().size(), candidate.frames.size());
        if (candidate.contactRobust && candidate.routeMode != RouteMode.DIRECT) {
            double positionTolerance = candidate.tube.position;
            double speedTolerance = candidate.tube.speed;
            double baselineSpeed = baseline.velocity().dotProduct(launch.lane().heading());
            Box bounds = boundsForEnvelope(baseline.feetPosition(), launch.lane().heading(),
                    -positionTolerance, positionTolerance, -positionTolerance,
                    positionTolerance, baseline.boundingBox().getLengthY());
            return new EnvelopeResult(new LaunchEnvelope(bounds, baseline.velocity(),
                    positionTolerance, speedTolerance, launch.lane().yaw(), candidate.tube.yaw,
                    baseline.feetPosition(), launch.lane().heading(),
                    -positionTolerance, positionTolerance, -positionTolerance,
                    positionTolerance, baselineSpeed - speedTolerance,
                    baselineSpeed + speedTolerance), candidate.robustness);
        }
        double minLong = 0, maxLong = 0, minLateral = 0, maxLateral = 0;
        double baselineSpeed = baseline.velocity().dotProduct(launch.lane().heading());
        double minSpeed = baselineSpeed, maxSpeed = baselineSpeed;
        int robustness = 1;
        Vec3d side = ControlInput.strafeDirection(launch.lane().heading());
        for (double offset : new double[]{-0.10, -0.06, -0.03, 0.03, 0.06, 0.10}) {
            if (System.nanoTime() >= deadline) break;
            ParkourState shifted = pose(baseline,
                    baseline.feetPosition().add(launch.lane().heading().multiply(offset)),
                    baseline.velocity(), baseline.yaw(), baseline.onGround(), baseline.sprinting(), false);
            if (replayStable(shifted, launchSuffix, 0)) {
                robustness++; minLong = Math.min(minLong, offset); maxLong = Math.max(maxLong, offset);
            }
        }
        for (double offset : new double[]{-0.08, -0.04, 0.04, 0.08}) {
            if (System.nanoTime() >= deadline) break;
            ParkourState shifted = pose(baseline, baseline.feetPosition().add(side.multiply(offset)),
                    baseline.velocity(), baseline.yaw(), baseline.onGround(), baseline.sprinting(), false);
            if (replayStable(shifted, launchSuffix, 0)) {
                robustness++; minLateral = Math.min(minLateral, offset); maxLateral = Math.max(maxLateral, offset);
            }
        }
        float yawTolerance = 1;
        for (float offset : new float[]{-2, 2, -4, 4}) {
            if (System.nanoTime() >= deadline) break;
            ParkourState shifted = pose(baseline, baseline.feetPosition(), baseline.velocity(),
                    baseline.yaw() + offset, baseline.onGround(), baseline.sprinting(), false);
            if (replayStable(shifted, launchSuffix, 0)) {
                robustness++; yawTolerance = Math.max(yawTolerance, Math.abs(offset));
            }
        }
        for (double offset : new double[]{-0.03, 0.03}) {
            if (System.nanoTime() >= deadline) break;
            ParkourState shifted = pose(baseline, baseline.feetPosition(),
                    baseline.velocity().add(launch.lane().heading().multiply(offset)), baseline.yaw(),
                    baseline.onGround(), baseline.sprinting(), false);
            if (replayStable(shifted, launchSuffix, 0)) {
                robustness++; minSpeed = Math.min(minSpeed, baselineSpeed + offset);
                maxSpeed = Math.max(maxSpeed, baselineSpeed + offset);
            }
        }
        Box bounds = boundsForEnvelope(baseline.feetPosition(), launch.lane().heading(), minLong,
                maxLong, minLateral, maxLateral, baseline.boundingBox().getLengthY());
        double maxPosition = Math.max(0.02, Math.max(Math.max(Math.abs(minLong), Math.abs(maxLong)),
                Math.max(Math.abs(minLateral), Math.abs(maxLateral))));
        double maxVelocity = Math.max(0.02, Math.max(Math.abs(minSpeed - baselineSpeed),
                Math.abs(maxSpeed - baselineSpeed)));
        return new EnvelopeResult(new LaunchEnvelope(bounds, baseline.velocity(), maxPosition, maxVelocity,
                launch.lane().yaw(), yawTolerance, baseline.feetPosition(), launch.lane().heading(),
                minLong, maxLong, minLateral, maxLateral, minSpeed, maxSpeed), robustness);
    }

    private boolean replayStable(ParkourState initial, List<ControlFrame> frames, float yawOffset) {
        ParkourState state = initial;
        boolean airborne = false;
        for (ControlFrame frame : frames) {
            if (frame.sneak() && !(state.onGround() && support(state).targetSupported())
                    && !(frame.phase() == ControlPhase.LADDER && !state.onGround()
                    && request.world().isLadder(BlockPos.ofFloored(state.feetPosition())))) return false;
            state = advance(state, new ControlFrame(frame.forward(), frame.strafe(), frame.sprint(),
                    frame.jump(), frame.sneak(), frame.desiredYaw() + yawOffset, frame.phase(), frame.guard()));
            if (state == null) return false;
            airborne |= !state.onGround();
            if (airborne && state.onGround() && !support(state).targetSupported()) return false;
        }
        return state.onGround() && support(state).targetSupported()
                && state.velocity().horizontalLength() <= LandingStabilityTracker.MAX_FINAL_SPEED;
    }

    private List<ControlFrame> groundFrames(LaunchState launch) {
        List<ControlFrame> frames = new ArrayList<>();
        for (ControlInput input : launch.groundPrefix()) frames.add(new ControlFrame(input.forward(),
                input.strafe(), input.sprint(), false, false, input.yaw(),
                ControlPhase.RUN_UP, FrameGuard.GROUNDED));
        return frames;
    }

    private List<ParkourState> replayGroundStates(LaunchState launch) {
        List<ParkourState> states = new ArrayList<>();
        ParkourState state = launch.initialState();
        states.add(state);
        for (ControlInput input : launch.groundPrefix()) {
            state = physics.tickState(request.world(), state, input);
            states.add(state);
        }
        return states;
    }

    private List<CollisionManifold> replayGroundContacts(LaunchState launch) {
        List<CollisionManifold> contacts = new ArrayList<>();
        ParkourState state = launch.initialState();
        for (ControlInput input : launch.groundPrefix()) {
            PhysicsStep step = physics.tick(request.world(), state, input);
            contacts.add(step.collisions());
            state = step.state();
        }
        return contacts;
    }

    private List<ControlFrame> reconstructObstacleFrames(ObstacleNode node, ControlFrame last) {
        List<ControlFrame> flight = new ArrayList<>();
        flight.add(last);
        for (ObstacleNode cursor = node; cursor != null; cursor = cursor.parent) flight.add(cursor.input);
        Collections.reverse(flight);
        List<ControlFrame> frames = groundFrames(node.launch);
        frames.addAll(flight);
        return frames;
    }

    private List<ParkourState> reconstructObstacleStates(ObstacleNode node, ParkourState last) {
        List<ParkourState> flight = new ArrayList<>();
        flight.add(last);
        for (ObstacleNode cursor = node; cursor != null; cursor = cursor.parent) flight.add(cursor.state);
        Collections.reverse(flight);
        List<ParkourState> states = replayGroundStates(node.launch);
        states.addAll(flight);
        return states;
    }

    private List<CollisionManifold> reconstructObstacleContacts(ObstacleNode node,
                                                                 CollisionManifold last) {
        List<CollisionManifold> flight = new ArrayList<>();
        flight.add(last);
        for (ObstacleNode cursor = node; cursor != null; cursor = cursor.parent) {
            flight.add(cursor.collisions);
        }
        Collections.reverse(flight);
        List<CollisionManifold> result = replayGroundContacts(node.launch);
        result.addAll(flight);
        return result;
    }

    private ContactProfile contactProfile(List<CollisionManifold> manifolds,
                                          List<ParkourState> states, RouteMode requestedMode) {
        Map<String, List<Integer>> ticksByFeature = new LinkedHashMap<>();
        Map<String, CollisionContact> representative = new LinkedHashMap<>();
        boolean head = false;
        boolean side = false;
        boolean xSide = false;
        boolean zSide = false;
        for (int tick = 0; tick < manifolds.size(); tick++) {
            for (CollisionContact contact : manifolds.get(tick).contacts()) {
                if (contact.support()) continue;
                String key = contact.featureId() + ":" + contact.face();
                ticksByFeature.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tick);
                representative.putIfAbsent(key, contact);
                head |= contact.face().headContact();
                side |= contact.face().sideContact();
                xSide |= contact.axis() == com.ariesninja.skulkpk.client.core.physics.CollisionAxis.X;
                zSide |= contact.axis() == com.ariesninja.skulkpk.client.core.physics.CollisionAxis.Z;
            }
        }
        boolean avoidance = requestedMode == RouteMode.AVOID_LEFT
                || requestedMode == RouteMode.AVOID_RIGHT;
        RouteMode mode = requestedMode == RouteMode.LADDER_ASSIST ? requestedMode
                : avoidance ? requestedMode : head ? RouteMode.CONTACT_HEAD
                : side && xSide && zSide ? RouteMode.CONTACT_CORNER
                : side ? RouteMode.CONTACT_SIDE : requestedMode;
        List<ContactEvent> events = new ArrayList<>();
        ticksByFeature.forEach((key, ticks) -> {
            int first = ticks.getFirst();
            int last = ticks.getLast();
            Vec3d entry = states.get(Math.min(first, states.size() - 1)).feetPosition();
            Vec3d exit = states.get(Math.min(last + 1, states.size() - 1)).feetPosition();
            CollisionContact contact = representative.get(key);
            ContactRequirement requirement = avoidance ? ContactRequirement.FORBIDDEN
                    : mode == RouteMode.CONTACT_HEAD
                    || mode == RouteMode.CONTACT_SIDE || mode == RouteMode.CONTACT_CORNER
                    ? ContactRequirement.REQUIRED : ContactRequirement.ALLOWED;
            events.add(new ContactEvent(contact.featureId(), contact.obstacle(), requirement, contact.face(),
                    feetBounds(entry, 0.03), feetBounds(exit, 0.04),
                    Math.max(0, first - 1), last + 1));
        });
        return new ContactProfile(mode, List.copyOf(events), avoidance ? events.size() : 0);
    }

    private Box feetBounds(Vec3d feet, double radius) {
        return new Box(feet.x - radius, feet.y - radius, feet.z - radius,
                feet.x + radius, feet.y + radius, feet.z + radius);
    }

    private List<TrajectorySample> samples(List<ParkourState> states) {
        List<TrajectorySample> result = new ArrayList<>();
        boolean airborne = false;
        for (int index = 0; index < states.size(); index++) {
            ParkourState state = states.get(index);
            airborne |= !state.onGround();
            SupportResolver.Contact contact = support(state);
            ControlPhase phase = contact.targetSupported()
                    ? state.velocity().horizontalLength() > LandingStabilityTracker.MAX_FINAL_SPEED
                        ? ControlPhase.LANDED_BRAKING : ControlPhase.SETTLING
                    : airborne ? request.world().isLadder(BlockPos.ofFloored(state.feetPosition()))
                        ? ControlPhase.LADDER : ControlPhase.AIRBORNE : ControlPhase.RUN_UP;
            result.add(new TrajectorySample(index, state.feetPosition(), state.velocity(), state.boundingBox(),
                    state.onGround(), state.horizontalCollision(), state.verticalCollision(), phase,
                    contact.kind(), contact.overlapArea()));
        }
        return List.copyOf(result);
    }

    private ParkourState advance(ParkourState state, ControlFrame frame) {
        PhysicsStep step = advanceStep(state, frame);
        return step == null ? null : step.state();
    }

    private PhysicsStep advanceStep(ParkourState state, ControlFrame frame) {
        try {
            return physics.tick(request.world(), state, new ControlInput(frame.forward(), frame.strafe(),
                    frame.sprint(), frame.jump(), frame.sneak(), frame.desiredYaw()));
        } catch (ParkourPhysics.UnsupportedPhysicsStateException exception) {
            recordRejection(exception.getMessage().equals("outside_captured_world")
                    ? "Trajectory left the captured planning region."
                    : "The route entered fluid or an unsupported climbable block.");
        } catch (RuntimeException exception) {
            recordRejection("Physics error: " + exception.getClass().getSimpleName() + ".");
        }
        return null;
    }

    private SupportResolver.Contact support(ParkourState state) {
        return SupportResolver.resolve(state.boundingBox(), state.feetPosition(), state.onGround(),
                request.problem().landingRegion(), request.problem().approachRegion(), request.world());
    }

    private ParkourState pose(ParkourState state, Vec3d feet, Vec3d velocity, float yaw,
                              boolean onGround, boolean sprinting, boolean preserveJump) {
        return new ParkourState(feet, velocity, state.boundingBox().offset(feet.subtract(state.feetPosition())),
                yaw, onGround, sprinting, preserveJump && state.jumpUsed(),
                state.horizontalCollision(), state.verticalCollision(),
                state.elapsedTicks(), state.baseMovementSpeed(), state.jumpStrength(), state.stepHeight(),
                state.gravity(), state.activeEffects(), state.previousSneak(), state.sneakingSpeed(),
                state.collidedSoftly(), state.sprintTapTicks(), state.previousForward(), state.sprintAllowed());
    }

    private List<Vec3d> positioningPath(Vec3d staging) {
        return horizontalDistance(request.player().feetPosition(), staging) <= 0.12
                ? List.of() : List.of(staging);
    }

    private double traceRunUp(Vec3d takeoff, Vec3d heading, double topY) {
        double supported = 0;
        for (double distance = 0; distance <= 10; distance += 0.10) {
            Vec3d point = takeoff.subtract(heading.multiply(distance));
            if (!fullSupport(point, topY)
                    || !request.world().collisionBoxes(playerBox(point).expand(-0.01)).isEmpty()) break;
            supported = distance;
        }
        return supported;
    }

    private boolean fullSupport(Vec3d feet, double topY) {
        if (Math.abs(feet.y - topY) > 0.08) return false;
        Box box = playerBox(feet);
        return SupportResolver.overlapArea(box, feet.y, request.problem().approachRegion())
                >= box.getLengthX() * box.getLengthZ() - 1.0E-4;
    }

    private boolean bodyClear(Vec3d feet) {
        return request.world().collisionBoxes(playerBox(feet).expand(-0.01)).isEmpty();
    }

    private double bodyObstacleClearance(ParkourState state) {
        return bodyObstacleClearance(state.boundingBox(), state.feetPosition().y);
    }

    private double trajectoryObstacleClearance(List<ParkourState> states) {
        double minimum = 10;
        for (int index = 0; index < states.size(); index++) {
            ParkourState state = states.get(index);
            minimum = Math.min(minimum, bodyObstacleClearance(state));
            if (index == 0) continue;
            ParkourState previous = states.get(index - 1);
            Vec3d movement = state.feetPosition().subtract(previous.feetPosition());
            for (double fraction : new double[]{0.25, 0.50, 0.75}) {
                minimum = Math.min(minimum, bodyObstacleClearance(
                        previous.boundingBox().offset(movement.multiply(fraction)),
                        previous.feetPosition().y + movement.y * fraction));
            }
        }
        return minimum;
    }

    private double bodyObstacleClearance(Box body, double feetY) {
        double minimum = 10;
        Vec3d feet = new Vec3d((body.minX + body.maxX) * 0.5, feetY,
                (body.minZ + body.maxZ) * 0.5);
        for (ConfigurationObstacle obstacle : configurationSpace.obstacles()) {
            Box forbidden = obstacle.forbiddenFeet();
            if (feet.y <= forbidden.minY + 1.0E-7 || feet.y >= forbidden.maxY - 1.0E-7) continue;
            double dx = Math.max(0, Math.max(forbidden.minX - feet.x, feet.x - forbidden.maxX));
            double dz = Math.max(0, Math.max(forbidden.minZ - feet.z, feet.z - forbidden.maxZ));
            minimum = Math.min(minimum, Math.hypot(dx, dz));
        }
        return minimum;
    }

    private boolean isExposedEdge(StandableSurface surface, Vec3d edge, Vec3d direction) {
        Vec3d beyond = edge.add(direction.multiply(0.38));
        return request.problem().approachRegion().stream().noneMatch(other -> other != surface
                && Math.abs(other.topY() - surface.topY()) <= 0.02 && pointOnSurface(beyond, other, 0.02));
    }

    private List<Vec3d> edgeSamples(StandableSurface surface, Vec3d direction) {
        Box box = surface.footprint();
        List<Vec3d> result = new ArrayList<>();
        if (Math.abs(direction.x) >= Math.abs(direction.z)) {
            double x = direction.x >= 0 ? box.maxX - PLAYER_RADIUS : box.minX + PLAYER_RADIUS;
            double min = box.minZ + PLAYER_RADIUS, max = box.maxZ - PLAYER_RADIUS;
            result.add(new Vec3d(x, surface.topY(), (min + max) * 0.5));
            if (max - min >= 0.30) { result.add(new Vec3d(x, surface.topY(), min)); result.add(new Vec3d(x, surface.topY(), max)); }
        } else {
            double z = direction.z >= 0 ? box.maxZ - PLAYER_RADIUS : box.minZ + PLAYER_RADIUS;
            double min = box.minX + PLAYER_RADIUS, max = box.maxX - PLAYER_RADIUS;
            result.add(new Vec3d((min + max) * 0.5, surface.topY(), z));
            if (max - min >= 0.30) { result.add(new Vec3d(min, surface.topY(), z)); result.add(new Vec3d(max, surface.topY(), z)); }
        }
        return result;
    }

    private Vec3d edgePoint(StandableSurface surface, Vec3d direction, double lateral) {
        Box box = surface.footprint();
        Vec3d center = surface.centerFeet();
        double tx = Double.POSITIVE_INFINITY, tz = Double.POSITIVE_INFINITY;
        if (direction.x > 1.0E-6) tx = (box.maxX - PLAYER_RADIUS - center.x) / direction.x;
        else if (direction.x < -1.0E-6) tx = (box.minX + PLAYER_RADIUS - center.x) / direction.x;
        if (direction.z > 1.0E-6) tz = (box.maxZ - PLAYER_RADIUS - center.z) / direction.z;
        else if (direction.z < -1.0E-6) tz = (box.minZ + PLAYER_RADIUS - center.z) / direction.z;
        Vec3d point = center.add(direction.multiply(Math.max(0, Math.min(tx, tz))));
        Vec3d side = ControlInput.strafeDirection(direction);
        Vec3d shifted = point.add(side.multiply(lateral));
        return new Vec3d(MathHelper.clamp(shifted.x, box.minX + PLAYER_RADIUS, box.maxX - PLAYER_RADIUS),
                surface.topY(), MathHelper.clamp(shifted.z, box.minZ + PLAYER_RADIUS, box.maxZ - PLAYER_RADIUS));
    }

    private double lateralCoordinate(StandableSurface surface, Vec3d sample, Vec3d heading) {
        return sample.subtract(surface.centerFeet()).dotProduct(ControlInput.strafeDirection(heading));
    }

    private Box boundsForEnvelope(Vec3d origin, Vec3d heading, double minLong, double maxLong,
                                  double minLateral, double maxLateral, double height) {
        Vec3d side = ControlInput.strafeDirection(heading);
        List<Vec3d> corners = List.of(origin.add(heading.multiply(minLong)).add(side.multiply(minLateral)),
                origin.add(heading.multiply(minLong)).add(side.multiply(maxLateral)),
                origin.add(heading.multiply(maxLong)).add(side.multiply(minLateral)),
                origin.add(heading.multiply(maxLong)).add(side.multiply(maxLateral)));
        double minX = corners.stream().mapToDouble(p -> p.x).min().orElse(origin.x);
        double maxX = corners.stream().mapToDouble(p -> p.x).max().orElse(origin.x);
        double minZ = corners.stream().mapToDouble(p -> p.z).min().orElse(origin.z);
        double maxZ = corners.stream().mapToDouble(p -> p.z).max().orElse(origin.z);
        return new Box(minX, origin.y - 0.06, minZ, maxX, origin.y + Math.min(0.18, height), maxZ);
    }

    private Box playerBox(Vec3d feet) {
        return request.player().boundingBox().offset(feet.subtract(request.player().feetPosition()));
    }

    private Box problemBounds() {
        Box bounds = request.player().boundingBox();
        for (StandableSurface surface : request.problem().approachRegion()) bounds = bounds.union(surface.footprint());
        for (StandableSurface surface : request.problem().landingRegion()) bounds = bounds.union(surface.footprint());
        return bounds.expand(2, 3, 2);
    }

    private String classifyMiss(ParkourState state, LaunchLane lane) {
        Vec3d delta = lane.landingAnchor().feet().subtract(state.feetPosition());
        Vec3d side = ControlInput.strafeDirection(lane.heading());
        if (Math.abs(delta.dotProduct(side)) > 0.65) return "Lateral miss: trajectory passed beside the fixed landing zone.";
        if (delta.dotProduct(lane.heading()) < -0.25) return "Overshot the connected target region.";
        return "Undershot the connected target region.";
    }

    private String collisionSignature(CollisionManifold collisions) {
        if (collisions.hasHeadContact()) return "D";
        if (collisions.hasSideContact()) return "S";
        if (collisions.hasSupportContact()) return "U";
        return "N";
    }

    private String mergeCollisionSignature(String signature, CollisionManifold collisions) {
        String next = collisionSignature(collisions);
        return next.equals("N") || signature.endsWith(next) ? signature : signature + next;
    }

    private boolean guideMatches(RouteMode approachMode, int guideSide) {
        return switch (approachMode) {
            case AVOID_LEFT -> guideSide < 0;
            case AVOID_RIGHT -> guideSide > 0;
            default -> true;
        };
    }

    private String launchKey(LaunchState state) {
        return state.lane().id() + ":" + q(state.state().feetPosition().x, 0.02) + ":"
                + q(state.state().feetPosition().z, 0.02) + ":" + q(state.state().velocity().x, 0.01)
                + ":" + q(state.state().velocity().z, 0.01) + ":" + state.state().sprinting()
                + ":" + state.state().sprintTapTicks() + ":" + state.state().previousForward();
    }

    private double launchCost(LaunchState launch) {
        return (launch.startsFromCurrentState() ? 0
                : horizontalDistance(request.player().feetPosition(), launch.stagingPosition()))
                + launch.runUpLength();
    }

    private double momentumRank(LaunchState launch) {
        return launch.startsFromCurrentState() ? -1 : launch.runUpLength();
    }

    private int inputChurn(List<ControlFrame> frames) {
        int churn = 0;
        for (int i = 1; i < frames.size(); i++) {
            ControlFrame a = frames.get(i - 1), b = frames.get(i);
            if (a.forward() != b.forward()) churn++;
            if (a.strafe() != b.strafe()) churn++;
            if (a.sprint() != b.sprint()) churn++;
            if (Math.abs(MathHelper.wrapDegrees(a.desiredYaw() - b.desiredYaw())) > 2) churn++;
        }
        return churn;
    }

    private void keep(Candidate candidate) {
        if (candidate == null) return;
        boolean contractRoute = candidate.routeMode == RouteMode.AVOID_LEFT
                || candidate.routeMode == RouteMode.AVOID_RIGHT
                || candidate.routeMode == RouteMode.LADDER_ASSIST
                || candidate.routeMode == RouteMode.CONTACT_HEAD
                || candidate.routeMode == RouteMode.CONTACT_SIDE
                || candidate.routeMode == RouteMode.CONTACT_CORNER;
        if (contractRoute && !candidate.contactRobust) {
            if (candidate.sourceStage == PlanningStage.OBSTACLE
                    && (candidate.routeMode == RouteMode.AVOID_LEFT
                    || candidate.routeMode == RouteMode.AVOID_RIGHT)) {
                nominalObstacleCandidates.add(candidate);
                nominalObstacleCandidates.sort(NOMINAL_VALIDATION_ORDER);
                if (nominalObstacleCandidates.size() > 32) {
                    nominalObstacleCandidates.subList(32, nominalObstacleCandidates.size()).clear();
                }
                return;
            }
            if (best != null && best.contactRobust
                    && candidate.launchCost > best.launchCost + RUN_UP_INCREMENT) return;
            int variants = validateRouteTube(candidate);
            if (variants < 8 && candidate.routeMode == RouteMode.LADDER_ASSIST) {
                Candidate precise = candidate.withTube(Tube.PRECISE, false);
                int precisionVariants = validateRouteTube(precise);
                if (precisionVariants == 8) {
                    candidate = precise;
                    variants = 8;
                } else {
                    recordRejection("Fragile ladder attachment: " + variants + "/8 standard and "
                            + precisionVariants + "/8 precision launch checks passed.");
                    if (precisionVariants >= 6 && fragileObstacleCandidates.size() < 8) {
                        fragileObstacleCandidates.add(candidate);
                    }
                }
            }
            if (variants < 8) {
                recordRejection("Fragile route: launch-tube perturbations did not preserve the contact contract.");
                return;
            }
            candidate = new Candidate(candidate.launch, candidate.frames, candidate.states,
                    candidate.stoppingMethod, candidate.coreLanding, candidate.edgeMargin,
                    candidate.landingSpeed, variants, candidate.churn, candidate.launchCost,
                    candidate.horizontalContacts, candidate.obstacleClearance,
                    candidate.momentumReady, candidate.sourceStage, candidate.routeMode,
                    candidate.contactEvents, candidate.unplannedContacts, true, candidate.tube);
        }
        if (best == null || CANDIDATE_ORDER.compare(candidate, best) < 0) {
            best = candidate;
            if (candidate.sourceStage == PlanningStage.OBSTACLE && bestObstacleDepth < 0) {
                bestObstacleDepth = obstacleDepth;
            }
        }
    }

    private void promoteNominalObstacleCandidate() {
        if (nominalObstacleCandidates.isEmpty()) return;
        List<Candidate> ordered = List.copyOf(nominalObstacleCandidates);
        nominalObstacleCandidates.clear();
        for (Candidate candidate : ordered) {
            int variants = validateRouteTube(candidate);
            if (variants < 8) {
                if (variants >= 4
                        && fragileObstacleCandidates.stream().noneMatch(old -> old.launch.equals(candidate.launch))) {
                    fragileObstacleCandidates.add(candidate);
                    fragileObstacleCandidates.sort(NOMINAL_VALIDATION_ORDER);
                    if (fragileObstacleCandidates.size() > 8) fragileObstacleCandidates.removeLast();
                }
                recordRejection("Fragile route: launch-tube perturbations did not preserve the contact contract.");
                continue;
            }
            Candidate robust = new Candidate(candidate.launch, candidate.frames, candidate.states,
                    candidate.stoppingMethod, candidate.coreLanding, candidate.edgeMargin,
                    candidate.landingSpeed, variants, candidate.churn, candidate.launchCost,
                    candidate.horizontalContacts, candidate.obstacleClearance,
                    candidate.momentumReady, candidate.sourceStage, candidate.routeMode,
                    candidate.contactEvents, candidate.unplannedContacts, true, candidate.tube);
            if (best == null || CANDIDATE_ORDER.compare(robust, best) < 0) {
                best = robust;
                if (bestObstacleDepth < 0) bestObstacleDepth = obstacleDepth;
            }
            // Validation order is a robustness heuristic only. Once a route passes the full
            // tube it can be compared with every other proven route by CANDIDATE_ORDER.
            break;
        }
    }

    private void tryPrecisionCandidates() {
        if (best != null) return;
        for (Candidate candidate : fragileObstacleCandidates) {
            if (System.nanoTime() >= finalizationDeadline) break;
            Candidate precise = candidate.withTube(Tube.PRECISE, false);
            if (validateRouteTube(precise) == 8) {
                best = precise.withTube(Tube.PRECISE, true);
                return;
            }
        }
    }

    private int validateRouteTube(Candidate candidate) {
        LaunchState launch = candidate.launch;
        int prefix = launch.groundPrefix().size();
        if (prefix >= candidate.frames.size()) return 0;
        List<ControlFrame> suffix = candidate.frames.subList(prefix, candidate.frames.size());
        Vec3d heading = launch.lane().heading();
        Vec3d side = ControlInput.strafeDirection(heading);
        ParkourState baseline = launch.state();
        int passed = 0;
        for (double offset : new double[]{-candidate.tube.position, candidate.tube.position}) {
            ParkourState shifted = pose(baseline, baseline.feetPosition().add(heading.multiply(offset)),
                    baseline.velocity(), baseline.yaw(), baseline.onGround(), baseline.sprinting(), false);
            if (replayVariantStable(shifted, suffix, candidate, 0)) passed++;
        }
        for (double offset : new double[]{-candidate.tube.position, candidate.tube.position}) {
            ParkourState shifted = pose(baseline, baseline.feetPosition().add(side.multiply(offset)),
                    baseline.velocity(), baseline.yaw(), baseline.onGround(), baseline.sprinting(), false);
            if (replayVariantStable(shifted, suffix, candidate, 0)) passed++;
        }
        for (double offset : new double[]{-candidate.tube.speed, candidate.tube.speed}) {
            ParkourState shifted = pose(baseline, baseline.feetPosition(),
                    baseline.velocity().add(heading.multiply(offset)), baseline.yaw(),
                    baseline.onGround(), baseline.sprinting(), false);
            if (replayVariantStable(shifted, suffix, candidate, 0)) passed++;
        }
        for (float yawOffset : new float[]{-candidate.tube.yaw, candidate.tube.yaw}) {
            ParkourState shifted = pose(baseline, baseline.feetPosition(), baseline.velocity(),
                    baseline.yaw() + yawOffset, baseline.onGround(), baseline.sprinting(), false);
            if (replayVariantStable(shifted, suffix, candidate, 0)) passed++;
        }
        return passed;
    }

    private boolean replayVariantStable(ParkourState initial, List<ControlFrame> frames,
                                        Candidate candidate, float yawOffset) {
        if (replayContractStable(initial, frames, candidate, yawOffset, false)
                || replayRecoveryTubeStable(initial, frames, candidate, yawOffset)) return true;
        ParkourState delayed = delayedTakeoffState(initial, frames.getFirst(), yawOffset);
        return delayed != null && (replayContractStable(delayed, frames, candidate, yawOffset, false)
                || replayRecoveryTubeStable(delayed, frames, candidate, yawOffset));
    }

    private ParkourState delayedTakeoffState(ParkourState state, ControlFrame jump,
                                             float yawOffset) {
        PhysicsStep step;
        try {
            step = physics.tick(request.world(), state, new ControlInput(jump.forward(),
                    jump.strafe(), jump.sprint(), false, false,
                    jump.desiredYaw() + yawOffset));
        } catch (RuntimeException exception) { return null; }
        ParkourState delayed = step.state();
        return delayed.onGround() && support(delayed).kind() == SupportKind.TAKEOFF
                && step.collisions().contacts().stream().noneMatch(contact -> !contact.support())
                && bodyClear(delayed.feetPosition()) ? delayed : null;
    }

    /**
     * Validates a perturbed avoidance launch as an outcome tube, rather than greedily chasing
     * the nominal state one tick at a time. A slightly slow launch is allowed to reach the same
     * stable landing a few ticks later, provided every branch remains collision-free and stays
     * on the selected obstacle homotopy.
     */
    private boolean replayRecoveryTubeStable(ParkourState initial, List<ControlFrame> frames,
                                             Candidate candidate, float yawOffset) {
        boolean avoidance = candidate.routeMode == RouteMode.AVOID_LEFT
                || candidate.routeMode == RouteMode.AVOID_RIGHT;
        if (!avoidance || frames.isEmpty()) return false;
        int transitCount = 0;
        while (transitCount < frames.size() && !frames.get(transitCount).phase().isLandingPhase()) {
            transitCount++;
        }
        if (transitCount == 0) return false;
        List<RecoveryNode> frontier = List.of(new RecoveryNode(initial, 0));
        int maximumTicks = Math.min(maximumHorizon(), transitCount + 5);
        for (int tick = 0; tick < maximumTicks && !frontier.isEmpty(); tick++) {
            int nominalIndex = Math.min(tick, transitCount - 1);
            ControlFrame planned = frames.get(nominalIndex);
            Map<RecoveryKey, RecoveryNode> next = new LinkedHashMap<>();
            for (RecoveryNode node : frontier) {
                for (ControlInput input : recoveryTubeActions(node.state, planned, candidate,
                        tick == 0, yawOffset)) {
                    PhysicsStep step;
                    try { step = physics.tick(request.world(), node.state, input); }
                    catch (RuntimeException exception) { continue; }
                    if (step.collisions().contacts().stream().anyMatch(contact -> !contact.support())) continue;
                    ParkourState state = step.state();
                    SupportResolver.Contact contact = support(state);
                    if (state.onGround()) {
                        if (!contact.targetSupported()) continue;
                        for (StoppingMethod method : List.of(candidate.stoppingMethod,
                                StoppingMethod.NATURAL, StoppingMethod.COUNTER_INPUT,
                                StoppingMethod.SNEAK)) {
                            if (simulateStopping(state, candidate.launch.lane(), method) != null) return true;
                        }
                        continue;
                    }
                    if (state.feetPosition().y < minimumLandingY() - 1.0) continue;
                    ParkourState expected = candidate.states.get(Math.min(candidate.states.size() - 1,
                            candidate.launch.groundPrefix().size() + nominalIndex + 1));
                    double score = recoveryTubeScore(state, expected, candidate);
                    RecoveryNode child = new RecoveryNode(state, score);
                    RecoveryKey key = RecoveryKey.from(state, candidate.launch.lane());
                    RecoveryNode existing = next.get(key);
                    if (existing == null || child.score < existing.score) next.put(key, child);
                }
            }
            frontier = next.values().stream().sorted(Comparator.comparingDouble(RecoveryNode::score))
                    .limit(32).toList();
        }
        return false;
    }

    private List<ControlInput> recoveryTubeActions(ParkourState state, ControlFrame planned,
                                                   Candidate candidate, boolean takeoff,
                                                   float yawOffset) {
        if (takeoff) {
            List<ControlInput> takeoffInputs = new ArrayList<>();
            float baseYaw = planned.desiredYaw() + yawOffset;
            for (float delta : new float[]{0, -3, 3, -6, 6, -9, 9, -12, 12}) {
                takeoffInputs.add(new ControlInput(planned.forward(), planned.strafe(),
                        planned.sprint(), true, false, baseYaw + delta));
            }
            return List.copyOf(takeoffInputs);
        }
        LinkedHashSet<ControlInput> inputs = new LinkedHashSet<>();
        float yaw = planned.desiredYaw();
        inputs.add(new ControlInput(planned.forward(), planned.strafe(), planned.sprint(),
                false, false, yaw));
        inputs.add(new ControlInput(1, planned.strafe(), planned.sprint(), false, false, yaw));
        inputs.add(new ControlInput(1, 0, planned.sprint(), false, false, yaw));
        inputs.add(new ControlInput(1, -1, planned.sprint(), false, false, yaw));
        inputs.add(new ControlInput(1, 1, planned.sprint(), false, false, yaw));
        inputs.add(new ControlInput(0, planned.strafe(), planned.sprint(), false, false, yaw));
        inputs.add(new ControlInput(planned.forward(), planned.strafe(), planned.sprint(),
                false, false, yaw - 6));
        inputs.add(new ControlInput(planned.forward(), planned.strafe(), planned.sprint(),
                false, false, yaw + 6));
        inputs.add(new ControlInput(1, 0, planned.sprint(), false, false, yaw - 12));
        inputs.add(new ControlInput(1, 0, planned.sprint(), false, false, yaw + 12));
        float desiredLandingYaw = yaw(horizontalDirection(state.feetPosition(),
                candidate.launch.lane().landingAnchor().feet()));
        for (float strafe : new float[]{-1, 0, 1}) {
            float cameraYaw = commandYaw(state.yaw(), desiredLandingYaw, 1, strafe);
            inputs.add(new ControlInput(1, strafe, planned.sprint(), false, false, cameraYaw));
        }
        return List.copyOf(inputs);
    }

    private double recoveryTubeScore(ParkourState state, ParkourState expected,
                                     Candidate candidate) {
        Vec3d predicted = predictedTouchdown(state);
        double landingMiss = SupportResolver.distanceToRegion(predicted,
                request.problem().landingRegion());
        Vec3d side = ControlInput.strafeDirection(candidate.launch.lane().heading());
        double expectedSide = expected.feetPosition().subtract(
                candidate.launch.lane().takeoffPoint()).dotProduct(side);
        double actualSide = state.feetPosition().subtract(
                candidate.launch.lane().takeoffPoint()).dotProduct(side);
        // Crossing the nominal path is allowed, but abandoning the selected side is not.
        double topologyPenalty = Math.signum(expectedSide) != 0
                && Math.signum(actualSide) != Math.signum(expectedSide) ? 20 : 0;
        return landingMiss * 30
                + state.feetPosition().squaredDistanceTo(expected.feetPosition()) * 2
                + state.velocity().squaredDistanceTo(expected.velocity())
                + topologyPenalty;
    }

    private boolean replayContractStable(ParkourState initial, List<ControlFrame> frames,
                                         Candidate candidate, float yawOffset,
                                         boolean allowRecovery) {
        if (candidate.routeMode == RouteMode.LADDER_ASSIST) {
            return replayLadderStable(initial, frames, candidate);
        }
        ParkourState state = initial;
        LinkedHashSet<String> satisfiedContacts = new LinkedHashSet<>();
        boolean avoidance = candidate.routeMode == RouteMode.AVOID_LEFT
                || candidate.routeMode == RouteMode.AVOID_RIGHT;
        int prefix = candidate.launch.groundPrefix().size();
        for (int index = 0; index < frames.size(); index++) {
            ControlFrame frame = frames.get(index);
            SupportResolver.Contact before = support(state);
            if (frame.guard() == FrameGuard.AIRBORNE && before.targetSupported()) continue;
            if (frame.guard() == FrameGuard.TARGET_GROUNDED && !before.targetSupported()) {
                for (int recoveryTick = 0; recoveryTick < 2 && !before.targetSupported(); recoveryTick++) {
                    PhysicsStep recovered = recoverLandingTransition(state, frame.desiredYaw());
                    if (recovered == null) return false;
                    state = recovered.state();
                    before = support(state);
                }
                if (!before.targetSupported()) return false;
            }
            ParkourState expected = candidate.states.get(Math.min(candidate.states.size() - 1,
                    prefix + index + 1));
            float commandYawOffset = index == 0 ? yawOffset : 0;
            PhysicsStep step = avoidance && allowRecovery && !frame.phase().isLandingPhase()
                    ? robustAvoidanceStep(state, frame, expected, commandYawOffset)
                    : tickFrame(state, frame, commandYawOffset);
            if (step == null || avoidance && step.collisions().contacts().stream()
                    .anyMatch(contact -> !contact.support())) return false;
            int routeTick = prefix + index;
            for (CollisionContact contact : step.collisions().contacts()) {
                if (contact.support()) continue;
                for (ContactEvent event : candidate.contactEvents) {
                    if (event.requirement() != ContactRequirement.REQUIRED
                            || routeTick < event.earliestTick() || routeTick > event.latestTick()) continue;
                    if (event.featureId().equals(contact.featureId())
                            && sameNormalClass(event.face(), contact.face())) {
                        satisfiedContacts.add(event.featureId() + ":" + event.face());
                    }
                }
            }
            state = step.state();
        }
        boolean requiredSatisfied = candidate.contactEvents.stream()
                .filter(event -> event.requirement() == ContactRequirement.REQUIRED)
                .allMatch(event -> satisfiedContacts.contains(event.featureId() + ":" + event.face()));
        return requiredSatisfied && state.onGround() && support(state).targetSupported()
                && state.velocity().horizontalLength() <= LandingStabilityTracker.MAX_FINAL_SPEED + 1.0E-4;
    }

    private boolean replayLadderStable(ParkourState initial, List<ControlFrame> frames, Candidate candidate) {
        LadderColumn column = ladders.stream().filter(value -> candidate.states.stream()
                .anyMatch(state -> value.contains(state.feetPosition()))).findFirst().orElse(null);
        if (column == null) return false;
        ParkourState state = initial;
        boolean attached = false;
        for (int tick = 0; tick < frames.size() + 40; tick++) {
            attached |= column.contains(state.feetPosition());
            ControlFrame frame = attached ? LadderContinuation.choose(request.world(), physics, state,
                    column, column.exit(landingZone)) : frames.get(Math.min(tick, frames.size() - 1));
            PhysicsStep step = tickFrame(state, frame, 0);
            if (step == null) return false;
            state = step.state();
            if (state.onGround()) {
                if (!support(state).targetSupported()) {
                    if (attached && column.supportsExit(state)) continue;
                    return false;
                }
                return simulateStopping(state, candidate.launch.lane(), StoppingMethod.NATURAL) != null
                        || simulateStopping(state, candidate.launch.lane(), StoppingMethod.COUNTER_INPUT) != null
                        || simulateStopping(state, candidate.launch.lane(), StoppingMethod.SNEAK) != null;
            }
            if (state.feetPosition().y < column.attachment().minY) return false;
        }
        return false;
    }

    private boolean sameNormalClass(CollisionFace expected, CollisionFace actual) {
        if (expected.headContact()) return actual.headContact();
        if (expected.sideContact()) return actual.sideContact()
                && sideAxisClass(expected) == sideAxisClass(actual);
        return expected == actual;
    }

    private int sideAxisClass(CollisionFace face) {
        return face == CollisionFace.WEST || face == CollisionFace.EAST ? 1
                : face == CollisionFace.NORTH || face == CollisionFace.SOUTH ? 2 : 0;
    }

    private PhysicsStep recoverLandingTransition(ParkourState state, float yaw) {
        PhysicsStep best = null;
        double bestDistance = Double.MAX_VALUE;
        for (float forward : new float[]{1, 0, -1}) {
            for (float strafe : new float[]{0, -1, 1}) {
                PhysicsStep step;
                try { step = physics.tick(request.world(), state, new ControlInput(
                        forward, strafe, state.sprinting(), false, false, yaw)); }
                catch (RuntimeException exception) { continue; }
                if (step.collisions().contacts().stream().anyMatch(contact -> !contact.support())) continue;
                SupportResolver.Contact contact = support(step.state());
                if (contact.targetSupported()) return step;
                double distance = SupportResolver.distanceToRegion(step.state().feetPosition(),
                        request.problem().landingRegion());
                if (distance < bestDistance) { bestDistance = distance; best = step; }
            }
        }
        return best;
    }

    private PhysicsStep robustAvoidanceStep(ParkourState state, ControlFrame planned,
                                             ParkourState expected, float yawOffset) {
        List<ControlInput> options = new ArrayList<>();
        options.add(new ControlInput(planned.forward(), planned.strafe(), planned.sprint(),
                planned.jump(), false, planned.desiredYaw() + yawOffset));
        if (!planned.jump()) {
            for (float forward : new float[]{planned.forward(), 1, 0}) {
                for (float strafe : new float[]{-1, 0, 1}) {
                    for (float yawDelta : new float[]{-6, 0, 6}) {
                        options.add(new ControlInput(forward, strafe, planned.sprint(),
                                false, false, planned.desiredYaw() + yawOffset + yawDelta));
                    }
                }
            }
        }
        PhysicsStep bestStep = null;
        double bestScore = Double.MAX_VALUE;
        for (ControlInput input : options) {
            PhysicsStep step;
            try { step = physics.tick(request.world(), state, input); }
            catch (RuntimeException exception) { continue; }
            if (step.collisions().contacts().stream().anyMatch(contact -> !contact.support())) continue;
            ParkourState next = step.state();
            double score = next.feetPosition().squaredDistanceTo(expected.feetPosition()) * 20
                    + next.velocity().squaredDistanceTo(expected.velocity()) * 6;
            SupportResolver.Contact actualSupport = support(next);
            SupportResolver.Contact expectedSupport = support(expected);
            if (actualSupport.kind() != expectedSupport.kind()) score += 25;
            if (score < bestScore) { bestScore = score; bestStep = step; }
        }
        return bestStep;
    }

    private PhysicsStep tickFrame(ParkourState state, ControlFrame frame, float yawOffset) {
        try {
            boolean attachment = frame.phase() != ControlPhase.LADDER
                    || !state.onGround() && request.world().isLadder(BlockPos.ofFloored(state.feetPosition()));
            return physics.tick(request.world(), state, new ControlInput(frame.forward(), frame.strafe(),
                    frame.sprint(), frame.jump() && attachment, frame.sneak() && attachment,
                    boundedYaw(state.yaw(), frame.desiredYaw() + yawOffset)));
        } catch (RuntimeException exception) { return null; }
    }

    private int maximumHorizon() {
        return minimumLandingY() < request.problem().standingSurface().topY() - 0.5
                ? request.policy().dropHorizonTicks() : request.policy().normalHorizonTicks();
    }

    private double minimumLandingY() {
        return request.problem().landingRegion().stream().mapToDouble(StandableSurface::topY).min().orElse(0);
    }

    private ControlFrame frame(float forward, float strafe, boolean sprint, boolean jump,
                               float yaw, ControlPhase phase, FrameGuard guard) {
        return new ControlFrame(forward, strafe, sprint, jump, false, yaw, phase, guard);
    }

    private Vec3d horizontalDirection(Vec3d from, Vec3d to) {
        Vec3d value = new Vec3d(to.x - from.x, 0, to.z - from.z);
        return value.lengthSquared() < 1.0E-8 ? new Vec3d(0, 0, 1) : value.normalize();
    }

    private float yaw(Vec3d direction) { return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z)); }
    private float boundedYaw(float current, float desired) {
        return current + MathHelper.clamp(MathHelper.wrapDegrees(desired - current), -12, 12);
    }
    private double horizontalDistance(Vec3d a, Vec3d b) { return Math.hypot(a.x - b.x, a.z - b.z); }
    private double distanceToFootprint(Vec3d point, Box box) {
        double dx = Math.max(0, Math.max(box.minX - point.x, point.x - box.maxX));
        double dz = Math.max(0, Math.max(box.minZ - point.z, point.z - box.maxZ));
        return Math.hypot(dx, dz);
    }
    private double edgeDistance(Box first, Box second) {
        double dx = Math.max(0, Math.max(first.minX - second.maxX, second.minX - first.maxX));
        double dz = Math.max(0, Math.max(first.minZ - second.maxZ, second.minZ - first.maxZ));
        return Math.hypot(dx, dz);
    }
    private boolean pointOnSurface(Vec3d p, StandableSurface s, double t) {
        return p.x >= s.footprint().minX - t && p.x <= s.footprint().maxX + t
                && p.z >= s.footprint().minZ - t && p.z <= s.footprint().maxZ + t;
    }

    private void recordRejection(String reason) { rejectionCounts.merge(reason, 1, Integer::sum); }
    private String rejectionSummary() {
        if (rejectionCounts.isEmpty()) return "No simulated trajectory reached stable target support.";
        return rejectionCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(4).map(e -> e.getValue() + "x " + e.getKey())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private PlanningTickResult.Rejected rejected(PlanRejectionReason reason, String message) {
        return new PlanningTickResult.Rejected(reason, message, directEvaluations + flightStatesExpanded);
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparing((Candidate c) -> !c.contactRobust)
            .thenComparingInt(c -> c.unplannedContacts)
            .thenComparing(c -> !c.momentumReady)
            .thenComparing(c -> !c.launch.startsFromCurrentState())
            // Once a route satisfies its contact contract, minimize the actual approach cost.
            .thenComparingDouble(c -> c.launchCost)
            .thenComparing(c -> !c.coreLanding)
            .thenComparingDouble(c -> Math.hypot(
                    c.launch.lane().takeoffPoint().x - c.launch.lane().landingAnchor().feet().x,
                    c.launch.lane().takeoffPoint().z - c.launch.lane().landingAnchor().feet().z))
            .thenComparing(Comparator.comparingDouble((Candidate c) -> c.edgeMargin).reversed())
            .thenComparingDouble(c -> c.landingSpeed)
            .thenComparing(Comparator.comparingInt((Candidate c) -> c.robustness).reversed())
            .thenComparing(Comparator.comparingDouble((Candidate c) -> c.obstacleClearance).reversed())
            .thenComparing(c -> c.stoppingMethod == StoppingMethod.SNEAK)
            .thenComparingInt(c -> c.churn)
            .thenComparingInt(c -> c.frames.size());
    private static final Comparator<Candidate> NOMINAL_VALIDATION_ORDER = Comparator
            // Tube replay is the expensive operation. Test candidates with the most geometric
            // room first instead of allowing a shorter but pillar-skimming prefix to consume
            // the validation reserve and evict its wider sibling.
            .comparingInt((Candidate c) -> c.unplannedContacts)
            .thenComparing(c -> !c.momentumReady)
            .thenComparing(Comparator.comparingDouble(
                    (Candidate c) -> c.obstacleClearance).reversed())
            .thenComparing(c -> !c.coreLanding)
            .thenComparing(Comparator.comparingDouble((Candidate c) -> c.edgeMargin).reversed())
            .thenComparingDouble(c -> c.launchCost)
            .thenComparingInt(c -> c.churn)
            .thenComparingInt(c -> c.frames.size());
    private static final Comparator<ObstacleNode> OBSTACLE_ORDER = Comparator
            .comparingDouble((ObstacleNode n) -> n.score).thenComparingInt(n -> n.launch.lane().id())
            .thenComparingInt(n -> n.airTicks).thenComparingDouble(n -> n.state.feetPosition().x)
            .thenComparingDouble(n -> n.state.feetPosition().z);

    private record Candidate(LaunchState launch, List<ControlFrame> frames, List<ParkourState> states,
                             StoppingMethod stoppingMethod, boolean coreLanding, double edgeMargin,
                             double landingSpeed, int robustness, int churn, double launchCost,
                             int horizontalContacts, double obstacleClearance, boolean momentumReady,
                             PlanningStage sourceStage, RouteMode routeMode,
                             List<ContactEvent> contactEvents, int unplannedContacts,
                             boolean contactRobust, Tube tube) {
        Candidate { contactEvents = List.copyOf(contactEvents); }
        Candidate withTube(Tube nextTube, boolean validated) {
            return new Candidate(launch, frames, states, stoppingMethod, coreLanding, edgeMargin,
                    landingSpeed, validated ? 8 : robustness, churn, launchCost, horizontalContacts,
                    obstacleClearance, momentumReady, sourceStage, routeMode, contactEvents,
                    unplannedContacts, validated, nextTube);
        }
    }
    private record Tube(double position, double speed, float yaw) {
        private static final Tube STANDARD = new Tube(0.025, 0.02, 1.5f);
        private static final Tube PRECISE = new Tube(0.005, 0.003, 0.35f);
    }
    private record DirectTrial(LaunchState launch, int scheduleIndex) {}
    private record StoppingOutcome(List<ControlFrame> frames, List<ParkourState> states) {}
    private record EnvelopeResult(LaunchEnvelope envelope, int robustness) {}
    private record RecoveryNode(ParkourState state, double score) {}
    private record RecoveryKey(int x, int y, int z, int vx, int vy, int vz) {
        static RecoveryKey from(ParkourState state, LaunchLane lane) {
            Vec3d relative = state.feetPosition().subtract(lane.takeoffPoint());
            Vec3d side = ControlInput.strafeDirection(lane.heading());
            return new RecoveryKey(qStatic(relative.dotProduct(lane.heading()), 0.035),
                    qStatic(state.feetPosition().y, 0.04),
                    qStatic(relative.dotProduct(side), 0.035),
                    qStatic(state.velocity().dotProduct(lane.heading()), 0.015),
                    qStatic(state.velocity().y, 0.02),
                    qStatic(state.velocity().dotProduct(side), 0.015));
        }

        private static int qStatic(double value, double quantum) {
            return (int) Math.round(value / quantum);
        }
    }
    private record RouteObstacleGeometry(ConfigurationObstacle obstacle,
                                         double minimumLongitudinal, double maximumLongitudinal,
                                         double minimumLateral, double maximumLateral,
                                         double routeLength) {}
    private record TangentSpan(double entry, double exit, double lateral) {}
    private record ObstacleGuide(int id, int side, List<Vec3d> waypoints, LadderColumn ladder) {
        ObstacleGuide { waypoints = List.copyOf(waypoints); }
        ObstacleGuide(int id, int side, List<Vec3d> waypoints) { this(id, side, waypoints, null); }

        int advance(Vec3d feet, int current) {
            int index = current;
            while (index < waypoints.size()) {
                Vec3d target = waypoints.get(index);
                if (horizontalDistanceStatic(feet, target) > 0.32) break;
                index++;
            }
            return index;
        }

        Vec3d target(int index, LaunchLane lane) {
            return index < waypoints.size() ? waypoints.get(index)
                    : ladder != null ? ladder.entry(lane.takeoffPoint().y) : lane.landingAnchor().feet();
        }

        float desiredYaw(Vec3d feet, int index, LaunchLane lane) {
            Vec3d target = target(index, lane);
            Vec3d direction = new Vec3d(target.x - feet.x, 0, target.z - feet.z);
            if (direction.lengthSquared() < 1.0E-8) direction = lane.heading();
            return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        }

        double remainingDistance(int index, LaunchLane lane) {
            if (index >= waypoints.size()) return 0;
            double result = 0;
            for (int i = index; i + 1 < waypoints.size(); i++) {
                result += horizontalDistanceStatic(waypoints.get(i), waypoints.get(i + 1));
            }
            result += horizontalDistanceStatic(waypoints.getLast(), target(waypoints.size(), lane));
            return result;
        }

        double crossTrackError(Vec3d feet, int index, LaunchLane lane) {
            Vec3d destination = target(index, lane);
            Vec3d origin = index == 0 ? lane.takeoffPoint()
                    : index <= waypoints.size() ? waypoints.get(index - 1) : waypoints.getLast();
            Vec3d segment = new Vec3d(destination.x - origin.x, 0, destination.z - origin.z);
            if (segment.lengthSquared() < 1.0E-8) return horizontalDistanceStatic(feet, destination);
            double t = MathHelper.clamp(new Vec3d(feet.x - origin.x, 0, feet.z - origin.z)
                    .dotProduct(segment) / segment.lengthSquared(), 0, 1);
            return horizontalDistanceStatic(feet, origin.add(segment.multiply(t)));
        }

        private static double horizontalDistanceStatic(Vec3d first, Vec3d second) {
            return Math.hypot(first.x - second.x, first.z - second.z);
        }
    }
    private record ObstacleNode(ParkourState state, LaunchState launch, ObstacleGuide guide,
                                int guideIndex, ObstacleNode parent, ControlFrame input,
                                CollisionManifold collisions, int airTicks,
                                String collisionSignature, double score) {}
    private record GroundNode(ParkourState state, List<ControlInput> prefix, int churn) {
        GroundNode { prefix = List.copyOf(prefix); }
    }
    private record ContactProfile(RouteMode mode, List<ContactEvent> events,
                                  int unplannedContacts) {
        ContactProfile { events = List.copyOf(events); }
    }
    private record SupportOnlyPhysicsWorld(PhysicsWorld delegate, double maximumShapeY)
            implements PhysicsWorld {
        @Override public List<Box> collisionBoxes(Box region) {
            return delegate.collisionBoxes(region).stream()
                    .filter(box -> box.maxY <= maximumShapeY).toList();
        }
        @Override public double slipperiness(BlockPos pos) { return delegate.slipperiness(pos); }
        @Override public double jumpMultiplier(BlockPos pos) { return delegate.jumpMultiplier(pos); }
        @Override public boolean hasFluid(BlockPos pos) { return delegate.hasFluid(pos); }
        @Override public boolean isClimbable(BlockPos pos) { return delegate.isClimbable(pos); }
        @Override public boolean isLadder(BlockPos pos) { return delegate.isLadder(pos); }
        @Override public boolean contains(Box region) { return delegate.contains(region); }
        @Override public int topY() { return delegate.topY(); }
    }
    private record AirSchedule(boolean sprint, int forwardTicks, boolean brake,
                               float strafe, int strafeTicks, float yawOffset) {
        ControlFrame frame(int tick, LaunchLane lane, boolean takeoff) {
            float forward = tick < forwardTicks ? 1 : brake ? -1 : 0;
            return new ControlFrame(forward, tick < strafeTicks ? strafe : 0, sprint, takeoff,
                    false, lane.yaw() + yawOffset, takeoff ? ControlPhase.TAKEOFF : ControlPhase.AIRBORNE,
                    takeoff ? FrameGuard.GROUNDED : FrameGuard.AIRBORNE);
        }
    }
    private record ObstacleStateKey(long x, long y, long z, long vx, long vy, long vz,
                                    int yaw, boolean horizontal, boolean vertical,
                                    int lane, int guide, int guideIndex, RouteMode routeMode,
                                    long launchLateral, long launchLateralSpeed,
                                    long launchLongitudinal) {
        static ObstacleStateKey from(ObstacleNode node) {
            ParkourState s = node.state;
            Vec3d side = ControlInput.strafeDirection(node.launch.lane().heading());
            return new ObstacleStateKey(q(s.feetPosition().x, 0.04), q(s.feetPosition().y, 0.04),
                    q(s.feetPosition().z, 0.04), q(s.velocity().x, 0.025), q(s.velocity().y, 0.025),
                    q(s.velocity().z, 0.025), Math.round(s.yaw() / 4), s.horizontalCollision(),
                    s.verticalCollision(), node.launch.lane().id(), node.guide.id(), node.guideIndex,
                    node.launch.approachMode(), q(node.launch.lane().lateralError(
                            node.launch.state().feetPosition()), 0.12),
                    q(node.launch.state().velocity().dotProduct(side), 0.04),
                    q(node.launch.lane().distanceBeforeEdge(
                            node.launch.state().feetPosition()), 0.20));
        }
    }
    private record DiversityKey(int lane, int guideShape, int guideIndex, int side,
                                String collision, long touchdownX, long touchdownZ,
                                RouteMode routeMode, int yawBucket) {
        static DiversityKey from(ObstacleNode node) {
            LaunchLane lane = node.launch.lane();
            Vec3d sideAxis = ControlInput.strafeDirection(lane.heading());
            int side = (int) Math.signum(node.state.feetPosition().subtract(lane.takeoffPoint()).dotProduct(sideAxis));
            double ticks = Math.max(1, Math.min(16,
                    (node.state.feetPosition().y - lane.landingAnchor().feet().y + 1) / 0.08));
            return new DiversityKey(lane.id(), node.guide.waypoints().size(), node.guideIndex,
                    side, node.collisionSignature,
                    q(node.state.feetPosition().x + node.state.velocity().x * ticks, 0.25),
                    q(node.state.feetPosition().z + node.state.velocity().z * ticks, 0.25),
                    node.launch.approachMode(), Math.round(node.state.yaw() / 8));
        }
    }
    private static long q(double value, double quantum) { return Math.round(value / quantum); }
}

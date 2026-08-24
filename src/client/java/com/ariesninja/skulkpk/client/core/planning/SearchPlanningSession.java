package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.analysis.StandableSurface;
import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import com.ariesninja.skulkpk.client.core.physics.ParkourPhysics;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import net.minecraft.util.math.Box;
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
    private static final long DIRECT_BUDGET_NANOS = 200_000_000L;
    private static final long LANDING_RESERVE_NANOS = 100_000_000L;
    private static final double PLAYER_RADIUS = 0.3;
    private static final double STANDARD_TRIGGER_MINIMUM = -0.10;
    private static final double MAXIMUM_REACH_TRIGGER_MINIMUM = -(PLAYER_RADIUS * 2 + 0.35);
    private static final double OBSTACLE_GUIDE_CLEARANCE = 0.22;
    private static final double RUN_UP_INCREMENT = 0.25;
    private static final int MAX_LANES = 36;
    private static final int MAX_LAUNCH_STATES = 96;
    private static final int MAX_GROUND_TICKS = 48;
    private static final int MAX_STOPPING_TICKS = 40;

    private final PlanningRequest request;
    private final ParkourPhysics physics;
    private final long startedNanos;
    private final long wallDeadline;
    private final long directDeadline;
    private final long obstacleDeadline;
    private final Map<String, Integer> rejectionCounts = new LinkedHashMap<>();
    private final Map<ObstacleStateKey, ObstacleNode> obstacleNext = new HashMap<>();

    private LandingZone landingZone;
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
    private long obstacleStartedNanos;
    private long obstacleFinishedNanos;
    private boolean prepared;
    private boolean cancelled;
    private Candidate best;
    private List<ObstacleNode> obstacleFrontier = List.of();
    private int obstacleIndex;
    private int obstacleDepth;

    public SearchPlanningSession(PlanningRequest request) { this(request, new ParkourPhysics()); }

    SearchPlanningSession(PlanningRequest request, ParkourPhysics physics) {
        this.request = Objects.requireNonNull(request);
        this.physics = Objects.requireNonNull(physics);
        startedNanos = System.nanoTime();
        wallDeadline = startedNanos + request.policy().maximumWallNanos();
        long reserve = Math.min(LANDING_RESERVE_NANOS,
                Math.max(1, request.policy().maximumWallNanos() / 5));
        directDeadline = startedNanos + Math.min(DIRECT_BUDGET_NANOS,
                Math.max(1, request.policy().maximumWallNanos() - reserve));
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
        while (System.nanoTime() < sliceDeadline) {
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
        landingZone = LandingZone.build(request.problem().landingRegion(), request.player());
        lanes = buildLaunchLanes();
        obstacleGuides = buildObstacleGuides();
        launchStates = buildLaunchStates();
        directTrials = buildDirectTrials();
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
        return List.copyOf(result);
    }

    private void beginObstacle() {
        stage = PlanningStage.OBSTACLE;
        obstacleStartedNanos = System.nanoTime();
        List<ObstacleNode> roots = new ArrayList<>();
        for (LaunchState launch : obstacleLaunchStates()) {
            for (ObstacleGuide guide : obstacleGuides.getOrDefault(launch.lane().id(), List.of())) {
                int guideIndex = guide.advance(launch.state().feetPosition(), 0);
                float guideYaw = boundedYaw(launch.state().yaw(), guide.desiredYaw(
                        launch.state().feetPosition(), guideIndex, launch.lane()));
                float guidedStrafe = guide.side() == 0 ? 0 : guide.side() * 0.35f;
                for (float strafe : new float[]{0, guidedStrafe}) {
                    ControlFrame jump = frame(1, strafe, launch.state().sprinting(), true, guideYaw,
                            ControlPhase.TAKEOFF, FrameGuard.GROUNDED);
                    ParkourState next = advance(launch.state(), jump);
                    if (next != null && !next.onGround()) {
                        int nextGuideIndex = guide.advance(next.feetPosition(), guideIndex);
                        roots.add(new ObstacleNode(next, launch, guide, nextGuideIndex,
                                null, jump, 1, collisionSignature(next),
                                obstacleScore(next, launch.lane(), guide, nextGuideIndex)));
                    }
                }
            }
        }
        obstacleFrontier = retainDiverse(roots);
        if (obstacleFrontier.isEmpty()) recordRejection("No physically viable flight root left takeoff support.");
    }

    private List<LaunchState> obstacleLaunchStates() {
        Map<Integer, List<LaunchState>> byLane = new LinkedHashMap<>();
        for (LaunchState launch : launchStates) {
            // Near-side fringe anchors expand direct maximum-reach geometry. They must not
            // multiply obstacle homotopies when a stable core exists, or the 256-state beam is
            // diluted across duplicate neo routes before it clears the pillar.
            if (!launch.lane().landingAnchor().core() && !landingZone.coreAnchors().isEmpty()) continue;
            byLane.computeIfAbsent(launch.lane().id(), ignored -> new ArrayList<>()).add(launch);
        }
        byLane.values().forEach(states -> states.sort(Comparator
                .comparing((LaunchState state) -> !state.state().sprinting())
                .thenComparing(Comparator.comparingDouble((LaunchState state) ->
                        Math.abs(state.lane().lateralError(state.state().feetPosition()))).reversed())
                .thenComparing(Comparator.comparingDouble(this::forwardSpeed).reversed())
                .thenComparingDouble(this::launchCost)));
        List<LaunchState> selected = new ArrayList<>();
        for (int rank = 0; selected.size() < 32; rank++) {
            boolean added = false;
            for (List<LaunchState> states : byLane.values()) {
                if (rank >= states.size()) continue;
                selected.add(states.get(rank));
                added = true;
                if (selected.size() >= 32) break;
            }
            if (!added) break;
        }
        return List.copyOf(selected);
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
        if (now >= obstacleDeadline) {
            obstacleFinishedNanos = now;
            if (best != null) { stage = PlanningStage.LANDING_VALIDATION; return null; }
            return rejected(PlanRejectionReason.SEARCH_TIMEOUT,
                    "Trajectory search reached its obstacle-stage limit before a stable route was validated. "
                            + rejectionSummary());
        }
        if (obstacleFrontier.isEmpty()) {
            obstacleFinishedNanos = now;
            if (best != null) { stage = PlanningStage.LANDING_VALIDATION; return null; }
            return rejected(PlanRejectionReason.UNREACHABLE, "No stable route was found. " + rejectionSummary());
        }
        if (obstacleDepth >= maximumHorizon()) {
            obstacleFrontier.forEach(node -> recordRejection(classifyMiss(node.state, node.launch.lane())));
            obstacleFrontier = List.of();
            return null;
        }
        if (obstacleIndex >= obstacleFrontier.size()) {
            obstacleFrontier = retainDiverse(new ArrayList<>(obstacleNext.values()));
            obstacleNext.clear();
            obstacleIndex = 0;
            obstacleDepth++;
            return null;
        }
        expandObstacle(obstacleFrontier.get(obstacleIndex++));
        return null;
    }

    private void expandObstacle(ObstacleNode node) {
        for (ControlFrame action : obstacleActions(node)) {
            ParkourState next = advance(node.state, action);
            if (next == null) continue;
            flightStatesExpanded++;
            if (next.feetPosition().y < minimumLandingY() - 4) {
                recordRejection("Undershot: trajectory fell below the landing region.");
                continue;
            }
            if (next.onGround()) {
                if (!support(next).targetSupported()) {
                    recordRejection("Wrong support: first grounded contact was outside the connected target.");
                    continue;
                }
                keep(stopAndValidate(node.launch, reconstructObstacleFrames(node, action),
                        reconstructObstacleStates(node, next), PlanningStage.OBSTACLE));
                continue;
            }
            int guideIndex = node.guide.advance(next.feetPosition(), node.guideIndex);
            ObstacleNode child = new ObstacleNode(next, node.launch, node.guide, guideIndex,
                    node, action, node.airTicks + 1,
                    mergeCollisionSignature(node.collisionSignature, next),
                    obstacleScore(next, node.launch.lane(), node.guide, guideIndex));
            ObstacleStateKey key = ObstacleStateKey.from(child);
            ObstacleNode existing = obstacleNext.get(key);
            if (existing == null || child.score < existing.score) obstacleNext.put(key, child);
            else statesDeduplicated++;
        }
    }

    private Candidate simulateDirect(LaunchState launch, AirSchedule schedule) {
        List<ControlFrame> frames = groundFrames(launch);
        List<ParkourState> states = replayGroundStates(launch);
        ParkourState state = launch.state();
        boolean airborne = false;
        for (int tick = 0; tick < maximumHorizon(); tick++) {
            ControlFrame action = schedule.frame(tick, launch.lane(), !airborne);
            state = advance(state, action);
            if (state == null) return null;
            frames.add(action);
            states.add(state);
            airborne |= !state.onGround();
            if (airborne && state.onGround()) {
                if (!support(state).targetSupported()) {
                    recordRejection(classifyMiss(state, launch.lane()));
                    return null;
                }
                return stopAndValidate(launch, frames, states, PlanningStage.DIRECT);
            }
        }
        recordRejection(classifyMiss(state, launch.lane()));
        return null;
    }

    private Candidate stopAndValidate(LaunchState launch, List<ControlFrame> baseFrames,
                                      List<ParkourState> baseStates, PlanningStage sourceStage) {
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
            return new Candidate(launch, List.copyOf(frames), List.copyOf(states), method,
                    landingZone.isCore(end.boundingBox(), end.feetPosition()), margin,
                    end.velocity().horizontalLength(), 1, inputChurn(frames), launchCost(launch),
                    horizontalContacts, obstacleClearance,
                    !requiresPreparedMomentum(launch.lane()) || launch.state().sprinting(), sourceStage);
        }
        recordRejection("Unstable landing: target contact could not remain supported through settling.");
        return null;
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
        for (StandableSurface surface : request.problem().reachableTakeoffs()) {
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
                    LaunchLane edgeLane = new LaunchLane(id++, surface,
                            takeoff.subtract(side.multiply(0.20)),
                            takeoff.add(side.multiply(0.20)), takeoff, corridor, heading, yaw(heading),
                            maximumReach ? MAXIMUM_REACH_TRIGGER_MINIMUM : STANDARD_TRIGGER_MINIMUM,
                            0.42, runUp, anchor);
                    result.add(edgeLane);

                    // A pillar touching the exposed edge leaves no flight time to establish
                    // lateral clearance. Keep the real edge lane, but also create deterministic
                    // early-jump lanes on the supported runway. This is equivalent to the
                    // physically useful geometry produced when the last runway block is absent,
                    // without requiring the player to edit the course.
                    if (firstBlockingObstacle(edgeLane) != null) {
                        for (double setback : new double[]{0.45, 0.75, 1.05}) {
                            Vec3d early = takeoff.subtract(heading.multiply(setback));
                            StandableSurface earlySurface = supportingApproachSurface(early, surface.topY());
                            if (earlySurface == null || !fullSupport(early, surface.topY()) || !bodyClear(early)) {
                                continue;
                            }
                            double earlyRunUp = traceRunUp(early, heading, surface.topY());
                            Box earlyCorridor = playerBox(early).union(
                                    playerBox(early.subtract(heading.multiply(earlyRunUp))));
                            result.add(new LaunchLane(id++, earlySurface,
                                    early.subtract(side.multiply(0.20)), early.add(side.multiply(0.20)),
                                    early, earlyCorridor, heading, yaw(heading), -0.12, 0.32,
                                    earlyRunUp, anchor));
                        }
                    }
                }
            }
        }
        return sortLanes(result).stream().limit(MAX_LANES).toList();
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

    /**
     * Builds fixed left/right homotopy guides around the first body-sized obstruction on a
     * lane. The beam still owns the controls, but it no longer has to discover which side of
     * a wall to commit to from an undifferentiated distance heuristic.
     */
    private Map<Integer, List<ObstacleGuide>> buildObstacleGuides() {
        Map<Integer, List<ObstacleGuide>> result = new LinkedHashMap<>();
        int guideId = 0;
        for (LaunchLane lane : lanes) {
            List<ObstacleGuide> guides = new ArrayList<>();
            guides.add(new ObstacleGuide(guideId++, 0, List.of()));
            BlockingObstacle blocking = firstBlockingObstacle(lane);
            if (blocking != null) {
                for (int sideSign : new int[]{-1, 1}) {
                    double lateral = sideSign < 0
                            ? blocking.minimumLateral - OBSTACLE_GUIDE_CLEARANCE
                            : blocking.maximumLateral + OBSTACLE_GUIDE_CLEARANCE;
                    double near = Math.max(0.12, blocking.minimumLongitudinal - 0.18);
                    double far = Math.min(blocking.routeLength - 0.10,
                            blocking.maximumLongitudinal + 0.18);
                    if (far <= near + 0.05) continue;
                    Vec3d side = ControlInput.strafeDirection(lane.heading());
                    Vec3d nearPoint = lane.takeoffPoint().add(lane.heading().multiply(near))
                            .add(side.multiply(lateral));
                    Vec3d farPoint = lane.takeoffPoint().add(lane.heading().multiply(far))
                            .add(side.multiply(lateral));
                    guides.add(new ObstacleGuide(guideId++, sideSign,
                            List.of(nearPoint, farPoint)));
                }
            }
            result.put(lane.id(), List.copyOf(guides));
        }
        return Map.copyOf(result);
    }

    private BlockingObstacle firstBlockingObstacle(LaunchLane lane) {
        double routeLength = horizontalDistance(lane.takeoffPoint(), lane.landingAnchor().feet());
        Vec3d side = ControlInput.strafeDirection(lane.heading());
        BlockingObstacle best = null;
        for (Box raw : request.problem().nearbyCollision()) {
            // Floors are support, not lateral route obstructions. Low head blocks remain in
            // the direct/contact solver; only body-height walls get side guides.
            if (raw.maxY <= Math.min(lane.takeoffPoint().y, lane.landingAnchor().feet().y) + 0.20
                    || raw.minY >= lane.takeoffPoint().y + 1.65) continue;
            Box expanded = raw.expand(PLAYER_RADIUS + 0.06, 0, PLAYER_RADIUS + 0.06);
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
            BlockingObstacle candidate = new BlockingObstacle(raw, minLong, maxLong,
                    minSide, maxSide, routeLength);
            if (best == null || candidate.minimumLongitudinal < best.minimumLongitudinal) best = candidate;
        }
        return best;
    }

    private List<Vec3d> horizontalCorners(Box box, double y) {
        return List.of(new Vec3d(box.minX, y, box.minZ), new Vec3d(box.minX, y, box.maxZ),
                new Vec3d(box.maxX, y, box.minZ), new Vec3d(box.maxX, y, box.maxZ));
    }

    private List<LaunchState> buildLaunchStates() {
        List<LaunchState> result = new ArrayList<>();
        PlayerSnapshot player = request.player();
        double footArea = player.boundingBox().getLengthX() * player.boundingBox().getLengthZ();
        for (LaunchLane lane : lanes) {
            double remaining = lane.distanceBeforeEdge(player.feetPosition());
            boolean supported = SupportResolver.overlapArea(player.boundingBox(), player.feetPosition().y,
                    request.problem().approachRegion()) > footArea * 0.45;
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
            for (double runUp = 0; runUp <= lane.availableRunUp() + 1.0E-6; runUp += RUN_UP_INCREMENT) {
                // A zero-prefix launch does not need to balance at the safe edge. Stage it at
                // the fully supported interior of a normal block, then jump from rest. This is
                // still a zero-run-up route, but it is safe for closed-loop positioning.
                double stagingOffset = runUp == 0
                        ? Math.min(0.20, lane.availableRunUp()) : runUp;
                Vec3d staging = lane.takeoffPoint().subtract(lane.heading().multiply(stagingOffset));
                if (!fullSupport(staging, lane.takeoffSurface().topY()) || !bodyClear(staging)) continue;
                ParkourState start = ParkourState.at(player, staging, Vec3d.ZERO, lane.yaw(), true, false);
                collectLaunchStates(result, start, lane, staging, false, false, runUp);
                collectLaunchStates(result, start, lane, staging, false, true, runUp);
                if (result.size() >= MAX_LAUNCH_STATES * 2) break;
            }
        }
        // An immediate corner obstruction needs lateral momentum before flight. Generate a
        // compact family of deterministic late diagonal prefixes from the same runway; the
        // obstacle beam remains flight-only and does not wander over ground states.
        for (LaunchLane lane : lanes) {
            if (obstacleGuides.getOrDefault(lane.id(), List.of()).stream()
                    .anyMatch(guide -> guide.side() != 0)) collectCornerLaunchStates(result, lane);
        }
        Map<String, LaunchState> unique = new LinkedHashMap<>();
        result.stream().sorted(Comparator.comparing(LaunchState::startsFromCurrentState).reversed()
                .thenComparingDouble(this::launchCost).thenComparingInt(state -> state.lane().id())
                .thenComparingInt(LaunchState::jumpTick))
                .forEach(state -> unique.putIfAbsent(launchKey(state), state));
        return prioritizeLaunchStates(new ArrayList<>(unique.values()));
    }

    private void collectCornerLaunchStates(List<LaunchState> output, LaunchLane lane) {
        if (lane.availableRunUp() < 0.70) return;
        double runUp = lane.availableRunUp();
        Vec3d staging = lane.takeoffPoint().subtract(lane.heading().multiply(runUp));
        if (!fullSupport(staging, lane.takeoffSurface().topY()) || !bodyClear(staging)) return;
        ParkourState initial = ParkourState.at(request.player(), staging, Vec3d.ZERO,
                lane.yaw(), true, false);
        for (int side : new int[]{-1, 1}) {
            for (double turnDistance : new double[]{0.55, 0.85, 1.15, 1.45}) {
                ParkourState state = initial;
                List<ControlInput> prefix = new ArrayList<>();
                for (int tick = 0; tick <= MAX_GROUND_TICKS; tick++) {
                    if (lane.inTriggerInterval(state.feetPosition())
                            && Math.abs(lane.lateralError(state.feetPosition())) >= 0.42
                            && bodyClear(state.feetPosition())) {
                        output.add(new LaunchState(initial, state, lane, staging, runUp,
                                tick, List.copyOf(prefix), false));
                    }
                    if (lane.distanceBeforeEdge(state.feetPosition()) < lane.triggerMinimum()) break;
                    float strafe = lane.distanceBeforeEdge(state.feetPosition()) <= turnDistance ? side : 0;
                    ControlInput input = new ControlInput(1, strafe, true, false, false, lane.yaw());
                    ParkourState next;
                    try { next = physics.tick(request.world(), state, input); }
                    catch (ParkourPhysics.UnsupportedPhysicsStateException exception) { break; }
                    SupportResolver.Contact contact = SupportResolver.resolve(next.boundingBox(),
                            next.feetPosition(), next.onGround(), request.problem().landingRegion(),
                            request.problem().approachRegion(), request.world());
                    if (!next.onGround() || next.horizontalCollision()) break;
                    prefix.add(input);
                    if (contact.kind() != SupportKind.TAKEOFF) {
                        if (contact.kind() == SupportKind.NONE
                                && lane.inTriggerInterval(next.feetPosition())
                                && Math.abs(lane.lateralError(next.feetPosition())) >= 0.42
                                && bodyClear(next.feetPosition())) {
                            output.add(new LaunchState(initial, next, lane, staging, runUp,
                                    tick + 1, List.copyOf(prefix), false));
                        }
                        break;
                    }
                    state = next;
                }
            }
        }
    }

    private List<LaunchState> prioritizeLaunchStates(List<LaunchState> states) {
        Map<Integer, List<LaunchState>> byLane = new LinkedHashMap<>();
        for (LaunchState state : states) byLane.computeIfAbsent(state.lane().id(), ignored -> new ArrayList<>())
                .add(state);
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
        return distance >= 2.45 || rise >= 0.45 || hasLowOverhead(lane);
    }

    private boolean hasLowOverhead(LaunchLane lane) {
        Vec3d forward = lane.heading().multiply(Math.min(1.35,
                horizontalDistance(lane.takeoffPoint(), lane.landingAnchor().feet())));
        Box launchBody = playerBox(lane.takeoffPoint());
        Box headSweep = launchBody.union(launchBody.offset(forward).offset(0, 0.75, 0));
        return request.world().collisionBoxes(headSweep).stream().anyMatch(box ->
                box.minY >= lane.takeoffPoint().y + 1.65 && box.minY <= lane.takeoffPoint().y + 2.55);
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
            ControlInput input = new ControlInput(1, 0, sprint, false, false, lane.yaw());
            ParkourState next;
            try { next = physics.tick(request.world(), state, input); }
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

    private List<ControlFrame> obstacleActions(ObstacleNode node) {
        float targetYaw = node.guide.desiredYaw(node.state.feetPosition(),
                node.guideIndex, node.launch.lane());
        float yaw = boundedYaw(node.state.yaw(), targetYaw);
        float inward = node.guide.side() == 0 ? 0 : node.guide.side() * 0.45f;
        boolean sprint = node.state.sprinting();
        return List.of(frame(1, 0, sprint, false, yaw, ControlPhase.AIRBORNE, FrameGuard.AIRBORNE),
                frame(0, 0, false, false, yaw, ControlPhase.AIRBORNE, FrameGuard.AIRBORNE),
                frame(-1, 0, false, false, yaw, ControlPhase.AIRBORNE, FrameGuard.AIRBORNE),
                frame(1, inward, sprint, false, yaw, ControlPhase.AIRBORNE, FrameGuard.AIRBORNE),
                frame(1, -1, sprint, false, boundedYaw(node.state.yaw(), targetYaw - 8),
                        ControlPhase.AIRBORNE, FrameGuard.AIRBORNE),
                frame(1, 1, sprint, false, boundedYaw(node.state.yaw(), targetYaw + 8),
                        ControlPhase.AIRBORNE, FrameGuard.AIRBORNE));
    }

    private List<ObstacleNode> retainDiverse(List<ObstacleNode> nodes) {
        if (nodes.isEmpty()) return List.of();
        nodes.sort(OBSTACLE_ORDER);
        Map<DiversityKey, ObstacleNode> buckets = new LinkedHashMap<>();
        for (ObstacleNode node : nodes) buckets.putIfAbsent(DiversityKey.from(node), node);
        diversityBuckets = Math.max(diversityBuckets, buckets.size());
        LinkedHashSet<ObstacleNode> retained = new LinkedHashSet<>();
        buckets.values().stream().sorted(OBSTACLE_ORDER).limit(request.policy().beamWidth()).forEach(retained::add);
        for (ObstacleNode node : nodes) {
            if (retained.size() >= request.policy().beamWidth()) break;
            retained.add(node);
        }
        return List.copyOf(retained);
    }

    private double obstacleScore(ParkourState state, LaunchLane lane,
                                 ObstacleGuide guide, int guideIndex) {
        Vec3d predicted = predictedTouchdown(state);
        Vec3d delta = lane.landingAnchor().feet().subtract(predicted);
        Vec3d side = ControlInput.strafeDirection(lane.heading());
        Vec3d guideTarget = guide.target(guideIndex, lane);
        double guideDistance = horizontalDistance(state.feetPosition(), guideTarget)
                + guide.remainingDistance(guideIndex, lane);
        double guideCrossTrack = guide.crossTrackError(state.feetPosition(), guideIndex, lane);
        double clearance = bodyObstacleClearance(state);
        return Math.abs(delta.dotProduct(lane.heading())) * 8 + Math.abs(delta.dotProduct(side)) * 14
                + Math.abs(predicted.y - lane.landingAnchor().feet().y) * 4
                + guideDistance * 1.5 + guideCrossTrack * 5
                + state.velocity().horizontalLength() * 0.3
                + (state.horizontalCollision() ? 12 : 0)
                + Math.max(0, 0.18 - clearance) * 30;
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
        EnvelopeResult envelope = deriveEnvelope(candidate, Math.min(wallDeadline, now + LANDING_RESERVE_NANOS));
        List<Vec3d> positioning = candidate.launch.startsFromCurrentState()
                ? List.of() : positioningPath(candidate.launch.stagingPosition());
        boolean immediate = candidate.launch.startsFromCurrentState()
                && envelope.envelope.containsPosition(request.player().feetPosition())
                && Math.abs(MathHelper.wrapDegrees(request.player().yaw() - envelope.envelope.desiredYaw()))
                <= envelope.envelope.yawTolerance();
        long directNanos = Math.max(0, (directFinishedNanos == 0 ? now : directFinishedNanos) - startedNanos);
        long obstacleNanos = obstacleStartedNanos == 0 ? 0
                : Math.max(0, (obstacleFinishedNanos == 0 ? now : obstacleFinishedNanos) - obstacleStartedNanos);
        long finished = System.nanoTime();
        PlanMetrics metrics = new PlanMetrics(finished - startedNanos,
                directEvaluations + flightStatesExpanded, candidate.launch.runUpLength(), candidate.landingSpeed,
                envelope.robustness, candidate.edgeMargin, statesDeduplicated, launchStates.size(),
                candidate.stoppingMethod, candidate.stoppingMethod == StoppingMethod.SNEAK,
                candidate.sourceStage == PlanningStage.DIRECT ? "direct_stable" : "obstacle_stable",
                directNanos, obstacleNanos, Math.max(0, finished - validationStart),
                flightStatesExpanded, diversityBuckets, coreTouchdowns, fringeTouchdowns, candidate.sourceStage);
        return new PlanningTickResult.Ready(new MovementPlan(positioning, candidate.frames,
                samples(candidate.states), request.problem().landingRegion(), candidate.launch.stagingPosition(),
                candidate.launch.state().feetPosition(), immediate, candidate.launch.startsFromCurrentState(),
                envelope.envelope, metrics,
                request.problem().worldFingerprint(), problemBounds(), landingZone, candidate.launch.lane(),
                candidate.launch.lane().landingAnchor().feet(), candidate.sourceStage));
    }

    private EnvelopeResult deriveEnvelope(Candidate candidate, long deadline) {
        LaunchState launch = candidate.launch;
        ParkourState baseline = launch.initialState();
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
            if (replayStable(shifted, candidate.frames, 0)) {
                robustness++; minLong = Math.min(minLong, offset); maxLong = Math.max(maxLong, offset);
            }
        }
        for (double offset : new double[]{-0.08, -0.04, 0.04, 0.08}) {
            if (System.nanoTime() >= deadline) break;
            ParkourState shifted = pose(baseline, baseline.feetPosition().add(side.multiply(offset)),
                    baseline.velocity(), baseline.yaw(), baseline.onGround(), baseline.sprinting(), false);
            if (replayStable(shifted, candidate.frames, 0)) {
                robustness++; minLateral = Math.min(minLateral, offset); maxLateral = Math.max(maxLateral, offset);
            }
        }
        float yawTolerance = 1;
        for (float offset : new float[]{-2, 2, -4, 4}) {
            if (System.nanoTime() >= deadline) break;
            if (replayStable(baseline, candidate.frames, offset)) {
                robustness++; yawTolerance = Math.max(yawTolerance, Math.abs(offset));
            }
        }
        for (double offset : new double[]{-0.03, 0.03}) {
            if (System.nanoTime() >= deadline) break;
            ParkourState shifted = pose(baseline, baseline.feetPosition(),
                    baseline.velocity().add(launch.lane().heading().multiply(offset)), baseline.yaw(),
                    baseline.onGround(), baseline.sprinting(), false);
            if (replayStable(shifted, candidate.frames, 0)) {
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
            if (frame.sneak() && (!state.onGround() || !support(state).targetSupported())) return false;
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
            state = physics.tick(request.world(), state, input);
            states.add(state);
        }
        return states;
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
                    : airborne ? ControlPhase.AIRBORNE : ControlPhase.RUN_UP;
            result.add(new TrajectorySample(index, state.feetPosition(), state.velocity(), state.boundingBox(),
                    state.onGround(), state.horizontalCollision(), state.verticalCollision(), phase,
                    contact.kind(), contact.overlapArea()));
        }
        return List.copyOf(result);
    }

    private ParkourState advance(ParkourState state, ControlFrame frame) {
        try {
            return physics.tick(request.world(), state, new ControlInput(frame.forward(), frame.strafe(),
                    frame.sprint(), frame.jump(), frame.sneak(), frame.desiredYaw()));
        } catch (ParkourPhysics.UnsupportedPhysicsStateException exception) {
            recordRejection("The route entered unsupported fluid or a climbable block.");
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
                yaw, onGround, sprinting, preserveJump && state.jumpUsed(), false, false,
                state.elapsedTicks(), state.baseMovementSpeed(), state.jumpStrength(), state.stepHeight(),
                state.gravity(), state.activeEffects());
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
        for (Box obstacle : request.problem().nearbyCollision()) {
            // Collision tops supporting the current feet are floors, not side obstacles.
            if (obstacle.maxY <= feetY + 0.05
                    || obstacle.minY >= body.maxY - 0.05) continue;
            double dx = Math.max(0, Math.max(obstacle.minX - body.maxX, body.minX - obstacle.maxX));
            double dz = Math.max(0, Math.max(obstacle.minZ - body.maxZ, body.minZ - obstacle.maxZ));
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

    private String collisionSignature(ParkourState state) {
        return state.horizontalCollision() ? "H" : state.verticalCollision() ? "V" : "N";
    }

    private String mergeCollisionSignature(String signature, ParkourState state) {
        String next = collisionSignature(state);
        return next.equals("N") || signature.endsWith(next) ? signature : signature + next;
    }

    private String launchKey(LaunchState state) {
        return state.lane().id() + ":" + q(state.state().feetPosition().x, 0.02) + ":"
                + q(state.state().feetPosition().z, 0.02) + ":" + q(state.state().velocity().x, 0.01)
                + ":" + q(state.state().velocity().z, 0.01);
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
        if (candidate != null && (best == null || CANDIDATE_ORDER.compare(candidate, best) < 0)) best = candidate;
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
            // Transit correctness comes before landing aesthetics. A centered landing reached
            // through a 0.03-block pillar graze is less executable than a stable fringe landing
            // with a clean flight corridor.
            .comparingInt((Candidate c) -> c.horizontalContacts)
            .thenComparing(Comparator.comparingDouble((Candidate c) -> c.obstacleClearance).reversed())
            .thenComparing(c -> !c.momentumReady)
            .thenComparing(c -> !c.coreLanding)
            .thenComparing(c -> !c.launch.startsFromCurrentState())
            .thenComparing(Comparator.comparingDouble((Candidate c) -> c.edgeMargin).reversed())
            .thenComparingDouble(c -> c.landingSpeed)
            .thenComparing(Comparator.comparingInt((Candidate c) -> c.robustness).reversed())
            .thenComparing(c -> c.stoppingMethod == StoppingMethod.SNEAK)
            .thenComparingDouble(c -> c.launch.startsFromCurrentState() ? -1 : c.launch.runUpLength())
            .thenComparingDouble(c -> c.launchCost).thenComparingInt(c -> c.churn)
            .thenComparingInt(c -> c.frames.size());
    private static final Comparator<ObstacleNode> OBSTACLE_ORDER = Comparator
            .comparingDouble((ObstacleNode n) -> n.score).thenComparingInt(n -> n.launch.lane().id())
            .thenComparingInt(n -> n.airTicks).thenComparingDouble(n -> n.state.feetPosition().x)
            .thenComparingDouble(n -> n.state.feetPosition().z);

    private record Candidate(LaunchState launch, List<ControlFrame> frames, List<ParkourState> states,
                             StoppingMethod stoppingMethod, boolean coreLanding, double edgeMargin,
                             double landingSpeed, int robustness, int churn, double launchCost,
                             int horizontalContacts, double obstacleClearance, boolean momentumReady,
                             PlanningStage sourceStage) {}
    private record DirectTrial(LaunchState launch, int scheduleIndex) {}
    private record StoppingOutcome(List<ControlFrame> frames, List<ParkourState> states) {}
    private record EnvelopeResult(LaunchEnvelope envelope, int robustness) {}
    private record BlockingObstacle(Box box, double minimumLongitudinal, double maximumLongitudinal,
                                    double minimumLateral, double maximumLateral, double routeLength) {}
    private record ObstacleGuide(int id, int side, List<Vec3d> waypoints) {
        ObstacleGuide { waypoints = List.copyOf(waypoints); }

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
            return index < waypoints.size() ? waypoints.get(index) : lane.landingAnchor().feet();
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
            result += horizontalDistanceStatic(waypoints.getLast(), lane.landingAnchor().feet());
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
                                int airTicks, String collisionSignature, double score) {}
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
                                    int lane, int guide, int guideIndex) {
        static ObstacleStateKey from(ObstacleNode node) {
            ParkourState s = node.state;
            return new ObstacleStateKey(q(s.feetPosition().x, 0.04), q(s.feetPosition().y, 0.04),
                    q(s.feetPosition().z, 0.04), q(s.velocity().x, 0.025), q(s.velocity().y, 0.025),
                    q(s.velocity().z, 0.025), Math.round(s.yaw() / 4), s.horizontalCollision(),
                    s.verticalCollision(), node.launch.lane().id(), node.guide.id(), node.guideIndex);
        }
    }
    private record DiversityKey(int lane, int guide, int guideIndex, int side,
                                String collision, long touchdownX, long touchdownZ) {
        static DiversityKey from(ObstacleNode node) {
            LaunchLane lane = node.launch.lane();
            Vec3d sideAxis = ControlInput.strafeDirection(lane.heading());
            int side = (int) Math.signum(node.state.feetPosition().subtract(lane.takeoffPoint()).dotProduct(sideAxis));
            double ticks = Math.max(1, Math.min(16,
                    (node.state.feetPosition().y - lane.landingAnchor().feet().y + 1) / 0.08));
            return new DiversityKey(lane.id(), node.guide.id(), node.guideIndex, side, node.collisionSignature,
                    q(node.state.feetPosition().x + node.state.velocity().x * ticks, 0.25),
                    q(node.state.feetPosition().z + node.state.velocity().z * ticks, 0.25));
        }
    }
    private static long q(double value, double quantum) { return Math.round(value / quantum); }
}

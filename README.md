# Skulk Client

Skulk is a Minecraft 1.21.4 Fabric client mod that analyzes a selected parkour landing, searches Minecraft movement physics for a stable route, and executes the resulting per-tick controls.

## Controls

- `G` selects and analyzes the block under the crosshair.
- `H` plans and executes the current valid selection.
- `C` cancels execution and clears the selection.

Selecting a new target cancels planning; selection is refused once movement has begun. Cancelling, disconnecting, disabling the mod, invalidating world geometry, failing execution, or timing out releases every movement input.

## Parkour engine checkpoint

The client pipeline is split into immutable, bounded stages:

1. `JumpAnalyzer` captures exact player state, collision-shape surfaces, distance-legal takeoff edges, the connected flat approach components behind them, and the selected block's connected same-height landing platform in a `JumpProblem`. The approach region remains available even when only its front block is within the 4.3-block level/upward jump bound, while reachable terrain below the course no longer floods the 256-surface planning input. Downward targets are not subject to the 4.3-block bound. Vertical reach has no block-count veto: partial shapes such as a block plus trapdoor reach the production simulator, which decides whether their exact collision top is attainable.
2. `ParkourPhysics` is the one immutable dry-land physics kernel used by production planning, airborne recovery, landing control, and the automated fixtures. It preserves captured movement, jump-strength, gravity, step-height, and effect state; ground and air acceleration; sprint-jump impulse; slipperiness/drag; collision clipping; step-up; and head/side contact without cloning a live player or reading mutable player state.
3. `SearchPlanningSession` builds fixed launch lanes and fixed landing zones, then uses two bounded stages. The first deterministically simulates real and shortest-run-up launch prefixes against compact direct air schedules. Direct trials are wave-ordered across launch geometry so center lanes cannot starve shifted-edge or diagonal families. Each takeoff retains a safe interior anchor and the mutually nearest legal fringe anchors, allowing a four-block target shifted sideways to pair the appropriate takeoff and landing edges. Long, rising, and low-ceiling routes prioritize launch states whose sprint and velocity have already formed. Only a true maximum platform gap may use Minecraft's bounded retained-ground edge-departure tick; ordinary and obstacle lanes commit at a supported edge. Only direct misses enter a diverse, flight-only obstacle beam. Body-height obstructions add fixed left/right route guides, earlier supported jump lanes, and a small deterministic family of late diagonal prefixes, allowing neos to form lateral clearance before reaching a pillar rather than relying on a perfect edge clip. Planning receives at most 10 ms per client tick and retains the 750 ms wall-time limit, with time reserved for final landing and launch-envelope validation.
4. `MovementPlan` records the fixed route heading, whether it depends on the captured current state, measured launch envelope, trigger interval, settle anchor, guarded controls, collision/support-aware trajectory, landing zone, and stage metrics. `TrajectoryStepController` positions in lane coordinates, aligns once, triggers jump from observed edge-relative state, and uses the same production kernel for suffix-aware airborne recovery.
5. `SupportResolver` classifies support from the player bounding box and collision-shape tops. `LandingStabilityTracker` requires eight slow grounded ticks followed by six settling ticks, so legal edge overlap can succeed while contact followed by sliding or falling cannot.

Landing anchors never move during search. Core and near-side fringe anchors are both exposed to direct search; transit clearance and collision freedom rank before landing aesthetics, while core support wins between otherwise comparable routes. Any positive, persistently stable target support is valid. Launch prefixes start from the real state, then increase available lead-up in quarter-block increments; shared ground motion does not compete for obstacle-beam capacity. The 4.3-block level/upward edge-distance limit remains a coarse horizontal bound, descending routes have no analogous pre-filter, and vertical reach is entirely simulation-governed.

Collision contact is not itself a rejection: head hits and useful side contact remain valid when the resulting state can still reach a stable landing. Before launch, the controller rotates toward one fixed route heading and corrects longitudinal/lateral error with forward/back and strafe. Synthetic zero-run launches stage at a fully supported interior point; a launch derived from the real current state is never chased after it becomes stale. It enters a simulation-proven envelope rather than chasing an exact point, is bounded to 30 ticks, and replans once from the actual state if alignment misses. Jump timing is keyed to the real trigger interval rather than a frame number. Requesting the jump key no longer counts as takeoff: the takeoff frame remains pending until Minecraft reports upward motion or a valid upward head collision. A falling walk-off fails as a missed takeoff instead of entering airborne recovery, and non-maximum routes may not approach into supportless coyote states.

Planning owns and releases movement inputs while its incremental snapshot is being solved. A validated immediate route applies its first command on the planning handoff tick, avoiding an unmodelled idle tick. Each non-takeoff control advances exactly once; takeoff advances only after physical acknowledgement. In flight, the validated control is applied unchanged while observed position and velocity remain inside a conservative deadband. Genuine divergence or proximity to a body-height obstacle invokes recovery. Under obstacle threat, planned, neutral, brake, left, and right alternatives receive a coordinated two-tick lookahead before the nominal suffix is replayed. Planning ranks minimum clearance along interpolated swept player boxes rather than only discrete tick endpoints. This prevents a locally attractive correction or between-tick pillar graze from destroying a valid neo.

Sneak has a hard safety boundary. It is illegal in positioning, run-up, takeoff, and airborne frames, and execution forcibly releases it unless the real player is grounded on the connected target region. After landing, natural friction and counter-input simulations are preferred; sneak is used for at most a short final stopping window only when all tested non-sneak options would leave support.

Rendering now distinguishes the connected landing region and core footprints (red), fixed settle anchor, launch corridor/trigger edge and takeoff footprint (blue), positioning path (gray), required momentum only (cyan), and predicted flight/landing trajectory (green).

The obsolete live-player simulator, all-in-one beam boundary, and fake fixture physics were removed. The extracted physics kernel retains the LiquidBounce-derived notice. Server and license behavior are outside this checkpoint.

## Verification

Run the automated checkpoint from the repository root:

```powershell
.\gradlew.bat clean build --warning-mode all
```

The JUnit suite runs the production kernel against an in-memory collision-shape world and covers standing, walk/sprint acceleration, captured jump strength and gravity, jump/sprint-jump impulse, drag, collision clipping, head contact, effects/state independence, ordinary direct jumps, a true four-air-block gap whose only legal takeoff retains a four-block runway, a laterally shifted four-block target, lower-floor approach pruning, stable fringe acceptance, unrestricted downward analysis, a simulator-validated 1.2522-block rise, a production-budget three-block rise, the supplied-map headhitter, two-surface neo, and one-block full-height-column neo geometry, separated standard/maximum trigger windows, physical takeoff acknowledgement, walk-off rejection, connected platforms, fixed anchors, wrong-yaw alignment policy, obstacle-clearance recovery, airborne correction deadbands, plan-preserving flight control, airborne-sneak prevention, obstruction/timeout, planning lifecycle, world invalidation, execution failure/timeout/cancellation, duplicate-start refusal, and terminal cleanup.

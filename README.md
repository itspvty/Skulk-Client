# Skulk Client

Skulk is a Minecraft 1.21.4 Fabric client mod that analyzes a selected parkour landing, searches Minecraft movement physics for a stable route, and executes the resulting per-tick controls.

## Controls

- `G` selects and analyzes the block under the crosshair.
- `H` plans and executes the current valid selection.
- `C` cancels execution and clears the selection.

Selecting a new target cancels planning; selection is refused once movement has begun. Cancelling, disconnecting, disabling the mod, invalidating world geometry, failing execution, or timing out releases every movement input.

## Parkour engine checkpoint

The client pipeline is split into immutable, bounded stages:

1. `JumpAnalyzer` captures exact player state, collision-shape surfaces, reachable takeoffs, and the selected block's connected same-height landing platform in a `JumpProblem`.
2. `ParkourPhysics` is the one immutable dry-land physics kernel used by production planning, airborne recovery, landing control, and the automated fixtures. It preserves captured movement, jump-strength, gravity, step-height, and effect state; ground and air acceleration; sprint-jump impulse; slipperiness/drag; collision clipping; step-up; and head/side contact without cloning a live player or reading mutable player state.
3. `SearchPlanningSession` builds fixed launch lanes and fixed landing zones, then uses two bounded stages. The first deterministically simulates real and shortest-run-up launch prefixes against compact direct air schedules. Only direct misses enter a diverse, flight-only obstacle beam. Planning receives at most 10 ms per client tick and retains the 750 ms wall-time limit, with time reserved for final landing and launch-envelope validation.
4. `MovementPlan` records the fixed route heading, whether it depends on the captured current state, measured launch envelope, trigger interval, settle anchor, guarded controls, collision/support-aware trajectory, landing zone, and stage metrics. `TrajectoryStepController` positions in lane coordinates, aligns once, triggers jump from observed edge-relative state, and uses the same production kernel for suffix-aware airborne recovery.
5. `SupportResolver` classifies support from the player bounding box and collision-shape tops. `LandingStabilityTracker` requires eight slow grounded ticks followed by six settling ticks, so legal edge overlap can succeed while contact followed by sliding or falling cannot.

Landing anchors never move during search. Full-footprint core anchors with a 0.05-block support margin rank ahead of legal partial-support fringe anchors, preventing a short route from displacing a centered landing with a corner hang. Launch prefixes start from the real state, then increase available lead-up in quarter-block increments; shared ground motion does not compete for obstacle-beam capacity. The existing one-block rise and 4.3-block edge-distance limits remain coarse bounds; production physics is the final authority.

Collision contact is not itself a rejection: head hits and useful side contact remain valid when the resulting state can still reach a stable landing. Before launch, the controller rotates toward one fixed route heading and corrects longitudinal/lateral error with forward/back and strafe. Synthetic zero-run launches stage at a fully supported interior point; a launch derived from the real current state is never chased after it becomes stale. It enters a simulation-proven envelope rather than chasing an exact point, is bounded to 30 ticks, and replans once from the actual state if alignment misses. Jump timing is keyed to the real trigger interval rather than a frame number.

Planning owns and releases movement inputs while its incremental snapshot is being solved. A validated immediate route applies its first command on the planning handoff tick, avoiding an unmodelled idle tick. Each executed control then advances exactly once; the controller no longer skips to a nearby future sample. In flight, the validated control is applied unchanged while observed position and velocity remain inside a conservative deadband. Only genuine divergence invokes recovery, and planned, neutral, brake, left, and right alternatives are then scored by replaying the complete remaining flight and stable-landing outcome. This prevents a locally attractive one-tick correction from destroying a valid long jump or neo.

Sneak has a hard safety boundary. It is illegal in positioning, run-up, takeoff, and airborne frames, and execution forcibly releases it unless the real player is grounded on the connected target region. After landing, natural friction and counter-input simulations are preferred; sneak is used for at most a short final stopping window only when all tested non-sneak options would leave support.

Rendering now distinguishes the connected landing region and core footprints (red), fixed settle anchor, launch corridor/trigger edge and takeoff footprint (blue), positioning path (gray), required momentum only (cyan), and predicted flight/landing trajectory (green).

The obsolete live-player simulator, all-in-one beam boundary, and fake fixture physics were removed. The extracted physics kernel retains the LiquidBounce-derived notice. Server and license behavior are outside this checkpoint.

## Verification

Run the automated checkpoint from the repository root:

```powershell
.\gradlew.bat clean build --warning-mode all
```

The JUnit suite runs the production kernel against an in-memory collision-shape world and covers standing, walk/sprint acceleration, captured jump strength and gravity, jump/sprint-jump impulse, drag, collision clipping, head contact, effects/state independence, ordinary direct jumps, a one-block-runway four-block regression, one-block rises, connected platforms, fixed anchors, core landing preference, wrong-yaw alignment policy, airborne correction deadbands, plan-preserving flight control, airborne-sneak prevention, obstruction/timeout, planning lifecycle, world invalidation, execution failure/timeout/cancellation, duplicate-start refusal, and terminal cleanup.

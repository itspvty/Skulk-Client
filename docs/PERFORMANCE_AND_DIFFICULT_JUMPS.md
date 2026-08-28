# Performance and difficult-jump checkpoint — 2026-08-28

This pass follows the archived [45/45 live-client checkpoint](LIVE_CLIENT_CHECKPOINT.md).
It changes only the client and its development test harness; Minecraft version, server/main,
and license sources are unchanged.

## Frame-time work

The initial real-client profile reproduced the reported spikes. In the same process,
four-block staging took up to 278.71 ms, neo planning 281.03 ms, and the off-center neo
controller 249.42 ms. Jump success did not imply acceptable frame time.

The fixes address the measured work:

- Support classification no longer computes an unused, 32-direction edge-margin scan on
  every simulated state. Landing ranking still computes its actual margin when needed.
- The client captures immutable cells in 2 ms slices. A single bounded, cancellable worker
  indexes the shapes and performs preparation, trajectory search, and launch validation.
  No live Minecraft world API is read on that worker. Unknown snapshot space is blocking,
  not air; capture is capped at 131,072 cells.
- Execution uses the same indexed geometry, with live shape/block-state/property
  fingerprints checked before commands. World changes and cancellation discard work.
- Staging uses state-only production physics, numeric deduplication keys, and bounded
  first-action yaw refinement instead of duplicating camera branches throughout the tree.

The search retains a 750 ms ordinary soft limit and 1.5-second collision limit. Capture and
queueing have a separate bounded allowance. These are not render-thread waits. The 2 ms
capture target is cooperative, not a hard real-time guarantee: class loading, allocation,
JVM scheduling, and other Minecraft/rendering work can still cause frame-time variation.

Final repeated-run timings: four-block planning/execution maxima 1.00/4.88 ms, neo
1.53/3.80 ms, and headhitter 0.98/3.86 ms. The first cold one-block trial still recorded
34.48/10.62 ms, so this is not an all-ticks latency guarantee. These measure Skulk's
client tick work, not GPU frame time or whole-game FPS. Full beam recording is opt-in
(`-PtrialBeams=true`) and disabled for timing runs to avoid instrumentation allocation.

## Difficult avoidance routes

Two-block-deep obstacles exposed two distinct failures: routing around internal seams of
adjacent blocks, and a coarse staging sample that left too little room at the far corner.

The solver now follows the merged configuration-space silhouette. If its conservative
flight search fails, a bounded tighter-clearance beam preserves distinct outcome cells
and rejects forbidden contacts immediately. A nominal landing seeds local shooting:
nearby supported staging poses and jump headings are replayed through the entire ground
approach and flight. No position or velocity is invented in midair. Refinement starts at
the first completed near-miss landing layer, before the remaining search budget is spent
on already-missed falling trajectories.

Standard contact routes still require all eight launch perturbation checks. A difficult
avoidance route can fall back to an explicitly labelled **precision launch**:

| Tested launch perturbation | Standard | Precision |
| --- | --- | --- |
| Longitudinal/lateral position | +/-0.025 blocks | +/-0.005 blocks |
| Forward velocity | +/-0.02 blocks/tick | +/-0.003 blocks/tick |
| Initial yaw | +/-1.5 degrees | +/-0.35 degrees |

All eight checks and the stable nominal landing are still required. Execution and staging
revalidation use the selected narrower bounds. The requested takeoff heading must fit
within one actual 12-degree camera command; a launch-state tolerance cannot silently
permit a different jump command. These are sampled robustness checks, not a proof of every
combined perturbation. Narrower launch bounds are a deliberate capability tradeoff, not
a relaxation of collision checking or the stable-landing requirement.

## Regular ladders

The production kernel now implements Minecraft 1.21.4's pre-move ladder velocity clamps
and post-move climbing impulse, checked against the pinned vanilla bytecode and actual
player motion. Ladder calibration matched actual positions exactly in the focused run.

Supported workflow: jump from supported ground, catch a regular ladder, climb onto a
grounded target platform. Select the platform, not a ladder rung. The live fixtures cover
two approach directions and a wrong-facing start. Starting/hanging on ladders,
ladder-to-ladder endpoints, vines, scaffolding, trapdoor-assisted climbing, and fluids
remain outside the validated domain.

## Verification record

- Clean JDK 21 build: 90 JUnit tests, zero failures/errors/skips, no source warnings.
- Reachability audit: 67 shipping top-level units, six roots, zero unreachable units,
  missing internal references, test artifacts in the jar, or access-widener entries.
- Fast real-client calibration: coordinate selection/cancellation and both movement
  calibrations passed after the clean build.
- Focused normal-timing runs: 12/12 (neo, double neo, floating double neo, ladder catch,
  three repetitions each); a subsequent cold-start double/floating-double run passed 6/6.
- Final 23-fixture, three-repeat normal-timing matrix: 69/69 completed checks passed
  across two runs. The first run (`07-53-00`) ended on world disconnect after 51 checks;
  the remaining 18 passed in the `15-21-41` run. This was not one uninterrupted run.
- Archived artifact SHA256:
  `EAEE2C73188827027B8355183A4395D8D756FBEB0D23B4951CFECEA16452DD11`.

Every successful jump must remain grounded and slow on the target for 40 ticks after all
inputs are released. No airborne sneak is allowed. Head fixtures must record real ceiling
contact; ladder fixtures must record actual climbing. The consumed-input simulator error
must stay below 0.002 blocks/tick. Fixture setup can place blocks/reset the player only
before a trial; execution uses vanilla movement and normal Skulk controls without pose or
velocity intervention.

These are generated disposable courses, not the user's original map. Repeated exact
fixtures establish repeatability for those geometries and starts, not a universal success
rate for arbitrary double neos or ladders. The harness and its setup commands are absent
from the shipping jar. Earlier measurements and iteration traces are archived under
`.agent/evidence/2026-08-28-performance-pass/preclean`; the previous checkpoint jar remains
separately archived and recoverable.

Reproduce with JDK 21:

```powershell
.\gradlew.bat clean build --warning-mode all
.\tools\audit-client.ps1 -JdkPath $env:JAVA_HOME
.\gradlew.bat runClientGameTest "-PtrialCases=coordinate-selection,movement,fractional-strafe"
.\gradlew.bat runParkourLive "-PtrialCases=all" "-PtrialRepeats=3"
```

The first normal-client launch may require dismissing Minecraft's accessibility welcome
screen. `clean` removes generated worlds/results, so archive evidence first. Fabric Loader's
absent Kotlin-output-directory warning comes from empty Kotlin source sets in the
development classpath, not from a source compilation failure.

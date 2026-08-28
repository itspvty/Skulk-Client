# Live-client reliability checkpoint — 2026-08-28

## Camera-independent selection

Use `/skulk select 76 -59 42`, close chat, then press `H`. Coordinates identify the
landing block, not the player's feet. `C` cancels and clears the selection; `G` still works.
The command is client-local, does not move the camera, and uses the same analysis and
busy-state guards as crosshair selection. Invalid/unloaded targets clear stale geometry.

## What the real client exposed

These changes were driven by vanilla player traces, then checked with regression tests:

| Measured failure | Correction |
| --- | --- |
| Ceiling contact retained upward simulated velocity; one-tick Y-velocity error was about 0.412. | Clear clipped vertical velocity on head contact, not just landing. |
| Pillar corners resolved horizontal axes in the opposite order to Minecraft. | Resolve the larger horizontal displacement first. |
| Fractional simulated strafe became a full digital key press in game. | Canonicalize forward/strafe to the inputs the keybinding adapter can actually issue. |
| Long-runway and lateral launch states were starved by early candidates. | Per-lane prefix budgets, velocity-bearing launch priority, and canonical obstacle lane allocation. |
| Obstacle launches hung by roughly 0.0067 blocks and failed their required support perturbations. | Check the launch support/body-clearance tube before admitting roots to the obstacle beam. No landing inset was added. |
| Nearby starting positions oscillated or entered the approach with stale launch offsets. | Cumulative-cost staging control, finer bounded yaw/key actions, and nominal replay plus eight fresh variants before rebasing the execution reference. |
| The repeated four-block test reached the final grounded tick about 0.000003 blocks/tick below the nominal minimum speed and aborted. | Separate 0.00001 velocity-comparison precision from physical launch tolerances; reject meaningful underspeed as before. Recorded-value and full delayed-loop regressions cover this boundary. |
| Landing braking was incorrectly projected as holding backward for too long; sneak clamping falsely erased velocity. | Project counter-input then release over the full stability window; clamp sneak displacement without clearing residual velocity. |

One direct-jump exception preserves Minecraft's final retained-ground tick when the actual
and predicted takeoff states agree. It commits jump immediately and cannot issue another
ground approach command. Obstacle launches still require supported takeoff states.

## How the checkpoint is tested

The development-only `gametest` source set provides two runners:

- `runClientGameTest`: creates an isolated integrated world using Fabric's client test API.
- `runParkourLive`: runs the same fixtures with ordinary Minecraft client/server scheduling,
  without GameTest timing overrides, in an exact-path, marked disposable save.

Both invoke the actual coordinate command and H keybinding. Setup may place blocks and
reset the starting player state. After execution begins, the harness does not teleport,
change velocity, or replace vanilla movement/collisions. Observational mixins record
consumed inputs, command epochs, actual motion, predictions, and contact flags.

A jump passes only if the executor succeeds, all movement keys are released, no airborne
sneak occurs, and vanilla collision shapes confirm grounded, slow target support for 40
additional ticks after release. Headhitter cases must record an actual ceiling contact.
Physics calibration compares position and velocity independently of the jump success oracle.

## Verification

- Clean JDK 21 build: 81 JUnit tests, zero failures, errors, or skips; no source warnings.
- Bytecode audit: 65 shipping top-level classes reachable from six runtime roots; no
  missing internal references, no test-harness classes/dependencies in the jar, zero
  access-widener entries.
- Server/main, license, and Minecraft-version diffs are empty.
- Freeze negative test: the test render thread was deliberately stalled; a thread dump
  and failed status were written and the disposable JVM exited. No unrelated process
  was terminated. This checks render-thread stalls, not total JVM/OS failure.
- The first full normal-timing matrix passed 42/45; all three four-block attempts exposed
  the velocity-boundary regression above. After correction, the focused four-block rerun
  passed 3/3. The final complete matrix passed **45/45**: 36 jumps and nine selection/physics
  checks, across 15 fixtures repeated three times, using normal client tick scheduling.

Final run: `2026-08-28T06-21-17.624966400Z-realtime` (02:21–02:25 EDT).
It covers gaps one through four, wrong-facing starts, headhitters from two starts, full and
floating pillar neos, and left/right/back neo starts. No airborne sneak occurred.
The artifact is `build/libs/Skulk-0.1.jar`, SHA-256
`84456BAE43B34005DC941C4FD7C915E94AADA13357F28F46088BC28E3E5B8A98`.
That exact jar and final traces are also archived under
`.agent/evidence/2026-08-28-live-pass/final` before further development.

Reproduce with JDK 21:

```powershell
.\gradlew.bat clean build --warning-mode all
.\tools\audit-client.ps1 -JdkPath $env:JAVA_HOME
.\gradlew.bat runClientGameTest "-PtrialCases=coordinate-selection,movement,fractional-strafe"
.\gradlew.bat runParkourLive "-PtrialCases=all" "-PtrialRepeats=3"
```

The normal client's first launch may require dismissing Minecraft's accessibility welcome
screen. All subsequent selection is by coordinates. Results, plans, and per-tick traces are
in `build/parkour-results`; screenshots are in each runner's `screenshots` directory.
`clean` deletes these generated files. This session's earlier evidence was copied to
`.agent/evidence/2026-08-28-live-pass`, deliberately untracked.

## Limits and follow-up

These are generated regression courses, not the original supplied map. Repeating an exact
fixture measures repeatability, not the success probability over arbitrary geometry or
starting states. No universal neo/headhitter success rate is claimed.

The search is bounded but incomplete: a rejection does not prove physical impossibility.
Planning targets 10 ms slices; preparation, individual validation, and staging operations
are not preemptible and can exceed a frame's work budget. A hard frame-time guarantee and
broader stair/effect/sprint-transition parity need further measurement. Next coverage should
use copied original-map geometry, more collision shapes, both forced obstacle sides, and
systematic start-state perturbations through this same live harness.

Fabric Loader's warning about absent Kotlin output directories is external development
classpath noise from empty Kotlin source sets. The test runner and watchdog are excluded
from the shipping mod; server and license behavior are unchanged.

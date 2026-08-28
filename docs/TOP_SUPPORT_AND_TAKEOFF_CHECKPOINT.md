# Tight takeoffs and ladder-top progression — 2026-08-28

Client-only follow-up to [the niche/ladder checkpoint](NICHE_AND_LADDER_CHECKPOINT.md).
G/H/C, coordinate selection, Minecraft 1.21.4, server code and license behavior are unchanged.
The previous 120-live-check artifact is preserved under `.agent/evidence/2026-08-28-niche-pass/final`.

## Reproduced causes and changes

The initial 14-case real-client run failed eight checks: three tight execution starts and
all five ladder-top selections. This was not just an extra jump tick to add globally.

- Oblique approach physics used double trigonometry/arithmetic where vanilla uses float
  values and `MathHelper`'s sine lookup. Small accumulated errors crossed a one-sided
  takeoff envelope. Ground/air acceleration, friction, input scaling, sprint modifier and
  sprint-jump direction now preserve the pinned game's operation order.
- Staging admitted ordinary routes without replaying residual velocity, and sometimes
  compared the resulting approach against an unrevised reference. Every admitted state now
  replays the complete route. Its reference is rebased even when the final launch already
  fits the old envelope. Before spending the last supported command, execution predicts
  departure and the following jump together. It stops while still supported if they disagree.
- Sprint is stateful, not equivalent to holding the sprint key. Continued forward movement,
  hard versus soft collision, and Minecraft's seven-tick double-tap timer affect the jump
  impulse. `PlayerSnapshot`/`ParkourState` now carry the timer and previous forward input.
  One read-only client access-widener entry exposes `ticksLeftToDoubleTapSprint`; no code
  writes it or changes vanilla sprint policy. Its retained bytecode reader is audited.
  Captured hunger/creative-flight permission also gates sprint; pressing the key must not
  create unavailable acceleration or a sprint-jump boost in simulation.
- Crouched physical dimensions can outlast the slowdown flag. The kernel now checks whether
  standing is possible when predicting the release epoch, instead of inferring slowdown
  solely from the old bounding-box height.
- Rung tops were rejected by a minimum-width test and center-based body clearance. Shared
  `SupportGeometry` now subtracts body-expanded obstacles from the positive-support feet
  region. It handles partial collision tops generally; there is no ladder-pattern override.
  Anchors inside the backing wall are removed. Narrow-support launch selection reserves
  distinct stances, including both ends, before filling slots with duplicate headings.
- Ladder attachment recovery gets its own bounded 120-observation climb window. A late
  catch no longer consumes the short ballistic recovery tail while legitimately climbing.

Exact mechanics were inspected in the locally mapped 1.21.4 `ClientPlayerEntity.tickMovement`,
`LivingEntity` and `Entity` bytecode. Versioned names are documented in
[Yarn 1.21.4 build 8](https://maven.fabricmc.net/docs/yarn-1.21.4%2Bbuild.8/net/minecraft/client/network/ClientPlayerEntity.html).
The live oblique and forward-double-tap calibration fixtures test actual inputs and motion.

## Test protocol and new geometry

All live tests run the normal 20 Hz client in the marked disposable test world. Setup may
place blocks and position the player; after H, movement comes only from the shipping
executor and vanilla physics. Success requires 40 slow, grounded ticks on the actual
target collision shape after all keys are released. The selected rung can itself be the
endpoint. Tower legs are individually selected/executed with no position, velocity, or
camera reset between legs.

Fixture setup restores food and saturation with a vanilla command in the disposable world,
clears the effect, and verifies full food before measurement. It also checks every commanded
ladder after neighbor updates: the rung must exist, have the exact requested facing, and
pass vanilla's support/placement check. The recorded `.preflight.json` lists these checks.
Per-tick traces and summaries include food level; losing sprint permission invalidates a
normal capability trial instead of being counted as a solver regression. No feeding or
block replacement occurs during execution.

The interrupted `2026-08-28T21-21-55.942789900Z-realtime` batch is excluded from acceptance:
its persistent survival player ran out of hunger. Its late sprint mismatches and route
failures are diagnostic evidence of missing test preconditions, not capability measurements.

| Family | Geometry and variation |
| --- | --- |
| Offset four-block | Start runway x=-3..0, z=0, top y=101; target (5,100,1); four positions/yaws plus an already-moving start |
| Short runway | One start block (0,100,0), target (5,100,0); start x=.3/.5/.7 and differing yaw; an additional two-block runway |
| Rung landing | Target west-facing rung (2,101,0), tall backing column x=3; ground runway x=-2..0 |
| Rung launch | West-facing rung (0,100,0), tall backing column x=1; jump to (0,100,-3); rotated north-facing variant |
| Ascending rung | West-facing rungs (0,100,0) and (0,101,2), with a continuous backing wall |
| Corner transfer | West-facing rung (0,100,0) to south-facing rung (1,101,1), around a tall pillar |
| Tower progression | Four one-level rises around pillar x=1,z=0: west → south/north → east → north/south → west; both directions, no inter-leg resets |
| Physics calibration | Oblique float/LUT sequence; forward tap, release, second tap+jump, expiration, then non-sprint jump |
| Exploratory opposite face | West rung (0,100,0) to east rung (2,101,0), one isolated high rung on each side of a tall pillar |

The catalog now has 59 fixtures, including the previous 40. `-PtrialCases=all` also runs the
exploratory opposite-face probe; its presence is deliberate and does not promise a solution.

The trace oracle was strengthened in this pass: both raw-key and issued-command prediction
errors count toward failure. Earlier diagnostics used the *post-input sprint state* as a
simulated sprint key, which hid automatic double-tap sprint. That was exposed by a .0763-block
command error in a tower transfer. Historical trace residuals should not be interpreted as
proof of input-history parity. Current tests capture state before input processing.

## Verification

Verified on 2026-08-28 with Corretto 21.0.5:

- `clean build compileGametestJava --warning-mode all`, with `-Xlint:deprecation` enabled:
  **113 JUnit tests, zero failures/errors/skips**, no source warnings. Tests executed rather
  than reporting `NO-SOURCE`.
- Bytecode audit: 70 shipping top-level units, six runtime roots, zero unreachable units,
  missing internal references or test-harness leaks. The one sprint-timer access-widener
  entry has a retained read-only client reference.
- All 115 clean-compiled client class files match the real-client-tested class files by
  SHA-256. The clean artifact therefore uses the same client bytecode as these live runs.
- Whitespace checks pass. Server/main Java, license sources and Minecraft version have
  empty diffs. The shared-resource access-widener change names only a client field.

| Normal 20 Hz live run | Result |
| --- | --- |
| `2026-08-28T21-31-20.765864300Z-realtime`, full 59-fixture catalog | 58/59; only the isolated opposite-face rung was rejected |
| `2026-08-28T21-37-56.221361800Z-realtime`, 19 supported fixtures repeated twice | 38/38 |
| Combined | **96/97 checks passed**, one explicitly unresolved capability probe |

The second run repeats all nine tight-start variants, the seven supported rung-top/tower
fixtures, and offset/side/back ladder catches. Together these runs provide **27/27 tight
jumps** and **21/21 rung-top/tower trials**. Six tower trials contain four successive legs
each: **24/24 ascending transfers**, without inter-leg pose/velocity/camera resets. The
previous 40-fixture catalog also passed in full. No automatic replan was needed in either
final run. Intermediate development runs are not included in these totals.

All 97 preflights had food 20, saturation 20 and a supported start. All 128 commanded ladder
placements survived with correct facing and backing support. Minimum food remained 20
throughout every measured trial. No unguarded airborne sneak occurred. The maximum recorded
one-tick position error was `4.9651e-16` blocks and velocity error `5.5527e-17` blocks/tick,
including both consumed-key and issued-command checks. This establishes parity for the
tested sequences, not for every possible status effect or movement state.

Performance remains bounded in these measurements, but is not a zero-spike guarantee:

| Measured family (across both runs) | Max client planning tick | Max client execution tick |
| --- | ---: | ---: |
| Nine tight variants | 4.75 ms | 5.01 ms |
| Seven rung-top/tower fixtures | 4.99 ms | 6.23 ms |
| First cold ordinary jump | 31.57 ms | 11.01 ms |
| First cold offset-ladder trial in the repeat process | 12.77 ms | 17.30 ms |

The repeated side-ladder catch also had a 14.71 ms execution outlier. These are Skulk tick
measurements, not GPU/whole-frame timings. JVM startup/JIT/GC variability remains visible.
The live development loader warns about absent Kotlin output directories for Java-only
source sets; this is external classpath noise, not a source compilation warning.

Reproduction uses `runParkourLive -PtrialCases=all -PtrialRepeats=1` for the complete catalog.
It intentionally exits nonzero while `ladder-top-back` remains unresolved. For the repeated
supported matrix, pass the comma-separated names of the nine tight variants, seven
rung-top/tower fixtures excluding `ladder-top-back`, and
`ladder-catch-offset,ladder-catch-side,ladder-catch-back`, with `-PtrialRepeats=2`.

Evidence (including the excluded hunger-contaminated batch) is preserved under
`.agent/evidence/2026-08-28-top-and-timing-pass/preclean`; 2,216 result files totaling
280,811,691 bytes were copied and checked before cleaning. The final directory contains the
clean build log, JUnit XML/report, audit and matching distributable. These generated files
remain untracked.

Artifact: `build/libs/Skulk-0.1.jar`, also preserved in
`.agent/evidence/2026-08-28-top-and-timing-pass/final/Skulk-0.1.jar`.

SHA-256: `F05AC61A03FE15616A5EB3F4EECAF8E3AC9001D799E56F3EC92FF888BE55C928`.

## Boundaries

These are generated fixtures, not the original user map or a universal jump guarantee.
Standing on a rung top is supported; starting while hanging/climbing is not yet validated.
Vines, scaffolding, open-trapdoor climbing, liquids, custom player scale and all effect
combinations remain outside the tested domain. A single isolated opposite-face rung one
level up remains an exploratory search limitation; it must not be conflated with the
previously verified back-face catches on a multi-rung column.

No experimental tangent-runway fallback was retained: it added search work without solving
that opposite-face fixture. The successful changes use shared support geometry, input-state
physics and stance diversity instead.

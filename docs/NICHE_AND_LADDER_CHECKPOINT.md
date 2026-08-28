# Niche geometry and ladder assistance — 2026-08-28

Client-only follow-up to the archived [performance/double-neo checkpoint](PERFORMANCE_AND_DIFFICULT_JUMPS.md).
G/H/C, coordinate selection, Minecraft 1.21.4, server/main and license behavior are unchanged.
The previous 90-test/69-live-check jar remains archived separately.

## Mechanics and search changes

- Overhead broadphase considers the simulated jump-height range of the complete body.
  A block that is clear during walking can still obstruct ascent. The reported cross section
  `AABA / AAAE / SAAA` is tested as start `(0,100,0)`, destination `(3,101,0)`, blocker `(2,102,0)`.
- Supported perimeter lanes let a route establish lateral position before jumping. Fixed
  headings, actual acceleration prefixes and bounded control-knot shooting complement the
  local flight beam; no fixture name or named neo pattern controls the shipping solver.
- Regular ladders are intermediate attachment goals. Search follows their actual backing
  faces, preserves both obstacle sides, and can continue down to a usable ladder's bottom
  instead of rejecting at a height relative to the final platform. The ballistic 4.3-block
  level/upward prefilter does not veto potential ladder assistance; physics still decides reach.
- Attachment, ascent, holding and exit use the same short-horizon production controller in
  planning and execution. Climb jump input is not a second ground-jump impulse. A grounded
  top-rung contact can continue onto the destination without being mistaken for success or
  an unrelated wrong landing.
- A useful nominal catch seeds immediate local refinement. This avoids wasting the hard
  deadline on hundreds of alternatives before validating an already promising family.
  Standard or precision attachment routes still require all eight launch checks; execution
  uses the matching bounds. These are sampled axis checks, not a proof of every combined error.
- The exact last retained-ground launch is independent of later contact type. Float-scale
  position noise up to `1e-5` is tolerated, not a widened movement envelope; tests reject `2e-5`.
- Search remains on the immutable-world worker, with the existing 750 ms ordinary soft and
  1.5-second collision limits. Normal physics ticks avoid redundant pose collision queries
  when dimensions have not changed.

## What Shift actually does

Shift does not attract the player or enlarge a ladder's attachment volume. Vanilla checks
the **feet block**, clamps ladder motion before moving, and can stop downward movement
while the player holds the ladder. The new input's ladder hold, previous input's movement
slowing, and next tick's crouched bounding box are different parts of the tick sequence.
The kernel captures the sneaking-speed attribute and preserves that ordering.

The runtime boundary permits sneak only on actual target ground or while a `LADDER`
command observes real regular-ladder attachment. Losing attachment clears sneak immediately.
Positioning, runway, takeoff and ordinary flight cannot request it. Ladder holding is
considered alongside movement/climb commands, not forced on every catch.

Research was checked on 2026-08-28. Mojang's [Taking Inventory: Ladder](https://www.minecraft.net/en-us/article/taking-inventory-ladder)
(2019-01-04) describes catching falls and holding position with sneak. Exact Java mechanics
were inspected in the locally mapped 1.21.4 `LivingEntity.applyClimbingSpeed`,
`applyMovementInput`, `ClientPlayerEntity.tickMovement` and `PlayerEntity.updatePose` bytecode,
using [Yarn 1.21.4 build 8](https://maven.fabricmc.net/docs/yarn-1.21.4%2Bbuild.8/net/minecraft/entity/LivingEntity.html)
for the pinned names. Real-client hold/release/sideways calibration checks the resulting motion.

For additional course ideas, the author's [MCPK nomenclature](https://github.com/drakou111/MCPK_Nomenclature)
distinguishes partial-block, ceiling, wall and orientation variants. It targets **1.8.9**, so
its mechanics were not imported. Microsoft's [parkour walkthrough](https://learn.microsoft.com/en-us/minecraft/creator/documents/parkourworldwalkthrough?view=minecraft-bedrock-stable)
includes constrained and corner-ladder courses but targets **Bedrock**; it was used for ideas,
not as Java physics authority.

## Expanded real-client catalog

Seventeen additions bring the catalog to 40 fixtures, keeping the original 23:

| Family | New fixtures |
| --- | --- |
| Raised overhang | X direction, Z direction, off-center/backward-facing start |
| Different reach/landing shapes | Offset four-block gap, block-plus-trapdoor rise, lower slab, fence, stair |
| Delayed overhead contact | Ceiling encountered after takeoff |
| Ladder assistance | Five-block catch (column and single rung), offset five-block catch, adjacent-face and back-face catches, both rotated |
| Ladder mechanics | Shift hold, sideways input while held, release and resumed climbing |

The ladder fixtures deliberately distinguish reach from a long descent. Start blocks are
at Y=100 (feet Y=101). The five-block destination is `(6,100,0)`, with its front ladder
at X=5; the single-rung variant has only `(5,100,0)` and no lower catching rungs. The
offset destination moves one block sideways. The adjacent-face fixture climbs a pillar
at `(4,102,0)` using ladders at Z=1, Y=98..102. The back-face fixture must pass the pillar
at X=3, catch its hidden X=4 ladder column at Y=98..102, and climb to the Y=103 top.
Those last two are also rotated into the Z direction. A successful lower catch on a tall
column does not establish that the same maneuver works with a single high back-face rung.

All jump trials use the shipping coordinate command and H executor with normal vanilla
20 Hz client/server scheduling. Setup resets a marked disposable world only before the trial.
After execution begins, the harness never changes position, velocity or collision behavior.
Success requires 40 slow grounded ticks on the selected block after movement is released,
cleaned-up keys, actual ladder attachment where requested, and one-tick physics errors
below 0.002 blocks. Thus a transient catch, a top-rung stop short of the destination, or
a later fall does not count as a pass.

## Verification

- Clean JDK 21 build with deprecation lint: 101 JUnit tests, zero failures/errors/skips,
  no source compilation warnings.
- Shipping audit: 69 top-level units, six runtime roots, zero unreachable units,
  missing internal references, test-harness leaks or access-widener entries.
- Server/main, license and Minecraft-version diffs are empty; whitespace checks pass.
- Post-clean real-client coordinate selection and both movement calibrations passed.
- Initial 39-case normal-timing smoke passed 39/39. The final normal-timing matrix
  passed 117/117 (39 fixtures, three repeats each) in one uninterrupted run,
  `2026-08-28T16-16-22.759726100Z-realtime`. No automatic replans occurred.
  The stricter single-rung fixture then passed 3/3 in
  `2026-08-28T16-27-27.490484900Z-realtime`, including its cold start.
  Combined: **120/120 checks across 40 scenarios** (105 jump executions and 15
  coordinate/calibration checks). This was two runs, not one 120-check process.
- Maximum consumed-input one-tick position/velocity errors in that matrix were
  `2.92104e-5` blocks / `1.59490e-5` blocks per tick. Unguarded airborne sneak: zero.
- Artifact SHA256: `08F362206EB487264EC3CAB8B6C1728974E2D575DD02DEC752DA6544D1EC3FFB`
  verified against both `build/libs/Skulk-0.1.jar` and the archived copy under
  `.agent/evidence/2026-08-28-niche-pass/final/Skulk-0.1.jar`.

Selected maxima from the 117-check run (milliseconds):

| Fixture | Client planning work | Client execution work | Background search |
| --- | ---: | ---: | ---: |
| Four-block gap | 1.01 | 4.70 | 62.66 |
| Double neo | 1.48 | 5.55 | 449.94 |
| Raised overhang | 0.91 | 3.62 | 163.41 |
| Five-block ladder catch | 0.97 | 3.30 | 86.67 |
| Adjacent-face ladder | 1.34 | 4.24 | 316.20 |
| Back-face ladder | 1.20 | 3.79 | 322.89 |

The first cold one-block trial still took 33.85 ms in planning and 10.42 ms in execution.
These are Skulk client-tick timings, not complete GPU frame times or a universal latency
guarantee. The background search is not a render-thread wait. Full beam tracing was disabled.
The separate cold single-rung run recorded maxima of 17.54 ms client planning, 5.29 ms
client execution and 184.74 ms background search; all three executions passed.

Iteration evidence is archived under `.agent/evidence/2026-08-28-niche-pass/preclean`.
Final traces, summaries, test XML, run logs and the jar are in its sibling `final` directory.
Individual successful probes are not a substitute for the final regression run. Empty
Kotlin output directories can still trigger Fabric Loader's development-classpath warning;
that is external to source compilation and is not a shipping-jar dependency change.

## Limits

These are generated fixtures, not the user's original map or a universal success-rate
claim. Very narrow routes may still exhaust the budget or fail safely from a different
staging state. Routes currently start on supported ground and finish on a grounded
platform. Hanging starts, ladder-to-ladder endpoints, multiple ladder-transfer chains,
vines, scaffolding, open-trapdoor climbing and fluids remain unvalidated/unsupported.
Custom scaled player dimensions and all possible attribute/effect combinations are not
covered by this checkpoint. Ordinary-flight sneak remains forbidden.

Reproduce with JDK 21:

```powershell
.\gradlew.bat clean build --warning-mode all
.\tools\audit-client.ps1 -JdkPath $env:JAVA_HOME
.\gradlew.bat runClientGameTest "-PtrialCases=coordinate-selection,movement,fractional-strafe"
.\gradlew.bat runParkourLive "-PtrialCases=all" "-PtrialRepeats=3"
```

`clean` deletes generated worlds/results. Archive evidence first. The first normal client
launch may need Minecraft's accessibility welcome screen dismissed. The separate development
harness, world-setup commands and watchdog are excluded from the shipping jar.

# Spike SOS-108: citizens on worker threads, measured

Card: [SOS-108](https://fighterx2500.atlassian.net/browse/SOS-108). Owner, 2026-10-30: spike the parallel citizen tick, SyxSpeed's biggest gain, measuring first and using halby's findings. Branch `SOS-108-citizen-spike` (throwaway; patch `37-citizen-spike` only adds timers).

## Question

Where does a citizen's update spend its time? And which part of it could run on worker threads while every PC still gets the same result?

## How it was measured

Mogus-72, own-city mode, two copies at 25x, 40,000 steps (rig C108B). Two sources:
- **Timers** (patch 37, through the SOS-106 step profile):
  - a whole entity update, in `ENTETIES.update`;
  - every search inside one, timed between the shared search buffer's `init` and `done` (`PathUtilOnline.Flooder`).
- **Java Flight Recorder** on the game thread ("updater"): 150 s of execution samples, started after a 60 s load delay (`JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...`). The scripts are in `spikes/sos-108-citizen-threads/`. There were 9,266 samples on the game thread.

Both copies stayed identical to step 40,000; the timers don't touch the simulation.

## Results

**Timers, per step:**
- 2,143 entity updates take 1.61 ms (`ENTETIES` in all: 2.07 ms).
- Searches inside them take 0.57 ms, from only 17 searches a step, about 33 µs each.

**Flight recorder, share of the game thread:**
- `ENTETIES.update`: 56%.
- Citizens (`Humanoid.update`): 41%.
- Searches inside citizens: about 20%. That's 49% of citizen samples.

Inside a citizen's update (share of citizen samples):

| Part | Share |
|---|---|
| `AIManager.update`, of which `setNextState` (choosing the next action) is 66% | 76% |
| Boost lookups (`Boostable.get`) | 8% |
| The person's periodic update (`Induvidual.update`, stats) | 4% |
| Tile change (`initTile`) | 3.5% |
| Collisions (`ResolverTile.collide`) | 2.3% |
| The planning modules (`AIModules.update`) | 3.3% |
| Random draws, anywhere inside | 2.6% |

The searches, by entry point (share of citizen samples):

| Search | Share | What it is |
|---|---|---|
| `SPath.request` → `SPathFinder.find` | 39% | The first leg of every citizen path, on the game thread: the component search (`SCompFinder`), then the tiles to the first stretch. Only the rest goes to the path threads (SOS-37). |
| `SFinderFindable.reserve` | 15% | Find the nearest free service, idle spot or shrine, and reserve it. Called from about 10 plans (idle, spectator, temple, shrine, mourning...). |
| Goods, homes, storage, maintenance | 13% | `SFinderResources`, `SFinderHome`, `SFinderResourceStore`, `SFinderMaintenance` |

The hottest code is the component search: `SCompFinder.find`, `Flooder.pushSmaller` and the heap together are about 30% of citizen samples.

## What it means

1. **The work to parallelise is the searches,** as halby found. They are half of a citizen's time and read only, except the reservation at the end. The rest is small pieces spread over about 150 AI classes, each writing shared state, and isn't worth the risk.
2. **Lockstep doesn't need halby's "same as vanilla" proof. It needs "the same on every PC".** halby runs searches early and checks at run time that each answer equals the serial one ("0 mismatches"). For lockstep it is enough, and much safer, to compute searches at a fixed point against a fixed state. SOSDET already does this for path jobs:
   - a citizen asks for a search during the step;
   - the search runs at the step's end on the path workers, against the world as it stands then;
   - the citizen gets the answer next step.

   The result then depends only on the state and the order of requests, not on thread timing. The cost is a behaviour change: a citizen waits one step for its target or first path leg, deterministic on every PC, but no longer vanilla's exact timing.
3. **The first path leg is the cheapest place to start.** `SPath.request` is one entry point at 39% of citizen time, and SOS-37 already threads the rest of the same path. Moving the first leg to the step-end jobs makes the citizen stand still for one step before walking.
4. **A hard constraint found on the way:** classes that extend `Humanoid.HumanoidResource` (`AIManager`, `Induvidual`) can't be shipped in the engine's jar. The game's script scan loads them at the main menu, which runs `HumanoidResource`'s static initializer, and that reads `TIME` before it exists: a crash. The build's menu check (`menuSafetyCheck`) misses this case, because it looks only at a shipped class's own initializer, not its parents'. Any AI-level change must go around these classes or patch `Humanoid`'s initializer first.
5. **Exact serial wins come first, and need no threads.** halby measured:
   - remembering failed searches: +6 to 12% in saturated cities, where 68 to 76% of service searches find nothing;
   - a flat-array component search: his biggest single exact gain, beyond the heap SOS-106 ported.

   Both speed up the same 20% with no determinism risk.

## Expected gain

- **Searches to the step-end workers:** about 20% of the game thread spread over 3 workers, with the main thread waiting at the barrier as it already does for paths. The step would be about 10 to 13% faster on Mogus-72.
- **Exact serial wins:** perhaps 5 to 10%.
- **halby's full parallel tick:** more, but weeks of work, with run-time verification instead of proof.

## Recommendation

**Go, in this order, each with a two-copy rig run and a before/after profile:**
1. **Exact, serial:** remember failed searches (`SFinderFindable`, `SFinderResources`), then a flat-array component search. No ADR needed: same result, no threads.
2. **ADR "searches at the step's end":** the first path leg (`SPath.request`), then `SFinderFindable.reserve`, as step-end jobs on the path workers, answered next step.
   - This changes a rule ("no multithreaded updates" gets a second exception after path jobs) and vanilla's timing by one step.
   - The ADR has to settle the menu-crash constraint above.
3. **No-go:** the rest of the citizen update on threads. Many small writes, under 30% of citizen time, and halby's races show the risk.

## Not checked

- Whether halby's "remember failed searches" holds as exactly in SOSDET's city as in his saves.
- The cost of one step's delay on gameplay (citizens hesitate before walking). Needs a look in the game.
- Other cities. Mogus-72 only.

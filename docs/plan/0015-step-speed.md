# Plan 0015: step speed, from SyxSpeed's analysis

Card: [SOS-106](https://fighterx2500.atlassian.net/browse/SOS-106). Owner, overnight 2026-10-30: read `syxspeed-analysis.pdf` (SOSGSGMP `docs/plan/`) and run its plan and implementation of the optimisation techniques.

## Where the analysis leaves us

- SyxSpeed is a single-player speed mod. Its biggest gain, citizens updated on worker threads, needs its own spike and an ADR. It isn't in this plan.
- The parts that could be ported are:
  - the faster component path search (`SCompFinder`, about +22% by halby's estimate);
  - the GL frame hand-off (+9%);
  - more simulation per drawn frame (+25%, an idea);
  - the build points every 16 passes (+1.6%);
  - about 20 exact caches;
  - dropping the serial GC flag.
- halby's numbers are estimates on his PC against a newer vanilla. SOSDET already threads its path finding (SOS-37), which halves the path time, so the path gains would be smaller here.
- The analysis's first proposal: **profile one SOSDET step before choosing what to port.** That's step 1 below.

## Steps

1. **A step profile.** `sosdet.game.StepProfiler`, the engine's own implementation of the game's `Profiler`.
   - Patch 10 gives it to every game part's update in the step and times each part as one entry. The parts that log their own pieces (rooms, entities, terrain...) show them nested.
   - It allocates nothing once each class has a slot, and the simulation reads nothing of it.
   - The rig's `wprofile` turns it on and notes the mean ms per step since the last call.
2. **Profile Mogus-72** (the late-game test city) in own-city mode, two copies at 25x (rig P106A). Record the parts' shares.
3. **Choose the ports by the profile.** For each candidate:
   - the part it speeds up and that part's share of the step;
   - the expected gain = share × halby's factor for that part;
   - the determinism risk (the analysis, section 4).
4. **Port, one at a time.** For each port:
   - a before/after profile on the same save and steps;
   - a two-copy rig run with identical hashes;
   - a line in `docs/hooks.md` if it's a new patch.
5. **Not here:**
   - the parallel citizen tick (a spike and an ADR first);
   - the JVM flags (the launcher owns them, and SOSDET can't set them without changing the player's launcher settings);
   - the lobby check that refuses SyxSpeed (a mod card).

## Results

Mogus-72, own-city mode, two copies at 25x, 40,000 steps, rig `wprofile` every minute. The two baselines (P106B, P106D) agree on timing to 1%. Two runs never repeat each other's hashes (each run's world starts differently), so a port is checked copy against copy within its run.

The step's parts (P106B, copy 1, 3.34 ms a step):

| Part | ms a step | Share |
|---|---|---|
| Citizens (`ENTETIES`) | 1.96 | 59% |
| Path jobs at the step's end (3 threads) | 0.60 | 18% |
| The rest of the city (rooms, tiles, standings...) | 0.22 | 7% |
| Scripts (`ScriptEngine`) | 0.14 | 4% |
| World (`WORLD`, `FACTIONS`, `RAIDING`...) | 0.14 | 4% |
| Battle threads, events, hash, reports | about 0.27 | 8% |

What the shares decide:

- **The citizens** are the step. Only the parallel citizen tick moves them, and it stays out of this plan (spike and ADR).
- **Path search** is the one other part worth a port. Port 1, below.
- **Rooms, world, scripts and caches** together are under 15% of the step. halby's caches each save a part of one of these, so less than 1% each here: not ported.
- **The GL hand-off** and simulation per drawn frame speed the frame, not the step; the rig doesn't draw. They need a GUI measurement of their own: left for later.

### Port 1: the path heap (patch `36-path-heap`)

The path searches' open list (`RBTileTree`, a red-black tree) becomes a 4-ary heap on an array. It pops tiles in the tree's order: by value, and among equal values by when they were put. No settlement path code changes a queued tile's value, the one case where the tree's order and the heap's could part.

| Run | Path jobs, ms a step (copy 1 / 2) | Step mean, ms (copy 1 / 2) | Copies identical |
|---|---|---|---|
| P106B, baseline | 0.599 / 0.522 | 3.33 / 3.00 | yes, to 40,000 |
| P106D, baseline again | 0.600 / 0.525 | 3.34 / 3.00 | yes, to 40,000 |
| P106C, heap | 0.522 / 0.463 | 3.22 / 2.93 | yes, to 40,000 |

Path jobs take 13% less, the step 2 to 3%. halby's +22% was his whole component search; here the paths already run on three threads (SOS-37).

Not checked: a unit test of the heap's order against the tree's. The heap lives in patched game code, which the unit tests don't load.

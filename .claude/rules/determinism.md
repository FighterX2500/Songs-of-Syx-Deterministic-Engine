---
paths:
  - "src/**"
  - "patches/**"
---

# Determinism rules (engine code and patches)

Field battles run in lockstep ([ADR 0003](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0003-battle-lockstep.md)). Code that runs in lockstep must produce bit-identical results on every player's PC from the same start state and the same orders. That covers the engine's own code, its patches to the game (hook-surface B7–B11 and B15, and the SOS-23 city fixes) and the API points B12–B14, and any mod code that runs inside the step. The `paths` above follow the layout in [engine ADR 0001](../../docs/decisions/0001-build-and-packaging.md). Moved here from SOSGSGMP by [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md). Spikes SOS-11 ([report](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/plan/spikes/determinism.md)) and SOS-23 ([report](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/plan/spikes/city-determinism.md)) found each rule marked *SOS-11* or *SOS-23* breaking in practice.

## Time and threads

- Advance the simulation only with the fixed step (1/60 s). Never use frame time, `System.currentTimeMillis()` or `System.nanoTime()` in simulation logic.
- No multithreaded updates of battle state. Battle jobs and path finding run inside the step, in a fixed order. If work is ever split across threads, inputs are a snapshot and results are applied at a fixed later step.
- One exception: the AI general runs only on the host, on its own thread (ADR 0003, amended in SOS-23). It never writes battle state directly. Every write it makes goes through the AI log: orders, division settings and artillery targets. The host's simulation thread stamps each entry for a later step, and every PC applies it at that step. Shared scratch objects need a separate copy for the general's thread. *SOS-23:* a static vector in projectile `Updater.test` was shared with the trajectories job.
- A switch that says "the job runs inside the step" must be true only while the job runs. *SOS-11:* a flag that was always true made `BattleOrders.init` restart the real thread.

## Random numbers

- Simulation code draws only from the simulation stream, only inside the step, and only on the simulation thread. No `Math.random()`, `ThreadLocalRandom`, or `new Random()` without the battle seed.
- The simulation stream is reseeded at every step from the battle seed and the step number (SOS-30), so the stream at a step never depends on what drew before it.
- Anything visual or audible draws from the cosmetic stream, even when it is called from inside the step: rendering, animation such as tree sway, sound picks and pitch, light flicker, UI. *SOS-11:* all three of those examples drew from the simulation stream.
- Nothing that runs outside the step may draw from the simulation stream: class initializers, field initializers, constructors run on load, UI actions. *SOS-11:* a tile order was shuffled at class load, and the AI's `flanking` value was drawn when the AI was built.
- Battle start (deploy and AI start-up) is step 0 and runs inside the simulation context. *SOS-11:* the AI drew its start-up values outside it.

## State

- Every peer starts from the host's battle save, never from its own generation. After a load, at battle start or on resync at step N, any state the save doesn't hold must be set deterministically before the next step.
- Never let iteration order depend on identity: no iterating `HashMap`/`HashSet` keyed by objects without a stable `hashCode`, no `IdentityHashMap`, no `System.identityHashCode`. Iterate arrays, lists or maps sorted by a stable ID.
- Use `StrictMath` instead of `Math` for `sin`, `cos`, `tan`, `atan2`, `exp`, `log`, `pow`, `sqrt`-dependent chains, and similar functions (not yet tested across CPUs).
- No simulation behavior, and no simulation random draw, may depend on local settings: graphics quality, resolution, language, camera position, sound volume, the light-cycle setting, game speed. *SOS-11:* sound picks were skipped at zero volume and throttled by game speed, and light flicker ran only with the light cycle on.
- Anything that feeds the state hash must be part of the simulated state, not a render-side copy.

## Orders and testing

- Every peer applies orders only at the step the host confirmed, never on receipt.
- When changing lockstep code, run the determinism test before declaring the change done. Use several game processes at different game speeds, the same battle save, seed and orders, and compare state hashes every step (the scenario suite, SOS-31; until then SOSGSGMP `spikes/determinism/matrix.sh`).

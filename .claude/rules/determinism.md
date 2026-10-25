---
paths:
  - "src/**"
  - "patches/**"
---

# Determinism rules (engine code and patches)

In a session the city, the world map and field battles run in lockstep ([ADR 0003](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0003-battle-lockstep.md) for battles, [plan 0002](../../docs/plan/0002-city-and-world-lockstep.md) for the city and the world). Code that runs in lockstep must produce bit-identical results on every player's PC from the same start state and the same commands and orders. That covers the engine's own code, its patches to the game, the API the mod calls, and any mod code that runs inside the step. The `paths` above follow the layout in [engine ADR 0001](../../docs/decisions/0001-build-and-packaging.md). Moved here from SOSGSGMP by [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md). Each rule marked *SOS-11*, *SOS-23* or a later card broke in practice; [docs/architecture.md](../../docs/architecture.md) lists every cause found, with the patch that fixes it.

## Time and threads

- Advance the simulation only with the fixed step: 1/60 s at an agreed speed of 1x or slower, 1/16 s above (`StepClock`). Never use frame time, `System.currentTimeMillis()` or `System.nanoTime()` in simulation logic.
- No multithreaded updates of simulation state. Battle jobs run inside the step, in a fixed order. If work is ever split across threads, inputs are a snapshot and results are applied at a fixed later step.
- Path jobs queued in a step run at the step's end, on fixed shares (job i on share i % 3, each share with its own scratch), while the simulation thread waits; the jobs only read the settlement and each writes only its own path. Nothing they reach may hold static or shared scratch, shared iterators included, and they may not draw random numbers. *SOS-37:* `OpDist` held its target in static fields, and `FinderIdle` looped with a list's single iterator.
- One exception: the AI general runs only on the host, on its own thread (ADR 0003, amended in SOS-23). It never writes battle state directly. Every write it makes goes through the AI log: orders, division settings and artillery targets. The host's simulation thread stamps each entry for a later step, and every PC applies it at that step. Shared scratch objects need a separate copy for the general's thread. *SOS-23:* a static vector in projectile `Updater.test` was shared with the trajectories job.
- A switch that says "the job runs inside the step" must be true only while the job runs. *SOS-11:* a flag that was always true made `BattleOrders.init` restart the real thread.
- Everything a step does, its log entries included, runs under that step's tick (`GAME.updateI`). *SOS-36:* commands ran before the tick counted up, so they read a tick-keyed cache that only one PC's UI had filled.
- Nothing may stop this PC's steps while a prompt the step opened waits for its answer, because the answer is a command applied at a later step. *SOS-36:* the yes/no and world-battle interrupters returned false, VIEW gave the game no time, and every PC froze.

## Random numbers

- Simulation code draws only from the simulation stream, only inside the step, and only on the simulation thread. No `Math.random()`, `ThreadLocalRandom`, or `new Random()` without the session seed.
- The simulation stream is reseeded at every step from the session seed and the step number (SOS-30), so the stream at a step never depends on what drew before it.
- Anything visual or audible draws from the cosmetic stream, even when it is called from inside the step: rendering, animation such as tree sway, sound picks and pitch, light flicker, camera shake, UI. *SOS-11:* tree sway, sound picks and light flicker all drew from the simulation stream. *SOS-37:* the world minimap's colour grit drew in the step only when this PC's render had not redrawn the chunk first.
- Nothing that runs outside the step may draw from the simulation stream: class initializers, field initializers, constructors run on load, UI actions. *SOS-11:* a tile order was shuffled at class load, and the AI's `flanking` value was drawn when the AI was built. The build's `staticDrawCheck` fails on any static initializer the game runs that draws from `RND`.
- Battle start (deploy and AI start-up) is step 0 and runs inside the simulation context. *SOS-11:* the AI drew its start-up values outside it.

## State

- Every peer starts from the host's save, never from its own generation. After a load, at the session's start or on resync at step N, any state the save doesn't hold must be set deterministically before the next step. That includes static state vanilla never clears between games in one process (`WorldStatics`). *SOS-35, SOS-36:* garrison supply sums, a static world generator, static tick keys.
- Never let iteration order depend on identity: no iterating `HashMap`/`HashSet` keyed by objects without a stable `hashCode`, no `IdentityHashMap`, no `System.identityHashCode`. Iterate arrays, lists or maps sorted by a stable ID.
- Use `StrictMath` instead of `Math` for `sin`, `cos`, `tan`, `atan2`, `exp`, `log`, `pow`, `sqrt`-dependent chains, and similar functions (not yet tested across CPUs).
- No simulation behavior, and no simulation random draw, may depend on this PC: graphics quality, resolution, language, camera position, sound volume, the light-cycle and weather-effect settings, game speed, whether the battle view is open, the developer flag, or the player's profile (titles, blueprints). *SOS-11:* sound picks were skipped at zero volume and throttled by game speed, and light flicker ran only with the light cycle on. *SOS-35:* titles counted the races this PC's profile had won with.
- UI and rendering never write simulation state, and the step never reads what they hold: drafts the UI refills every frame, the placement being previewed, caches filled by whoever calls first, and caches keyed on the render frame (`VIEW.RI()`). Between steps such caches are only read; they refresh in the step. *SOS-23, SOS-35:* drawing placed floor tiles, region caches were written by rendering, and the deal window's draft reached faction worth.
- Anything that feeds the state hash must be part of the simulated state, not a render-side copy.
- With several humans ([ADR 0004](../../docs/decisions/0004-several-humans.md)), "the player" inside the world part is the step's current human, the same on every PC; this PC's own human never enters the step. Code about one human runs once per human, in slot order, with that human current (`Hooks.currentHumanSwap`, which returns the human to restore). A value kept per human is indexed by the human's position (`Hooks.humanIndex`), never by this PC, and slice 0 stays the game's own field. A cache the city part could fill with this PC's human is filled only in the world part. *SOS-46:* `RDDistance` filled by the city part split the first 3-human smoke at step 40; the city's stored divisions were one store for every human.

## Commands, orders and testing

- A player's action never changes state on the PC where it was clicked. It becomes a command ([ADR 0002](../../docs/decisions/0002-player-commands.md)) carrying everything the action reads from that PC (the area, rotation, options, a draft's accepted values, a blueprint's contents). Feedback such as sounds, camera moves and windows happens only on the issuer's PC (`Hooks.issuedHere()`).
- Every peer applies commands, orders and AI log entries only at the step the host confirmed, never on receipt. The applier re-checks what the UI checked, and skips the command on every PC alike if it no longer holds.
- When changing lockstep code, run the determinism test before declaring the change done: several game processes at different game speeds, the same save, seed and commands, with state hashes compared every step (`rig/rig.py`, [rig/README.md](../../rig/README.md)). One combined smoke run per stage is enough (owner's direction).

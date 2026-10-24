# SOSDET for agents: architecture and desync causes

A short map for an AI agent (or a person) about to change SOSDET. Read it first, then the files your task touches. The rules themselves live in [AGENTS.md](../AGENTS.md) (coding) and [.claude/rules/determinism.md](../.claude/rules/determinism.md) (determinism). [CLAUDE.md](../CLAUDE.md) is the owner's own workflow; outside contributors can skip its Jira and approval steps.

## What it is

- **A mod jar for Songs of Syx V71, build 71.44 only.** It holds the engine (package `sosdet`) and game classes compiled from [patches/](../patches/README.md). The game's launcher puts an enabled mod's jar ahead of `SongsOfSyx.jar`, so the patched classes replace the game's own ([ADR 0001](decisions/0001-build-and-packaging.md)). The game folder and `SongsOfSyx.jar` are never changed, and no game file is committed.
- **Lockstep:** the same save, seed and inputs at the same steps give a bit-identical state on every PC. Inputs travel between PCs, state never does.
- **No network inside.** The engine hands out and takes in bytes; the multiplayer mod ([SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP)) carries them. The pairs are `aiLogOut`/`aiLogIn`, `ordersOut`/`ordersIn` and `hashOut`/`hashIn` in [`Engine`](../src/main/java/sosdet/Engine.java).
- **No session, no change:** until something calls `Engine.begin()`, every hook gives the game's own behaviour. The only exception is the fixed tables of patch `50`.

## A session, step by step

1. **The mod starts a session:** `Engine.begin()`, then `seed`, `aiHost` or `aiPeer`, `localPc`, `runFreely` or `runUntil`, and `speed`.
2. **Every frame,** `GAME.update` (patch `10`) asks `Hooks.stepsFor(frame time × this PC's speed)` how many whole fixed steps to run; the rest carries over.
   - A step is 1/60 s at an agreed speed of 1x or slower, and 1/16 s above (vanilla's longest sub-step). See [`StepClock`](../src/main/java/sosdet/StepClock.java).
   - A peer never runs past the host's last confirmed step + 30.
   - The host runs at most a lead of steps (the mod's choice) past the slowest PC, learnt from that PC's hashes (`Engine.peerStep`), so the slowest PC sets the pace.
   - Speed only sets how fast a PC runs. A paused PC runs no steps.
3. **`Hooks.stepBegin`:**
   - lifts this PC's unbuilt room plans out of the simulation;
   - enters the simulation random stream, reseeded from the seed and the step number;
   - applies the log entries due at this step: orders, commands, speed changes and the AI general's settings.
4. **The step itself:** the city part, then the world part (patch `12`), each updating the game's resources by the step length; or the battle.
   - The world part never reads a live city: it reads the city report (patches `13`, `14`, `98`; `GameReport`) and changes the city only through transactions applied exactly once (`sosdet/txn`, `GameTxns`).
   - In own-city mode (`Engine.ownCity`) each PC runs its own city, and the world reads the host's reports only (one player faction, [requirements](requirements.md) §2).
   - Battle jobs run inside the step, not on their threads (patch `30`).
   - Path jobs queued in the step run at its end, before the hash: share 0 on the simulation thread, shares 1 and 2 on path threads, job i always on share i % 3 (patch `35`, `PathWorkers`).
   - The host's AI general keeps its own thread, but writes only to the log (patch `90`).
5. **`Hooks.stepEnd`:** every 30 steps the state hash ([`GameHash`](../src/main/java/sosdet/game/GameHash.java), [`HashLog`](../src/main/java/sosdet/hash/HashLog.java)). It has seven sections: clock, soldiers, divisions, projectiles, entities, world (armies, every world entity, region owners, credits, wars, raiders) and report (the city report the world part reads, ADR 0005). Rooms, stock and stats are not hashed, so a split there shows up in the entities a few steps later.
6. **Between steps** come the UI and rendering. They draw from the cosmetic stream and must never write simulation state (patches `65`, `67`, `98`).

## Player input: commands

A player's action never changes state on the PC where it was clicked. It becomes a command that every PC applies at the same step ([ADR 0002](decisions/0002-player-commands.md)).

1. **A hook captures the click,** in the method that commits the action: `if (sosdet.game.cmd.Laws.curfew(...)) return;`. It goes at the UI call site instead when the declaring class is menu-unsafe or on a hot path. `Commands.capturing()` is true only in a session and outside the step, so the game's own calls inside the step run as before.
2. **The command is written with `CmdWriter`:** an id, then ints, booleans, doubles and strings (a long goes as two ints). [`Args`](../src/main/java/sosdet/game/cmd/Args.java) has codecs for rooms, entities, races, divisions, armies and regions.
3. **The host stamps it** for step = last confirmed + 30. Every PC, the host included, applies it at that step, in sequence order.
4. **The applier** (`new Command(id, name, r -> ...)`) decodes the command, re-checks what the UI checked, and calls the game. If its target is gone, it returns false and the command is skipped on every PC alike.
5. **Rules for a command:**
   - Everything the action reads from the clicking PC (rotation, a draft, the typed value, the profile's blueprints) travels inside the command.
   - Sounds, camera moves and windows happen only where `Hooks.issuedHere()` is true.
   - An identical command repeated every frame (a held button) is captured once.

| Ids | Class | What |
| --- | --- | --- |
| 1000s | `Laws` | laws, decrees, technology, immigration |
| 2000s | `Rooms` | room settings, removal, upgrades |
| 3000s | `Employment` | work priorities, employment |
| 4000s | `Military` | divisions in the city |
| 5000s | `Names` | names, banners, nobles, map marks |
| 6000s | `Tools` | placing tools; area-room Build and refurnish, whose plans the `Plan*` classes hold ([ADR 0003](decisions/0003-room-planning.md)); 6100s: copier and blueprints |
| 7000s | `World` | armies, recruiting, regions |
| 7900s | `WorldBattles` | world-battle prompt decisions |
| 7950 | `StepPrompts` | yes/no questions the step asks |
| 7960s | `MapBattles` | a battle fought on the battle map: Start, Retreat, the victory or defeat panel's Close, Restart (SOS-41). A world command whose step falls in the battle is skipped, with a notice to its PC: the battle's end loads the game saved before it, which would undo it |
| 8000s | `Diplomacy` | deals, emissaries |
| 9000s | `Events` | event choices, raiders |

[docs/commands.md](commands.md) lists every player entry point in the game and its status.

**Prompts the step opens** (a world battle, "declare war and besiege?") open on every PC at the same step. Their answer is a command, so game time keeps running under them in a session (patch `65`); otherwise no PC would ever reach the step where the answer applies.

## Code map

| Path | What |
| --- | --- |
| `sosdet/Engine.java` | The API the mod calls |
| `sosdet/Hooks.java` | Every call the patches make into the engine |
| `sosdet/Session.java`, `StepClock`, `Streams` | One session's state, the fixed step, the simulation and cosmetic random streams |
| `sosdet/ai/` | The log: `AiLog` (host and peer, stamping, confirmations, the 30-step delay), `AiEntry`, `CmdWriter`/`CmdReader` |
| `sosdet/game/` | Everything that touches game classes: `GameAi` (applies log entries), `GameHash`, `GameResync`, `AiHooks`, world fixes; the city↔world link: `StepParts`, `GameReport`, `GameTxns`, `GameSoldiers`, `GameWorldLoad` (the world resync) |
| `sosdet/txn/` | Transactions: the world's changes to a city, each applied exactly once |
| `sosdet/game/cmd/` | Commands per area, room-plan records (`Plan*`) |
| `sosdet/hash/` | The hash log and the desync report |
| `patches/` | The game files the engine changes, one patch per topic; [docs/hooks.md](hooks.md) has one row per hook with the reason |
| `rig/` | The test rig: a second mod plus `rig.py`, which runs several game copies and compares them ([rig/README.md](../rig/README.md)); never shipped |

## What causes desyncs, and where each is fixed

Each line is a class of cause found in practice. The patch named holds the fix; [docs/hooks.md](hooks.md) has the details.

| Cause | Examples found | Fix |
| --- | --- | --- |
| **Frame time drives the simulation** | Sub-steps cut from the frame; the battle view updated once per frame | `10` |
| **Threads** | Battle jobs and path finding on wall-clock threads | `30`, `35` |
| | Path threads sharing a static heuristic target and a list's single iterator | `35` |
| | Graphics-thread jobs drawing random numbers | `80` |
| | The AI general sharing a lazy count and a scratch vector with the step | `90` |
| **Random draws** | One shared `RND` | `20` |
| | Visual draws inside the step: tree sway, sound picks skipped at zero volume, light flicker only with the light cycle on, the earthquake's camera shake, the world minimap's colour grit when this PC's render hadn't redrawn the chunk first | `40` |
| | Static initializers drawing whenever their class happens to load | `40`, `50` |
| | Start-up tables from an unseeded stream | `50` |
| | Draws while loading a save | `70` |
| **This PC's settings or view read by the simulation** | Whether the battle view is open; the placement being previewed; the weather-effects setting; where the camera looks (the serial killer) | `60`, `65` |
| | The developer flag; titles from this PC's profile | `98` |
| **UI or rendering writing simulation state** | Drawing placing floor tiles or bleeding gore | `67` |
| | Caches filled by whoever calls first; caches keyed on the render frame (`VIEW.RI()`) | `67`, `98` |
| | Drafts the UI refills every frame and the step reads (`DIP.TMP`, `RDLevelsTmp`) | `98` |
| | A formation's unused tail carrying this PC's move preview | `66` |
| **The world reading a live city** | Trade stock, credits, population, the garrison, army power, opinion and trust read from this PC's city in the world step; own cities differ per PC | `12`, `13`, `14`, `98`: the city report and transactions (ADR 0005) |
| **Loading part of a save** | The world resync loaded only the world's sections of a whole save; the game writes one object stream for the whole file, so the skipped city sections broke its back-references, the raiders misread and were regenerated per PC | `GameWorldLoad.save` writes a world-only save (no patch) |
| **Tick order** | Commands applied before the tick counted up, so they read a tick-keyed cache only this PC's UI had filled | `10` |
| **Static state surviving a load** | Garrison supply sums, a static world generator, static tick keys (race target, employee list) | `98` (`WorldStatics`) |
| **A player action applied at once on one PC** | Every UI setter, tool and dialog | commands: `95`, `96-*`, `97`, `99` |
| **Iteration and maths** | Object-keyed `HashMap` order; `Math` against `StrictMath` | rules only: [determinism.md](../.claude/rules/determinism.md) |

## Debugging a desync

- **Reproduce it with the rig:** `python rig/rig.py city --save <file> --copies 3 --speeds 5,7,7 --steps N`. The copies are compared at every step, and all are held at the first difference. Each copy's `desync-c<k>.txt` names the step and the hash section. `--play` lets a person play copy 1.
- **Trace the random draws:** `Engine.traceDraws` and `Engine.drawTrace` show which code drew, step by step.
- **What worked before:** compare whole saves at step 0 and just before the split. If those are identical, the cause is state the save doesn't hold, such as a table filled at class load or a cache. Then look for state written outside the step, or a per-PC value read inside it, using the table above.

## Pitfalls that cost us time

- **The engine compiles against the shipped jar.** It can't use members a patch adds. Patches call into the engine, never the other way round.
- **One patch per game file.** Add to the patch that already owns the file (`grep -l "^+++ b/<path>" patches/*.patch`). Make patches with `python tools/mkpatch.py <patch> <edits.py>`.
- **Menu safety:** the main menu loads every class in the mod jar. A patched class whose static initializer touches `GAME`, `SETT`, `TIME`, `STATS` and the like crashes the menu; hook at the UI call site instead.
- **Hook the commit, never a draft:** a deal window that rewrote its draft every frame produced 185,792 commands.
- **Anything that stops this PC's steps deadlocks a command-answered prompt:** a pause (`tmpPause`), or an interrupter whose `update` returns false (VIEW then gives the game no time).
- **Inputs redraw every frame:** with the 30-step delay a typed "12" came out as "02" (`GInputInt`).
- **Build levels:** the engine compiles with `--release 21`. The rebuild of the game's own source uses `--release 16` and `-proc:none`, so it matches the shipped jar byte for byte.
- **Environment:** never rebuild while a rig run is live, because the build swaps jars under the running copies. On Windows, never run `cmd /c` from Git Bash (it hangs).

## Checking a change

- `gradlew.bat build` runs the tests and the byte-for-byte checks. It also fails if any static initializer the game runs draws from `RND`, directly or through what it calls (`staticDrawCheck`).
- Unit tests only for logic that needs no game.
- Anything inside the step needs a rig run with several copies at different speeds.

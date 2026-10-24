# Plan 0002: city and world lockstep

Status: approved by the owner on 2026-10-23, with the decisions at the end.

## Goal

The owner's decisions on 2026-10-23, after SOS-32 M1 (PR #9):

- **City:** the whole city simulation runs in lockstep.
- **World:** the world map runs in lockstep on every PC, with the host's save for recovery.
- **Reuse:** anything from timjrobinson/songs-of-syx-multiplayer may be copied (MIT, with attribution), rewritten to fit this engine: shipped source patches with small named hooks, the host's log, and no `-javaagent`.

## Where we start

The engine already covers 28 of the other mod's 39 determinism fixes, and 6 don't apply to us. Patch 66 and the AI-log coalescing from this week are fixes the other mod doesn't have. The owner's 13-minute city-invasion session stayed identical (30,570 steps). The inventories behind this plan are in the scratchpad (competitor-determinism.md, competitor-commands.md):

- **City commands:** the other mod routes 225 player actions:

  | Area | Actions |
  |---|---|
  | Rooms | 61 |
  | Laws and stats | 32 |
  | World | 30 |
  | Employment | 29 |
  | City military | 24 |
  | Placement tools | 20 |
  | Diplomacy | 10 |
  | Events | 6 |
  | Names and colours | 6 |
  | Nobles | 3 |
  | Game speed | 2 |
  | Generic value holders | 2 |

  Every signature matches V71.44. A direct port touches about 150 game files.
- **World:**
  - **Small fixes:** about 8 patches; the world code has no threads, no wall-clock time and no random draws in static initializers.
  - **The player faction:** `FACTIONS.player()` appears 343 times in world code. The world step must see the same "player" on every PC.
  - **The city↔world link:** about 10 kinds of city value the world reads, at about 100 sites (mostly behind a few chokepoints), and about 8 kinds of world change to a city.

## Stages

Each stage is one card (or a card's milestone) with its own PR, in this order.

### A. City groundwork (SOS-32 M2a, small)

1. **Bugs neither mod fixes:**
   - `_EARTHQUAKE.update` draws random numbers only while this PC's city view is open.
   - `Floors.render` writes floor data from what is on screen.
   - `EUpdater` keeps a frame-counter cache.
2. **First game in a process:** the other mod's first-game joiners split at step 2. Rig check: a fresh process against one that has already played a game. Then fix the cause, or load twice as they do.
3. **Build check:** scan the shipped jar's static initializers for random draws outside our patched classes, so a new game version can't slip one in.
4. **Attribution:** `THIRD-PARTY-NOTICES.md` with the other mod's copyright and MIT license, before the first copied code.
5. **Requirements:** update `docs/requirements.md` §2 (the city and the world are in scope) and §4 (city and world targets), with the owner's approval in the same PR.

### B. City commands (SOS-34, large)

1. **ADR (SOSDET 0002), the command mechanism:**
   - The order log grows into a command log with a registry of command kinds.
   - Capture rule, as in the other mod's `shouldSync`: a hooked call becomes a command only from the UI. That means not inside the step, not while a command applies, and not on the general's thread. Our battle-order capture already works this way.
   - Hooks sit at the UI call site when the declaring class is unsafe at the menu (7 classes) or on a hot path (the generic int and boolean setters). That also cuts the file count, for example one hook in the UI module instead of 19 `autoEmploy` overrides.
   - Targets are addressed by stable index (entity id, room, division, region, faction and so on), with a short list of object paths for the rest.
   - Hand-written codecs only. The other mod's Java serialization for events and deals is rewritten, since our rules forbid it.
   - Every command carries the issuing PC and its faction, ready for several human players.
   - Goods, soldiers and money moved by a command carry a transaction ID and apply exactly once.
   - Profile data (wall material, saved blueprints) and issuer-only UI state (rotation, furniture group, placer caches) travel inside the command.
   - Random draws inside a command come from (seed, step, command sequence).
2. **Port, by area:**
   - rooms and tools (81);
   - employment (29);
   - laws and stats (32);
   - city military (24);
   - nobles, names and events (15).

   Each area gets a rig test that issues its commands through the real UI path and checks that every copy applies them identically, exactly once.
3. **Pitfalls to design against, from the other mod:**
   - render code writing a shared draft (a deal popup produced 185,792 commands);
   - auto-wall previews flipping walls while hovering;
   - placer caches keyed on the tick counter.

### C. City play sessions (SOS-32 M2b)

- The owner plays the Mogus-72 city with commands, world held, 10 minutes or more per session. Each desync gets fixed the way this week's were.
- Exit: one 30-minute session identical across 3 copies.

### D. World lockstep (new card)

1. **Unhold the world:**
   - The rig drops `worldHeld` as the default.
   - The step keeps vanilla's order: city first, then the world.
   - *As built (SOS-35):* nothing holds the world in a session; the rig holds it only with `--hold-world`.
2. **World patches:**
   - `Emissaries.penaltyMul` (cache keyed on the render frame).
   - `RDRaces.Visuals.cache` (render writes saved region data).
   - `RDBoostCache`, `DipWarPlayer` and `RaidingUtil`: caches the UI fills (the same treatment as patch 67).
   - `DealBools`: NPC deal drafts depend on the developer flag.
   - `PTitles`: title boost from the local profile.
   - `RDLevelsTmp`: the building popup's draft levels.
   - An audit of `DIP.TMP()`, the deal draft.
3. **World-battle prompt:** it opens from shared state on every PC, shows only on the owning player's PC, and the decision comes back as a stamped command.
   - *As built (SOS-35, patch 99-world-battles):* V0.1 is co-op, so the prompt shows on every PC and any player may decide; the first decision applies, and later ones find no prompt and are skipped everywhere. Commands 7900 to 7902 (`cmd.WorldBattles`). In a session the prompt doesn't pause the game, since a paused PC runs no steps and the decision could never apply; the world holds while it is open (`Hooks.worldHeld`, decision 2) and the city goes on. *SOS-45:* the world no longer holds; only the battle's armies wait (`Hooks.armyHeld`), until an answer or the 30 s time limit. Engage is refused with a notice until generated battles are ported (after V0.1). The rig's host player answers every prompt (`answerprompts`: auto-resolve, no captives or spoils, occupy).
   - *As built, the step's yes/no questions:* "declare war and besiege?" (`WArmyState`) and "take the routed invaders prisoner?" (`Invasion`) are kept by the engine and answered by command 7950 (`cmd.StepPrompts`); the rig says yes.
   - *Known gap:* a resync load while a prompt is open leaves the loading PC without it, so its world runs while the others' holds. Rare; fix after V0.1 (carry the open prompt in the resync, or delay the resync until it closes).
4. **The player faction:** inside the step, "the player" is the save's player faction on every PC. This PC's local player never enters the step (AGENTS.md, Determinism).
   - *As built (SOS-35):* in V0.1 co-op every PC loads the same save, so `FACTIONS.player()` is that faction everywhere; several human factions are the mod's work.
5. **World commands:** 46 of the 225 (world 30, diplomacy 10, events 6).
6. **Smoke run (SOS-35, decision 6):** D35B, 3 copies, 14,000 steps, the world running, `rig/cmds/world.txt` (16 world, diplomacy and event commands applied on every copy): identical to step 14,000, no engine desync. The state hash now has a world section (armies, region owners, credits, wars; hash format 3); it took 379 different values in 466 hashes, so the world moved. D35A, before the world section, only showed the city identical. No world-battle prompt or invasion question came up in the run, so those paths are checked by reasoning only.
7. **Exit:**
   - the rig runs world and city 30 minutes identical with the AI host;
   - the owner plays the world map for 10 minutes or more with no desync.

### E. City↔world link (new card, needs an ADR in SOSGSGMP)

1. **Step split:** the step gets a city part and a world part. `EVENTS` mixes world and city events and gets split.
2. **City → world:** the city values the world reads (about 10 kinds: population by race, tradable stock and trade settings, credits, garrison and invasion power, 3 laws per race, boosts, tech, level and titles; the wall count is render-only) are snapshotted at the chokepoints (`Player.updateSpecial`, `Player.res/buyer/seller/credits/offensivePower`, `BOOSTABLES.*.get(player)`, `SETT.INVADOR`, `SETT.ENTRY`). Each snapshot becomes a stamped input for a world step.
3. **World → city:** the world's changes to a city (about 8 kinds: deliveries, credits, invasions and raid demands, immigrants, returning soldiers, garrison casualties, supply withdrawals when a division is sent out, event triggers) become stamped transactions with IDs. Army supply from depots flows the other way, in the report. The scan: [notes/city-world-link-scan.md](notes/city-world-link-scan.md); the plan: [notes/city-world-link.md](notes/city-world-link.md). SOS-38 builds the whole link, own-capitol invasions and scripted events included (owner, 2026-10-23).
4. **Engine API:** the mod runs the world plus its own city, and feeds in the other cities' inputs.
5. **Verification:** record one copy's city inputs, then replay them into a copy that runs the world only, and get the same world.
6. *As built (SOS-38, PR #19):* the step parts (patch `12`), the city report (`13`, `14`, `98`), the transactions (`sosdet/txn`), own-city mode in the API, the world resync from a world-only save, and pacing by the slowest PC. Verified with own-city runs instead of the replay: followers run their own cities and their worlds read only the host's reports ([notes/stage-e-smoke-tests.md](notes/stage-e-smoke-tests.md): M4R2, 3 copies, 108,000 steps; W1R2 with a second PC as the follower).

## Outside this plan: the mod's own work (SOSGSGMP)

- **ADR 0005 (world lockstep and lockstep city viewing):** changes spec §4, §10 and §13 and the world row in `architecture.md`. This is a docs PR, allowed in the mod's planning phase.
- **Several human factions:** each player's own faction in one world. Vanilla has a single player faction, referenced 343 times in world code. This is the mod's "single-player assumptions" task, and the biggest open item for multiplayer. The engine keeps one player faction per save and never lets a PC's local player into the step.

## Parallel agents

- **Stage B:** about 5 agents, one per area, each in its own worktree. They check their entry points against the game source and write that area's patches, hooks and rig test. I review and merge.
- **Stage D:** 2 agents on the world patches.
- **Everywhere else:** one agent at a time.
- **Cost:** each area agent costs on the order of 300k tokens. Each wave starts only with the owner's go-ahead.

## Decisions (owner, 2026-10-23)

1. **Order:** A → B → D → C → E. The owner first approved the stages as written; on the same day they moved world lockstep (D) ahead of the city play sessions (C), so trade works in those sessions.
2. **World-battle prompts:** the world holds for everyone while a player decides a prompt, as vanilla pauses for its single player and as the spec pauses the world for manual battles.
   - *Changed (owner, 2026-10-25, SOS-45):* prompts never hold the world (spec §4). Only the battle's armies wait in place while it is open, and with no answer within 30 s (real time at the agreed speed) every PC gives the default answer at the same step: auto-resolve, wait for a siege, accept a result with no captives or spoils, occupy a captured settlement. *Known gap, left to playtests (owner, 2026-10-25):* the hold only skips an army's own movement. The world AI can still teleport, re-route or disband a held army (`War.java`, for one), which vanilla never meets because its prompt pauses the world. A smoke run (P45D) saw one held army move during an "enemy withdraws" result. It stays the same on every PC, so sync holds; an army disbanded while its prompt is open would be the case to watch for.
3. **Hook placement:** command hooks go in the declaring method where that's safe. When the class crashes the menu or the method is on a hot path, the hook goes at the UI call site instead.
4. **Several human factions:** that stays the mod's work. The engine keeps the save's single player faction identical on every PC.
5. **Room placement (SOS-34):** the player plans an area room (drag, items, rotation, refurnish, copier, saved blueprints) on their own PC, hidden from the simulation, which also works while paused. Build sends the whole plan as one command, applied at its step on every PC. The owner chose this over sending every planning step as a command.
6. **SOSDET V0.1 (owner, 2026-10-23, during SOS-34 milestone 2):** fewer tests, then a full product the owner tests for 30 minutes.
   - **Order now:** finish B (SOS-34) with the essentials only, then D (world lockstep), then package V0.1. Stage C's 30-minute session is the V0.1 acceptance test, played with city and world in lockstep so trade works. E (the city↔world link) comes after V0.1.
   - **SOS-34 essentials:** the construction-site doorway on refurnish, plans lifted out of menu saves, multi-digit number inputs. The copier, mass copier and saved prints are blocked in a session with a notice and ported after V0.1.
   - **Testing:** build and unit tests always; one rig smoke run at the end of SOS-34 and one at the end of D.


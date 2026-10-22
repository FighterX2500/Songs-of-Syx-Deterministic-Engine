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
2. **World patches:**
   - `Emissaries.penaltyMul` (cache keyed on the render frame).
   - `RDRaces.Visuals.cache` (render writes saved region data).
   - `RDBoostCache`, `DipWarPlayer` and `RaidingUtil`: caches the UI fills (the same treatment as patch 67).
   - `DealBools`: NPC deal drafts depend on the developer flag.
   - `PTitles`: title boost from the local profile.
   - `RDLevelsTmp`: the building popup's draft levels.
   - An audit of `DIP.TMP()`, the deal draft.
3. **World-battle prompt:** it opens from shared state on every PC, shows only on the owning player's PC, and the decision comes back as a stamped command.
4. **The player faction:** inside the step, "the player" is the save's player faction on every PC. This PC's local player never enters the step (AGENTS.md, Determinism).
5. **World commands:** 46 of the 225 (world 30, diplomacy 10, events 6).
6. **Exit:**
   - the rig runs world and city 30 minutes identical with the AI host;
   - the owner plays the world map for 10 minutes or more with no desync.

### E. City↔world link (new card, needs an ADR in SOSGSGMP)

1. **Step split:** the step gets a city part and a world part. `EVENTS` mixes world and city events and gets split.
2. **City → world:** the city values the world reads (about 10 kinds: population by race, tradable stock and trade settings, credits, garrison and invasion power, 7 policies plus the wall count, boosts, tech, level and titles) are snapshotted at the chokepoints (`Player.updateSpecial`, `Player.res/buyer/seller/credits/offensivePower`, `BOOSTABLES.*.get(player)`, `SETT.INVADOR`, `SETT.ENTRY`). Each snapshot becomes a stamped input for a world step.
3. **World → city:** the world's changes to a city (about 8 kinds: deliveries, credits, invasions and raid demands, immigrants, returning soldiers, garrison casualties, supply withdrawals, event triggers) become stamped transactions with IDs.
4. **Engine API:** the mod runs the world plus its own city, and feeds in the other cities' inputs.
5. **Verification:** record one copy's city inputs, then replay them into a copy that runs the world only, and get the same world.

## Outside this plan: the mod's own work (SOSGSGMP)

- **ADR 0005 (world lockstep and lockstep city viewing):** changes spec §4, §10 and §13 and the world row in `architecture.md`. This is a docs PR, allowed in the mod's planning phase.
- **Several human factions:** each player's own faction in one world. Vanilla has a single player faction, referenced 343 times in world code. This is the mod's "single-player assumptions" task, and the biggest open item for multiplayer. The engine keeps one player faction per save and never lets a PC's local player into the step.

## Parallel agents

- **Stage B:** about 5 agents, one per area, each in its own worktree. They check their entry points against the game source and write that area's patches, hooks and rig test. I review and merge.
- **Stage D:** 2 agents on the world patches.
- **Everywhere else:** one agent at a time.
- **Cost:** each area agent costs on the order of 300k tokens. Each wave starts only with the owner's go-ahead.

## Decisions (owner, 2026-10-23)

1. **Order:** the stages run as written, A → B → C → D → E.
2. **World-battle prompts:** the world holds for everyone while a player decides a prompt, as vanilla pauses for its single player and as the spec pauses the world for manual battles.
3. **Hook placement:** command hooks go in the declaring method where that's safe. When the class crashes the menu or the method is on a hot path, the hook goes at the UI call site instead.
4. **Several human factions:** that stays the mod's work. The engine keeps the save's single player faction identical on every PC.

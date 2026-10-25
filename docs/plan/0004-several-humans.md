# Plan 0004: several human factions on the world map (SOS-46)

Status: done, in review (2026-10-25). The owner chose option 1 and accepted [ADR 0004](../decisions/0004-several-humans.md) (2026-10-25); milestone 1 started. Card: [SOS-46](https://fighterx2500.atlassian.net/browse/SOS-46). Branch: `SOS-46-several-humans` (local). The design is settled in an ADR (0004) in milestone 1.

## Goal

2 to 16 humans share one world. Every PC runs the whole world in lockstep, and each PC runs only its own human's city. So the world part must hold every human the same way on every PC, read each human's values from that human's city reports, and treat each human as if they were vanilla's single player (SP1 and SP2, decided by the owner on 2026-10-22).

## What the game does today (V71)

- **One player.** `FACTIONS` builds a `Player` in slot 0 and 63 `FactionNPC`s in slots 1 to 63. All 64 objects live for the whole game (`FACTIONS.java:35-110`). Both subclasses are `final`.
- **The save has no type tags.** It writes the 64 slots in order, and slot 0's bytes are `Player`'s layout (`FACTIONS.java:130-160`).
- **"Is this the player?"** has no helper: about 150 `== FACTIONS.player()` checks in `world/`, `game/faction/`, `game/raiding/` and `game/events/`. There are also 33 `instanceof FactionNPC` branches and 54 casts there.
- **Several hidden dispatchers:**
  - `BValue.vGet(Faction)` sends every faction that isn't a `FactionNPC` to `vGet(FACTIONS.player())`.
  - `FACTIONS.activate` and the activity listeners cast to `FactionNPC`.
  - `RD.DIST()` measures distance and reach from the player's capital (76 call sites).
- **NPC relations are toward one player only:**
  - Each NPC's opinion is one `SuperData` in its `Royalty`, inside the boost framework. There is no table of opinions by pair.
  - Diplomacy is already pairwise (`DIP.data`, 64 × 64), but about 80 one-argument calls fill in the player.
  - Also single: `DipWarPlayer`, `UpVassal`, `Deal`, `NPCRequest`.
- **Events and raids aim at the player.** `EventDiplomacy`, `EventFactionWar`/`Expand` and `EventWorldRebellion` each keep one timer or cursor. Raiding has one active raid, always aimed at the player's capitol.
- **The engine today** (SOS-38) has one report stream. Own-city mode has one player faction; a follower PC reads the source PC's reports. Patches 13, 14 and 98 read "the" report at about 50 chokepoint files.

## The representation (ADR 0004 decides; recommendation first)

1. **Recommended: one `Player` per human, in fixed slots, the same on every PC.**
   - Each human's faction is a `Player` object in its own slot, on every PC, with the same index everywhere. Every human is the same kind of faction on every PC.
   - `FACTIONS.player()` gets one hook:
     - outside the world part (this PC's city part, the UI) it returns this PC's human;
     - inside the world part it returns the step's *current human*, which the engine sets the same on every PC (below).
   - The city half of `Player.update` already runs in the city part. It runs only for this PC's human; the other humans' cities run on their own PCs.
   - Every `Player` read of the city (`res`, `buyer`, `seller`, `credits`, `offensivePower`, `updateSpecial`, boosts) already goes through the report chokepoints. These now read the report of the `Player` being asked.
   - Why: city and UI code (about 620 references in `settlement/` and `view/`) needs `FACTIONS.player()` to be this PC's own human and to own this PC's capitol. Only a `Player` per human gives that without touching that code.
2. **Rejected: humans as `FactionNPC`s with AI off, on every PC, this PC's own included** (the card's first sketch).
   - It runs the same on every PC.
   - But this PC's city has no faction of its own: settlement code reads `FACTIONS.player()`'s capitol and realm, and that `Player` would own nothing in the world.
   - Humans would also run NPC code: court, stockpile, NPC bonuses through `BValue`.
3. **Rejected: puppets only for the other humans** (the mod's old sketch). This PC's human is the `Player` and the others are NPCs, which differs per PC. It desyncs at every `== player()` branch.
4. **Rejected: one index permutation per PC** (this PC's human always in slot 0). The world would match only up to the permutation. Every loop over faction indices would run in a different order on each PC, so the draws would differ.

### The world part's "current human"

- **Code that is about one human** keeps calling `FACTIONS.player()`. The engine runs it once per human, in faction order, with the current human set to each in turn, and with that human's slice of the state. This covers diplomacy events, the war coalition, tribute, rebellion, raids, and opinion toward the player. This is how each human gets their own timers and relations "as if alone" without rewriting each subsystem.
- **Code that loops over every faction** and asks "is this the player?" gets `isHuman(f)` (hook F5, mechanical, about 150 sites). Code that means "the human who owns this" gets that faction: an army's, a caravan's, a region's.
- **Outside those loops** the current human is *none*, and `FACTIONS.player()` inside the world part throws a clear error naming the call site. That way no world code silently reads one human for all of them.

## Milestones

Each milestone is a PR on SOS-46, rig-checked before the next one starts.

### M1. Several humans exist, and each world reads each city (the base)

- **Spike, first: where the humans' `Player` objects live.** Two choices:
  - (a) Spare `Player` objects swapped into the slots the save names as human.
  - (b) Fixed human slots 0 to 15. This moves the NPCs, so every faction-indexed array in an old save would need remapping.

  Done, 2026-10-25 ([spike report](spikes/several-humans.md)): (a) holds. Mogus-72 with 2 NPCs turned into humans saves and loads to the same world hash and a byte-identical `FACTIONS` section. A `Player` assumes it is the only one, so M1 adds:
  - spare Players built in `FACTIONS`'s constructor, a fixed count on every PC (they compose sprites, so they can't be built later, and the texture cache must see them);
  - a `Player` patch: only the first one registers game-wide values, locks and boosters, and each value and booster reads the `Player` it is asked about;
  - `FACTIONS.active()` with every human (it leaves out the swapped-in ones today).
- **ADR 0004:** records the representation, the current-human rule, and the options above.
- **Save:**
  - a human table (slots, and each one's stable id for the mod), with a format version;
  - each human slot saved in `Player`'s layout;
  - old single-player saves load as one human in slot 0.
- **Engine API:**
  - `humans()`, each with its faction id;
  - `addHuman(region)`, which turns an NPC's capitol or a free region into a human: setup, before the session starts;
  - `localHuman(id)`;
  - a report feed per human.
- **Reports per human:**
  - every human's report applies at its stamped step on every PC;
  - pacing waits for every human's report;
  - each chokepoint reads the report of the faction asked;
  - transactions go to their owner, and each PC applies only its own.
- **Hooks:**
  - `FACTIONS.player()` and `GAME.player()` with the current-human context;
  - `BValue.vGet` and `FACTIONS.activate` and its listeners accept `Player`;
  - the city half of `Player.update` runs only for this PC's human.
- **World commands** act for their issuing faction, which they already carry (SOS-34), never for `FACTIONS.player()`.
- **Hash:** covers every human faction.
- **Rig:**
  - `makehuman <faction>` (setup) and a save with 3 humans;
  - `--humans`: each copy runs a different human's city in own-city mode.
- **The casts (owner, 2026-10-25):** a game with several humans crashes in its first frame at the `(FactionNPC)` casts and the `instanceof FactionNPC` branches (85 sites). M1 guards each one so the game runs: there a human is skipped or treated as a faction that is not an NPC. What each human should get at those sites stays M2's work.
- **Until M2 to M5 are done,** the world part's current human outside a per-human loop is the first human, not *none*: with one human the game runs exactly as today. The rule that `player()` throws there comes in once the last site is converted.
- **Work order**, each step built and checked before the next:
  1. Humans in the game: spares, the save section, the slot swap, `active()`, `activate`, and `BoostCompound` and `PCredits` reading the `Player` asked; the rig's `makehuman`. Check: a 3-human save and load round trip, without frames.
  2. The current human: the `FACTIONS.player()` hook, this PC's human, the per-human world half of `Player`'s update, the city half for this PC's human only.
  3. Reports per human: a report store per human, the feed in and out by faction, pacing that waits for every human, the chokepoints reading the faction asked, transactions with their owner, the hash over every human.
  4. The cast guards.
  5. World commands for their issuing faction, the rig's `--humans`, and the smoke.
- **Done, 2026-10-25.** Steps 1 to 5, then a desync and crash audit before the smoke ([several-humans audit](notes/several-humans-audit.md)) and its 12 fix groups (owner-approved), then one store of stored city divisions per human (audit C9, found by the first smoke with commands) and the fixes from an agent review of the diff. Checks:
  - S46Q, 3 humans without commands: identical for 600 steps (it split at step 40 before the audit's fixes).
  - S46S, the M1 smoke: 3 copies, each its own human's city in own-city mode, war, peace, a gift and an army move from every human. All copies identical to step 12,000, 799 to 801 engine hashes compared per copy with none differing, 12 commands applied on each, no report misses, no skipped commands or transactions.

### M2. "Any human", each one's empire, goods and money (G1, G3, G4)

- `isHuman(f)` at the "is the player" sites in world, faction, raiding and event code. For example, War AI skips every human, and tariffs, sieges and region attacks treat every human as a player.
- Each human's empire: regions, armies, garrisons, and mercenary pay from that human's credits.
- Goods and money to their owner: caravans, sales, spoils, tribute, demand payments.
- **Done, 2026-10-25.** Every human is vanilla's player at the "is the player" sites (trade, deliveries, mercenary pay, supplies, garrisons, region rules, battle and siege prompts against NPCs), with that human current for the call; war ends, invasion refs, soldiers' relations and the haven's immigrants go to their own human's PC. A one-human game keeps vanilla's branches, draws and saved bytes. An agent drafted the edits from the audit and an agent review found two one-human regressions (a ref line swallowed by a comment, a changed random range), both fixed. Deferred: the region affiliation (M3), the prosecution read (M5), the "besieged" message per PC. Smoke S46T: 3 humans, a status probe, a move, a gift, war and peace from each; identical to step 12,000, hashes agree, 0 report misses; step time as in M1 (mean 7.3 to 7.7 ms, p99 23 ms). One peace deal was refused in the step: its price exceeded the treasury the report showed.

### M3. NPC relations per human (F8, SP1)

- **Opinion:** one `SuperData` per (NPC, human), with the current human choosing the slice for every read and update. This touches the boost framework.
- **Diplomacy:** the one-argument `DIP` and `DipStance` helpers mean the current human. One `DipWarPlayer` per human. `UpVassal`, `Deal`/`DealParty` and `NPCRequest` per human.
- **Saves:** the opinion, request and diplomacy save sections become per human, with a version number and old-save handling.
- **Done, 2026-10-25.** Opinion has one switch point: `Royalty.boostingData()` returns the current human's slice, so every opinion read and write follows the current human, and `Royalty.update` runs once per human. One `DipWarPlayer` per human, `UpVassal` and `NPCRequest` per human, the region affiliation per human, trespass and raid opinion for the army's own human. The other humans' slices are saved in the engine section `SOSDET_RELATIONS`; slot 0's stay in the vanilla sections. Smoke S46W: 3 humans, a gift, a war and a peace; identical to step 12,000, hashes agree. The NPC's stance toward the human at war is WAR while the others keep theirs, and the stored opinion moves only in that human's slice (human 1's opinion of NPC 3 went from 0 to 0.36, the others' stayed at 0). A one-human run reads the same opinion values as before M3. Human 0's opinion of NPC 12 dropping at about step 6,000 is that NPC's king dying: the new king starts with fresh opinions, as in vanilla.
- **Known gaps (agent review):** NPC messages are not yet routed to their human's PC, so the deal-proposal and request-expiry commands check that the issuer has its own request from that NPC, not that the message was made for the issuer. `DIP.playerWarSecond` is still one value (a city stat shows whichever human last changed a stance; the same on every PC).

### M4. Events and raids per human (F9, SP2)

- `EventDiplomacy`, `EventFactionWar`/`Expand` and `EventWorldRebellion` keep their timers and cursors per human.
- Raiding keeps a current raid per human. A raid that reaches a city becomes an invasion transaction for its owner's PC (SOS-38's transaction kind).
- **Done, 2026-10-25.** Each human has its own diplomacy-event entries, war timer, warner and teachings, rebellion timer and warning map, and its own current raid, raid map and raid timer; a raider raids one human at a time and the raider pool stays shared. The NPC war and expansion events run as the largest human and spare a small human anywhere; peace counts war with or alliance to any human. The raid answers act on the sender's raid. Slot 0's state stays in the game's sections byte for byte; the others' are in the engine section `SOSDET_EVENTS` v1, and the hash mixes the war timer, rebellion timer and current raider of every human but slot 0. Smoke S46AC: 3 humans, B's war and A's gift with `wrelations` and `wevents` on every copy; identical to step 12,000, hashes agree, 0 report misses, step mean 7.1 to 7.8 ms (p99 about 24 ms), as in M1. The rebellion timers ran apart per human (A's from its save value, B's and C's from fresh), and the same NPC took B's war while staying A's vassal. Each war timer held still across the reads: vanilla adds to it once per game day (1,152 game seconds) and the reads were half a day apart; the final smoke reads it across days. Known gaps: `teachings` is still not saved (decision 4); messages are not routed per human, so every PC shows every human's raid demand; vanilla's `DipStance.any(f)` compares with `FACTIONS.active().get(0)`, so a human at war with slot 0 can draw NPC joiners against it (left as vanilla; it matters once humans fight each other); `EventFactionCollapse`'s message loop and the raid debug actions still act on slot 0.

### M5. Player-only values the world reads (F10, G7)

- The rest of the world's reads come from the right human's report: `offensivePower`, emissaries, tech boosts, level and titles.
- `RD.DIST()` per human capitol (76 sites).
- `FACTIONS.WORTH()`.
- **Done, 2026-10-25 (done first: M3 and M4 read `RD.DIST`).** `RDDistance` keeps one slice per human (it also multiplied every other human's region output by 0.1), emissaries and `FWorth` read their own human, `offensivePower` reads this Player's report, the prosecution booster reads the region's owner, `RDMilitary` measures from the nearest human and `Chiller` from any human it borders. Base for M3 to M5: `Hooks.humanIndex()` (a human's place among the humans; slot 0 is always 0, so vanilla's fields stay slot 0's) and `Engine.asHuman`, a read as another human between steps only, for the rig. Smoke S46V: identical to step 12,000, hashes agree, step time as in M2.

## Acceptance (owner, 2026-10-25: smoke tests only, with draw tracing, 8 minutes at most)

Replaces the card's 30-minute run. Every run uses `--trace-draws`.

- **M1 to M5:** one smoke each, about 3 to 5 minutes. 3 copies, each running its own human's city in own-city mode, world commands from every human (war, peace, a trade, an army move). The world must stay identical and the engine hashes must agree.
- **Final:** one smoke of 8 minutes at most with every path at once. Scripted checks in the same run:
  - a report or command of one human changes nothing for another;
  - an NPC's opinion and stance toward two humans stay apart;
  - an event timer runs per human;
  - an old single-player save loads as one human.
- **Docs:** requirements, HANDOFF, determinism rules, and plan 0002's decision 4 and "Outside this plan" line.
- **Result, 2026-10-25.**
  - Final smoke S46FIN: 3 humans, 33,000 steps (about 2.9 game days, 8.4 min wall), draw tracing on, every human acting: a new army, moves, gifts, two wars and a peace. All copies identical to step 33,000, engine hashes agree (2,199 to 2,201 compared, 0 missed), 8 commands applied on every copy and none skipped, 0 report misses, 0 foreign draws. Step mean 6.6 to 7.3 ms, p99 18.5 to 19.8 ms.
  - One human's command changes nothing for another: A's new army left B's and C's armies as they were, and only C's army moved on C's move.
  - Opinion and stance stay apart: NPC 12 went to war with B, then with C, and stayed A's overlord; each human's opinion entries (war declaration, aggression, betrayal) are its own.
  - The war timer runs per human: across a game day, A's rose from 8,223 to 9,389 and C's from 1,257 to 3,769, while B's held (B was at war, as vanilla stops it then). The raid timers started per human. All of it the same on every copy.
  - B's war with 12 was back at the end of S46FIN. A targeted run (S46PEACE) showed the peace itself holds: 12 left B's wars at once, B paid, and 12 stayed out for 40 s. In S46FIN, C's war on 12 came right after, while B was still at war with 12's allies, so vanilla's joining rule can bring 12 back. It was the same on every copy.
  - An old single-player save loads as one human: Mogus-72 lists slot 0 alone, and `wrelations` and `wevents` read the same before and after a save round trip. The first resave of the vanilla-written file changes 7 vanilla sections, the same on the pre-M3 build (52e034a). A second round trip changes only `SETT` and `COUNTS`, as in the spike, and `SOSDET_RELATIONS` and `SOSDET_EVENTS` are byte-stable. A 3-human save made on 52e034a loads; humans B and C start with no stored opinion and a zero war timer.
  - One human keeps vanilla: a 3,000-step one-human run gives the same world hash at every step on 52e034a and on this branch.
  - Playtest PT1 (three LLM players, copy 3 on a second PC) split at step 34,923, in the world section's events per human: human C's war timer. Cause: `DipWarPlayer.init` loops over `FACTIONS.NPCs()`, and a lazy `DipStance.List` rebuild inside it, a for-each over the same list, reset the list's one shared iterator and cut the loop short. The rebuild's stamp had been moved outside the world part (UI, rig probes, the city part), differently per PC. Fixed in `15-humans`: outside the world part the stance and trader lists are fresh index-loop lists that touch no stamp. An agent audit for the same bug class found no other nested case, and two caches the city part could fill for the world part (`RDRacePopulation.totdTarget`, `BoostCompound.Boo`), now one value per part. The desync report now lists the world part by part (each army, each human's timers), which located the split. After the fixes: the PT1 repro identical to step 22,000 (it split at 18,764 before), the one-human hash equal to 52e034a, and the final smoke again identical to step 33,000 (S46FIN3).
  - Playtest PT2 crashed the two copies of humans in a field battle between humans A and C. A was at war with C without declaring it: C's war on NPC 12 drew in 12's ally NPC 3, A's vassal, and vanilla's ally rule gives an overlord its vassal's enemies. `WBattles.poll` then polled without end on the PCs that don't show the prompt (`isBusty` read the local dialog). Fixed in `99-world-battles` (the engine's prompt test); the repro (PT2RA) now runs identical to step 20,000 and the battle resolves, and the same repro without the fix crashes (PT2RB). The rig's `wstatus` now lists wars with other humans. Whether a human may be drawn into war with another human this way is open (spec §6 covers vassalage between players only); battles between humans stay outside this card.
  - Playtest PT3 (the same, 27 minutes) split at step 124,230: one army of human C 1 px apart on C's own PC, after an intercept order. The army sprite, panel and list call `WPath.moving()` every frame, which zeroed a stopped army's sub-pixel move remainder on the PC drawing it only. Fixed in `98-world-fixes` (only the world part resets it). The rig copies don't draw the world map, so a probe (`wsprite`) makes the same call between steps: the repro splits without the fix (PT3RD, step 10,770) and runs identical with it (PT3RE); one human still equals 52e034a. Open: the remainder is not in the save, so a resync can still start moving armies 1 px apart. The treasury drops the players reported are human A's city's own trade imports (about 1M a day, as in the save's history; rig probe `wcredits`).
  - An agent audit for UI code that writes world state found three more getters (`WArmyState.intercepting`'s lookup, `RDOwner.prevOwner`, `FACTIONS.active()`'s fresh mark); each write is now world-part only. Playtest PT4 ran 25 minutes without a desync, until a rig action with a bad building index crashed a copy (the rig now refuses it). Final smoke S46FIN4 identical to step 33,000.

## Outside this card

Battles and sieges between humans, and in own-city mode (B6). Networking, the lobby, campaign rules and UI, which are the mod's work. Local-view code (fog of war, minimap, overlays: G8), which stays this PC's player outside the step.

## Risks

- **Size:** the largest card yet. About 350 world references, plus the opinion framework, `RD.DIST` and raiding. M3 is the critical path.
- **Hidden references to the player:** `FACTIONS.player()` cached in a field at startup would keep slot 0 for good. M1 scans for these.
- **Shared scratch deals** (`DIP.TMP()`): several humans negotiating in the same step. Commands apply one at a time inside the step, so this should hold. M3 checks it.
- **Pre-existing bug:** `DipStance.any()` reads `active().get(0)` instead of `get(fi)`. It is noted, not fixed, unless a milestone trips on it.

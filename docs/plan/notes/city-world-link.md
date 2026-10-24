# The city↔world link: what crosses, and how stage E builds it (SOS-38)

Date: 2026-10-23 · Card: [SOS-38](https://fighterx2500.atlassian.net/browse/SOS-38) milestone 1 · Decision: [ADR 0005](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0005-world-lockstep-and-city-world-link.md) · Inventory: [city-world-link-scan.md](city-world-link-scan.md) · Spike: [world-load.md](../spikes/world-load.md)

Milestone 1 makes no game changes. It answers three questions: what the world reads from the city and writes into it, how `EVENTS` splits, and whether the world can be loaded alone. Milestones 2 to 4 build on the answers.

## What crosses the boundary

The full list, with every site, is in the scan. In short:

| Direction | Kinds | Step sites | Travels as |
|---|---|---|---|
| A. World reads the city | 10 (+1 that needs nothing) | about 140 | report fields |
| B. City writes into the world | 9 | about 40 | report inputs, stamped (B6 is the exception) |
| C. World writes into the city | 9, of which 6 matter | about 49 (11 are UI commands) | transactions |
| D. Objects that are both | 12 | | split by owner |

- **The report:** about 3–4 KB, or up to about 8 KB with per-division detail for 120 divisions. That matches ADR 0005's "a few KB".
- **What fills it:**
  - stock and trade settings per tradable (A1, A2);
  - credits (A3);
  - population by race and class (A4);
  - capitol garrison, power and invasion state (A5);
  - city division settings (A6);
  - boosts (A7);
  - data-driven values (A8);
  - laws per race (A9);
  - entry state (A10);
  - inputs: soldier joins (B1), supply deposits (B2), capitol population (B3), invasion results (B4), mercy (B5).

## The step split

`GAME.update` runs its resources in construction order:

`ScriptEngine, TIME, Intervals, TmpBoosting, SETT, Armies, BattleThreads, GCOUNTS, WORLD, RAIDING, EVENTS, TOURISM, NOBLES, FACTIONS, EVENT_HANDLER`

City work also runs after `WORLD`: `TOURISM`, `NOBLES`, the city events, `Player.updateSpecial` inside `FACTIONS.update` and `EVENT_HANDLER`. So ADR 0005's "city part first, then the world part, in vanilla's order" can't hold as written. The split order:

1. **Shared, before both parts:** `ScriptEngine`, `TIME`, `Intervals`, `TmpBoosting`. These run on every PC and are compared, as in V0.1.
2. **City part (local):** `SETT`, `GCOUNTS`, the 9 city events, `TOURISM`, `NOBLES`, the city half of `Player.updateSpecial` (tech, titles, levels, price history), the player's inflation (`FCredits.update` for the player, today in `Faction.update`), and the city half of `EVENT_HANDLER`.
3. **World part (lockstep, reseeded from (seed, step)):** `WORLD`, `RAIDING`, the 7 world events, `FACTIONS` without the player's city logic, emissaries (today in the city's `updateSpecial`, but they are world decisions), and the world actions of scripted events.

Each part keeps vanilla's order among its own resources. In co-op mode every PC runs the same split, so co-op still compares equal. It is no longer step-for-step vanilla's order, but this is invisible in play.

Still open for milestone 2: `Armies` and `BattleThreads`. A field battle runs in lockstep (ADR 0003) and pauses the world. An invasion of the own city is a city battle. In milestone 2 they stay where they are, and the choice is made with C6.

## The EVENTS split

`EVENTS` holds 16 `EventResource`s in one list. Each one saves under its own key (`SuperSaver`), so the save already splits by key.

| Part | Events (save key) | Crosses the boundary |
|---|---|---|
| City (9) | `CITIZEN`, `SLAVES`, `DISEASE`, `DISEASE_MILD`, `KILLER`, `TEMP`, `ADVICE`, `ACCIDENTS`, `ENGINE` | only `SLAVES`: a slave uprising takes 75 % of the treasury (`EventUprising:215`), a city write to the player's credits |
| World (7) | `FACTION_EXPAND`, `FACTION_COLLAPSE`, `FACTION_POPUP`, `FACTION_WAR`, `FACTION_PEACE`, `DIPLOMACY`, `REBELLION` | their `FACTIONS.player()` reads are of world state: realm, regions, armies. `REBELLION` reads the capitol garrison (A5) |

The split is clean:
- each part runs its own events, in the existing relative order;
- a world resync loads the 7 world keys.

`EVENT_HANDLER` (scripted events, `game/event/**`) is a separate resource, and it is mixed. It checks city conditions, runs world actions (boosts, opinions, region population, raids) and sets marks that are saved in the world. The plan:
- a stamped input says an event fired or a choice was made;
- the world actions and the marks run in the world part;
- city actions run in the city part. This is B6.

## Corrections to ADR 0005 and plan 0002

These are facts the scan found. The decision doesn't change.

1. **Step order** (above): it is "city part, then world part, each in vanilla's order". Today city and world work interleave, so the split reorders it.
2. **Army supply mostly flows the other way.** Supply depots and soldiers joining push goods into `AD.supplies()`: a city→world deposit, which travels in the report (B2). The world takes supply from the city in only two places, both player commands: sending a division out (`WDivStoredAll.attach`) and `ArmyRecruit`. These are the two-phase supply withdrawals.
3. **The wall count is render-only** (`RDBuildings.levelWall`, read only by `CSprite`). It leaves the report. Another player's PC still needs it to draw that capitol, which is the mod's display data and not world state.
4. **"7 policies":** the world reads 3 values per race: prosecution, war standing and the tourism permit (A9).

## How milestones 2–4 handle the hard parts

These are the scan's five hardest problems, with the proposed answer to each.

1. **Boosts and data-driven values (A7, A8).**
   - Don't list boostables key by key. Hook the one chokepoint instead, `BValue.vGet(Player)`: every route from a region, a division or `clP` to the player ends there.
   - At report time, every `BValue`'s player value goes into the report. In the world part, `vGet(Player)` returns the reported value.
   - The same goes for `GVALUES.FACTION`: snapshot every registered value at report time.
   - Milestone 2 measures both counts. If they are too big, the snapshot is narrowed to the keys the world part reads, which is found by recording reads during a co-op run.
2. **The capitol garrison and invasions (A5, B4, C5, C6).**
   - The world sees the capitol garrison through the report: aggregate power and men, plus per-division detail for world battles.
   - The world's effects on it become transactions: casualties per division, and "invade with spec S".
   - The invasion ID is the transaction ID, allocated by the world, not by the city. The outcome comes back in the report.
   - The heavy one: an `InvasionSpec` holds per-person data, but every PC computes it, so it never crosses the network.
3. **The treasury and trade counters (A2, A3, C1–C3).**
   - Deposits take one phase: goods and credits in.
   - Withdrawals take two: exports, purchases, upkeep, tribute, raider demands and sending a division out. The world asks; the city's next report returns what was taken.
   - The world keeps a ledger of open requests, so a report up to 30 steps old can't be spent twice.
   - This changes vanilla in one visible way: the world no longer sees money or goods move in the same tick.
4. **Soldiers crossing (B1, C4).**
   - A soldier who joins an army travels in the report with their full data.
   - A soldier who returns, or a retiree, is a transaction with their full data.
   - The entry-point pick and its random draws move into the city part.
   - Stored soldiers keep their references in the city's relation table. Changes to them from the world arrive as transactions.
5. **The step split itself** (above), plus the hidden writes. The credit changes inside the `MVassal` and `MessDefeated` constructors become C3 transactions. City listeners fired by world changes (`Invador` on peace, `Creator` on an owner change, emissaries on a capture) become transactions, or move into the world part.

## The world-only resync (from the spike)

- **What works:** loading `WORLD`, `FACTIONS` and `RAIDING` into a running game takes about 0.25 s, and the data is about 0.6 MB compressed.
  - When every PC reloads at the same step, all copies stay identical (W38C).
  - A PC that doesn't reload drifts, so every PC reloads, the host included.
- **What milestone 4 must keep from the own city:**
  - the player's city-owned data inside `FACTIONS` (tech, titles, credits, trade settings);
  - an invasion in progress (`RaidingCurrent.clear` cancels it);
  - the city's world area (`initCity`).
- **What milestone 2 must add:** world entities (caravans) in the world hash's world section. In the spike, only the clock section caught an extra caravan.

## Milestone 2 as built

**The report.** `sosdet.report`: `Field` lists every value, `Shape` says how many each holds, `Count` names the list sizes from the game's data, and `ReportLayout` places them. `CityReport` writes one report in a versioned format: a whole number takes a few bytes, any other value 9. `GameReport` (the adapter) fills it in the city part every 30 steps, and the world part reads it 30 steps later. The first world part after a load takes one at once.

**The reads (patches 12, 13, 14 and 98).** In the world part, each leaf read of the city returns its report field. The game's own rules around those reads stay as they are.

| Codes | What the world part reads from the report |
| --- | --- |
| A1, A2 | Stock, export and import tallies, stockpile space, deliveries on their way, the trade settings, slaves and the stockade |
| A3, B7 | The treasury |
| A4, B3 | Population by class and race, citizens, the capitol's population per race, city soldiers |
| A5 | The capitol garrison: which divisions, and each one's men, race, training, equipment after supplies and experience. Also whether the city is invaded and how hard, and the equipment `FWorth` counts |
| A6 | The city divisions world divisions are made of |
| A7 | Every boost value's player value, plus raid security and the proximity toll (the two class boosts the world reads) |
| A8 | Every `GVALUES.FACTION` value's player value |
| A9, A10, B5 | Laws per race, the entry, mercy |

**Boosts (A7).** The game has no list of boost values. The adapter finds them by asking each booster of each boostable (`BOOSTING.ALL()`, all boosters and `fGlobal`) what it reads, through a `BOOSTABLE_O` that only takes notes. Every booster reads its value that way; `RDBuilding`'s one exception reads nothing. In the world part, two routes read the report:
- `Player.boostableValue`, which every booster calls on the player;
- the default `BValue.vGet(Faction)`, where a player region, division or person ends up.

A value the report has no slot for is read live and counted in `Stats.reportMisses`. That happens only for a booster added after the first report. `Stats.reportBoosts` and `reportValues` give the counts; M2B measures them.

**Faction values (A8).** The report holds every `GVALUES.FACTION` value for the player. In the world part, three places read it: the requirement lockers (`Locker.LockerValue`), the region values made of faction values (`FACTION_*`), and the world camps' deltas (`WHavenType.amount`). Code-defined unlockers (tech, levels) read the player's progress on the `Player` object, which is world data, so they stay live.

**The hash.** The world section now hashes every world entity (caravans, camps) by slot, kind, tile and faction. A new report section hashes the report in force (hash format 4).

**Menu safety.** `menuSafetyCheck` fails the build when a shipped class's static initializer reaches a singleton that is still unset at the main menu (the game's script scan initializes every class in the mod jar). Edits keep their line counts wherever the outer class's initializer is unsafe.

**Known gaps, for milestone 3:**
- City code that runs inside the world part reads report values too. Today that's the listeners fired by world changes, invasions of the capitol (C5, C6) and scripted events (B6). Milestone 3 turns them into transactions.
- The event booster `_BOOST` has routes of its own (`vGet(Faction)`, `vGet(Div)`, `vGet(HCLASS_RACE)`). They read the city's event stat live (B6).
- Caches keyed on `GAME.updateI()`: both parts run at the same `updateI`, so a cache filled in the city part can serve the world part. The garrison list keeps one cache per part; the boost compound's cache is never reached in the world part. Others may show up as desyncs in follower mode (milestone 4).
- Size: a typical report is a few KB. The worst case, 120 garrison divisions with fractional training and equipment plus several hundred fractional boost and faction values, is about 20 KB every 30 steps.

## Milestone 3 as built

**The step.** Part −1 applies the city's commands. The city part runs the city. The world part starts with `stepPart(WORLD)`: it takes a report when one is due, reseeds the world stream, applies the reports now due (and their inputs), then applies the world commands of the step, then runs the world. The step's end runs the city tail: the transactions the world part queued, then the hash.

**Two directions.**
- *World → city: transactions* (`sosdet.txn`). In the world part, a hook queues a `Txn` instead of changing the city. Its ID is `step << 20 | seq`, and the city keeps the last ID it applied, so a repeat changes nothing. The tail makes the same game call outside the world part, so the game's own code changes the city as in vanilla. A transaction that changes something the world part reads (credits, queued and promised goods, the next invasion's ref) also goes in the ledger, which adds it to the report's value until a report taken after it applies.
- *City → world: report inputs* (`sosdet.report.Input`). In the city part (and part −1 and the tail), a hook adds an input instead of changing the world. Inputs travel in the next report and apply in the world part, in order, when it does, about 30 to 60 steps later. Supplies sent (`SentSupplies`) and world marks set (`EventMarks`) are read by the city as sent until then.

| Codes | Direction | What crosses |
| --- | --- | --- |
| C1, C2, C3 | transaction | goods bought, delivered, sold; credits in and out (M3.1) |
| C1 two-phase | transaction, then input | supplies for a division sent out or a recruit: the tail takes what the stockpiles have, and that goes back as a supply input (M3.3) |
| C4 | transaction | a soldier out of a world division comes home or retires; the relation table's writes (M3.2) |
| B1, B2 | input | a soldier joins their world division, with their save data; goods into an army's supplies (M3.2) |
| C5 | transaction | a world battle's survivors and experience for the capitol garrison (M3.4) |
| C6 | transaction | an invasion of the city with the ref the world gives it, its cancel, a war's end calling off that faction's invasions (M3.4) |
| B4 | input | an invasion's outcome for its listeners (raiders), the army stats, the vassal stance after a lost invasion, a world division's survivors of a battle in the city (M3.4) |
| B6 | input | a scripted event's world marks on regions, factions and royals, and its world actions: permanent boosts, opinions, region population, raids (M3.5) |

**World commands in the world part (M3.3).** Commands 7000 up to the step prompts' answer (world actions and world battle decisions), 8000–8999 (diplomacy, emissaries), and the raider payments now wait for the world part, so they change the world where the world changes. The city's commands still apply at part −1.

**Scripted events are the city's (M3.5).** Milestone 2 ran `EVENT_HANDLER` in the world part. It now runs in the city part, as the scan proposed: its conditions read the live city, its people selection marks the city's people, and a choice applies at part −1. What it does to the world crosses as inputs. The event booster `_BOOST` reads the running event and the selected shares (`EVENT_CURRENT`, `EVENT_CLASS_RACE`, `EVENT_DIV`) from the report in the world part, and its cache keeps the two parts' values apart. That closes milestone 2's `_BOOST` gap.

**Known gaps:**
- *Draws in the tail.* The tail's own draws (`randomReachable`, a garrison's experience roll) use the stream state the world part left. They are the same on every PC, but they count in `drawsSinceLoad`, so the world-only draw count of milestone 4 must leave them out.
- *A soldier with nowhere to enter.* When the city has no reachable entry, a returning soldier goes back to their division as an input, and tries again later. Their kit stays in the army's supplies.
- *Relation order.* A soldier joining their division writes the relation table's death (army) record in the world part's next report, after the city has already moved on. The table ends the same; the order of its entries can differ from vanilla's.
- *Faction totals.* `ADSupply.current().faction()` doesn't add the supplies the city has sent but the world hasn't applied yet. Only the per-army reads do.
- *An army slot reused.* Supplies sent to an army that is disbanded, and whose slot a new army takes in the same report window, are dropped by the iteration check; a new army with the same iteration would get them.
- *Moved targets.* A world division that moved or was disbanded before its survivors arrive loses them; a royal whose court changed loses an event mark.
- *World marks read live.* Event code that reads a world mark directly (a requirement on a region or faction, not through `EContext`) sees the world's value, not the one the city has sent.
- *Dormant paths.* `WDivCity.generate` and `resolve(Induvidual[])` in the world part are reachable only through Engage, which the engine refuses. The step prompts (the declare-war prompt in `WArmyState`) stay at part −1.
- *Not checked against the game.* Goods moved by diplomacy deals, `_INVASION`'s raid from an input, and the vanilla bug that registers an invasion's listeners with the next ref (kept as it is).

## Milestone 4 as built

**Own-city mode** (`Engine.ownCity`, set at step 0 on every PC). Co-op stays the default.
- *Commands.* A city command applies on its issuer's PC only. World commands and the step prompts' answers apply on every PC (`CommandParts.everyPc`).
- *Streams.* The city part draws from a stream seeded with the step and the PC number (`Session.citySeed`), so two cities differ at once. The world part is reseeded as before and is the same on every PC.
- *The hash.* It holds the world, the report the world reads, and the world part's draws (`Streams.worldDraws`, counted only inside the world part, so the tail's draws are left out). The city's sections are zeroed, and the player's credits are left out of the world hash, since the city owns them.
- *Resync.* `resyncSave` and `resyncLoad` refuse: they would reload this PC's city. The world resync replaces them.

**Reports between PCs (ADR 0005 §6).** `reportsOut()` gives this PC's reports taken since the last call. `reportsFrom(pc)` makes the world part read PC `pc`'s city, from the reports fed to `reportsIn(pc, bytes)` in any pieces (`ReportFeed`: each report is a 4-byte length and its bytes). A repeated report changes nothing.

**The follower (SOS-38).** The engine keeps the save's single player faction, so the testable case is a PC whose own city differs and whose world reads the host's reports; it must get the same world. It runs world step N only once the report it reads there has arrived (`Reports.lastReadableStep`: a report taken at T is read until the next report applies, (T / 30 + 1) · 30 + 29). Others' reports don't touch its shadows (`SentSupplies`, `EventMarks`), which follow its own reports. The world's transactions apply to its own city; a two-phase withdrawal's output goes into its own reports, which its world doesn't read.

**The world resync (ADR 0005 §5).** The host saves between steps (`worldResyncSave`) and gets the engine's world state: the step, the next hash epoch, the world part's draw count, and the ledger (`WorldResync`, format version 1). Every PC, the host too, loads with `worldResyncLoad`. `GameWorldLoad` loads WORLD, FACTIONS and RAIDING (the world-load spike), and puts back the player faction's city-owned data around them: credits, goods history, tech, titles, level, race order, bonus settings, trade settings, colours. Reloading the raiders cancels their invasions; the engine keeps those cancels from reaching the city. The city, the reports, the city's last applied transaction and the inputs waiting to go out are kept.

**Known gaps:**
- *An answer's draws.* A step prompt's answer runs at part −1, with the city stream, before the world reseed. A world prompt's answer that draws would differ between PCs. None found that draws.
- *Prompt keys.* City and world prompts share the step prompts' keys.
- *City battles.* The AI general of a battle in the city runs on the host only, so own-city mode with battles in the city isn't supported; the smoke test uses `--no-invade`.
- *Load listeners.* Whether FACTIONS' and DIP's load listeners leave the city alone isn't checked yet; the M4 smoke test is the first check.
- *The host's identity.* A world resync brings the player faction's name, banner and emissaries from the host's save.
- *A world prompt open across a world resync* stays open with the old world's subject.
- *Reports fed before the load* are dropped: `afterLoad` clears the feed.
- *Several cities in one world* (one player faction per PC) are the mod's work (plan 0002, decision 4).

## Estimates

In working time (a day ≈ 6–8 h). The earlier "about 3 days for all of stage E" came before the scan, and was too low.

| Milestone | Work | Days |
|---|---|---|
| 2. Step split and reports | Reorder (1); report codec and the plain chokepoints A1–A4, A6, A9, A10, B3, B5, B7 (1.5); the `BValue` and `GVALUES` snapshot (1); capitol garrison and invasion power from the report (0.5–1); world entities in the hash and a co-op check (0.5) | 4.5–5 |
| 3. Transactions | Queue, IDs and save (0.5); deposits C1, C3 (1); two-phase withdrawals and the ledger (1.5); soldiers both ways (1); invasions and garrison casualties C5, C6, B4 (1.5–2); scripted events B6 (0.5–1) | 6–7 |
| 4. API, follower mode, world-only resync | API and follower rig mode (1); resync that keeps the city (0.5); the 30-minute smoke run and docs (1) | 2.5 |
| **Total** | | **13–15** |

**A cut that saves about 3 days.** Two items could become a follow-up card, while co-op mode keeps both working:
- invasions of the player's capitol, and world battles on the capitol region (C5, C6, B4);
- scripted events (B6).

In the own-city mode, the engine would refuse those for now, as V0.1 refuses Engage. The 30-minute follower smoke run uses `--no-invade` and doesn't need them. That leaves about 10–12 days.

**Decision (owner, 2026-10-23): the full scope.** SOS-38 builds milestones 2–4 with every item above, C5, C6, B4 and B6 included: 13–15 days.

## Live-city reads found after milestone 4 (2026-10-24)

The M4 smoke test and its trace (M4T, `stage-e-smoke-tests.md`) found that the world part still reads the live city in places, outside the report. An audit of the world part followed (WORLD, RAIDING, and the world halves of FACTIONS and EVENTS). It was read-only, and its main rows were spot-checked in the source. The owner decides on the fixes; none is built.

| Where (game source) | What it reads | Effect | Proposed fix |
|---|---|---|---|
| `game/raiding/Raider.java` `hasInterrest`, `isScared`; `RaidingUtil.ransomCurrent`, `defences` | population, the player's credits, RAID_SECURITY, the barracks' requirements, the capital's defences | only the host's world starts a raid (M4T, step 99,216) | carry these values in the report and read them from it in own-city mode (report format version bump) |
| `init/value/Lockers.java:46-62` and the tech, level and title lockers reached through `Lockable.passes/progress`; e.g. `game/events/faction/player/Stance.java:85` returns before its `RND.oneIn(4)` | the player's live tech level, level and title | the world part's draw count; havens (`WHavenType:178`), region efficiency (`RDBuilding:330`) | a report field per locker the world part reads, as `LockerValue` already has (patch 14) |
| `game/faction/FWorth.java:213-219` (`WINT.get`) | a cache keyed on `GAME.updateI()` only, filled in the city part (`PCredits.java:103`) | trust and diplomacy (`RTrust:54`), raider demand (`MessDemand:113`) read the live city's worth | key it with `Hooks.partKey`, as `_BOOST` is |
| `game/faction/diplomacy/UpVassal.java:97` | `credits().worth` history, which only the city keeps | the tribute amount | a report field |
| `game/raiding/RaidingCurrent.java:380` | `SETT.INVADOR().invading()` through the report's `INVADING`, which is stale for up to 60 steps after `RaiderArmy.invade` | a new invasion can be cancelled at once (not a desync) | change `INVADING` in the ledger when the invasion is sent |
| `world/entity/haven/Player.java:111` | WRITES `SETT.ENTRY().immi()` from the world part | a write to the city with no hook | a city input |

The audit also lists caches with the same pattern as `FWorth`, keyed on the update or a step window: `RaidRegion.spoils`, `RaidingUtil.cache`, `DipWarPlayer.init/distress`, `Emissaries.penaltyMul` and `RDMilitaryGar.divisions`. Each needs `partKey` if its owning code is ever called from the city part.

It is unsure about four places:
- the recipe boosts that each settlement randomises (`Recipes.fboosts`, read by `NPCRes.update`);
- `BValue.vGet(HCLASS_RACE)`, which takes the `vGet(Player)` overload around the patched `vGet(Faction)`;
- `DivGeneration.set:214`;
- mercy (`OpsStance.java:156,176`).

W1, the own-city test with the laptop as follower, split on the clock at step 41,520 with no raider active, so at least one of these other reads is live in a normal run.

**The rig's gap:** `GameHash.worldHash` doesn't hash the RAIDING state, so the per-step world hash missed the raider split; only the engine's draw count caught it.

## Not verified

- The byte size of a soldier's data (`Induvidual`) and of an `InvasionSpec`.
- Which battle paths reach `WDivCity.resolve` rather than `invadeCapitol`.
- Whether `Attack.afterExit` runs inside the step.
- How many `BValue`s and `GVALUES.FACTION` values exist: milestone 2 measures this.
- Game scripts under `<game>/base/script`.
- A world load into a PC whose city differs from the host's: this is milestone 4's follower test.

# Several humans: the desync and crash audit (SOS-46)

Read-only audit for plan 0004 (ADR 0004), made after the first 3-human smoke split at step 40 with no commands. Game V71.44. "SRC" = `<game>/info/SongsOfSyx-sources/`, "P" = the patched copy in `build/patched-src/` (same relative path; read where it exists). Line numbers are from the copy named.

Status: the three sweeps are done (A: caches, B: world reads of live `Player` state and casts, C: per-PC inputs, engine one-human paths, saves). Each was a read-only subagent sweep; C1 and C5 were checked by hand, the other rows when fixed. The owner approved all 12 M1 groups of section F (2026-10-25); all 12 are built (see each group's "Done" line), with the hooks in [hooks.md](../../hooks.md) and the new commands in [commands.md](../../commands.md).

## Why a second human is new for determinism

The engine made one human's game deterministic. A second human adds one new per-PC value: `FACTIONS.player()` (and `GAME.player()`). Patched, it returns:
- in the world part: the current human, slot 0 except inside per-human loops; the same on every PC;
- everywhere else (city part, UI, between steps): this PC's own human, different on every PC.

And each PC runs only its own human's city, so the other humans' `Player` objects are stale there.

So the risks are:
1. values computed from `FACTIONS.player()` outside the world part and reused inside it (caches, stored fields);
2. world code that reads another human's live `Player` state instead of the city report;
3. code that assumes exactly one `Player` (casts, NPC lists, `== FACTIONS.player()` branches);
4. engine paths written for one human (inputs, reports, transactions, the hash, saves).

Engine facts the analysis rests on (`Hooks.java`, `StepParts.java`, patched `GAME.java`):
- Inside a step `GAME.updateI()` is the same on every PC, and the city part and the world part share it.
- `Hooks.outsideStep()` is false in the city part, so a cache guarded only by `outsideStep()` can be filled by the city part with this PC's human and read by the world part in the same step.
- `Hooks.cacheKey()` is `updateI` in a session and does not tell the parts apart; `Hooks.partKey()` does.
- An exact-`updateI` cache filled by the UI between steps is safe (the next step increments first); a window cache (`abs(...) < k`) is not, unless guarded.
- World commands apply in the world part with the current human set to the command's PC's human (step 5), so listeners and dirty flags that compare with `FACTIONS.player()` there are deterministic, though they track one human.

## A. Caches and stored values that depend on the player

Coverage: 998 `FACTIONS.player()` and 77 `GAME.player()` hits. All 451 in `game/`, `world/`, `init/`, `util/` read one by one; `settlement/` (185) and `view/` (438) triaged by file for caches, stamps and dirty flags. Also every `GAME.updateI()` stamp outside `view/`, every `VIEW.RI()`/`cacheKey`/`partKey` cache, every dirty flag, `RDOwnerChanger` and `DipActivityListener` in `game/` and `world/`, every one-argument player-relative helper, and every field assigned from `player()`.

### A1. Found by the smoke (confirmed by the draw trace)

| Site | What | Who fills it | World reader |
|---|---|---|---|
| P `world/region/RDDistance.java` `init()` (`bDirty`) | distances and neighbours from `FACTIONS.player().capitolRegion()` | city part: `PTrade.update` (SRC `game/faction/player/PTrade.java:81-106`), P `settlement/trade/PBuyer.java:205-208`, P `settlement/trade/PSeller.java:154-158`, via `RD.DIST().neighs()` | NPC conscripts (`ADConscripts`), `RaidingMap` spoils (P `game/raiding/RaidingMap.java:575`), AI. The first split, step 36 to 40. |
| P `world/region/RDDistance.java` `capitolDist` `dists[]` | filled from `FACTIONS.player()`, never refreshed | first caller | `OpsOther:61` |
| P `game/faction/diplomacy/DipWarPlayer.java` `init()` | 120-update cache of player war data | world event `War`, `DealBools`, UI only (no city caller found) | War event, deals |
| SRC `game/faction/diplomacy/DIP.java` `traders()` | cached per `updateI`, relative to the player | `PTrade`, `PBuyer`, `PSeller`, two UI goods screens | **none**: harmless |
| SRC `game/faction/diplomacy/DipStance.java` `List.all(f)` | the player path shares `state[]` stamps with the general path | — | diplomacy |

### A2. New: read by the world part, can differ per PC

| Site | Code | Kind | Problem | World reader | Suggested fix |
|---|---|---|---|---|---|
| P `world/region/building/RDBuildPoints.java:90-122, 175-177, 205-208, 236-259` | `for (int i = 0; i < FACTIONS.player().realm().regions(); i++) { ... co.lastValue[reg.index()] = -1;` | one fill per part (`partKey`) into per-region arrays `lastValue[]`, `eff[]`, `consumed[]`, never cleared | the city part fills this PC's human's regions with live boosts; those entries survive into the world part, which refills only the current human's regions | `vGet(Region)` for any region, `vGet(Player)`, `consumed(Faction)`: POINT_* boosters read by region boostables, e.g. `Shipper` → `RD.OUTPUT().MONEY.boost.get(r)` | arrays per part, or the world part fills every human's realm from report values; the city part never writes the world's arrays |
| P `world/region/pop/RDRaces.java:66-75`, P `util/data/DOUBLE_O.java:31`, P `RDRaces.java:297-298` | `if (upI != sosdet.Hooks.cacheKey(VIEW.RI()) \|\| upR != t)` … `if (reg.faction() == FACTIONS.player()) return capacity.get(reg);` | step-keyed single-slot cache (`popTarget`) | `capacity()` branches on the asker's player; the key does not separate the parts | `RDRace` loyalty boosters (`RDRace.java:114, 130`), `RDProblem:42`, GVALUES `POPULATION_TARGET` | key on `partKey`; test `instanceof Player`, not `== FACTIONS.player()` |
| P `game/faction/player/PCredits.java:35, 104`, read at P `game/faction/diplomacy/UpVassal.java:97` | `credits = Math.max(credits, FACTIONS.player().credits().worth.get(0)-...` | stored history (saved), not in the report | `worth` is set only in the owning PC's city part; the other PCs hold a stale copy | `DIP.update` → `UpVassal.update` → `new MVassal(...)`: the tribute amount differs per PC | add `worth` to the city report, or compute the tribute from report fields |
| P `game/faction/player/emmi/Emissaries.java:236-238` | `if (sosdet.Hooks.cacheKey(VIEW.RI()) != viewI) { ... DIPLOMACY.get(FACTIONS.player())` | step-keyed cache, one per Player | reads `FACTIONS.player()`, not the owning Player; the key does not separate the parts | `RDOwner.update:74`, `OpsEmi`, `Emissaries.update:283`. No city caller today: latent | `partKey`, and the owning Player |
| P `game/faction/diplomacy/DipWarPlayer.java:257-307` | `if (sosdet.Hooks.outsideStep() \|\| Math.abs(d.upI-GAME.updateI()) < 120) return d.distress;` | 120-update window cache (`distresses[]`) | `f == FACTIONS.player()` branch subtracts `SETT.INVADOR().invadingPower()` (this PC's city); a city-part fill would leak | `init()`, `coalitionAdvantage()`, `DealBools:402-404`, War event | key on part and player; fill only in the world part |
| P `game/raiding/RaidingUtil.java:126-155` (`defences` 41-57) | `if (sosdet.Hooks.outsideStep() \|\| Math.abs(cp-GAME.updateI()) < 128) return;` | 128-update window cache | built from `FACTIONS.player()`'s capitol and armies; guarded only by `outsideStep`. No city caller found | `Raider.isScared` → `weakestRegionPow`; `RAIDING.active()` | `worldCacheClosed()` as in `RaidingMap`; key on the human |
| SRC `world/map/pathing/WRegFinder.java:25-49, 364`, SRC `world/map/pathing/WRegSel.java:40-80` | `if (upI == GAME.updateI() && tx == lx && ty == ly && lastTreaty == treaty && selector == lastSelector) return li;` | exact-`updateI` single-slot cache | selectors are singletons with static parameters; some treaties read `FACTIONS.player()` (`FACTION_REACHABLE_NPC_TRADE`, `DipWarPlayer.treaty`, `Rebel.ally`). The city fills it (via `RDDistance.init`) only with player-independent treaties today. Low | AI, `RDDistance`, `TradeShipper`, events | add the part, the player and the selector parameters to the key, or drop the cache |
| P `world/region/RDMilitaryGar.java:72-84, 133` | `if (lReg == r && GAME.updateI() == upI) return res;` … `Race big = FACTIONS.player().race();` | exact-`updateI` single-slot cache | leftover soldiers get the asker's race. City callers (`Attack.java:73, 176, 194`) pass only the local capitol, which returns before filling. Low | `RD.MILITARY().power`, `divisions`, `Player.offensivePower` | key on part and player; use the region owner's race |
| SRC `world/entity/haven/Player.java:42-44, 85-132` | `if (f == FACTIONS.player()) return oldValue[t.index()];` | stored world state (`oldValue[]`, saved) | written in the world part for slot 0. (1) Each PC's city reads it (`Immigration:375`, `PlanEmmigrate:43`) and gets slot 0's haven progress; (2) the world part writes `SETT.ENTRY().immi().setHigher(...)` (line 111) into this PC's city, whichever human that is | city immigration | arrays per human; the immigration effect goes to the owning human through a transaction |

### A3. Deterministic, but they serve one human (milestones 2 to 5)

Written only in the world part, so equal on every PC; wrong for humans other than slot 0.

| Site | What |
|---|---|
| SRC `game/faction/diplomacy/DIP.java:52, 248, 299-303` | `pemeny`, `playerWarSecond`: one value for the world. The city reads it (`StatsBattle:123`). Also `DipStance.any()` reads `FACTIONS.active().get(0)` instead of `get(fi)`: a game bug |
| P `game/faction/diplomacy/DipWarPlayer.java:46-63` | `warName`: one war name (text, `KingMessages:159, 167`) |
| P `game/raiding/RaidingMap.java:68-88, 302-412` | marked dirty only when the change involves the current human; one map |
| P `game/faction/trade/TradeManager.java:116-127, 185-192` | `shipper.init(FACTIONS.player())`: only slot 0 trades with NPCs |
| SRC `game/events/world/EventWorldRebellion.java:65-74` | one rebellion timer and warning bitmap |
| SRC `world/region/RDOwner.java:70-79` | slot 0's emissary assimilation applies to every region not owned by slot 0, including other humans' |
| `== FACTIONS.player()` branches | `RDUpdater:53`, `RDRaces.capacity`, `RDBuildPoints.eff`, `RDMilitary.garrisonTarget:152`, `ADConscripts:76`, `RDDistance` booster `:96`, `RDOwner` affiliation booster `:51` |
| P `game/faction/player/Player.java:339` | `offensivePower` uses `FACTIONS.player().credits()`, not this Player's |
| P `world/army/WArmy.java:216-231` | only the current human's armies return supplies |
| SRC `game/faction/royalty/NPCCourt.java:220-223` | an NPC with no king falls back to `FACTIONS.player().race()` |
| `WRegSel` static parameters + the `WRegFinder` key | two callers in one step with the same origin and treaty but different factions get the first caller's list (e.g. `Attacker.java:34, 125`): wrong in vanilla too |
| P `game/faction/player/Player.java:244-250` | old-save fallback: every human's race order comes from slot 0's race |
| P `game/faction/FACTIONS.java:90` | `FactionProfileFlusher.load(FACTIONS.player())`: slot 0 gets this PC's profile on a fresh game; overwritten by a load |

### A4. UI or render only (no desync)

- `world/map/fow/FOW.java:57-94, 156-165` (P): the fog bitmap is rebuilt in the world part for slot 0, so every PC shows slot 0's fog. Render only. Per human in milestone 5.
- SRC `view/world/ui/army/List.java:47`: `Faction f = FACTIONS.player()` captured when the UI is built (slot 0 while loading): other PCs may list slot 0's armies.
- P `view/ui/diplomacy/UIDipMessDeal.java:59-60`: `powerP` stored from slot 0 when a world event makes the message, compared with the local human's power: the message can expire on other PCs. Its `pactive()` re-initialises the shared `DIP.TMP()` between steps; every world user calls `setFactionAndClear` first, so the world is safe.
- `GAME.Cache` UI caches over the local player (`UINotifications`, `UIPanelTopWorld`, `UICaravanList`, `UICampList`, `UIMiniRaces`, `UIMiniResources`, `UIPanelTopSett`, `UIAdvice`, `IManager`, room print lists), `WorldMinimap` dirty flags, `RDBuildings.levelWall`, `RDRaces` visuals, `RDLevelsTmp` costs, `Row:44`, `MainChart:26`, `GameSpec` save header, `PlayerColors` static saver.
- City-only caches (each PC's own city, read by the world through the report): `RoomProduction`, `GuardPower`, `StatsMultipliers:917`, `RoomEmploymentIns`, `UtilStats`, supply `Cache`/`Crate`/`Gui`, `RoomDistribution`, `EVENT_HANDLER:344, 376`, `_PARDON:52`, `PTech` dirty, `BoostCompound` (patched per human).

Checked, no finding: P `FWorth.WINT` and P `_BOOST` (`partKey`), P `ResourcePrices` (NPC data), P `game/battle/util/Boosts`, P `ADSupply` and `ADPower` (`worldCacheClosed`), P `WDivsCapitol` (keyed on the report), `RDRace.totdTarget`, `WHavenFactionData` (per faction), P `NOBLES`, `RebelBuilder.cacheReg`, `tmp/Data`, P `FACTIONS.active()`, P `Locker`/`Lockers`.

## B. World reads of live Player state, player branches, casts

Sweep of world-side code (`world/`, `game/faction/`, `game/events/`, `game/raiding/`, `init/value`, boosting). Rows that repeat A or C name them and are kept short.

Facts checked first:
1. The current human is set to a slot other than 0 in three places only: the `updateSpecial` loop (P `game/faction/FACTIONS.java:185-191`), command apply (E `game/GameAi.java:81`) and report-input apply (E `Hooks.java:292-300`). Everywhere else in the world part (NPC updates, `TradeManager`, `WORLD`, battles, caravans, `RAIDING`, world events, `DIP.update`) `FACTIONS.player()` is slot 0.
2. The report reads without a slot use the current human (E `Session.java:197-198`): `Hooks.reportD/I/B` (E `Hooks.java:455-473`), `reportBoostClP` (503-511), `invasionRef` (782), `PCredits.getD/credits` (P `PCredits.java:177-186`), `Player.citizens` (P `Player.java:358-359`), the patched `POP`, `SENTRY`, `Invador`, `PBuyer`, `PSeller`, `PBuyerRes`, `PSellerRes`, `PSellerSlave`. Only `reportBoost(v, f)`, `reportValue` and `reportUnlock` take the human from the object.
3. City changes queued in the world part go to the current human (E `Hooks.java:553-561`); only `PCredits.inc` looks up the owner (`creditsOwner`, 565-572).
4. `Player.buyer()`/`seller()` return this PC's city objects for every Player (P `Player.java:145-152`); `Player.res().getAvailable` reads this PC's city (43-50).
5. The NPC list no longer holds the human slots (`GameHumans.put` rebuilds `npcs`, E `game/GameHumans.java:154-160`); `FACTIONS.active()` holds every Player, also one with no capitol (P `FACTIONS.java:310-311`). No array is sized by NPC count and indexed by slot except `EContext` (B17).
6. **No unguarded `(FactionNPC)` or `(Player)` cast is reachable in the world part.** Each has an `instanceof` before it or a `// SOSDET` guard, or is generator or editor code (SRC `world/region/Gen.java:78`, `Placers.java:134, 221`, `world/map/regions/Gen.java:66`, `GenAssign.java:279`).
7. `Induvidual.faction()` returns `FACTIONS.player()` for every person who is not an enemy, NPC royals included (SRC `settlement/stats/Induvidual.java:241-245`).

### B-crash (all from one root: a human other than slot 0 can lose its capitol)

| # | Site | Problem | Suggested fix |
|---|---|---|---|
| B1 | SRC `world/battle/Resolver.java:158-166`, SRC `ResolverSide.java:64`, P `world/battle/Util.java:218-221, 290`, SRC `world/region/RD.java:428-432` | only slot 0's capitol becomes a city invasion (`b.player` is `== FACTIONS.player()`); a siege of human 1's or 2's capitol is auto-resolved and `Util.conquer` takes it like an NPC region. `RD.removeFaction` moves `capitolI` to the realm's last region or to −1: the city's population is then written into an unrelated region (P `Player.java:161-172`) or the capitol is null (B2-B4, C6). Same on every PC | M1 guard: the world part never conquers a human's capitol (the siege fails, or is held); M2: every human's capitol becomes that human's invasion |
| B2 | P `Player.java:351` via P `deal/DealBools.java:420-431` | `offensivePower()` of a human with no regions → `RD.MILITARY().power.getD(null)` → NPE on every PC, through `allyValue` over `FACTIONS.all()` (proposals, deal commands) | guard a null capitol; skip inactive factions in `allyValue` |
| B3 | P `world/entity/army/WArmyState.java:165, 182` | raid spoils caravan to `to.capitolRegion()` = null for a human with no regions (its armies are never disbanded) → NPE | skip when null, as `Util.ship` does |
| B4 | SRC `world/map/pathing/WRegFinder.java:36`, P `DealBools.java:383`, P `DipWarPlayer.java:139` | a deal command from a human with no capitol → null dereference | refuse diplomacy commands from a human with no capitol |
| B5 | P `game/faction/FACTIONS.java:115` | `activate` returns for a Player, so when a human loses its last region `FactionActivityListener.remove` never fires: stances, emissary and event data stay, NPCs stay at war with it, `allyValue` still counts it (B2) | fire remove and add for humans too (M2) |

### B-desync

| # | Site | Problem | Suggested fix |
|---|---|---|---|
| B6 | P `world/region/RDDistance.java:193-248` | = A1. Confirmed city filler: `Player.updateSpecial` city half → `PTrade.update` (SRC `PTrade.java:82, 84, 93`) → `NPCRes.priceBuyP` (SRC `NPCRes.java:146-152`) → `RD.DIST().distance` → `init()`. `bDirty` is set by any owner or stance change, so the PCs part after the first such change. World readers: the proximity booster on every region's output and loyalty (79-99), `TradeManager` tolls, reachable/borders/neighbours, `Chiller`, `DipWarPlayer`, `EventFactionExpand`/`War`, `DealParty`, stored-division return time, GVALUES `PLAYER_*` | fill only in the world part; elsewhere read the world's copy |
| B7 | SRC `game/faction/diplomacy/DipStance.java:88-99, 112-115`, filled from P `FWorth.java:142` ← P `PCredits.java:104` | one shared `player` list per stance but a `state[]` stamp per slot. On PC C the city part (`PCredits.update` → `FWorth.faction(local)` → `worthVassals` → `DIP.VASSAL().all(human 2)`) builds the list for human 2 and stamps `state[2]`; the world part asks for slot 0, finds `state[0]` current and gets human 2's vassals. World readers: `UpVassal.java:75-76`, `WDivMercenaries.java:160-166`, slot 0's vassal worth (raider strength, tribute). The player path also lists only NPCs | the list remembers which slot it was built for (rebuild when another asks), or no fast path |
| B8 | P `UpVassal.java:97` | = A2 (`PCredits.worth`): tribute differs per PC, and `credits().inc(-credits)` puts different deltas into `reports(0)` | `worth` from the report |
| B9 | P `UpVassal.java:239-250` | the overlord message's Accept runs between steps (not a command) on whichever PC clicks: NPC royal opinion changes on that PC only, and the tribute goes to the clicker's human | Accept becomes a command carrying slot 0; the message shows only on slot 0's PC |
| B10 | SRC `init/tech/TECH.java:96-104`, P `PLevels.java:206-215`, P `PTitles.java:~418`, E `game/GameReport.java:117-124, 130-134` | an INDU (person) lock checked in the world part: `ownerHuman(Induvidual)` = `FACTIONS.player()` (fact 7), no UNLOCK slot for INDU, so a live read of slot 0's tech, levels, titles: live on one PC, stale on the others (possible; watch `Stats.reportMisses`) | report slots for INDU locks, or a fixed answer in the world part |
| B11 | P `game/boosting/BValue.java:193, 225` | `return vGet(FACTIONS.player());` binds `vGet(Player)` statically and skips the patched `vGet(Faction)`: a live read for `HCLASS_RACE`. No world reader found (possible) | `vGet((Faction) FACTIONS.player())` |
| B12 | P `game/faction/trade/ResourcePrices.java:35-58` | refreshed in the city part (`PTrade.update`, `FWorth`) on a 16-update window; `PTrade.ri` is not saved and the early return tests the local human's capitol, so PCs may refresh at different steps from different world states; the world part reads them (NPC trade, `FWorth`) | refresh only in the world part |
| B13 | P `RDBuildPoints.java:105-122, 197-213` | = A2 | = A2 |
| B14 | P `Emissaries.java:236-238` | = A2 (latent) | = A2 |

### B-wrong human, same on every PC

| # | Site | What happens to humans 1 and 2 | Milestone |
|---|---|---|---|
| B15 | E `Session.java:197-198` and fact 2 | credits, citizens, stock, slaves, offensive power and `FWorth` come back as the current human's (= C10) | M1 (plan: "each chokepoint reads the report of the faction asked") |
| B16 | P `TradeManager.java:139-140`, P `TradeSorter.java:221, 234-238, 323-324` | their slots run the NPC buy path through this PC's city objects (human 0's report, changes queued for human 0): human 0 imports and pays twice, humans 1-2 never import; NPCs "buying from human 2" take human 0's exports (= C11) | M2 |
| B17 | SRC `world/entity/caravan/Shipment.java:166, 217, 246-248`, SRC `world/region/updating/Shipper.java:45, 56, 61`, P `WArmyState.java:165, 182`, P `Util.java:310, 356` | tax caravans, raid and battle spoils, trade caravans to humans 1-2 are delivered into human 0's city (their tax credits go right, through `creditsOwner`) | M2 |
| B18 | P `Player.java:339-351` | `offensivePower` uses the current human's credits and the one mercenary pool | M2/M5 |
| B19 | P `game/faction/player/emmi/Emissaries.java:163-176` | every Player's constructor (the 15 spares too) adds an emissary booster to `CIVICS.DIPLOMACY`; `GameHumans.spare` does not set those aside, so DIPLOMACY holds 16 copies, each returning its own Player's spent emissaries: each human's available emissaries drop by what all humans spent | M1 (plan step 1: "each booster reads the Player it is asked about") |
| B20 | SRC `game/faction/player/PBonusSetting.java:96-123, 145-152` | each human's saved custom bonuses become global boosters (`vGet(Player)` returns 1) for every Player and are never removed; a reload on some PCs only would add them again (possible desync) | M1 (same plan point) |
| B21 | P `game/events/faction/player/War.java:42-56` | slot 0's war propaganda penalty applies to every human's city | M1 (same plan point) |
| B22 | SRC `world/entity/haven/Player.java:85-111, 129` | = A2 (havens) | M4 |
| B23 | P `world/army/WDivStoredAll.java:30, 76-91`, P `WDivStored.java:80-104` | = C9; `ADConscripts` (SRC :55, 61) also subtracts this global count for any faction | M2 |
| B24 | SRC `world/army/WDivMercenaries.java:47-66, 160-166` | only slot 0 pays mercenary upkeep | M2 |
| B25 | P `world/army/ADSupplies.java:157`, SRC `ADUpdaterDiv.java:34` | their armies never use supplies or starve | M2 |
| B26 | P `RDMilitary.java:55, 152`, P `RDMilitaryGar.java:74` | their capitol garrison is a generic one sized by human 0's population (= C21); makes B1 likely | M2 |
| B27 | P `world/region/pop/RDRaces.java:298-321` | their regions get rebel population capacity (10%) and shrink | M2 |
| B28 | SRC `world/region/updating/RDUpdater.java:53-75`, SRC `Builder.java:58-62, 71` | the rebel builder AI rebuilds buildings in their regions | M2 |
| B29 | SRC `world/region/RDOwner.java:48-50, 70-74`, SRC `OpsEmi.java:69, 93, 103, 154, 166-179` | only slot 0's emissaries act; their regions drift to affiliation 0; NPC opinion exists only toward slot 0 | M3 |
| B30 | SRC `DIP.java:163`, SRC `DipStance.java:52-62`, SRC `EventDiplomacy.java:86-100`, P `War.java:88-157`, P `EventFactionPeace.java:84`, P `UpVassal.java:69-77`, P `DipWarPlayer.java` | NPCs propose, declare war, pay or demand tribute and form coalitions only with slot 0 | M3/M4 |
| B31 | SRC `ResolverPlayer.java:72`, SRC `PSiege.java:153, 170, 182, 215, 244`, SRC `PRegAttack.java:75, 108, 157-159`, SRC `Side.java:196-197` | only slot 0 gets battle prompts, sallies, the siege poll and the 0.8 power factor; their battles auto-resolve | M2 |
| B32 | P `WArmyState.java:54, 78, 192, 364-368`, P `WArmy.java:193-216`, SRC `ai/Rebel.java:54, 93-99`, SRC `ai/Chiller.java:28-30` | their armies besiege neutral regions with no prompt (= C22); trespass opinion and resource return only for slot 0; rebels raid only slot 0 | M2 |
| B33 | P `DipWarPlayer.java:74-75, 258-260` | a deal command for human h refreshes the 120-update cache around h; the slot-0 War event then uses it | M3 |
| B34 | SRC `RD.java:491-496`, P `Util.java:257`, SRC `RDHealth.java:164-169, 188`, SRC `RDBuilding.java:94, 115, 327`, P `RDBuildPoints.java:236-238`, P `RDEdicts.java:63, 118` | building requirements, build-point limits, reset on capture, epidemics and edicts apply only to slot 0's regions | M2 |
| B35 | `game/raiding/*` (P `RaidingUtil.java:35-95`, P `RaidingMap.java`, SRC `RAIDING.java:120-170`, SRC `RaidingCurrent.java:248, 285`, SRC `RaiderArmy.java:194, 212`) | only slot 0 is raided | M4 |
| B36 | P `init/value/GValuesInit.java:200` | `IS_PLAYER` is `t == FACTIONS.player()` read directly but true for every human through `reportValue` | M2 |
| B37 | P `game/event/engine/EContext.java:769` | `FACTIONS.NPCs().size()*NPCCourt.MAX` as a slot bound: royals of the two highest NPC slots can never be picked (no crash) | M2 (one line: `FACTIONS.MAX()`) |
| B38 | SRC `RAIDING.java:159-170` | a booster on RAID_SECURITY returns slot 0's vassal status for every faction | M4 |
| B39 | P `Player.java:248-250` | = A3 / C28 | M2 |

### B-local view only (no desync)

- FOW: = A4 / C32.
- World-part messages show slot 0's on every PC (P `Emissaries.java:284`, P `UpVassal.java:70-77`, SRC `WDivMercenaries.java:70-76`, P `ADSupplies.java:166-170`, SRC `RDHealth.java:167`, SRC `PSiege.java:162`, P `Util.java:219-220`, SRC `haven/Player.java:90-119`, raiding). The interactive ones can be answered by humans 1-2 and then act for the clicker's human: deal offers through P `view/ui/diplomacy/UIDipMessDeal.java:129` → a Diplomacy command applied as the clicker's human; prompts through P `WArmyState.java:368` (= C1, C16). B9 is the one that also desyncs.
- Minimap and overlays (SRC `WorldMinimap.java:66, 151, 165`, SRC `WorldOverlays.java:59, 90, 104`, SRC `OverlayRegnames.java:130, 162`, SRC `RDBuildings.java:49-58`) show reachability around slot 0 or a mixed human (follows B6).

Checked and correct: the patched `BValue.vGet(Faction)` and `Player.boostableValue` (report per human), `PCredits.inc` (`creditsOwner`), `Lockers`/`Locker` faction and region locks (per-owner report), the `updateSpecial` capitol population (current human = i), `FWorth` (part key), `RDMilitaryGar.WDivsCapitol` (per part), `ADPower`/`ADSupply` (`worldCacheClosed`). `RDMilitaryGar.extractLostEquipment` (writes the city directly) is dead code; `extractSpoils` is never called.

## C. Per-PC inputs, engine one-human paths, saves, UI crashes

Sweep of the engine (`Session`, `Hooks`, `Engine`, `game/*`, `game/cmd/*`, patches 12 and 15) and the game paths they touch. "E" = `src/main/java/sosdet/`. "W" = reaches the world part; "H" = the engine hash sees it. Verified by hand: C1 (`CommandParts.everyPc` holds `ANSWER`, `world()` does not, so the answer runs at step start where `FACTIONS.player()` is each PC's human) and C5 (`Session.afterLoad` resets `humans` but not `city`). The rest as reported by the sweep, to verify when fixed.

### C-critical

| # | Site | Code | Kind | W/H | Effect with 3 humans on 3 PCs | Suggested fix |
|---|---|---|---|---|---|---|
| C1 | P `world/entity/army/WArmyState.java:338` (prompt opened at :367); answer applied by E `game/cmd/StepPrompts.java:25-41` at step start (E `Hooks.java:195`, part −1) | `ROPINION.STANCE().setNewStance((FactionNPC) aReg.faction(), DIP.WAR(), aa.faction() == FACTIONS.player());` | per-PC input | W, H (`DIP.WAR`) | The "declare war and besiege?" prompt opens on every PC; any PC may answer. `ANSWER` (7950) is every-PC but not a world command, so it runs where `FACTIONS.player()` is each PC's own human: each PC declares war for its own human. Certain desync on the first such prompt (the smoke's `wwarnear`/army moves can hit it) | make `ANSWER` of a world prompt a world command; keep the prompt's human with its kept action and set the current human to it; open the dialog only on that human's PC |
| C2 | E `Hooks.java:1288-1299` | `long key = step * 64 + s.stepPromptsInStep++;` | per-PC input | W | city prompts (SRC `settlement/battle/invasion/Invasion.java:352`, one PC's city) and world prompts share one per-PC counter: a city prompt in the same step shifts the world prompt's key on that PC, and an answer runs a different action there | separate counters and key spaces for world and city prompts; city answers not every-PC |
| C3 | E `game/GameHumans.java:253-264`; P `init/value/Lockers.java:71`; SRC `game/faction/player/PTitles.java:397-407` | `vv.LOCK.inits.add(p);` | spare Players | W, H indirectly | `spare()` sets aside `GValueCat.map` and `Locks.map` but not their `inits`; `GVALUES.finishSetup` runs after `new FACTIONS()` (SRC `game/GAME.java:168`, `:178`), so each lockable gets one copy of each level and title lock per spare; `Lockable.passes` ANDs them and an unused spare's title is never selected, so title-gated unlocks stay locked for every human. Open: does any V71 title have `UNLOCKS_*`? | set aside and restore `inits` too; title and level lambdas read the asked Player's title or level by index |
| C4 | E `game/GameReport.java:104-109`, E `Hooks.java:531-544` | `for (PTitle ti : FACTIONS.player().titles.all()) { addUnlocks(ti.lockers); }` | per-PC input | W, H indirectly | `unlockIndex` is keyed by this PC's human's lock objects (slot 0's on one PC, a spare's on another), so in the world part `reportUnlock(locker)` hits on one PC and misses on the others; a miss reads live. A level- or title-gated region building passes on some PCs only | index the unlocks by position over the registered (slot 0) lockers; fill from the local human's lockers at the same position; read `reports(owner)` by position |
| C5 | E `Session.java:389-394` (`city()`), `:288-332` (`afterLoad`), E `Engine.java:390` | `if (city == null) { city = new GameReport(); }` | engine lifetime | W | `GameReport` is built once per session and never rebuilt on a load; `pcHuman()` → `isHuman` → `humans()` → `city()` can build it before the save loads. Its identity maps (BValue, Value, Locker) then belong to the old game: report lookups miss and read live. May explain the smoke's 45 report misses on copy 1 only. Pre-existing for a reload, but `pcHuman` makes it early | in `afterLoad` set `city = null` (and the inputs, txn applier, input applier and soldier caches); `humans()` reads `GameHumans` directly |
| C6 | SRC `world/battle/PSiege.java:213-216, 244`; then E `game/GameReport.java:137-143` | `return ((Locker<Region>) lock.unlocker).progress(FACTIONS.player().capitolRegion());` | crash | W | in the world part the current human is 0, so human k's capitol is besieged and auto-resolved like an NPC's and can fall with no city battle; on PCk the capitol is then null and the next report's `playerUnlock` calls `progress(null)` → NPE in the region locker lambda (e.g. SRC `PLevels.java:219-221`) → crash on PCk | guard a null capitol in `GameReport.fill`; M2: every human's capitol is a player capitol in `PSiege`/`WBattles`, invasions go to the owner as INVADE |
| C7 | P `game/save/GameLoader.java:64-69` (VIEW built before the save loads) → SRC `view/world/ui/army/List.java:47`, SRC `view/sett/ui/army/Actions.java:146` | `private Faction f = FACTIONS.player();` | per-PC input via the UI | W (through commands) | while VIEW is built slot k is still an NPC, so `Hooks.player` falls back to slot 0: on PC1 and PC2 the army list and the city's send-out picker show and select human 0's armies, and world commands (E `game/cmd/World.java:75-277`) have no ownership check, so PC1 can move, disband or rename human 0's armies. Also bound to slot 0: `RRow.java:61-62`, `UIProfile.java:41`, `Titles.java:79`, `tech/Info.java:43` | bind these views through a getter on `FACTIONS.player()` or rebuild after load; world commands refuse an army whose faction is not the issuer's human |

### C-high

| # | Site | Kind | Effect | Suggested fix |
|---|---|---|---|---|
| C8 | P `world/region/RDDistance.java:301-311`, P `OpsOther.java:57-62` (`capitolDist`) | per-PC input, W | cached per NPC for good, no step guard, no reset; the UI (`OverlayDiplomacy`, `OtherHov`, `Hoverer`, `UIFactionList`) reaches it through `ROPINION.get` (PROXI factor), so the distance is measured from whichever human asked first: NPC opinions, then wars and deals, differ per PC (same as A1) | per human; cache only in the world part, compute without storing elsewhere; reset on faction activation |
| C9 | P `world/army/WDivStoredAll.java:30-38, 72-89, 108-148`, E `game/GameInputs.java:186-199` | one-human, W | one set of 40 stored city divisions: every human's SOLDIER_JOINS and CITY_DIV_ATTACH share slots by index; soldiers merge between humans; returns and retirees go to the current human's city. Deterministic, wrong | one store per human; divisions named (human, index); transactions to the owner |
| C10 | P `PCredits.java:176-187`, P `Player.java:43-51, 144-152, 337-360`, E `Hooks.java:455-473, 503-511, 781-783`, P `FWorth.java:61, 87, 99, 163` | one-human, W | `reportD(Field.CREDITS, 0)` and the other Player chokepoints read the current human's report, not the asked Player's: in world loops over factions (FWorth, `offensivePower`, trade, deals) human 1's credits, citizens and goods come out as human 0's. Deterministic, wrong; plan 0004 M1 says "the report of the faction asked" | pass the owner: `reports(index())`, `reportD(f, …)` overloads |
| C11 | P `TradeManager.java:113-157`, P `TradeSorter.java:206-355` | one-human, W | a human in slot k≥1 is traded as an NPC buyer through this PC's city trade objects (human 0's report); transactions go to human 0; NPCs "buy" human 0's stock from human k's capitol. Deterministic, misrouted | skip humans in the NPC loop; M2: each human trades as `sellPlayer` with itself current |
| C12 | E `Hooks.java:489-497, 503-511, 514-524, 531-544` | per-PC input, W, maybe H | a report miss reads live on this PC (its city, or a stale copy of another human), visible only in `Stats.reportMisses` | with several humans throw on a miss, or a fixed fallback |
| C13 | E `Hooks.java:825-833`, P `Invador.java:87-99` | one-human, W | every war ending in the world part (also NPC against NPC) becomes a WAR_ENDED transaction for the current human: PC0 cancels invasions it should keep | route to the human side of the war; drop when neither is human |
| C14 | E `Hooks.java:781-795, 808-819`, E `GameInputs.java:214-224`, P `Invador.java:283` | one-human, W | invasion refs are per-city counters; one human's tell or cancel fires the other's listeners or cancels the wrong city's invasion | refs named (human, ref) |
| C15 | P `RDDistance.java:195-200`, P `DipWarPlayer.java:75, 94, 260`, P `ResourcePrices.java:39, 63`, P `RDBoostCache.java:32-38`, P `GAME.java:558`, P `RaidingUtil.java:128` | per-PC input, W | these caches only refuse to refresh between steps; a refresh in the city part, the report fill or the city tail uses this PC's human and its live city, and the world part reads it (A1/A2 give the confirmed fillers) | refresh only in the world part (`Hooks.reportRead()`); compute without storing elsewhere; per human where it is about "the player" |
| C16 | E `game/cmd/WorldBattles.java:81-128`, `Events.java:59-70, 177-197`, `Diplomacy.java:50-61`, E `Hooks.java:1271-1280` | per-PC input via the UI, W | world prompts and messages for human 0 (battle prompt, raider demand, NPC proposals) open on every PC; any PC answers, and the answer runs as the answering human (its mercy, its credits pay the raider, the deal is struck with it) | record the target human; show only on that PC; refuse other PCs' answers; run as the target |
| C17 | E `Engine.java:253-256, 364-397`, E `Hooks.java:374-391` | one-human / crash, W | `localHuman()` does not set `pcHumans[localPc]`, unset PCs default to slot 0, nothing checks the table is complete and distinct; `Hooks.player` silently falls back to slot 0 | `localHuman` sets `pcHumans` too; validate at step 0 and after a load; throw instead of the fallback; mix `pcHumans` into the hash clock |
| C18 | E `Session.java:202-207, 351-362` | crash / per-PC, W | `humans` is cached at first use; a `reportsIn` during a load (before `GameHumans` swaps the spares) caches `[0]` | refuse or queue `reportsIn` while loading; recompute in `loadEnd` |
| C19 | E `Engine.java:184-189, 321-327`, E `GameHumans.java:174-204`, P `Player.java` save | save, – | full resync is refused in own-city mode and world resync with several humans: a desync cannot be recovered. A save holds the saving PC's city and stale copies of the other humans' city-side Player state; one save loaded on every PC gives each the same city | M3/M4 (plan 0004): coordinated per-PC saves with identical world sections, or world resync with per-human ledgers. *Closed by SOS-52 (PR #33):* the world resync with per-human ledgers |
| C20 | E `game/GameHash.java:120-165` | hash | covers armies, world entities, region owners, active factions, the war matrix; correctly leaves out humans' credits. Leaves out: other stances, vassals, opinions, emissaries, region data (population, buildings), stored divisions, supplies, `pcHumans`, the human list. C3, C4, C8, C9, C16 can diverge long undetected | add the stance and overlord tables, an opinion summary per NPC, emissary targets, the human slots and `pcHumans`, region building levels |
| C21 | P `RDMilitaryGar.java:72-77`, P `RDMilitary.java:148-170` | one-human, W | only the current human's capitol gets the real garrison; other humans' capitols get an NPC-style garrison from the current human's population. Feeds C6 | the owner human's report and divisions |
| C22 | P `WArmyState.java:364-370`, SRC `PSiege.java:170-216` | one-human, W | humans 1 and 2 besiege non-war regions with no prompt or declaration; their sieges resolve as AI sieges | `isHuman(a.faction())` with the army's owner current (M2) |

### C-medium and low

| # | Site | Effect | Suggested fix |
|---|---|---|---|
| C23 | E `Hooks.java:203-208, 426-429` | city path jobs run at step end with the part still the world part and the current human 0, on worker threads: report hooks and `FACTIONS.player()` give human 0 on PC1 and PC2 (wrong city behaviour) | tag jobs with part and human, or check them in `reportRead()`/`player()` |
| C24 | P `FACTIONS.java:303-317`, P `RDDistance.java:250-253` | a human without a capitol is still in `active()`; NPC-style branches (`RD.DIST().distance(human)`) NPE; UI lists can crash | guard null capitols; leave capitol-less humans out of NPC loops |
| C25 | E `GameHumans.java:114-142` | `add()` accepts an inactive NPC slot: a human with no realm, whose city part NPEs | refuse slots without a capitol |
| C26 | E `Hooks.java:808-819`, E `GameTxns.java:89-97` | through C9 and C13, human k's REL_* and SOLDIER_RETURNS transactions apply in human 0's city; the ids may not exist there (crash risk, uncertain) | fixed by routing to the owner (C9) |
| C27 | E `Engine.java:224-247`, E `Session.java:338-348` | follower mode (`reportsFrom`) rejects the other humans' reports, so the world never runs: a hang | refuse `reportsFrom` with several humans (rig.py already refuses `--follow` with `--humans`) |
| C28 | P `Player.java:249` | old-save race-order fallback reads `FACTIONS.player()` during load (this PC's human, maybe not loaded yet) | `Player.this.race()` (same as A3) |
| C29 | E `game/cmd/Names.java:86-94, 154`, E `BannerDraft.java:117` | faction or ruler name and banner are city commands applied only on the issuer's PC; other PCs keep the old ones. Not hashed | a world command for the issuer's human |
| C30 | P `Player.java:113, 127` | `PlayerColors.saver` static: the last slot loaded wins (cosmetic) | colours per slot |
| C31 | SRC `PTitles.java:100-141` | every spare reads this PC's profile for title unlocks; only the UI differs | skip for spares |
| C32 | P `FOW.java:56-66, 155-163`, P `WArmy.java:162-166` | fog computed in the world part for human 0: every PC sees human 0's fog (same as A4) | compute outside the step for this PC's human |
| C33 | P `UpVassal.java:163` | the world part rewrites the UI's scratch deal (`DIP.TMP()`) with human 0 as party: PC1's open deal screen resets. No desync | `TMP2` or a private deal |
| C34 | E `GameHumans.java:217-250` | 15 debug-panel entries per spare act on unused spares | ignore or suppress |
| C35 | E `Hooks.java:369-380` | outside per-human loops the current human is slot 0, so about 150 world `== FACTIONS.player()` sites serve human 0 only (events, raids, coalitions, tribute, tolls). Deterministic | the planned M2-M5 work |

### Checked and sound

Reports (one store per human; `worldMayRun` waits for every remote human; the load report goes out in `stepBegin`); transactions (`ownTxns` applies this PC's human only; every PC keeps the ledger for every human); inputs (the `reportsFrom < 0 && currentHuman == localHuman` flag settles sent supplies and event marks for own reports only); event marks and actions (travel in reports, run with the owner current); world commands (current human set and reset around each); `FACTIONS.update` (each Player's world half in slot order, the city half for this PC's human only); `GAME.updateI()` counts steps and is saved; a single-human save loads (`GameSaver` skips the unknown `SOSDET_HUMANS` key); spares go into slots in slot order on every PC; humans' credits are left out of the hash in own-city mode.

## F. Proposed fix list (for the owner's approval)

Rule for the split: M1 fixes everything that can **desync or crash** a 3-human game, plus what plan 0004 M1 already promises and step 1 to 5 left out. What is the same on every PC but wrong for humans 1 and 2 stays with M2 to M5, as planned: the smoke checks identical worlds, not fair treatment.

### M1, before the next smoke

Each group is one change, built and unit-tested before the next.

1. **World caches are filled only in the world part.** Replace the `outsideStep()` guards with the existing `Hooks.worldCacheClosed()` (true in the city part, the UI and other threads); there the cache is read as the world last built it (or computed without storing), never refilled. Sites:
   - `RDDistance.init()` and `capitolDist` (A1, B6, C8): the confirmed split;
   - `DipWarPlayer.init()` and the distress cache (A2), `RaidingUtil` (A2), `ResourcePrices` (B12), `RDBoostCache`, `GAME.java:558` (C15);
   - `RDRaces.popTarget`, `Emissaries.penaltyMul` (A2): key on `partKey` instead of `cacheKey`;
   - `RDBuildPoints` (A2): the city part never writes the world's arrays.
   Done: `worldCacheClosed` in `RDDistance` (both), `DipWarPlayer` (both), `RaidingUtil`, `RDBoostCache`, `ResourcePrices`; `partKey` in `DOUBLE_O` and `Emissaries`; `RDBuildPoints` keeps one array set per part. `GAME.Cache` (C15) turned out UI-only and is unchanged.
2. **`DipStance.List.all(f)`** (B7, A1): the player list remembers the slot it was built for and is rebuilt when another slot asks.
   Done: the list keeps its own stamp and the human it was built for.
3. **World prompts and messages belong to one human** (C1, C2, C16, B9):
   - a world prompt and an interactive world message record the human they are for;
   - they show only on that human's PC;
   - the answer is a world command, applied with that human as the current human; other PCs' answers are refused;
   - world and city prompts get separate keys;
   - the overlord message's Accept becomes such a command.
   Done: world questions are command 7951 (world, applied as the asked human; others skipped), city ones 7950 (issuer's PC only), keys 0-31 and 32-63 of the step; the battle prompt shows only on its human's PC and its open test is the engine's; raider and NPC-request answers apply only from the first human (until M3/M4); the overlord Accept is command 8005. Interactive messages still show on every PC (M3/M4); only the answers are guarded.
4. **The engine across a load** (C5, C17, C18, C27):
   - `afterLoad` drops the city report (`city = null`) and the caches built from it; `humans()` reads `GameHumans`;
   - `localHuman` also sets `pcHumans[localPc]`; the PC table is validated at step 0 and after a load (each human one PC, each PC one human); `Hooks.player` throws instead of falling back to slot 0;
   - `reportsIn` during a load is refused; `reportsFrom` is refused with several humans.
   Done: `Session.dropGameObjects` at load start and end; `pcHumans` starts unset (-1), `localHuman` sets it, `checkPcHumans` before the run and after a load in the run, `humanOf` throws for an unset PC with several humans, `Hooks.player` throws outside loading; `reportsIn` refused while loading, `reportsFrom` with several humans.
5. **Report reads follow the faction asked** (C10, B15, fact 2; plan M1 "each chokepoint reads the report of the faction asked"): `PCredits`, `Player.citizens`, `res`, the patched `POP`/`SENTRY`/`Invador`/buyer/seller reads take the slot from the object; `reportD(field, slot, i)` overloads.
   Done for the reads that know their owner: `PCredits` (`reportCredits`), `Player.citizens`. The other chokepoints are this PC's single city objects (trade, population, invasion), which stand for the current human until M2 gives each human its own.
6. **Report misses and unlocks** (C4, C12, B10, B11):
   - the unlock index by position over slot 0's lockers, read from the owner's report;
   - INDU locks get report slots or a fixed answer in the world part;
   - `BValue.java:193, 225` calls `vGet((Faction) …)`;
   - with several humans a report miss throws (a test run finds the rest).
   Done: unlocks indexed over slot 0's lockers; a person's unlock is 1 in the world part; `BValue` defaults call `vGet((Faction) …)`; `Session.reportMiss` throws with several humans.
7. **Spare Players register nothing game-wide** (C3, B19, B20, B21; plan M1 step 1):
   - `GameHumans.spare` also sets aside the `inits` of `GValueCat` and `Locks`;
   - the emissary, custom-bonus and war-propaganda boosters register once and read the `Player` asked;
   - first: check whether any V71 title has `UNLOCKS_*` (decides how urgent C3 is).
   Done: 34 of 68 V71 titles and every level have `UNLOCKS_*`, so this was live. Spares' `Lockers` promises and directly added boosters are dropped; title locks read the asked human's title; emissary boosters and custom bonuses read the asked human; war propaganda applies to slot 0.
8. **`PCredits.worth` in the report** (A2, B8), so the vassal tribute is the same on every PC.
   Done: report field `WORTH_SEASON` (protocol 5).
9. **No crash from a lost capitol** (B1 to B4, C6, C24, C25): the world part never conquers a human's capitol (M2 makes it that human's invasion); null-capitol guards in `GameReport.fill`, `offensivePower`, `allyValue`, raid spoils and diplomacy commands; `GameHumans.add` refuses a slot without a capitol.
   Done: `Util.conquer` never takes a human's capitol; guards in `GameReport.playerUnlock`, `Player.offensivePower` (also this human's treasury now), `DealBools.allyValue`, raid spoils, deal commands; `GameHumans.add` refuses a slot without a capitol.
10. **World commands only for the issuer's own armies** (C7): `World` commands refuse an army whose faction is not the issuing human; the army list and send-out picker read `FACTIONS.player()` through a getter instead of a field captured while the UI is built.
   Done: `Args.ownArmy` in every world army command (not the intercept target), own divisions only; the army list and send-out picker ask `FACTIONS.player()` each time.
11. **City path jobs** (C23): run them with the part reset, so report hooks and `FACTIONS.player()` give this PC's human.
   Done: run with the part set to the city.
12. **Hash** (C20; plan M1 "covers every human faction"): add the human slots and `pcHumans` (clock section), the stance and overlord tables. Opinions, emissaries and region data come with M3/M5, where they become per human.
   Done: every stance (not only war) and each faction's overlord in the world section; the human slots and `pcHumans` in the clock section with several humans.

Then: build, unit tests, the 3-human smoke (`--humans 3 --trace-draws`, 3 to 5 minutes, `cmds3.txt`), and watch `Stats.reportMisses` (0 expected after group 6).

### Found after the fix list (fixed in M1)

- **C9, found by the first smoke with world commands** (S46R crashed on every copy: "I'm full! 200 200" from `WDivStored.add`, through SOLDIER_JOINS): one store per human. Slot 0's stays `AD`'s; the others are made at first use and saved in the engine's section `SOSDET_CITY_DIVS`, after `AD`'s. Each store updates as its human, reads its human's report, and sends returns and retirees to that human's city (C26). An army's city divisions are looked up in its faction's store.
- **Review of the M1 diff** (an agent, read-only):
  - the load report was stamped `takenAt = S+1`, the same as the regular report when S+1 is a 30th step, so the other PCs dropped the second as a repeat: a desync on 1 load in 30. Now stamped `takenAt = S`, applying at S+1;
  - a world-battle prompt's timeout ran as human 0 and couldn't close a prompt asked of another human: it now runs as the human asked;
  - the hash ran with a part that depended on whether this PC ran city paths or a city tail: the part is now fixed before the hash;
  - a siege of another human's region (battles between humans are outside this card) could take a human's capitol and leave it without one: refused. The region attack (a region's garrison attacking a foreign army in it) is accepted only on the issuer's own region and against an army no human owns; the M1 change to `ownArmy` had refused it for every human, found by the M2 sweep;
  - `Args.npc` range-checks the slot (`-1` from the tribute message threw);
  - `reportsIn` names a PC out of range; a person's unlock is read live again in co-op with one human; the PC table is checked again after a load in the run.
- **Checked, no fix:** REL_SOLDIER and REL_DEATH write a stored soldier's `RELATION_DATA` word on the owning PC only. Only the city's UI reads it (`UISubjectActions`), and a returning soldier carries it into its own city, so the step and the hash never see the difference. A save of the world differs there between PCs; a world resync with several humans is refused anyway (C19).

### Later milestones (recorded here; M2 to M5 are done, see plan 0004)

- **M2:** trade and caravans per human (C11, B16, B17), `ADConscripts`' count of stored men (B23; the stores themselves are per human since M1), WAR_ENDED and invasion refs to the owner (C13, C14, C26), `activate` listeners for humans (B5), every `== FACTIONS.player()` branch in armies, regions, battles and supplies (B24 to B28, B31, B32, B34, B36, C21, C22), `EContext` bound (B37), the race-order fallback (B39), the haven write into this PC's city (A2).
- **M3:** emissaries and opinion per human (B29), diplomacy per human (B30, B33, A3 `DIP` fields), the `DIP.TMP()` scratch deal (C33; no result of a check on it is recorded).
- **M4:** raiding (B35, B38, A3 `RaidingMap`), events and the rebellion timer (A3), havens (B22).
- **M5:** `RD.DIST()` per human, `offensivePower` (B18), `FWorth`, FOW per human (A4, C32).
- **Resync** (C19), *closed by SOS-52 (PR #33)*, the world resync with per-human ledgers; before it: a desync can't be recovered with several humans (full resync is refused in own-city mode, world resync with several humans). Needs its own design: coordinated per-PC saves with identical world sections, or a world resync with per-human ledgers. Proposed as a follow-up card, not in plan 0004 today.
- **Still open: cosmetic or UI** (no desync): names and banners as world commands (C29), `PlayerColors` per slot (C30), spares' profile reads (C31), debug-panel entries for spares (C34), `UIDipMessDeal` expiry (A4).

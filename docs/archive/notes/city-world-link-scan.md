# City↔world link: the scan (SOS-38 milestone 1)

Read-only inventory for SOSDET stage E (ADR 0005). Game V71.44. Paths are relative to `<game>/info/SongsOfSyx-sources/`. Line numbers are from the shipped source; spot-checked with `sed`. "Report" = the city report of ADR 0005 §2; "txn" = a world→city transaction (§3).

Method: grep over the world-part and city-part folders for `SETT.`, `STATS.`, `POP.`, `FACTIONS.player()`/`GAME.player()` member reads, the Faction chokepoints (`res() buyer( seller( credits() offensivePower() citizens(`), `BOOSTABLES.`, `INVADOR`, `ENTRY()`, `STOCKPILE`/`tally()`, `.ps()`, `HUMANOIDS().create`, `setDeath`, `.passes(`, `GAME.ARMIES().player()`, `AD.cityDivs()`, `RD.MILITARY()`, plus codegraph for callers and reading every hit. `view/**` (UI) is excluded from the counts and listed separately where it matters. Game scripts under `<game>/base/script` were not scanned (they can do anything).

Notes on the ADR text found by the scan (details below):
- "Supply withdrawals (`ADSupplies`)" is backwards: in vanilla army supply flows city→world (supply depots push into `AD.supplies()`); `ADSupplies` never takes from the city. The only world→city supply moves are two player commands and one deposit.
- "the wall count" is render-only (`RDBuildings.levelWall`, read only by `world/map/regions/centre/CSprite.java:36`). No report field needed for the simulation.

---

## 0. The step today, and where the partition is wrong

`GAME.update` (game/GAME.java:241) runs every `GameResource` in construction order, every sub-step (SOSDET patch 10 also runs `afterTick` at step start):

`ScriptEngine, TIME, Intervals, TmpBoosting, SETT, Armies, BattleThreads, GCOUNTS, WORLD, RAIDING, EVENTS, TOURISM, NOBLES, FACTIONS, EVENT_HANDLER`

(constructors at game/GAME.java:119-175; `GameResource.all` at :452ff). `WORLD.update` (world/WORLD.java:307) runs the `WorldResource`s (AD, WBattles, WorldLog, WorldBuildings, FOW, WorldLandmarks, WPATHING, WREGIONS, WorldRoads, WorldTerrain, RD) and the minimap; `WORLD.afterTick` (:319) runs `RD.afterTick` and `WBattles.poll()`.

Working partition used by this scan:
- **World part:** WORLD, RAIDING, the world events in EVENTS (`EventWorld`: factionExpand/Collapse/Popup/War/Peace, `EventDiplomacy`; `EventWorldRebellion`), FACTIONS except `player.updateSpecial` (faction IUpdater → `Faction.update` incl. the player's `FCredits` update, `TradeManager`, `DIP`).
- **City part:** SETT and its SettResources, TOURISM, NOBLES, the city events (EventCitizen, EventUprising, EventDisease(Mild), EventKiller, EventTemperature, EventAdvisor, EventAccident, EventGeneral), EVENT_HANDLER, `Player.updateSpecial`, GCOUNTS, Intervals, TIME, ScriptEngine.

Where that partition does not hold:
1. **"City first, then world, in vanilla's order" is impossible without reordering.** TOURISM, NOBLES, the city half of EVENTS, `Player.updateSpecial` (inside `FACTIONS.update`, game/faction/FACTIONS.java:174, between `npcManager` and `dip`) and EVENT_HANDLER all run *after* WORLD today. Splitting moves them before WORLD, which changes outcomes vs. vanilla and vs. the V0.1 co-op order.
2. **`Player.updateSpecial` is mixed** (game/faction/player/Player.java:160-198): it writes the capitol's `RD.RACES().pop` from `STATS.POP` (city→world, B3), runs PTech/PTitles/PLevels (city), `emissaries.update` (world-facing, see 3) and `trade.update` (reads NPC prices into the player's price history; read only by city/UI).
3. **Emissaries belong in the world part.** Their arrays are player decisions about NPC royals and regions, read by world code (world/region/RDOwner.java:74; game/faction/royalty/opinion/OpsEmi.java:70, 93, 103, 154, 166-179) and cleared by world events (Emissaries.java:126-135, 202-229). Treat assignments as world commands; only `CIVIC_DIPLOMACY(player)` (the emissary budget) comes from the city.
4. **The player's treasury update runs in the world part but is city logic.** `FCredits.update` (game/faction/FCredits.java:85-97) via `Faction.update`, plus `PCredits.update` (PCredits.java:102-114): inflation proportional to the live treasury, `DEFALTION.get(player)`, an `RND.rFloat()` draw, and `FWorth.faction(player)` (city stock). Move to the city part.
5. **EVENT_HANDLER / EContext is mixed** (B6): it marks world regions, factions and royals, writes world boosts, opinions and region population, and can spawn raids. Run locally it splits the world.
6. **`EVENTS` is one `GameResource`** with one static list (`EVENTS.all`, game/events/EVENTS.java:87) and one `SuperSaver` keyed per event; city and world events interleave in one loop (:129-137) and one random stream.
7. **Per-PC caches the step reads** (determinism hazards, independent of E but on the same code): `Emissaries.penaltyMul` (Emissaries.java:235) cached on `VIEW.RI()` (render frame; SOSDET patch 98 targets it), FWorth cache, `DipWarPlayer.init` (120-tick window), `ResourcePrices` (16 ticks), `RecipeRates.calc`, `BoostCompound`, `RDBoostCache` (`updateI>>5`), `RDBuildPoints.upI`, `RDMilitaryGar.WDivsCapitol` static `supplies/suppliesHave/suppliesNeeded/upI` (world/region/RDMilitaryGar.java:417-461, static mutable, keyed on `GAME.updateI`).

---

## A. World → city reads

Value sizes use the V71 data: 8 races, ~50 tradables (42 resources + 1 slave tradable per race), 8 battle equipment types, 4 supply resources, up to 120 player divisions × 200 men (`Battle.txt`). Hot = runs per tick or per updater slice; daily = trade/AI day; event = on a world event or AI decision.

### A1. Player stock per tradable
- **Chokepoint:** `Player.res().getAvailable(t)` = `ceil(0.9 × t.ps().playerOwned())` (game/faction/player/Player.java:43-51). `playerOwned` = stockpile (reserved + amount) + import capacity used + incoming.
- **Sites:**
  - game/faction/FWorth.java:57 `resources.pget` (all resources, cached per updateI). Step callers of `FWorth.faction(player)`: PCredits.update:103; RTrust.java:54-55 (NPC trust vs. player worth); OpsEmi.java:138; DealBools.java:389; UpVassal.java:95 (MVassal tribute); MessDemand.java:126, 128.
  - game/faction/diplomacy/deal/DealParty.java:73 `max`, :179-184 `init` (binary search on `TradeManager.totalFee`). In the step through `DIP.TMP()` deal drafts: Peace.java:33, Stance.java:95/124/131/138/167/184/197, WarMessages.java:86, UpVassal.java:159, FWorth.java:153-154.
  - game/raiding/MessDemand.java:331 `DEntries.max` (`playerOwned × 0.95`, sizes a raider demand), :160 `canPay`.
- **Type/size:** int per tradable, ~50 × 4 B = 200 B.
- **Hot:** no (daily / per event).

### A2. Player trade settings and export/import capacity
- **Chokepoint:** `Player.seller(t)` / `Player.buyer(t)` → `SETT.TRADE().seller/buyer(t)` (Player.java:146, 151).
- **What is read:** seller (`PSellerRes`): `removeMax` = EXPORT tally − `promised` (0 if `SETT.ENTRY().isClosed()`), `prio`, `removePrice` (player price cap × amount), and `player.trade.pricesSell` (PSellerRes.java:133). Buyer (`PBuyer`/`PBuyerRes`): `buyPriority` (closed entry, credits vs. `minMoney`, price cap, IMPORT capacity, incoming, limit, stockpile space), `addPrice`. Slaves: `PSellerSlave` (`STATS.POP().pop(race, SLAVE)`, limit), `PBuyerSlave` (STOCKADE, crimes).
- **Sites:** game/faction/trade/TradeManager.java:185 (`sellPlayer`, entry closed), :49/53 (tariff); TradeSorter.java:41-177 `sellPlayer` (~10 reads of `removeMax/removePrice/prio`), :209-344 `buy` (player as buyer: `buyPriority`, `addPrice`), :309 credits; FWorth.java:99 (`seller(slave).removeMax`). `TradeSorter.java:192` reads static vanilla recipe rates (not city state).
- **Type/size:** ~6 numbers per tradable × 50 + 2 flags ≈ 1.2 KB.
- **Hot:** no (trade updater, once per trade day).

### A3. Player credits
- **Chokepoint:** `FACTIONS.player().credits()` (`PCredits`, one double plus history).
- **Sites:** TradeSorter.java:309 and `PBuyer.buyPriority` (settlement/trade/PBuyer.java:165-176); UpVassal.java:97-106 (MVassal clamps tribute to the treasury); DealParty.java:38-48 (`credits.max`); MessDemand.java:157, 160 (`canPay`), :364-385 (`set`: demand = credits × RND); RaidingUtil.java:60 (`ransomCurrent`); FWorth.java:163; Player.offensivePower (Player.java:333-342, mercenaries the treasury can afford); world/army/WDivMercenaries.java:54, 64 (upkeep check); FCredits.update / PCredits.update (§0.4). UI-only: world/region/building/RDBuilding.java:355/364/542 `canAfford`, world/region/building/RDLevelsTmp.java:73.
- **Type/size:** 1 double, 8 B (plus `worth` history if MVassal keeps using it).
- **Hot:** no.

### A4. City population by race and class
- **Chokepoints:** `POP.tot(cl, race)` (settlement/stats/POP.java; includes children, rioters, criminals and `AD.cityDivs().total(race)`, i.e. stored world soldiers), `Player.citizens(race)` (Player.java:352), `STATS.POP().pop(race, cl)`.
- **Sites:**
  - world/region/RDMilitary.java:159 `garrisonTarget` for NPC regions (`POP.tot(null)/8000`) and :208 `conscripts` for NPCs (`POP.tot(null)/20000`): NPC garrisons and conscripts scale with the player's city.
  - world/region/pop/RDRace.java:151-153, 168-169 (loyalty boosters "Representation", every region's loyalty target).
  - game/raiding/RaidingUtil.java:60; game/raiding/Raider.java:98-100 (`hasInterrest`, pop < 200).
  - game/faction/royalty/opinion/OpsOther.java:136-153 (`kintreatment`, slaves of the royal's race / total).
  - FWorth.java:87 (`citizens`); PSellerSlave (A2).
  - world/army/ADStats.java:214 `report`: `STATS.BATTLE().DIV.stat().data().get(null)` (soldiers in city divisions).
  - City part, for reference: Player.updateSpecial:163-171 (`STATS.POP().POP.data(null).get(r)`) feeds B3.
- **Type/size:** int per race per class: 8 × ~4 × 4 B ≈ 128 B, plus soldier count 4 B.
- **Hot:** RDRace boosters run per region update (amortised hot); the rest daily/event.

### A5. Capitol garrison and military power (city soldiers seen as world divisions)
- **Chokepoint 1: `RD.MILITARY().divisions(capitol)` / `.power.getD(capitol)` / `.garrison.get(capitol)`.** For the player's capitol, `RDMilitaryGar.divisions` (world/region/RDMilitaryGar.java:72-76) returns `WDivsCapitol`: one `WDivCity` per city battle division not attached to a world army (:377-391). `WDivCity` reads live city data: `men()` = `div.menNrOf()`, training `tr.stat.div().getD(div)`, experience `COMBAT_EXPERIENCE.div()`, equipment via `initSupplies` (:423-461: iterates **every city entity** `SETT.ENTITIES().getAllEnts()`, sums soldier equipment, adds `STOCKPILE.tally().amountReservable`), `generate()` (:546-562, copies every soldier's `Induvidual`). `GARRISON` for the capitol (RDMilitary.java:52-60) sums `men()` of those divisions.
- **Sites that can hit the capitol** (power/defence): game/event/actions/_INVASION.java:63; game/events/faction/EventFactionExpand.java:76; game/faction/diplomacy/DipWarPlayer.java:270; Player.java:345; game/raiding/MessAlly.java:57; RaidingMap.java:546 (+619/625/640 UI text); RaidingUtil.java:43; UpdaterRegions.java:131; world/army/ai/Attacker.java:134; world/army/ai/Rebel.java:45, 60, 67, 102; world/battle/PRegAttack.java:152; PSiege.java:154, 231 (`defensePower`); Resolver.java:126; Side.java:197; Util.java:168. Garrison count: EventWorldRebellion.java:159; PRegAttack.java:105, 141; Side.java:221; Tests.java:89/145/201. Division lists: ResolverSide.java:186-187, 223-224; Side.java:173, 181. Render: CSprite.java:41 (+13 view/** sites).
- **Chokepoint 2: `Player.offensivePower()`** (Player.java:331-348) = `AD.power(player)` + affordable mercenaries + capitol power − `SETT.INVADOR().invadingPower()`. Callers: DipWarPlayer.java:93 (war AI, cached 120 ticks), :121; DealBools.java:424-431 (`allyValue`, f may be the player); game/events/faction/player/War.java:156-158 (daily).
- **Chokepoint 3: `SETT.INVADOR()`** `invadingPower()`: DipWarPlayer.java:272, Player.java:345. `invading()`: game/events/faction/player/Peace.java:17, War.java:86, 147; game/raiding/RaidingCurrent.java:380.
- **Other direct reads of city divisions:** FWorth.java:59-67 (counts the player's division equipment into **every** faction's worth; vanilla quirk); RDMilitaryGar.java:330, 371-386, 439.
- **Battle power reads city boosts:** `GAME.battle().power.get(div)` (game/battle/util/Power.java:61) → `Boosts.get` (game/battle/util/Boosts.java) adds `bo.fGlobal` boosters for `div.faction()`, the player for every player division (capitol `WDivCity`, stored `WDivStored`, player mercenaries). Used by `AD.power` (world/army/ADPower.java:51, cached per army in world data), `WDIV.provess` (world/army/WDIV.java:31), Player.java:340.
- **Type/size:** aggregate per report: power (8 B), men (4 B), invading power (8 B), invading flag (1 B). Per-division detail for world battles: up to 120 × ~32 B (men, race, experience, 4 training, 8 equipment, prowess) ≈ 4 KB, typically < 1 KB. Per-soldier data (`generate`, `resolve(Induvidual[])`) cannot travel in a report (see C5).
- **Hot:** `initSupplies` walks all city entities once per tick whenever any capitol power is read; region AI reads it often. The highest-cost read in the scan.

### A6. Player division settings used by world divisions
- **Chokepoint:** `GAME.ARMIES().player().divisions().get(i)` (the city's battle `Div`).
- **Sites:** world/army/WDivStored.java:83 `div()` (race, name, equipment `e.target(div())` at :78), :98 `menTarget` (`info.men()`); WDivStoredAll.java:146 (rejoin division); RDMilitaryGar.java:371-386.
- **What:** race, men target, name, banner, equipment targets of each city division; they define the player's world-army divisions (`AD.cityDivs()`).
- **Type/size:** 120 × (race 1 B, target 1 B, 8 equipment targets, banner 1 B, name ≤24 B) ≈ 4 KB max; only changes on player action (could be "on change").
- **Hot:** per world-army update (men target, equipment).

### A7. Boosts (`BOOSTABLES.*.get(player)` and routed boosts)
- **How it resolves:** `Boostable.get(o)` sums its boosters live (game/boosting/BUtil.java:60). `BValue.vGet(Faction)` dispatches to `vGet(Player)` (game/boosting/BValue.java:32-38); `BValueFaction` routes `vGet(Region)` → `reg.faction()`, `vGet(Induvidual)` → `indu.faction()`, `vGet(Div)` → `div.faction()`, `vGet(HCLASS_RACE)` → player; `BValuePop.vGet(Player)` → `HCLASS_RACE.clP()` (the city's population class). So **any boost read on the player, a player-owned region, a player division or clP reads city state**.
- **City sources feeding them:** rooms (AdminData settlement/room/infra/admin/AdminData.java:103-117; ROOM_EMBASSY.java:80-95; ROOM_MONUMENT (region→faction at :117); ROOM_POLICE; RoomUpgrades; RoomIrrigated; ROOM_WATER; BonusExperience; RoomEquip), stats (StatsReligion, StatsWork, BoostsHealth via POP.tot, StatLawBoosts, StatsMultipliers, StandingBuff), Immigration, `_BOOST` event boosts (city-part event state). Player progress: `BoostCompound` (PTech/PTitles/PLevels levels, cached per updateI). World/static sources: TBooster, CLIMATES, RDBuildPoints, RDReligions, RDBuilding, constants.
- **Faction-level sites:** FCredits.java:87 (`CIVIC_DEFLATION`); Emissaries.java:238, 258, 262 (`CIVIC_DIPLOMACY`, used by RDOwner.java:74 and OpsEmi ×7); RaidingUtil.java:60 (`RAID_SECURITY` on clP); TradeManager.java:88-89 (`WORLD_PROXIMITY_TOLL` on CITIZEN class); DipWarPlayer.java:247 (`CIVIC_TRUST`, f probably always NPC, unconfirmed).
- **Region-path sites on player regions:** RDOutputs 133/139/207-225 (tax/outputs); Shipper 71/88/101; RDMilitary 99/105/115 (fortification), 153 (`bgarrison`, the only garrison source for player regions), 190 (conscripts); RDHealth 154/157; RDReligions 78/93/126/134/184/186; RDBuildPoints 124/240; RDBuilding 526; RDRace 211/216/330/346/377; RDRaces 299; RDDistance 98; RDBoostCache 43 (VISUAL_*, render).
- **Battle boosts:** A5 (`fGlobal` of BATTLE/PHYSICS boostables, ~20).
- **Boostables to carry** (from data.zip): CIVIC_DEFLATION, CIVIC_DIPLOMACY, CIVIC_RAIDING/RAID_SECURITY, WORLD_PROXIMITY_TOLL, CIVIC_TRUST; region path: WORLD_TAX_INCOME and outputs, WORLD_GARRISON, WORLD_FORTIFICATION, WORLD_CONSCRIPTABLE_TARGET, WORLD_HEALTH, WORLD_CONVERSION_*, CIVIC_GOV, WORLD_POINT_WORKFORCE, WORLD_BUILDING_* efficiency, WORLD_POPULATION_TARGET_*/CAPACITY, WORLD_LOYALTY*, WORLD_PROXIMITY, WORLD_REGIONS; battle: the BATTLE/PHYSICS set. Not confirmed key by key which have live (per-tick) city sources; most WORLD_* come from tech/titles/religion/race/events.
- **Type/size:** the player-faction contribution per boostable (add, mul): ~60 boostables × 16 B ≈ 1 KB.
- **Hot:** yes for the region path (every region update of a player region); battle power cached per army.

### A8. Data-driven requirement locks and game values (`GVALUES.FACTION`)
- **Chokepoint:** `Lockable<Faction>.passes/progress(player)` and `Value<Faction>.d.getD(player)` (init/value/Lockable.java, Locker.java:58, 120). `GVALUES.FACTION` holds ~75 registered value families (population, religion share, room counts, employment, loyalty, credits, food days, monuments, …; settlement/stats/SValues.java and room classes) and every one is also exposed as a Region value `FACTION_<key>` and a Royalty value (init/value/GValuesInit.java:45-99).
- **Sites:** world/entity/haven/WHavenType.java:178 (`amount`: `reqsFrom.passes` + each Delta's `value.d.getD(player)`, :184-209), :240-250 `progress`; world/entity/haven/Player.java:99 (`progress`); game/raiding/Raider.java:98 (`BARRACKS.reqs.passes(player)`); game/events/faction/player/Stance.java:85 (`IMPORT.reqs.passes(player)`); world/region/RD.java:493 and RDBuilding.java:369 (region locks; vanilla data uses only region values, but `FACTION_*` keys are legal in mods).
- **Vanilla keys** (camps ARGONOSH, CANTOR): RELIGION_*_F, FOOD_FOOD_DAYS, MONUMENTS_MONUMENT_*, POPULATION, POPULATION_SLAVE_F, plus room-unlock requirements of BARRACKS and IMPORT.
- **Type/size:** the set is defined by data files, so the report must carry the values of the keys referenced by world-part locks, collected at load: ~5-20 doubles (≤160 B) in vanilla, unbounded with mods.
- **Hot:** WHavens every ~10 game s; Raider daily.

### A9. City laws and policies per race
- **Sites:** world/region/pop/RDEdicts.java:78 (`STATS.MULTIPLIERS().PROSECUTION.value(CITIZEN, race)`, feeds every race's region loyalty target); world/region/pop/RDRace.java:194 (`STATS.BATTLE().WAR.data(CITIZEN).getD(race)`, war standing per race, loyalty); OpsOther.java:136-153 (PROSECUTION per race, `TOURISM.permit(race)`).
- Excluded as static data: RDRace.java:111/186-187 and world/region/updating/Builder.java:455 (`STATS.ENV().OTHERS.standing().definitionD` / standing definitions are race data).
- **Type/size:** per race: 2 doubles + 1 bool ≈ 8 × 17 B = 136 B.
- **Hot:** loyalty boosters per region update; opinion on evaluation.

### A10. City entry state
- **Chokepoint:** `SETT.ENTRY()` (settlement/entry/SENTRY).
- **Sites:** TradeManager.java:185 and PSeller/PBuyer (`isClosed`); world/army/WDivStoredAll.java:123 (`points.hasAny() || isClosed()`), :133 (`randomReachable(upDI)`); WDivStored.java:286 (`randomReachable()`, draws RND).
- **Type/size:** 2 bools. The coordinate picks are not reportable: they must move into the city part (C4).
- **Hot:** WDivStoredAll.update runs ~32 times per game second.

### A11. Reads that need no report
- world/region/building/RDBuildings.java:52-57 wall count (`SETT.TERRAIN().FORTIFICATIONS` tiles): render only (CSprite.java:36). Other PCs only need it to draw another player's capitol.
- world/WORLD.java:301-302 `SETT.WORLD_AREA().info.initCity`: load time only.
- world/map/fow/FOW.java:158: `SETT.exists()` and the capitol (world). FOW itself is this PC's view (not saved), see D.
- Type registries (`STATS.EQUIP().BATTLE_ALL()`, `STATS.BATTLE().TRAINING_ALL`, `STATS.all()`, `SETT.ROOMS().ARTILLERY`, `SETT.ROOMS().collection.readMany` in world/region/building/Creator.java:140/290, ADSupplies.java:81/104): static, not city state.
- `FACTIONS.player().race()`, `.name`, `.banner`: constant per session.
- `RDMilitary.extractSpoils` / `RDMilitaryGar.extractLostEquipment` (:241 / :303-341): dead code, nothing calls it.

### A counts
About 11 kinds, 10 that need report fields; about 140 step sites (A5 ≈ 45, A7 ≈ 45, A2 ≈ 15, A3 ≈ 14, A4 ≈ 12, A1 ≈ 8, A8 ≈ 7, A9 ≈ 3, A10 ≈ 5, A6 ≈ 4), plus about 25 UI-only sites in view/** and the UI paths listed.

---

## B. City → world writes

| Kind | Sites | Report as input at a stamped step? |
|---|---|---|
| B1 soldier joins a world division | 1 entry point | yes, an input carrying the soldier's full data |
| B2 goods into army supply | 3 | yes, deltas |
| B3 capitol population per race | 1 | yes, a level |
| B4 invasion results | ~9 | yes, event records |
| B5 mercy statistic | 2 | yes, a running total |
| B6 scripted events (EVENT_HANDLER) | ~15 | no: stamped "event fired/choice" input, run in the world part |
| B7 treasury and progress | 6 + 3 | balance as a level; world credit changes become txns |
| B8 player resource history | 64 | stays local (the world reads stock, not history) |
| B9 game counters | ~4 | split or report; low risk |

**B1. Soldier leaves the city and joins a world division.** settlement/entity/humanoid/ai/subject/PlanJoinArmy.java:64 → `AD.cityDivs().add(h, div)` → world/army/WDivStoredAll.java:98-103 (also adds the soldier's equipment to the army's supply) → WDivStored.java:144-161 (`add`: a new `Induvidual` copy, `STATS.NEEDS().clear`, `STATS.REL().setSoldier`), `report` (:182-186: `WDivStoredAll.amount`, `ramounts[race]`). Per soldier, in bursts when an army is sent out. Report: yes, "soldier joined division d" with the full `Induvidual` (`STATS.count().longCount()` longs + race + type; not measured, est. 150-400 B per soldier) and its equipment. Hazard: the city reads `AD.cityDivs()` straight back (POP.java:44 `total(race)`; settlement/battle/ArmyTrainingInfo.java:210/245/249/322/350; StatsRelations.java:300), so it needs a pending-change ledger for the 30-step gap.

**B2. Goods from the city into army supply (deposits into world state).** PlanJoinArmy.java:137 (`fetchGear.res`) and :170 (`dequip`) raise `AD.supplies().equip.get(i).current()`; settlement/room/military/supply/Cache.java:75 (`deliver`, from Crate.java:125; goods left the city at Crate.java:124-126) raises `ADSupply.current()` for every player army. Per item / about daily per depot. Report: yes, deltas (army ID, supply index, amount). Hazards: the city sizes deliveries from the live world need (Cache.java:27-46, PlanJoinArmy.java:115), so it over-delivers unless pending deltas are subtracted; goods for an army that disbands before the stamp need a refund rule tied to the txn ID.

**B3. Capitol population.** Player.java:160-173 (`updateSpecial`) sets `RDRace.pop` on the capitol from `STATS.POP` every step (races without a region entry spread evenly). Report: yes, a level, one int per region race ≈ 40-80 B.

**B4. Invasion results.** settlement/battle/invasion/Invasion.java:435 (`loose`: `ROPINION.STANCE().setNewStance(invader, VASSAL)`, a diplomacy write), :420-421 (75 % of credits), :393 (`AD.stats().report`), :382/:387/:151/:218 and Attack.java:158 (invasion listeners → game/raiding/RaidingCurrent.java:81-120, `GAME.raiders().defeat` at :95, later `MessDefeated` credits); Attack.java:105 (`AD.stats().report`), :174-185 (`WDIV.resolve(survivors)` on the capitol garrison and the player's nearby world armies, found by flood fill at :206-243). Invasion.java:119/121 `FACTIONS.otherFactionSet(...)` is a city-side random pick saved in FACTIONS (FACTIONS.java:139) but read only by city/battle code: make it city-local. **Unsure** whether `Attack.afterExit` runs inside the step or when the battle view closes. Report: yes, stamped records: outcome (invasion ref, victory, losses, kills, invader) and survivors per world division ID; the vassal change applied by the world part from the outcome.

**B5. Mercy/cruelty.** settlement/stats/law/StatPunishment.java:38, 40 change `AD.stats().mercy()` for the player, several times a day; read by the world (OpsStance.java:156, 176) and the city (StatsBattle.java:141, 150). Report: yes, one double.

**B6. Scripted events writing the world** (game/event/**, run by EVENT_HANDLER): `_BOOST_PERM.java:81/88/96/105/115` and `_BOOST_PERM_REMOVE.java:78-97` (permanent boosts on factions, regions, classes); `_OPINION.java:74-78`, `_OPINION_CLEAR.java:59-60` (NPC royal opinion); `_REGION_POP.java:79` (`r.pop.inc`); `_INVASION.java:73` (spawns a raid; RND at :70; reads `AD.power`, `RD.MILITARY`); selection marks kept in the world save: EContext.java:569/584 (regions), :651/661 (factions), :791/802 (royals); `_BOOST.java:340-385` (temporary boosters whose world-side value depends on `GAME.EVENT().current()`). On spawn, timers or player choice. Not reportable as values: run the world-touching actions in the world part from a stamped "event fired / choice made (event ID, choice)" input and set the marks there.

**B7. Values on the Player object written by the city.** Treasury: settlement/misc/placers/PlacerLanding.java:70; Invasion.java:421; game/events/slave/EventUprising.java:216; settlement/stats/muls/StatsMultipliers.java:983 (`Handout`, player action); game/tourism/TOURISM.java:215; game/event/actions/_CREDITS.java:58. (PBuyer.java:133 / PSeller.java:141 are called from world trade, so they are C3.) Report: the balance as a level; world-side changes as txns. Progress: PLevels.java:138-150, PTitles.java:247-281 (`unlock` also writes the local profile, `saveUnlocked`), PTech.java:319-348; the world sees these only through boosts (A7).

**B8. Player resource history** (`res().inc`, 64 city sites): local; the world never reads the history, only stock (A1).

**B9. Game counters** (`GAME.count()`): Attack.java:162/189, Invasion.java:385/390; PBuyer.java:134 / PSeller.java:143 (from the world). Achievements and GVALUES only.

**City UI actions that write the world** (outside the step; V0.1 commands): send/recall a city division (view/sett/ui/army/Actions.java:152, 234; UIArmyCitySendOut.java:74 → `WDivStoredAll.attach`, V0.1 `cmd.World.cityDivAttach`): stays a world command, but its supply take is a two-phase withdrawal (C2). `Handout`: local city action. Event choices: world command if the actions touch the world. Paying a raider demand (`MessDemand`, V0.1 `Events.raiderPay`): world command with a credits/goods withdrawal.

B counts: 9 kinds, ~40 simulation sites (excluding B8's 64 local history writes), 4 UI action families.

---

## C. World → city writes

Vanilla already splits trade goods in two steps on the city side (credits move at once, goods later): `PBuyer.addReserve` (settlement/trade/PBuyer.java:131-136) charges credits and adds to `toBeAdded`; `addDeliver` (:139-142) moves to `toBeStored`; the goods enter in the city part (`SettTrade.updater`, settlement/trade/SettTrade.java:34-36 → `PBuyerRes.deliver` → caravans). `PSeller.remove` (PSeller.java:140-145) pays credits at once and adds to `promised`; the city extracts later (`PSellerRes.extract`). A failed extraction leaves `promised` outstanding: the world was already paid.

| Kind | Sites | Deposit / withdrawal | Txn carries (rough) |
|---|---|---|---|
| C1 goods in | 13 | deposit | id 6 B, kind 1, trade type 1, phase 1, + 6 B per tradable |
| C2 goods out | 6 (2 auto, 3 cmd, 1 dead) | withdrawal | id, kind, type, price 4 B, requested per tradable; report returns taken |
| C3 credits | 13 (7 auto, 6 cmd) | both | id, kind, credit type 1 B, amount 8 B (+ tradable/units) ≈ 18-24 B |
| C4 people created | 3 | deposit | full soldier data (est. 150-400 B), immigrant limit 14 B |
| C5 city people killed/changed | ~10 | withdrawal-like | per division: survivors, xp ≈ 20 B; per soldier: id, event, cause |
| C6 invasions, cancels | 4 | other | invasion spec: tens-hundreds of KB (est.); cancel 4 B |
| C7 messages | ~70 sends | other (UI) | type 2 B + args |
| C8 city caches invalidated | 2 | other | flag on an owner-change txn |
| C9 counters | ~4 | deposit | id, counter, delta |

**C1. Goods into the city (deposit).**
- Caravan arrival: world/entity/caravan/Shipment.java:166 (`arrive`: `f.buyer(tt).addDeliver`), :217 (`removeAction`, same). Incoming counter `Shipment.loadAndReserve` (:243-251, `addReserve(-old)` + `addReserve(new)` at price 0).
- Trade imports: TradeSorter.java:321 (`buy`, player as buyer through `TradeManager.pbuy`; real price, so also a C3 withdrawal), TradeManager.java:220 (`ship`), :239 (fallback `addDeliver(am)` when no caravan; vanilla passes the total `am`).
- Region tax goods: world/region/updating/Shipper.java:56-61 (`ship`, every player region except the capitol, 16 s cycle), :103 (`shipAll`, `addReserveAndDeliver`); world/region/RDOutputs.java:221-227 (`squeze`, UI command).
- Battle spoils: world/battle/Util.java:310-313 (slaves), :356-359 (loot), from ResolverPlayer.java:495/497/533/540/548 and `enemyWithdraws`.
- Raid loot and captives: world/entity/army/WArmyState.java:165-168, :180-183.
- Surplus army supply sent home: world/entity/army/WArmy.java:229-240 (`returnResources`, from `checkForResources` :214).
- NPC goods in a deal: DealParty.java:115-122, :130 (UI command).
- Delivered slaves become new city people in the city part (`PBuyerSlave`), so they need no extra txn.
- One phase is enough. Keeping vanilla's "incoming" display needs a reserve and a deliver txn per caravan.

**C2. Goods out of the city (withdrawal).**
- Trade exports: TradeSorter.java:163 (`sellPlayer`: `player.seller(t).remove`, paired with the NPC's `addReserve` at :162). Same pass reads `removeMax/prio/removePrice` (A2); stops when `SETT.ENTRY().isClosed()` (TradeManager.java:185). Daily.
- Player goods in a deal: DealParty.java:138 (the NPC caravan is loaded from the player's capitol at :115-122 before the city extracts). Command.
- Raid tribute: MessDemand.java:188 (`Demand.pay`, button at :272; demand sized from `playerOwned × 0.95` at :331, `canPay` at :160). Command.
- Army supply from the stockpile: world/army/WDivStoredAll.java:76-86 (`attach`: clamps to `STOCKPILE.tally().amountReservable` at :81, `RESOURCE.remove(am, ARMY_SUPPLY)` at :83, then adds to the army's supply); view/world/ui/army/ArmyRecruit.java:105-106. Commands.
- Dead: RDMilitaryGar.java:303-341 `extractLostEquipment`.
- Two phases: the world asks; the city's next report returns (txn ID, amount taken). Vanilla credits the price at once whatever the city later extracts.

**C3. Credits.**
- Automatic: PBuyer.java:133 via TradeSorter.java:321 (−, purchase; reads credits first in `buyPriority`); PSeller.java:141 via TradeSorter.java:163 (+, export); WDivMercenaries.java:62, 65 (−, upkeep; reads credits at :54/64, two misses unassign the mercenary at :57, so world state depends on the real treasury); game/faction/diplomacy/UpVassal.java:109 (−, yearly tribute inside the `MVassal` message constructor; clamped to the treasury at :97-106); Shipper.java:45 (+, region tax); MessDefeated.java:30 (+, raider bounty inside a message constructor, sent from RaidingCurrent.java:375; the state can be set by the city's invasion callback, RaidingCurrent.java:94-97); FCredits.java:85-97 (± inflation, RND draw; §0.4).
- Commands: UpVassal.java:241 (+, accept vassal tribute); RDOutputs.java:207 (`squeze`); DealParty.java:95-96 (± deal); MessDemand.java:184 (−, raid tribute; checked at :157); view/world/ui/army/UIMerenaries.java:112 (−, sign a mercenary); view/world/ui/region/PlayBuildingsPop.java:639 (−, region building).
- Two phases where the world decides on the treasury: purchases, upkeep (the rule needs "paid / short" back before acting), tribute.

**C4. People created in the city (deposit).**
- Returning soldiers: world/army/WDivStoredAll.java:117-147 (`update`, from `AD.update`, ~32 per game second) reads `SETT.ENTRY()` (:123, :133), then `WDivStored.popSoldier` (WDivStored.java:245-275: `SETT.HUMANOIDS().create(race, tx, ty, SUBJECT, SOLDIER_RETURN)` :247, `STATS.Arrive(h)` :249, copies the stored data, zeroes equipment and injuries :251-263, removes the stored soldier), then `h.setDivision(dd)` (WDivStoredAll.java:143-146).
- Retirees: WDivStored.java:276-295 (`age`, daily from ADUpdaterDiv.java:52): `SETT.ENTRY().points.randomReachable()` (:286), `HUMANOIDS().create(..., RETIREE, ...)` (:288), `STATS.REL().setHumanoid(h)` (:291).
- Immigrant limit: world/entity/haven/Player.java:111 (`SETT.ENTRY().immi().setHigher`, from WHavens every ~10 game s; only raises, so idempotent).
- RNG: `EntryPoints.randomReachable` (settlement/entry/EntryPoints.java:304-321), `HUMANOIDS().create` and `STATS.Arrive` draw RND, and how many draws depends on city state. They run in the world part today, so the city shifts the world stream. They must move to the city part.
- The world deletes the soldier at once, so the txn carries the whole soldier: division index, type, race and `longCount` longs.

**C5. City people the world kills or changes.**
- Capitol garrison `WDivCity` (RDMilitaryGar.java:494-660): `resolve(Induvidual[])` (:565-590) copies battle results into live city people and kills the missing (`STATS.POP().COUNT.reg` SLAYED :581, `helloMyNameIs…` :582); `resolve(int, double)` (:592-620) kills `deaths` people (:613-614) and adds experience to survivors with an `RND.rFloat()` draw (:610-611). Reached from `ResolverSide.extract(Region)` / `kill` (world/battle/ResolverSide.java:223-246) when the player's capitol region is a battle side, and from `ResolverPlayer.afterBattle` (:420-426, dormant while SOSDET refuses Engage). **Unsure** which paths reach it rather than `invadeCapitol` (Resolver.java:142-160 sends a besieged player capitol to `sallyOut` or `invadeCapitol`).
- Stored soldiers in the city relation table (`StatsRelations.setSoldier/setDeath/setHumanoid`, settlement/stats/relation/StatsRelations.java:416-456, allocate city references): WDivStored.java:159 (`add`), :169/:172 (`remove`: death + renumbering of the soldiers after it), :199 (`resolve(Induvidual[])`), :218 (`resolve(int, double)`: vanilla calls `setDeath` on survivors and re-adds them), and via `menSet` (:233). World triggers: battle losses (ResolverSide.java:246), desertion when supply health < 1 (ADUpdaterDiv.java:57-67, daily, RND), old age and retirement (`age`).
- Age and experience written on stored soldiers (WDivStored.java:281, :222) go to copies in the world save, not city state.
- Txn: per division (index, survivors 2 B, xp 8 B) ≈ 20 B, or per soldier (stored ID 4 B, event 1 B, cause 1 B).

**C6. Invasions and cancels.**
- Capitol invasion: world/battle/ResolverPlayer.java:205-239 (`invadeCapitol`, from Resolver.java:159) builds an `InvasionSpec` (faction, position, every division with per-person data, loot from army supplies, artillery), calls `SETT.INVADOR().invade(spec, army)` (:232) and disbands the attackers at once (:234-236).
- Raider invasion: game/raiding/RaiderArmy.java:179 (`SETT.INVADOR().invade(sp, null)`, from RaidingCurrent.java:267, 335).
- Cancel: RaidingCurrent.java:195 (`SETT.INVADOR().cancel(invadeRef)`); settlement/battle/invasion/Invador.java:87-105 (a DIP listener, city code fired by world diplomacy, cancels invasions when the war ends).
- Handshake: `Invador.invade` (:196-211) returns a city-allocated ID and calls `InvasionListener.register(a, ref)` at once; `RaidingCurrent` learns its ID that way (:110-115). A delayed txn can't answer, so the world must allocate the ID (use the txn ID). The spec is computed on every PC, so it never crosses the network, but it is large for the txn queue and save.

**C7. Messages.** ~70 `.send()` calls in world-part code (ADSupplies.java:169; ADUpdaterDiv.java:76; WDivMercenaries.java:72, 75; haven/Player.java:90/101/113/119; game/events/faction/**; RaidingCurrent; UpVassal; PCredits yearly report). Per-PC UI, not city state, but each has a "pause on arrival" setting (view/ui/message/Messages.java:104) and with several humans must reach only its owner. `MVassal` and `MessDefeated` must have their credit changes taken out into C3 txns.

**C8. City caches the world invalidates.** settlement/recipe/Creator.java:199-205 (`changeAll` on every region owner change); Emissaries.java:126-135 (assignments cleared when the player takes a region; world state if Emissaries move, §0.3).

**C9. Counters.** DealBools.java:213 (`UNITES`), OpsEmi.java:113 (`ROYALTIES_KILLED`), PBuyer.java:134 / PSeller.java:143.

**Read-modify-write in one breath (the two-phase cases):** TradeSorter.sellPlayer (stock limit/prio/price, then remove); TradeSorter.buy for the player (credits in `buyPriority`, then reserve); WDivMercenaries.java:54-66 (check credits, then pay or miss/disband); UpVassal MVassal (:97-109, clamp to treasury); FCredits.update (inflation ∝ treasury); MessDemand (sized from stock, `canPay`, then `pay`); WDivStoredAll.attach and ArmyRecruit (reservable stock, then remove); WDivStoredAll.update and WDivStored.age (entry points, then create, RND); WDivCity.resolve (walks live city people).

Excluded as not city state: STATS writes on world-owned individuals (NPCCourt.java:98/155, Royalty.java:56, WDivMercenaries.java:90-91, WDivMercenary.java:119, RaiderPortrait.java:44/47); NPCRes.java:237-238/264-265 (NPC side).

C counts: 9 kinds; C1-C6 matter: ~49 sites (13 + 6 + 13 + 3 + ~10 + 4), of which ~11 are UI commands.

---

## D. Mixed objects

1. **`FACTIONS.player()` (Player)**, saved in the FACTIONS (world) block (Faction.save: name, `res()`, banner, `credits()`, flags; Player.save adds tech, titles, level, races, emissaries, bonus settings, trade, ruler name, PlayerColors; game/faction/player/Player.java:100-129). Holds:
   - `PCredits`: written by city (B7) and world (C3), read by both; inflation runs in the world part (§0.4).
   - `FResources` history: city production plus world trade, army supply, spoils; `getAvailable` reads the city stockpile (A1).
   - `buyer/seller`: city objects (`SETT.TRADE()`) that are the player's face in world trade; `promised`/`toBeAdded` counters are written by the world (C1-C3) and drained by the city.
   - PTech/PTitles/PLevels: city progress the world sees through boosts; PTitles writes the local profile.
   - Emissaries: world-facing decisions (§0.3).
   - PTrade: price history filled from NPC prices (world data) and from city trade.
   - `capitolRegion`, realm, armies: world.
   - Construction-time `FactionProfileFlusher.load(player)` (FACTIONS.java:89) reads the local profile; overwritten by the save on load.
   - Only one exists: several human factions need several Players (343 `FACTIONS.player()` sites in world code, plan 0002).
2. **EVENTS** (game/events/EVENTS.java): one GameResource, a static list of 15 `EventResource`s (city and world), one `SuperSaver` keyed per event (so the save can be split by key), one update loop, one random stream.
3. **EVENT_HANDLER / EContext** (game/event/**): city conditions (`request.passes(player)`), world actions and world-saved marks (B6), event boosts read by both parts (`_BOOST`).
4. **`AD.cityDivs()` (WDivStoredAll / WDivStored)**: world state (saved in AD) holding full `Induvidual` copies of city soldiers; indexed by the city's battle `Div` index; reads the city `Div` settings (A6); its per-race totals feed the city's `POP.tot` (POP.java:44) and NPC/world conscript logic (ADConscripts.java:55, 61); its soldiers have references in the city's `StatsRelations` table (C5). `WDivStored.stats[]` is a `STATS.all()`-sized sum over stored soldiers. One instance for the single player.
5. **`GAME.ARMIES().player()`** (game/battle/Armies, a battle GameResource): the city's divisions, read by the world as the capitol garrison (`WDivCity`), as the settings of world-army divisions (A6) and in FWorth (A5).
6. **RD region data of the player's capitol:** `RD.RACES().pop` (written by the city, B3), `RD.MILITARY().garrison`/`power` (overridden to read the city, A5), `RDBuildings.levelWall` (render). The capitol is a region (world) whose contents are the city.
7. **Boosts:** world BValues feed the city (RDReligions.java:201, RDBuildPoints.java:197, RDBuilding.java:498 `vGet(Player)`), city sources feed world reads (A7), TmpBoosting/`GAME.BOOST()` holds boosts on factions, regions and classes written by events (B6). Caches keyed on updateI or render frame (§0.7).
8. **SETT.TRADE / SENTRY:** city objects the world writes (C1-C3, `immi().setHigher`) and reads (A2, A10).
9. **GVALUES** (init/value/GVALUES.java): static registries of value getters across city, world and royalty; `FACTION_*` values are re-exposed on regions and royals (GValuesInit.java:45-99).
10. **Static or shared state:** `GameResource.all` (one list for both parts); `EVENTS.all`; `RDMilitaryGar.WDivsCapitol` static caches; `PlayerColors.cats`; `GAME.updateI()` used as a cache key by both parts; TIME (one clock for both, as the ADR intends); `FACTIONS.otherFaction` (saved in FACTIONS, used only by city/battle).
11. **FOW** (world/map/fow/FOW.java): a WorldResource, not saved, the local player's visibility. With several humans it is per PC and must stay out of the world hash.
12. **AD.power and ADStats caches** (world/army/ADPower.java:47-70, ADStats.java:210-220): world-saved values computed from city boosts (battle power) and city soldier counts.

---

## E. Summary

Bytes are per report (A, B) or per transaction (C).

| Kind | Dir | Sites | Proposed hook chokepoint | Bytes | Risk |
|---|---|---|---|---|---|
| A1 player stock per tradable | W←C read | ~8 | `Player.res().getAvailable`, `TRADABLE.ps().playerOwned` (one place: `PSeller` for the player) | 200 | low; deal drafts in `DIP.TMP` |
| A2 trade settings/capacity | W←C read | ~15 | `Player.buyer/seller` → report-backed facade over `PBuyer/PSeller` | 1,200 | high: `promised` and capacity are also written by world txns; over-sell with a 30-step lag |
| A3 credits | W←C read | ~14 | `PCredits` read accessor (`credits()/getD()`) when called from the world part | 8 | high: world decides on treasury (C3 two-phase) |
| A4 population by race/class | W←C read | ~12 | `POP.tot`, `Player.citizens`, `STATS.POP().pop` when in the world part | 130 | medium: `POP.tot` includes stored soldiers (D4) |
| A5 garrison, power, invasion | W←C read | ~45 | `RDMilitaryGar.divisions/WDivsCapitol`, `RDMilitary.GARRISON.get`, `Player.offensivePower`, `Invador.invadingPower/invading` | 30 aggregate, ≤4 KB per division | very high: battles need per-soldier data (C5/C6); hottest read (all entities per tick) |
| A6 player division settings | W←C read | ~4 | `WDivStored.div()` | ≤4,000 (on change) | medium |
| A7 boosts | W←C read | ~45 | `BValue.vGet(Player)` / `vGet(HCLASS_RACE)` when in the world part; battle `Boosts.get` `fGlobal` | ~1,000 | high: data-driven booster sets, caches |
| A8 GVALUES locks | W←C read | ~7 | `Lockable.passes/progress`, `Value.d.getD` on the player in the world part | ≤160 vanilla | high: data/mod-defined key set |
| A9 laws and policies per race | W←C read | ~3 | `StatsMultipliers.PROSECUTION.value`, `StatsBattle.WAR.data`, `TOURISM.permit` | 136 | low |
| A10 entry state | W←C read | ~5 | `SENTRY.isClosed`, `points.hasAny` | 2 | low (coordinate picks move to C4) |
| B1 soldier joins a world division | C→W write | 1 | `WDivStoredAll.add` | 150-400 per soldier | high: full person payload, pending ledger |
| B2 goods into army supply | C→W write | 3 | `ADSupply.current().inc` from the city | 8 per delta | medium: over-delivery, refunds |
| B3 capitol population | C→W write | 1 | `Player.updateSpecial` | 40-80 | low |
| B4 invasion results | C→W write | ~9 | `Invasion.resolve/loose`, `Attack.afterExit`, invasion listeners | 20-500 | very high (with C6) |
| B5 mercy | C→W write | 2 | `StatPunishment.report` | 8 | low |
| B6 scripted events | C→W write | ~15 | `EVENT_HANDLER.setActions` / EContext selection → stamped input | 8 per event | high |
| B7 treasury, progress | C→W write | 9 | `PCredits.inc` from the city; progress via A7 | 8 | medium |
| B9 counters | C→W write | ~4 | `GCOUNTS` | small | low |
| C1 goods in | W→C deposit | 13 | `PBuyer.addReserve/addDeliver/addReserveAndDeliver` on the player | 10 + 6 per tradable | medium |
| C2 goods out | W→C withdrawal | 6 | `PSeller.remove`, `WDivStoredAll.attach`, `RESOURCE.remove` from world code | 15 + 6 per tradable, + reply | high |
| C3 credits | W→C both | 13 | `PCredits.inc/set` from the world part (incl. message constructors) | 18-24, + reply | high |
| C4 people created | W→C deposit | 3 | `WDivStored.popSoldier`, `WDivStored.age` retiree, `SENTRY.immi().setHigher` | 150-400 / 14 | high: RNG and entry picks in the world part |
| C5 city people killed or changed | W→C withdrawal | ~10 | `WDivCity.resolve`, `StatsRelations.set*` from world code | 20 per division | very high |
| C6 invasions, cancels | W→C other | 4 | `Invador.invade/cancel` | 10-100 KB / 4 | very high: handshake |
| C7 messages | W→C UI | ~70 | `Message.send` from the world part | 20-200 | low; owner routing |
| C8, C9 | W→C other | ~6 | owner-change listeners, `GCOUNTS` | small | low |

Report size estimate: A1-A10 + B3/B5/B7 ≈ 3-4 KB per report without per-division detail, up to ~8 KB with 120 divisions and settings, plus B1/B2/B4/B6 records when they happen. Matches the ADR's "a few KB".

---

## The 5 hardest problems, ranked

1. **The player's capitol and its soldiers are world objects made of live city people.** `WDivCity` (capitol garrison) feeds world battle power and is resolved by world battles that kill and edit city people directly (C5), with an `RND` draw for experience; `invadeCapitol` builds an invasion spec from world armies and hands it to the city with a city-allocated ID and synchronous listener callbacks (C6); the outcome flows back through city callbacks into RAIDING, bounty credits, `AD.stats` and vassalage (B4). None of this fits a 30-step report; it needs a world-allocated invasion ID, an outcome record in the report, and a decision on what the world uses for the capitol garrison between reports (an aggregate from the report, per-division detail, or a battle the city resolves).
2. **The shared treasury and trade counters.** Credits live on the Player object and move instantly on both sides (`PBuyer.addReserve`, `PSeller.remove`, deals, tribute, upkeep, inflation), while goods move later through `promised`/`toBeAdded`. The world makes decisions on the live treasury and live export tallies (buy priority, mercenary misses, tribute clamp, raider demands, NPC sell limits). The ADR's two-phase rule ("the world counts only what was taken") changes vanilla behaviour: the world must keep its own ledger of outstanding requests so a 30-step-old report doesn't over-sell or double-charge, and decide what happens when the city can't pay.
3. **Soldiers crossing the boundary both ways.** Joining (B1) and returning (C4) move full `Induvidual` records between the city's people and the world's `AD.cityDivs()`; the world picks entry coordinates and draws RND inside the world part with a city-dependent draw count; stored soldiers keep references in the city's `StatsRelations` table that world events renumber (C5); the city reads `AD.cityDivs()` totals back into its own population (`POP.tot`). Needs: the person payload in both the report and the txn, the coordinate pick moved to the city part, and a pending ledger on both sides.
4. **Boosts and data-driven values route silently to the city.** `BValue` sends every player, player-region, player-division and clP boost to `vGet(Player)`, which sums live city sources (rooms, stats, laws, event boosts); `GVALUES.FACTION` locks and deltas (world camps, Raider, Stance) read arbitrary city values chosen by data files and mods. A grep can't list them. The hook must sit inside the boost and value machinery (snapshot the player-faction contribution per boostable and the value of every key the world part references, collected at load) and the caches keyed on `updateI` or the render frame have to be dealt with first.
5. **The step split itself.** Vanilla interleaves city and world resources (TOURISM, NOBLES, city events, `updateSpecial`, EVENT_HANDLER run after WORLD; the player's inflation runs inside FACTIONS; Emissaries sit in the city update but are world decisions), so "city first, then world" reorders vanilla. `EVENTS` and EVENT_HANDLER mix both parts in one resource and one random stream. Hidden write channels make the split leaky: credits changed inside message constructors (`MVassal`, `MessDefeated`), city listeners fired by world changes (`Invador` on peace, `Creator.RegBoost`, Emissaries on region capture), ~70 world messages that pause the game. On top of that, every one of these sites assumes one player faction.

Not verified: the size of a soldier's `Induvidual` (`STATS.count().longCount()`) and of an `InvasionSpec`; which battle paths reach `WDivCity.resolve`; whether `Attack.afterExit` runs inside the step; which `WORLD_*` boostables have live per-tick city sources key by key; game scripts under `<game>/base/script`.

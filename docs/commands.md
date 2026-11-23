# Player commands: coverage

Every player action that changes the simulation, how it reaches the game, and where it stands (ADR 0002, plan 0002). The list starts from the competing mod's survey of V71.44 (timjrobinson/songs-of-syx-multiplayer, `docs/sync-coverage.md` and `AutoSyncTable.java`; MIT, see THIRD-PARTY-NOTICES.md), checked against the game's source. Stage B is the city (SOS-34), stage D the world and diplomacy (plan 0002; its commands came with SOS-35). An action missing from this list stays local to the PC that took it, and the rig's comparison catches it.

Status: **done** (hooked, with its command id), **to do**, or why it needs no command. Rows that share a status are grouped, and signatures give parameter types only. Unless a row names another patch, an area's hooks are in its own patch: employment `96-cmd-employment`, rooms `96-cmd-rooms`, laws/stats `96-cmd-laws`, city military `96-cmd-military`, names/colours and nobles `96-cmd-names`, tools `96-cmd-tools`, world, diplomacy and events `96-cmd-world`.

## Done outside this list

- Curfew (`Curfew.setForADay`): pilot, command 1000 `curfew`, patch 96. It's in the competitor's coverage document but not in its hook table.
- Battle orders, battle settings, deployment, artillery and the agreed speed: SOS-32 (patches 90 and 95).
- Deals between humans, from the mod's Diplomacy tab: 8100 propose, 8101 answer, 8102 cancel, 8103 tariff and 8104 war (`HumanDeals`; SOS-72 to SOS-74, SOS-77; plans 0009 to 0012), through `Engine.dealPropose`, `dealAnswer`, `dealCancel`, `setTariff` and `declareWar`; no game entry point is hooked.
- A human's sally from its besieged capital: 7970 (`Sieges`; SOS-92, plan 0014), from the mod's siege popup and region row (`Engine.sallyOut`).
- The attacker's terms after a taken throne: 7973 (`Sieges`; SOS-92), from the mod's terms window (`Engine.assaultTerms`).
- Armies stationed at another human's capital: 7980 station, 7981 send away or call back, 7982 the host's supply (`Stations`; SOS-233), 7983 its player's grant of command to the host (batch 13), from the mod's Armies tab (`Engine.station`, `stationEnd`, `stationSupply`, `stationGrant`).
- Test tooling, never a player's (SOS-162; the mod's autopilot and the rig only): 7969 battleMoraleForTest (`MapBattles.moraleForTest`: one army's morale held below zero until the battle ends), 4009 divisionRecruitForTest (`Military.recruitForTest`: citizens drafted into an empty division at once, trained), 7979 regionGiveForTest (`Sieges.regionGiveForTest`: an NPC region becomes a human's); the older 7971, 7972, 7974 to 7978 (`Sieges`).
- An ally's answer to a battle prompt: 7903 (`WorldBattles`); it passes a watch-only PC's filter, as 7951, 7966 and 7967 do (`AiLog.captureOrder`, SOS-232). A local battle's result, 7964; a battle's speed vote, 7965; an ally's order and vote in a local battle, 7966 and 7967 (`MapBattles`). The sally against an invasion of a human's capital, 7968 (`MapBattles`; SOS-145): the invasion's ref and the invader's faction, -1 for raiders and rebels (SOS-232: the allies near are asked first).

## Method hooks (the competitor's entry hooks)

| Area | Entry point (V71.44) | Stage | Status |
| --- | --- | --- | --- |
| generic holder | `util.data.INT$IntImp` `set(int)` | B | not needed: hot path; the laws UI call sites are hooked instead (DPanel auto: command 1004) |
| generic holder | `util.data.BOOLEAN$BOOLEANImp` `set(boolean)` | B | not needed: hot path; no laws UI site writes one |
| employment | `settlement.room.main.employment.RoomEmployment$Priority` `set(int)` | B | done: 3000 workPriority |
| employment | `RoomEmployment$GRoupInt` `set(WGROUP, int)`, `RoomEmployment$2` `set(WGROUP, int)` | B | done: 3001 workGroupPriority |
| employment | `RoomEmployment` `setPrioOnSkill(WGROUP)`, `setPrioOnFullfillment(WGROUP)` | B | done: 3003 workPrioOnSkill, 3004 workPrioOnFulfillment |
| employment | `RoomEmployment` `setPrioOnSkill()` | B | not needed: no UI caller (new game and load only) |
| employment | `RoomEmployment` `setPrioOnFullfillment()` | B | not needed: no caller |
| employment | `RoomEmployments` `setTargets()` | B | done: 3002 workTargets |
| employment | `RoomEmploymentIns` `neededSet(int)` | B | done: 3005 roomWorkers (the trainer panel's gauge with the rooms patch; room rebuild and move-order paste run inside their own commands) |
| employment | `RoomEquip$Target` `set(int)` | B | done: 3007 equipTarget |
| employment | `autoEmploy(Room, boolean)` of `ROOM_PASTURE`, `ROOM_PHYSICIAN`, `ROOM_BREEDER`, `ROOM_BATH`, `ROOM_LAVATORY`, `ROOM_EATERY`, `ROOM_TAVERN`, `ROOM_PLEASURE`, `ROOM_CANTEEN`, `ROOM_BARBER`, `ROOM_MARKET`, `ROOM_SUPPLY`, `ROOM_EXPORT`, `ROOM_JANITOR`, `ROOM_STATION`, `ROOM_HAULER`, `ROOM_INN`, `ROOM_TRANSPORT`, `ROOM_STOCKPILE` | B | done: 3006 roomAutoEmploy (ModuleEmployment call sites) |
| tools/placement | `settlement.room.main.placement.RoomPlacer` `init(RoomBlueprintImp, int)`, `popHistory()`, `removeAllItems()`, `removeArea()`; `RoomPlacer$AutoWalls` `set(boolean)`; `UtilStructure` `set(TBuilding)`, `set(int, int)` | B | not needed: area-room planning is local, lifted out of the simulation around its steps (ADR 0003, `PlanStash`); the plan, auto walls and wall material included, travels whole in 6007 roomBuild. A refurnish start is the `UIRoomPlacer.init` row; `UtilStructure`'s write of the save's `ROOM_CONSTRUCTION` property stays local |
| tools/placement | `RoomPlacer` `create()` | B | done: 6007 roomBuild for a new area room, 6008 roomRefurnish for a refurnish, patch 97-room-plans (the whole plan; every PC rebuilds it in the step and creates it, or skips it alike) |
| tools/placement | `settlement.room.main.copy.CopierMass` `copy(int, int, int, int, int)` | B | done: 6102 massCopy (patch 97-room-plans, `CopyTools`, hook in `Second.place`): the selected tiles, centre, rotation, overwrite and road convert, applied by a fresh `Second`. The room copier is 6100 roomCopy and saved blueprints 6101 printPlace (ADR 0003, SOS-36) |
| tools/placement | `settlement.job.JOBS$1` `set(boolean)` | B | done: 6001 planMode |
| laws/stats | `settlement.entry.Immigration` `setWanted(Race, int)` | B | not needed: nothing calls it in V71.44 |
| laws/stats | `Immigration` `setHigher(Race, int)` | B | not needed: only the simulation calls it, inside the step |
| rooms | `settlement.room.main.RoomInstance` `activate(boolean)` | B | done: 2000 roomActivate |
| rooms | `RoomInstance` `remove(int, int, boolean, Object, boolean)` | B | done: 2001 roomRemove |
| rooms | `RoomInstance` `upgradeSet(int)`; `init.resources.RESOURCE` `remove(int, RTYPE)`; `settlement.maintenance.MAINTENANCE` `maintain(int, int)` | B | done: 2002 roomUpgrade (with its cost, `RESOURCE.remove`) and 2003 roomDowngrade (with `MAINTENANCE.maintain`); the map upgrader is tools 6000 |
| rooms | `radiusRawSet(byte)` of `ExportInstance`, `HaulerInstance`, `BuilderInstance`, `TransportInstance`, `StockpileInstance`, `SupplyInstance` | B | done: 2004 roomRadius (one UI site) |
| rooms | `setIndustry(int)` of `RefinerInstance`, `WorkshopInstance`, `HunterInstance`, `PastureInstance` | B | done: 2005 roomIndustry (one UI site) |
| rooms | `settlement.room.main.job.JobIterator` `resetResourceSearch()` | B | not needed: only called from inside other commands |
| rooms | `RoomConsumption` `enabledToggle(IndustryResource, ROOM_IDATA_INSTANCE, RoomInstance)` | B | done: 2006 roomConsumption |
| rooms | `FarmInstance` `changeTo(ROOM_FARM)` | B | done: 2007 farmChange |
| rooms | `PastureInstance` `slaughterAll()` | B | done: 2008 pastureSlaughterAll |
| rooms | `ExportInstance` `resourceSet(RESOURCE)`, `fetchingSet(boolean)`, `prioSet()` | B | done: 2009-2011 |
| rooms | `HaulerInstance` `setResource(RESOURCE)`, `fetchingSet(boolean)`, `prioSet()`, `storingSet(boolean)` | B | done: 2012-2015 |
| rooms | `ImportInstance` `allocate(RESOURCE)` | B | done: 2016 importAllocate |
| rooms | `StationInstance` `allocate(RESOURCE, int)` | B | done: 2017 stationAllocate |
| rooms | `TransportInstance` `fetchingSet(boolean)` | B | done: 2018 transportFetching |
| rooms | `settlement.room.infra.transport.Cart` `resourceSet(RESOURCE, TransportInstance)` | B | done: 2020 transportResource (call site) |
| rooms | `StockpileInstance` `fetchingSet(boolean)`, `prioritizeToggle()`, `storingSet(boolean)`, `allocateCrate(RESOURCE, int)`, `setSpecialAmount(RESOURCE, int)` | B | done: 2021-2025 |
| rooms | `SupplyInstance` `fetchingToggle()`, `prioritizeToggle()`, `allowedToggle(RESOURCE)`, `reset()` | B | done: 2026-2028 |
| rooms | `settlement.room.infra.logistics.MoveOrderPull` `destSet(RoomInstance)` | B | done: 2042 moveOrderSet |
| rooms | `init.resources.RBIT$RBITImp` `toggle(RESOURCE)`, `clear()` | B | done: 2044 moveOrderRes (both), 2033 prisonFetch (`toggle`) |
| rooms | `CanteenInstance` `usesToggle(ResG)` | B | done: 2029 canteenUses |
| rooms | `RoomDistribution` `usesToggle(RESOURCE, RoomInstance)` | B | done: 2030 distributionUses |
| rooms | `util.data.BOOLEANO$BooleanOEImp` `set(INDEXED, boolean)` | B | done: 2031 punishRace |
| rooms | `settlement.entity.humanoid.ai.types.prisoner.PrisonerData$1` `set(HAI, PUNISHMENT)`; `settlement.entity.humanoid.Humanoid` `interrupt()` | B | done: 2032 prisonerPunish (prison, stockade and subject panels); `interrupt`'s other two calls are developer buttons |
| rooms | `ROOM_GUARD$1` `set(Div, boolean)` | B | done: 2034 guardDuty |
| rooms | `ROOM_GATE` `lock(int, int, boolean)` | B | done: 2035 gateLock |
| city military | `settlement.room.military.artillery.ArtilleryInstance` `muster(boolean)`, `fireAtWill(boolean)`, `targetCooSet(int, int, boolean, boolean)` | B | done: battle screen (SOS-32, patch 95); placement inside the tool commands |
| city military | `ArtilleryInstance` `clearTarget()`, `targetDivSet(Div, boolean)` | B | done: 4007 artilleryClear, 4008 artilleryTargetDiv (gaps in patch 95) |
| laws/stats | `settlement.stats.law.Curfew` `setForADay(boolean)` | B | done: 1000 curfew |
| laws/stats | `StatCrime` `punishmentSet(HCLASS, Race, PUNISHMENT)` | B | done: 1001 punishmentSet |
| laws/stats | `settlement.stats.muls.StatsMultipliers` `mark(HCLASS, Race, int)` and `unmark(HCLASS, Race)` of `$Emancipate`, `$Prosecution`, `$StatMultiplierActionImp`, `$StatMultiplierWork` | B | done: 1002 actionMark, 1003 actionUnmark (DPanel call site) |
| laws/stats | `StatsMultipliers` `mark(Humanoid, boolean)` of `$Emancipate`, `$Handout`, `$Prosecution`, `$StatMultiplierActionImp`, `$StatMultiplierWork` | B | done: 1005 actionPerson, patch 67-read-only-ui |
| laws/stats | `util.race.PERMISSION$Permission` `set(HCLASS, Race, boolean)` | B | done: 1007 religionPermission, 1008 gravePermission |
| laws/stats | `StatsFood$3` `set(HCLASS, Race, boolean)` | B | done: 1006 foodAllowed |
| laws/stats | `StatServiceImp$Perm` `set(HCLASS_RACE, boolean)` | B | done: 1009 servicePermission |
| laws/stats | `StatsHome` `targetSet(int, HCLASS, Race, RESOURCE)` | B | done: 1010 homeTarget |
| laws/stats | `EquipCivic` `targetSet(int, HCLASS, Race)` | B | done: 1011 civicTarget |
| laws/stats | `StatsEducation` `policySet(HCLASS, Race, StatEducation)`, `policySet(HCLASS_RACE, StatEducation)` | B | done: 1012 educationPolicy |
| laws/stats | `StatsEducation$AgeType` `limitSet(HCLASS, Race, int)`, `limitSet(HCLASS_RACE, int)` | B | done: 1013 educationLimit |
| laws/stats | `StatDecree$2` `set(Race, int)` | B | done: 1014 statDecree |
| laws/stats | `game.faction.player.PTech` `levelSet(TECH, int)` | B | done: 1015 techLevel |
| names/colours | `StatsAppearance` `setCustomName(Induvidual, String)` | B | done: 5010 subjectName (the event's caller is stage D) |
| names/colours | `game.faction.FBanner` `bannerTypeSet(int)` | B | done: the player's banner, whole with its pixels, is 5016 playerBanner from the profile's draft (SOS-40); 5015 banner (no pixels, SOS-32) and 5009 bannerType still apply from older logs |
| nobles | `Humanoid` `nobleSet()` | B | done: 5011 noble and 5014 nobleReplace (call sites) |
| nobles | `game.nobility.NOBLES` `setOffice(Noble, NobleOffice)` | B | done: 5012 nobleOffice |
| nobles | `NOBLES` `ranksAllocate(Noble)` | B | done: 5013 nobleRank |
| city military | `game.battle.div.DivInfo` `menSet(int)` | B | done: pilot, 4000 divisionMen |
| city military | `DivInfo` `raceSet(Race)` | B | done: 4001 divisionRace |
| city military | `DivInfo` `trainingSet(StatTraining, double)` | B | done: 4002 divisionTraining |
| city military | `DivInfo` `equipSet(EquipBattle, double)`; `EquipBattle` `targetSet(Div, int)` | B | done: 4003 divisionEquipment |
| city military | `DivInfo` `bannerISet(int)` | B | done: 4004 divisionBanner |
| city military | `game.battle.Army` `setDivAtOrderedIndex(Div, Div)` | B | done: 4005 divisionOrder |
| city military | `world.army.WDivStoredAll` `attach(WArmy, Div)` | B | done: 7019 cityDivAttach, patch 96-cmd-world |
| city military | `settlement.battle.ArmyTrainingInfo` `sendOutWithoutTraining(boolean)` | B | done: 4006 sendOutUntrained |
| city military | `game.battle.setting.DivSettings` `musteringSet(boolean)`, `moppingSet(boolean)` | B | battle screen done (SOS-32, patch 95); no city screen calls them (only a developer tool) |
| world | `world.entity.army.WArmy` `setDestination(int, int)`, `intercept(WArmy)`, `besiege(Region)`, `stop()`, `raid(boolean)`, `disband()` | D | done: 7000 armyMove, 7001 armyIntercept, 7002 armyBesiege, 7003 armyStop, 7004 armyRaid, 7005 armyDisband. The step's "declare war and besiege?" is 7951 stepPromptWorldAnswer, applied as the human it asks (SOS-46; 7950 stepPromptAnswer is a city question's), patch 99-world-battles |
| world | `world.army.ADDiv` `disband()`, `reassign(WArmy)` | D | not needed: the Army screen's loop is one command at the call site, 7007 divsReassign; other callers run in the step |
| world | `ADDivs` `insert(int, int)` | D | done: 7009 divOrder |
| world | `bannerSet(int)` of `WDivRegional`, `WDivStored`, `WDivMercenary` | D | not needed: the Army banner editor is one command at the call site, 7008 divsBanner |
| world | `WDivRegional$1` `trainingSet(StatTraining, double)`, `equipSet(EquipBattle, double)`, `menSet(int)`, `raceSet(Race)`, `bannerISet(int)` | D | done: 7010 regionalTraining, 7011 regionalEquip, 7012 regionalMen, 7013 regionalRace, 7014 regionalBanner |
| world | `ADSupplies$ADArtillery$1` `set(WArmy, int)` | D | done: 7015 artillery |
| world | `world.region.RDOutputs` `squeze(Region)` | D | done: 7024 regionSqueeze, at the call site in PlayOutput |
| world | `world.region.RD` `setFaction(Region, Faction, boolean)` | D | not needed: hooked at its UI call sites, 7020 regionAutonomy and 7021 regionAbandon; other callers are the step, deals or debug |
| world | `world.battle.WBattles` `regAttack(Region, WArmy)` | D | not needed: hooked at its UI call site, 7026 regionAttack (war, then the attack) |
| diplomacy, events | `DipStance` `set(Faction, Faction)`, `set(FactionNPC)`; `NPCRequest` `clear()`; `OpsGifts` `makeDeal(FactionNPC, double)`; `game.faction.FCredits` `inc(double, CTYPE)`; `game.raiding.RaidingCurrent` `clear()` | D | not needed: every UI caller now runs inside a command; the rest is the step or debug |
| diplomacy | `game.faction.player.emmi.EmiType` `set(Object, int)` | D | done: 8004 emissaries |
| diplomacy | `game.faction.diplomacy.UpVassal$MOverlord` Accept | D | done: 8005 tributeAccept, patch 15-humans (SOS-46: the click paid at once, outside the step) |
| diplomacy | `game.faction.npc.NPCRequest` `expire()` | D | done: 8002 requestExpire |
| events | `game.events.killer.EventKiller` `setSuspect(int)` | D | done: 9003 killerSuspect, at the call site in Messenger (EventKiller is not menu-safe) |
| events | `RaidingCurrent` `setAllyFight()` | D | done: 9002 raiderAllyHelp, at the call site in MessAlly |

## Field writes from the UI

| Area | Entry point (V71.44) | Stage | Status |
| --- | --- | --- | --- |
| rooms | `TransportInstance.prio:Z`, written by `settlement.room.infra.transport.Gui$2` | B | done: 2019 transportPrio |
| rooms | `ROOM_BREEDER.prosecute:Z`, `limitTotal:I`, `limitSpecies:I`, written by `settlement.room.service.breeder.Gui$2`, `Gui$4`, `Gui$6` | B | done: 2037 breederProsecute, 2038 breederLimitTotal, 2039 breederLimitSpecies |
| rooms | `ROOM_M_TRAINER.trainingLimit:I`, written by `settlement.room.military.training.Gui$4` | B | done: 2040 trainerLimit |
| rooms | `MoveOrderPull.pullLimit:B`, written by `MoveOrderPullUI$1`, `MoveOrderPullUI$Detail$6`, `MoveOrderPullUI$Paster` | B | done: 2045 moveOrderLimit (+2041, 2046) |
| city military | `DivSettings.formation:DIV_FORMATION`, `guard:Z`, `running:Z` (by `view.battle.UISelection`), `fireAtWill:Z`, `shouldNotMoveToFire:Z`, `ammoI:S` (by `UISelection$11`, `$12`, `$13`) | B | done: battle settings (SOS-32, patch 95) |

## Other entry hooks

| Area | Entry point (V71.44) | Stage | Status |
| --- | --- | --- | --- |
| time | `game.GameSpeed.speedSet(D)V` | - | done: speed in the log (SOS-32); pause is the mod's |
| time | `game.GameSpeed.togglePause()V` | - | the mod's (spec §4 pause) |
| time | `GameSpeed.tmpPause()V`, `updateOnce()V` | - | the mod's (spec §4: menus don't pause) |
| tools | `view.tool.PlacableMultiTool.place()V` (private) | B | done: 6000 toolArea, patch 96-cmd-tools |
| city military | `BattleOrder$Locked.set(Copyable)V` | B | done: battle orders (SOS-32, patch 90) |
| city military (generated battle deploy) | `game.battle.Armies.initAndTeleport(LIST)V` | - | done: deployment (SOS-32, patch 95) |
| world | `UIWBattlePrompt.battle/assist/lastStand/battleSally(WBattleSpec)`, `siege(WBattleSiege)`, `result(WBattleSiege$Result)`, `result(WBattleResult,Z)` | D | done: not a command; the world step opens it on every PC alike, the engine keeps what it decides, and in a session it doesn't pause the game (patch 99-world-battles) |
| world | `view.world.ui.army.ArmyRecruit$3.clickA()V` | D | done: 7016 recruit; the step re-checks conscripts and room in the army |
| world | `view.world.ui.army.UIMerenaries$4.clickA()V` | D | done: 7017 mercHire |
| world | `view.world.ui.region.PlayInfo$9$1.exe()V` | D | done: 7020 regionAutonomy, 7021 regionAbandon |
| names/colours | `view.sett.ui.right.UIMiniHotSpots$1.placeFirst(II)V` | B | done: 5004 markAdd |
| tools | `view.sett.ui.home.UIHomeAssign.place(II AREA PLACER_TYPE)V` | B | done: 6004 homeAssign |
| tools | `view.sett.ui.room.priority.UIRoomRaceAssign.place(...)V` | B | done: 6005 workGroups |
| tools | `view.sett.ui.home.UIHomeOdd.place(...)V` | B | done: 6006 homeOddjobbers |
| rooms | `settlement.room.health.hospital.Gui$1.clickA()V` | B | done: 2036 hospitalFetch |
| rooms | `MoveOrderPullUI$1.clickA`, `$Placer.placeFirst(II)`, `$Detail$8.clickA`, `$Paster.place(...)` | B | done: 2041-2043, 2046 |
| tools | `view.sett.ui.room.construction.UIRoomPlacer.init(Room$RoomInstanceImp)V` | B | done: in a session the room keeps working under the plan; Build is 6008 roomRefurnish, the trash button 2001 roomRemove (patch 97-room-plans, `Refurnish`) |
| diplomacy | `view.ui.diplomacy.UIDipMessDeal$3.clickA`, `$4.clickA` | D | done: 8001 dealProposal; the vassal release action is 8003 vassalRelease |
| battle infra | `game.save.GameLoader.set()V` | - | -: engine load path (SOS-30) |
| world (generated battle) | `view.battle.IDeploy.update(F)Z`, `BattleState$ILiveConclude.update(F)Z`, `BattleState$ILiveConclude$1.close()V` | D | done (SOS-32): in a session the deploy panel doesn't pause and the battle doesn't set the speed; the victory or defeat panel's Close is 7962 battleConclude |
| laws/stats | `settlement.entry.Immigration.admit(Race,I)V` | B | done: 1016 admit (call sites; Immigration holds the menu-unsafe Immigrator) |

## Call sites

| Area | Entry point (V71.44) | Stage | Status |
| --- | --- | --- | --- |
| tools | `PlacableSimple.place(II)V` at `view.tool.PlacableSimpleTool`; `PlacableSimpleTile.place(II)V` at `view.tool.PlacableSimpleTileTool` | B | not needed: only the debug panel and the battle editor use them |
| tools | `PlacableFixed.place(IIII)V` at `view.tool.PlacableFixedTool`, `UIRoomPlacer$PlacerItemSingleArea` | B | done: 6002 roomItem and 6003 throneMove; the copier's and the print placer's are 6100 roomCopy and 6101 printPlace, patch 97-room-plans (SOS-36) |
| tools | `PlacableSingle.placeFirst/placeExpanded(II)V` at `view.tool.PlacableSingleTool` | B | not needed: the copier's source pick is local setup; move orders and marks are in their areas |
| events | `EVENT_HANDLER.choiceSelect`, `EventAction.exe`, `EVENT_HANDLER.set` at `game.event.engine.EventMessage$2` | D | done: 9000 eventChoice |
| diplomacy | `Deal.execute(Z)D` at `view.world.ui.faction.UIDiplomacy$4`, `view.ui.diplomacy.UIDipMessDeal$3` | D | done: 8000 dealAccept and 8001 dealProposal: the commit carries every term in DealSave's order, and the step rebuilds the deal and skips it if a term no longer holds |
| events | `MessDemand$Demand.pay()`, `RaidingCurrent.clear()`, `Messages.hide()` at `game.raiding.MessDemand$Demand$3` (pay), `$4` (decline hide) | D | done: 9001 raiderPay; decline only hides the message (not needed) |
| world | `RDLevelsTmp.accept()V` at `view.world.ui.region.PlayBuildingsPop$Info$1` | D | done: 7025 regionBuildings; the draft stays local, the step re-checks cost and credits |
| world | `WBattleSpec.engage/auto/retreat`, `WBattleSiege.retreat`, `WBattleResult.accept([I[I)`, `WBattleSiege$Result.occupy/abandon/puppet(DD[I[I)`, `DOUBLE_OE.incD` (mercy), `ACTION.exe` (close) at `view.world.ui.battle.BattleAssist$1-3, BattleBattle$1-3, BattleLastStand$1-2, BattleSally$1-3, BattleSiege$1-3, Conquer$7-9, Res$1` | D | done: 7900 worldBattleDecide (auto, retreat, the siege's Wait), 7901 worldBattleAccept, 7902 worldSiegeResult (patch 99-world-battles); Engage is 7900 with choice 4 (SOS-32), fought on the battle map on every PC; with own cities the asked human's PC generates it and every PC loads it (SOS-61) |
| world | `WArmyConstructor.create(IILFaction;)WArmy`, `UIArmies.openList(WArmy)` at `view.world.ui.army.List$9`, `List$Placer` | D | done: 7018 armyCreate; the first army's random tile is drawn in the step |
| names/colours | `Str.clear()`, `Str.add(CharSequence)` at `Army$2` (army name), `PlayInfo$11` (region), `UIProfile$3` (capital), `UIRoom$Detail$1` (room), `UIMiniHotSpots$Panel$1` (mark) | B | room 5001 and mark 5006 done, capital through 5002 factionName; army 7006 armyName and region 7022 regionName, patch 96-cmd-world |
| names/colours | `Str.add(C)`, `Str.add(CharSequence)`, `Str.clear()` at `snake2d.util.sprite.text.StringInputSprite` (faction name), `view.ui.div.UIDivEditor$3` (division name) | B | done: 5002 factionName (+5003 rulerName) and 5000 divisionName |
| names/colours | `ColorImp.setRed/Green/Blue(I)` at `util.gui.misc.GColorPicker$1/$2/$3` | B | done: 5008 colour for marks. The banners go into drafts: the player's (5016 playerBanner) and the division banners (5017 divisionBanner, SOS-40); 5008 for banners still applies from older logs |
| names/colours | `SettMarks$SettMark.remove()V` at `view.sett.ui.right.UIMiniHotSpots$Panel$2` | B | done: 5005 markRemove |
| rooms | `RBIT$RBITImp.or(RBIT)` at `MoveOrderPullUI$Detail$3` | B | done: 2044 moveOrderRes |
| laws/stats, world | `RMapInt.set`, `ArrayInt.set`, `INT_OE.set` at `CatPopulationGrowth$8`, `CatPopulationGrowth$9`, `UISubjectType$2`, `PlayPop$15`, `Army$10` | B/D | city side done: 1018 birthLimit, 1019 breeding, 1020 favourite; world side done: 7023 regionEdict (PlayPop$15), 7015 artillery (Army$10), patch 96-cmd-world |
| world (generated battle) | `BattleState.deploy()`, `liveRetreat()`, `reloadBattle()` at `view.battle.IDeploy$1`, `BattlePanel$12$1`, `BattlePanel$11$1`, `UIBattleResult$3` | D | done (SOS-32): Start is 7960 battleDeploy, Retreat 7961 battleRetreat; restart is 7963 battleRestart (SOS-41), and with own cities a vote of every player, the host's restart log entry (SOS-231) |

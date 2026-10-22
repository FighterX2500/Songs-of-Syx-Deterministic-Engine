# Player commands: coverage

Every player action that changes the simulation, how it reaches the game, and where it stands (ADR 0002, plan 0002). The list starts from the competing mod's survey of V71.44 (timjrobinson/songs-of-syx-multiplayer, `docs/sync-coverage.md` and `AutoSyncTable.java`; MIT, see THIRD-PARTY-NOTICES.md), checked against the game's source. Stage B is the city (SOS-34), stage D the world and diplomacy (plan 0002). An action missing from this list stays local to the PC that took it, and the rig's comparison catches it.

Status: **done** (hooked, with its command id), **to do**, or why it needs no command.

## Done outside this list

- Curfew (`Curfew.setForADay`): pilot, command 1000 `curfew`, patch 96. It's in the competitor's coverage document but not in its hook table.
- Battle orders, battle settings, deployment, artillery and the agreed speed: SOS-32 (patches 90 and 95).

## Method hooks (the competitor's entry hooks)

| Area | Entry point (V71.44) | Stage | Status |
| --- | --- | --- | --- |
| generic holder | `util.data.INT$IntImp` `set(I)V` | B | not hooked (hot path): the UI call sites instead (ADR 0002) |
| generic holder | `util.data.BOOLEAN$BOOLEANImp` `set(Z)Lutil/data/BOOLEAN$BOOLEAN_MUTABLE;` | B | not hooked (hot path): the UI call sites instead (ADR 0002) |
| employment | `settlement.room.main.employment.RoomEmployment$Priority` `set(I)V` | B | done: command 3000 workPriority, patch 96-cmd-employment |
| employment | `settlement.room.main.employment.RoomEmployment$GRoupInt` `set(Linit/type/WGROUP;I)V` | B | done: command 3001 workGroupPriority, patch 96-cmd-employment |
| employment | `settlement.room.main.employment.RoomEmployment$2` `set(Linit/type/WGROUP;I)V` | B | done: command 3001 workGroupPriority, patch 96-cmd-employment |
| employment | `settlement.room.main.employment.RoomEmployment` `setPrioOnSkill()V` | B | not needed: no UI caller (new game and load only) |
| employment | `settlement.room.main.employment.RoomEmployment` `setPrioOnFullfillment()V` | B | not needed: no caller |
| employment | `settlement.room.main.employment.RoomEmployment` `setPrioOnSkill(Linit/type/WGROUP;)V` | B | done: command 3003 workPrioOnSkill, patch 96-cmd-employment |
| employment | `settlement.room.main.employment.RoomEmployment` `setPrioOnFullfillment(Linit/type/WGROUP;)V` | B | done: command 3004 workPrioOnFulfillment, patch 96-cmd-employment |
| employment | `settlement.room.main.employment.RoomEmployments` `setTargets()V` | B | done: command 3002 workTargets, patch 96-cmd-employment |
| employment | `settlement.room.main.employment.RoomEmploymentIns` `neededSet(I)V` | B | done: command 3005 roomWorkers, patch 96-cmd-employment (the trainer panel's gauge with the rooms patch; room rebuild and move-order paste run inside their own commands) |
| employment | `settlement.room.main.employment.RoomEquip$Target` `set(I)V` | B | done: command 3007 equipTarget, patch 96-cmd-employment |
| tools/placement | `settlement.room.main.placement.RoomPlacer` `init(Lsettlement/room/main/RoomBlueprintImp;I)V` | B | to do |
| tools/placement | `settlement.room.main.placement.RoomPlacer` `create()Lsnake2d/util/datatypes/COORDINATE;` | B | to do |
| tools/placement | `settlement.room.main.placement.RoomPlacer` `popHistory()Z` | B | to do |
| tools/placement | `settlement.room.main.placement.RoomPlacer` `removeAllItems()Z` | B | to do |
| tools/placement | `settlement.room.main.placement.RoomPlacer` `removeArea()Z` | B | to do |
| tools/placement | `settlement.room.main.placement.RoomPlacer$AutoWalls` `set(Z)Lutil/data/BOOLEAN$BOOLEAN_MUTABLE;` | B | to do |
| tools/placement | `settlement.room.main.placement.UtilStructure` `set(Lsettlement/tilemap/terrain/TBuilding;)V` | B | to do |
| tools/placement | `settlement.room.main.placement.UtilStructure` `set(II)V` | B | to do |
| tools/placement | `settlement.room.main.copy.CopierMass` `copy(IIIII)V` | B | to do |
| laws/stats | `settlement.entry.Immigration` `setWanted(Linit/race/Race;I)V` | B | to do |
| laws/stats | `settlement.entry.Immigration` `setHigher(Linit/race/Race;I)V` | B | to do |
| rooms | `settlement.room.main.RoomInstance` `activate(Z)V` | B | to do |
| rooms | `settlement.room.main.RoomInstance` `remove(IIZLjava/lang/Object;Z)Lsettlement/room/main/TmpArea;` | B | to do |
| rooms | `settlement.room.main.RoomInstance` `upgradeSet(I)V` | B | to do |
| rooms | `init.resources.RESOURCE` `remove(ILgame/faction/FResources$RTYPE;)I` | B | to do |
| rooms | `settlement.maintenance.MAINTENANCE` `maintain(II)V` | B | to do |
| employment | `settlement.room.food.pasture.ROOM_PASTURE` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.health.physician.ROOM_PHYSICIAN` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.service.breeder.ROOM_BREEDER` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.service.hygine.bath.ROOM_BATH` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.service.lavatory.ROOM_LAVATORY` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.service.food.eatery.ROOM_EATERY` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.service.food.tavern.ROOM_TAVERN` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.service.pleasure.ROOM_PLEASURE` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.service.food.canteen.ROOM_CANTEEN` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.service.barber.ROOM_BARBER` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.service.market.ROOM_MARKET` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.military.supply.ROOM_SUPPLY` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.infra.export.ROOM_EXPORT` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.infra.janitor.ROOM_JANITOR` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.infra.station.ROOM_STATION` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.infra.hauler.ROOM_HAULER` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.infra.inn.ROOM_INN` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.infra.transport.ROOM_TRANSPORT` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| employment | `settlement.room.infra.stockpile.ROOM_STOCKPILE` `autoEmploy(Lsettlement/room/main/Room;Z)V` | B | done: command 3006 roomAutoEmploy, patch 96-cmd-employment (ModuleEmployment call sites) |
| rooms | `settlement.room.infra.export.ExportInstance` `radiusRawSet(B)V` | B | to do |
| rooms | `settlement.room.infra.hauler.HaulerInstance` `radiusRawSet(B)V` | B | to do |
| rooms | `settlement.room.infra.builder.BuilderInstance` `radiusRawSet(B)V` | B | to do |
| rooms | `settlement.room.infra.transport.TransportInstance` `radiusRawSet(B)V` | B | to do |
| rooms | `settlement.room.infra.stockpile.StockpileInstance` `radiusRawSet(B)V` | B | to do |
| rooms | `settlement.room.military.supply.SupplyInstance` `radiusRawSet(B)V` | B | to do |
| rooms | `settlement.room.industry.refiner.RefinerInstance` `setIndustry(I)V` | B | to do |
| rooms | `settlement.room.industry.workshop.WorkshopInstance` `setIndustry(I)V` | B | to do |
| rooms | `settlement.room.food.hunter.HunterInstance` `setIndustry(I)V` | B | to do |
| rooms | `settlement.room.food.pasture.PastureInstance` `setIndustry(I)V` | B | to do |
| rooms | `settlement.room.main.job.JobIterator` `resetResourceSearch()V` | B | to do |
| rooms | `settlement.room.industry.module.consumption.RoomConsumption` `enabledToggle(Lsettlement/room/industry/module/IndustryResource;Lsettlement/room/industry/module/ROOM_IDATA_INSTANCE;Lsettlement/room/main/RoomInstance;)V` | B | to do |
| rooms | `settlement.room.food.farm.FarmInstance` `changeTo(Lsettlement/room/food/farm/ROOM_FARM;)V` | B | to do |
| rooms | `settlement.room.food.pasture.PastureInstance` `slaughterAll()V` | B | to do |
| rooms | `settlement.room.infra.export.ExportInstance` `resourceSet(Linit/resources/RESOURCE;)V` | B | to do |
| rooms | `settlement.room.infra.export.ExportInstance` `fetchingSet(Z)V` | B | to do |
| rooms | `settlement.room.infra.export.ExportInstance` `prioSet()V` | B | to do |
| rooms | `settlement.room.infra.hauler.HaulerInstance` `setResource(Linit/resources/RESOURCE;)V` | B | to do |
| rooms | `settlement.room.infra.hauler.HaulerInstance` `fetchingSet(Z)V` | B | to do |
| rooms | `settlement.room.infra.hauler.HaulerInstance` `prioSet()V` | B | to do |
| rooms | `settlement.room.infra.hauler.HaulerInstance` `storingSet(Z)V` | B | to do |
| rooms | `settlement.room.infra.importt.ImportInstance` `allocate(Linit/resources/RESOURCE;)V` | B | to do |
| rooms | `settlement.room.infra.station.StationInstance` `allocate(Linit/resources/RESOURCE;I)V` | B | to do |
| rooms | `settlement.room.infra.transport.TransportInstance` `fetchingSet(Z)V` | B | to do |
| rooms | `settlement.room.infra.transport.Cart` `resourceSet(Linit/resources/RESOURCE;Lsettlement/room/infra/transport/TransportInstance;)V` | B | to do |
| rooms | `settlement.room.infra.stockpile.StockpileInstance` `fetchingSet(Z)V` | B | to do |
| rooms | `settlement.room.infra.stockpile.StockpileInstance` `prioritizeToggle()V` | B | to do |
| rooms | `settlement.room.infra.stockpile.StockpileInstance` `storingSet(Z)V` | B | to do |
| rooms | `settlement.room.infra.stockpile.StockpileInstance` `allocateCrate(Linit/resources/RESOURCE;I)V` | B | to do |
| rooms | `settlement.room.infra.stockpile.StockpileInstance` `setSpecialAmount(Linit/resources/RESOURCE;I)V` | B | to do |
| rooms | `settlement.room.military.supply.SupplyInstance` `fetchingToggle()V` | B | to do |
| rooms | `settlement.room.military.supply.SupplyInstance` `prioritizeToggle()V` | B | to do |
| rooms | `settlement.room.military.supply.SupplyInstance` `allowedToggle(Linit/resources/RESOURCE;)V` | B | to do |
| rooms | `settlement.room.military.supply.SupplyInstance` `reset()V` | B | to do |
| rooms | `settlement.room.infra.logistics.MoveOrderPull` `destSet(Lsettlement/room/main/RoomInstance;)V` | B | to do |
| rooms | `init.resources.RBIT$RBITImp` `toggle(Linit/resources/RESOURCE;)V` | B | to do |
| rooms | `init.resources.RBIT$RBITImp` `clear()V` | B | to do |
| rooms | `settlement.room.service.food.canteen.CanteenInstance` `usesToggle(Linit/resources/ResG;)V` | B | to do |
| rooms | `settlement.room.service.food.eatery.RoomDistribution` `usesToggle(Linit/resources/RESOURCE;Lsettlement/room/main/RoomInstance;)V` | B | to do |
| rooms | `util.data.BOOLEANO$BooleanOEImp` `set(Lsnake2d/util/sets/INDEXED;Z)Lutil/data/BOOLEANO$BOOLEAN_OE;` | B | to do |
| rooms | `settlement.entity.humanoid.ai.types.prisoner.PrisonerData$1` `set(Lsettlement/entity/humanoid/ai/main/HAI;Linit/type/CRIME_PUNISHMENTS$PUNISHMENT;)V` | B | to do |
| rooms | `settlement.entity.humanoid.Humanoid` `interrupt()V` | B | to do |
| rooms | `settlement.room.law.guard.ROOM_GUARD$1` `set(Lgame/battle/div/Div;Z)Lutil/data/BOOLEANO$BOOLEAN_OE;` | B | to do |
| rooms | `settlement.room.infra.gate.ROOM_GATE` `lock(IIZ)V` | B | to do |
| city military | `settlement.room.military.artillery.ArtilleryInstance` `muster(Z)V` | B | to do |
| city military | `settlement.room.military.artillery.ArtilleryInstance` `fireAtWill(Z)V` | B | to do |
| city military | `settlement.room.military.artillery.ArtilleryInstance` `clearTarget()V` | B | to do |
| city military | `settlement.room.military.artillery.ArtilleryInstance` `targetCooSet(IIZZ)V` | B | to do |
| city military | `settlement.room.military.artillery.ArtilleryInstance` `targetDivSet(Lgame/battle/div/Div;Z)V` | B | to do |
| tools/placement | `settlement.job.JOBS$1` `set(Z)Lutil/data/BOOLEAN$BOOLEAN_MUTABLE;` | B | to do |
| laws/stats | `settlement.stats.law.Curfew` `setForADay(Z)V` | B | to do |
| laws/stats | `settlement.stats.law.StatCrime` `punishmentSet(Linit/type/HCLASS;Linit/race/Race;Linit/type/CRIME_PUNISHMENTS$PUNISHMENT;)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$Emancipate` `mark(Linit/type/HCLASS;Linit/race/Race;I)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$Emancipate` `mark(Lsettlement/entity/humanoid/Humanoid;Z)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$Emancipate` `unmark(Linit/type/HCLASS;Linit/race/Race;)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$Handout` `mark(Lsettlement/entity/humanoid/Humanoid;Z)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$Prosecution` `mark(Linit/type/HCLASS;Linit/race/Race;I)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$Prosecution` `mark(Lsettlement/entity/humanoid/Humanoid;Z)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$Prosecution` `unmark(Linit/type/HCLASS;Linit/race/Race;)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$StatMultiplierActionImp` `mark(Linit/type/HCLASS;Linit/race/Race;I)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$StatMultiplierActionImp` `mark(Lsettlement/entity/humanoid/Humanoid;Z)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$StatMultiplierActionImp` `unmark(Linit/type/HCLASS;Linit/race/Race;)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$StatMultiplierWork` `mark(Linit/type/HCLASS;Linit/race/Race;I)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$StatMultiplierWork` `mark(Lsettlement/entity/humanoid/Humanoid;Z)V` | B | to do |
| laws/stats | `settlement.stats.muls.StatsMultipliers$StatMultiplierWork` `unmark(Linit/type/HCLASS;Linit/race/Race;)V` | B | to do |
| laws/stats | `util.race.PERMISSION$Permission` `set(Linit/type/HCLASS;Linit/race/Race;Z)V` | B | to do |
| laws/stats | `settlement.stats.colls.StatsFood$3` `set(Linit/type/HCLASS;Linit/race/Race;Z)V` | B | to do |
| laws/stats | `settlement.stats.service.StatServiceImp$Perm` `set(Linit/type/HCLASS_RACE;Z)Lutil/data/BOOLEANO$BOOLEAN_OE;` | B | to do |
| laws/stats | `settlement.stats.colls.StatsHome` `targetSet(ILinit/type/HCLASS;Linit/race/Race;Linit/resources/RESOURCE;)V` | B | to do |
| laws/stats | `settlement.stats.equip.EquipCivic` `targetSet(ILinit/type/HCLASS;Linit/race/Race;)V` | B | to do |
| laws/stats | `settlement.stats.colls.StatsEducation` `policySet(Linit/type/HCLASS;Linit/race/Race;Lsettlement/stats/colls/StatsEducation$StatEducation;)V` | B | to do |
| laws/stats | `settlement.stats.colls.StatsEducation` `policySet(Linit/type/HCLASS_RACE;Lsettlement/stats/colls/StatsEducation$StatEducation;)V` | B | to do |
| laws/stats | `settlement.stats.colls.StatsEducation$AgeType` `limitSet(Linit/type/HCLASS;Linit/race/Race;I)V` | B | to do |
| laws/stats | `settlement.stats.colls.StatsEducation$AgeType` `limitSet(Linit/type/HCLASS_RACE;I)V` | B | to do |
| laws/stats | `settlement.stats.stat.StatDecree$2` `set(Linit/race/Race;I)V` | B | to do |
| names/colours | `settlement.stats.colls.StatsAppearance` `setCustomName(Lsettlement/stats/Induvidual;Ljava/lang/String;)V` | B | to do |
| nobles | `settlement.entity.humanoid.Humanoid` `nobleSet()V` | B | to do |
| nobles | `game.nobility.NOBLES` `setOffice(Lgame/nobility/Noble;Lgame/nobility/NobleOffice;)V` | B | to do |
| nobles | `game.nobility.NOBLES` `ranksAllocate(Lgame/nobility/Noble;)V` | B | to do |
| city military | `game.battle.div.DivInfo` `menSet(I)V` | B | done: pilot, command 4000 divisionMen, patch 96 |
| city military | `game.battle.div.DivInfo` `raceSet(Linit/race/Race;)V` | B | to do |
| city military | `game.battle.div.DivInfo` `trainingSet(Lsettlement/stats/colls/StatsBattle$StatTraining;D)V` | B | to do |
| city military | `game.battle.div.DivInfo` `equipSet(Lsettlement/stats/equip/EquipBattle;D)V` | B | to do |
| city military | `game.battle.div.DivInfo` `bannerISet(I)V` | B | to do |
| city military | `settlement.stats.equip.EquipBattle` `targetSet(Lgame/battle/div/Div;I)V` | B | to do |
| city military | `game.battle.Army` `setDivAtOrderedIndex(Lgame/battle/div/Div;Lgame/battle/div/Div;)V` | B | to do |
| city military | `world.army.WDivStoredAll` `attach(Lworld/entity/army/WArmy;Lgame/battle/div/Div;)V` | B | to do |
| city military | `settlement.battle.ArmyTrainingInfo` `sendOutWithoutTraining(Z)V` | B | to do |
| city military | `game.battle.setting.DivSettings` `musteringSet(Z)V` | B | battle screen done (SOS-32, patch 95); city army screen to do |
| city military | `game.battle.setting.DivSettings` `moppingSet(Z)V` | B | battle screen done (SOS-32, patch 95); city army screen to do |
| world | `world.entity.army.WArmy` `setDestination(II)V` | D | to do |
| world | `world.entity.army.WArmy` `intercept(Lworld/entity/army/WArmy;)V` | D | to do |
| world | `world.entity.army.WArmy` `besiege(Lworld/map/regions/Region;)V` | D | to do |
| world | `world.entity.army.WArmy` `stop()V` | D | to do |
| world | `world.entity.army.WArmy` `raid(Z)V` | D | to do |
| world | `world.entity.army.WArmy` `disband()V` | D | to do |
| world | `world.army.ADDiv` `disband()V` | D | to do |
| world | `world.army.ADDiv` `reassign(Lworld/entity/army/WArmy;)V` | D | to do |
| world | `world.army.ADDivs` `insert(II)V` | D | to do |
| world | `world.army.WDivRegional` `bannerSet(I)V` | D | to do |
| world | `world.army.WDivStored` `bannerSet(I)V` | D | to do |
| world | `world.army.WDivMercenary` `bannerSet(I)V` | D | to do |
| world | `world.army.WDivRegional$1` `trainingSet(Lsettlement/stats/colls/StatsBattle$StatTraining;D)V` | D | to do |
| world | `world.army.WDivRegional$1` `equipSet(Lsettlement/stats/equip/EquipBattle;D)V` | D | to do |
| world | `world.army.WDivRegional$1` `menSet(I)V` | D | to do |
| world | `world.army.WDivRegional$1` `raceSet(Linit/race/Race;)V` | D | to do |
| world | `world.army.WDivRegional$1` `bannerISet(I)V` | D | to do |
| world | `world.army.ADSupplies$ADArtillery$1` `set(Lworld/entity/army/WArmy;I)V` | D | to do |
| world | `world.region.RDOutputs` `squeze(Lworld/map/regions/Region;)V` | D | to do |
| world | `world.region.RD` `setFaction(Lworld/map/regions/Region;Lgame/faction/Faction;Z)V` | D | to do |
| world | `world.battle.WBattles` `regAttack(Lworld/map/regions/Region;Lworld/entity/army/WArmy;)V` | D | to do |
| diplomacy | `game.faction.diplomacy.DipStance` `set(Lgame/faction/Faction;Lgame/faction/Faction;)V` | D | to do |
| diplomacy | `game.faction.diplomacy.DipStance` `set(Lgame/faction/npc/FactionNPC;)V` | D | to do |
| diplomacy | `game.faction.player.emmi.EmiType` `set(Ljava/lang/Object;I)V` | D | to do |
| diplomacy | `game.faction.npc.NPCRequest` `expire()V` | D | to do |
| diplomacy | `game.faction.npc.NPCRequest` `clear()V` | D | to do |
| diplomacy | `game.faction.royalty.opinion.OpsGifts` `makeDeal(Lgame/faction/npc/FactionNPC;D)V` | D | to do |
| diplomacy | `game.faction.FCredits` `inc(DLgame/faction/FCredits$CTYPE;)V` | D | to do |
| events | `game.raiding.RaidingCurrent` `clear()V` | D | to do |
| laws/stats | `game.faction.player.PTech` `levelSet(Linit/tech/TECH;I)V` | B | to do |
| events | `game.events.killer.EventKiller` `setSuspect(I)V` | D | to do |
| events | `game.raiding.RaidingCurrent` `setAllyFight()V` | D | to do |
| names/colours | `game.faction.FBanner` `bannerTypeSet(I)V` | B | to do |

## Field writes from the UI

| Area | Entry point (V71.44) | Stage | Status |
| --- | --- | --- | --- |
| rooms | `TransportInstance.prio:Z`, written by `settlement.room.infra.transport.Gui$2` | B | to do |
| rooms | `ROOM_BREEDER.prosecute:Z`, written by `settlement.room.service.breeder.Gui$2` | B | to do |
| rooms | `ROOM_BREEDER.limitTotal:I`, written by `settlement.room.service.breeder.Gui$4` | B | to do |
| rooms | `ROOM_BREEDER.limitSpecies:I`, written by `settlement.room.service.breeder.Gui$6` | B | to do |
| rooms | `ROOM_M_TRAINER.trainingLimit:I`, written by `settlement.room.military.training.Gui$4` | B | to do |
| rooms | `MoveOrderPull.pullLimit:B`, written by `MoveOrderPullUI$1`, `MoveOrderPullUI$Detail$6`, `MoveOrderPullUI$Paster` | B | to do |
| city military | `DivSettings.formation:DIV_FORMATION`, written by `view.battle.UISelection` | B | done: battle settings (SOS-32, patch 95) |
| city military | `DivSettings.guard:Z`, written by `view.battle.UISelection` | B | done: battle settings (SOS-32, patch 95) |
| city military | `DivSettings.running:Z`, written by `view.battle.UISelection` | B | done: battle settings (SOS-32, patch 95) |
| city military | `DivSettings.fireAtWill:Z`, written by `view.battle.UISelection$11` | B | done: battle settings (SOS-32, patch 95) |
| city military | `DivSettings.shouldNotMoveToFire:Z`, written by `view.battle.UISelection$12` | B | done: battle settings (SOS-32, patch 95) |
| city military | `DivSettings.ammoI:S`, written by `view.battle.UISelection$13` | B | done: battle settings (SOS-32, patch 95) |

## Other entry hooks

| Area | Entry point (V71.44) | Stage | Status |
| --- | --- | --- | --- |
| time | `game.GameSpeed.speedSet(D)V` | - | done: speed in the log (SOS-32); pause is the mod's |
| time | `game.GameSpeed.togglePause()V` | - | the mod's (spec §4 pause) |
| time | `GameSpeed.tmpPause()V`, `updateOnce()V` | - | the mod's (spec §4: menus don't pause) |
| tools | `view.tool.PlacableMultiTool.place()V` (private) | B | to do |
| city military | `BattleOrder$Locked.set(Copyable)V` | B | done: battle orders (SOS-32, patch 90) |
| city military (generated battle deploy) | `game.battle.Armies.initAndTeleport(LIST)V` | - | done: deployment (SOS-32, patch 95) |
| world | `UIWBattlePrompt.battle/assist/lastStand/battleSally(WBattleSpec)`, `siege(WBattleSiege)`, `result(WBattleSiege$Result)`, `result(WBattleResult,Z)` | D | to do |
| world | `view.world.ui.army.ArmyRecruit$3.clickA()V` | D | to do |
| world | `view.world.ui.army.UIMerenaries$4.clickA()V` | D | to do |
| world | `view.world.ui.region.PlayInfo$9$1.exe()V` | D | to do |
| names/colours | `view.sett.ui.right.UIMiniHotSpots$1.placeFirst(II)V` | B | to do |
| tools | `view.sett.ui.home.UIHomeAssign.place(II AREA PLACER_TYPE)V` | B | to do |
| tools | `view.sett.ui.room.priority.UIRoomRaceAssign.place(...)V` | B | to do |
| tools | `view.sett.ui.home.UIHomeOdd.place(...)V` | B | to do |
| rooms | `settlement.room.health.hospital.Gui$1.clickA()V` | B | to do |
| rooms | `MoveOrderPullUI$1.clickA`, `$Placer.placeFirst(II)`, `$Detail$8.clickA`, `$Paster.place(...)` | B | to do |
| tools | `view.sett.ui.room.construction.UIRoomPlacer.init(Room$RoomInstanceImp)V` | B | to do |
| diplomacy | `view.ui.diplomacy.UIDipMessDeal$3.clickA`, `$4.clickA` | D | to do |
| battle infra | `game.save.GameLoader.set()V` | - | -: engine load path (SOS-30) |
| world (generated battle) | `view.battle.IDeploy.update(F)Z`, `BattleState$ILiveConclude.update(F)Z`, `BattleState$ILiveConclude$1.close()V` | D | to do |
| laws/stats | `settlement.entry.Immigration.admit(Race,I)V` | B | to do |

## Call sites

| Area | Entry point (V71.44) | Stage | Status |
| --- | --- | --- | --- |
| tools | `PlacableSimple.place(II)V` at `view.tool.PlacableSimpleTool` | B | to do |
| tools | `PlacableSimpleTile.place(II)V` at `view.tool.PlacableSimpleTileTool` | B | to do |
| tools | `PlacableFixed.place(IIII)V` at `view.tool.PlacableFixedTool`, `UIRoomPlacer$PlacerItemSingleArea` | B | to do |
| tools | `PlacableSingle.placeFirst/placeExpanded(II)V` at `view.tool.PlacableSingleTool` | B | to do |
| events | `EVENT_HANDLER.choiceSelect`, `EventAction.exe`, `EVENT_HANDLER.set` at `game.event.engine.EventMessage$2` | D | to do |
| diplomacy | `Deal.execute(Z)D` at `view.world.ui.faction.UIDiplomacy$4`, `view.ui.diplomacy.UIDipMessDeal$3` | D | to do |
| events | `MessDemand$Demand.pay()`, `RaidingCurrent.clear()`, `Messages.hide()` at `game.raiding.MessDemand$Demand$3` (pay), `$4` (decline hide) | D | to do |
| world | `RDLevelsTmp.accept()V` at `view.world.ui.region.PlayBuildingsPop$Info$1` | D | to do |
| world | `WBattleSpec.engage/auto/retreat`, `WBattleSiege.retreat`, `WBattleResult.accept([I[I)`, `WBattleSiege$Result.occupy/abandon/puppet(DD[I[I)`, `DOUBLE_OE.incD` (mercy), `ACTION.exe` (close) at `view.world.ui.battle.BattleAssist$1-3, BattleBattle$1-3, BattleLastStand$1-2, BattleSally$1-3, BattleSiege$1-3, Conquer$7-9, Res$1` | D | to do |
| world | `WArmyConstructor.create(IILFaction;)WArmy`, `UIArmies.openList(WArmy)` at `view.world.ui.army.List$9`, `List$Placer` | D | to do |
| names/colours | `Str.clear()`, `Str.add(CharSequence)` at `Army$2` (army name), `PlayInfo$11` (region), `UIProfile$3` (capital), `UIRoom$Detail$1` (room), `UIMiniHotSpots$Panel$1` (mark) | B | to do |
| names/colours | `Str.add(C)`, `Str.add(CharSequence)`, `Str.clear()` at `snake2d.util.sprite.text.StringInputSprite` (faction name), `view.ui.div.UIDivEditor$3` (division name) | B | to do |
| names/colours | `ColorImp.setRed/Green/Blue(I)` at `util.gui.misc.GColorPicker$1/$2/$3` | B | to do |
| names/colours | `SettMarks$SettMark.remove()V` at `view.sett.ui.right.UIMiniHotSpots$Panel$2` | B | to do |
| rooms | `RBIT$RBITImp.or(RBIT)` at `MoveOrderPullUI$Detail$3` | B | to do |
| laws/stats, world | `RMapInt.set`, `ArrayInt.set`, `INT_OE.set` at `CatPopulationGrowth$8`, `CatPopulationGrowth$9`, `UISubjectType$2`, `PlayPop$15`, `Army$10` | B/D | to do |
| world (generated battle) | `BattleState.deploy()`, `liveRetreat()`, `reloadBattle()` at `view.battle.IDeploy$1`, `BattlePanel$12$1`, `BattlePanel$11$1`, `UIBattleResult$3` | D | to do |

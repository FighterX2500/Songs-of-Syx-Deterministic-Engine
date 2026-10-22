package sosdet.rig.act;

import java.util.ArrayList;
import java.util.List;

import game.GAME;
import init.race.RACES;
import init.race.Race;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import init.type.CRIME_PUNISHMENTS;
import init.type.CRIME_PUNISHMENTS.PUNISHMENT;
import init.type.HTYPES;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.entity.humanoid.ai.types.prisoner.AIModule_Prisoner;
import settlement.main.SETT;
import settlement.misc.job.JOBMANAGER_HASER;
import settlement.room.food.farm.ROOM_FARM;
import settlement.room.industry.module.ROOM_IDATA_INSTANCE;
import settlement.room.industry.module.ROOM_PRODUCER_INSTANCE;
import settlement.room.industry.module.consumption.RoomConsumption;
import settlement.room.industry.module.consumption.RoomConsumption.ROOM_CONSUMPTION_HASER;
import settlement.room.infra.export.ExportInstance;
import settlement.room.infra.gate.ROOM_GATE;
import settlement.room.infra.stockpile.StockpileInstance;
import settlement.room.law.PUNISHMENT_SERVICE;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomBlueprintIns;
import settlement.room.main.RoomInstance;
import settlement.room.main.TmpArea;
import settlement.room.main.job.ROOM_RADIUS.ROOM_RADIUSE;
import settlement.room.service.food.eatery.RoomDistribution.RoomDistributionIns;
import sosdet.Engine;
import sosdet.game.cmd.Rooms;

/**
 * The rig's scripted player: room actions, each the call the game's UI makes (SOS-34). Where the hook sits at a UI
 * call site, the action makes that call site's statement; where the game's class or method is package-private, it
 * makes the hook's own call ({@code Rooms.*}), which is what the hooked method does first.
 *
 * <p>Rooms are named by kind (stockpile, hauler, farm, ...): the first room of the first blueprint of that kind that
 * has one, so every copy picks the same room in the same save. Resources and races are their game indices, -1 for
 * none.
 */
public final class RoomActions {

    private RoomActions() {
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        case "room-activate": // room-activate <kind> 1|0: the room panel's on/off button
            Engine.betweenSteps(() -> room(a[1]).activate(on(a[2])));
            return true;
        case "room-remove": // room-remove <kind>: the room panel's delete button
            Engine.betweenSteps(() -> {
                RoomInstance r = room(a[1]);
                TmpArea t = r.remove(r.mX(), r.mY(), true, r, false);
                if (t != null) {
                    t.clear();
                }
            });
            return true;
        case "room-upgrade": // room-upgrade <kind>: the upgrade button
            Engine.betweenSteps(() -> Rooms.upgrade(room(a[1]), false));
            return true;
        case "room-upgrade-all": // room-upgrade-all <kind>: the table's "upgrade all", for one room
            Engine.betweenSteps(() -> Rooms.upgrade(room(a[1]), true));
            return true;
        case "room-downgrade": // room-downgrade <kind>: the downgrade button
            Engine.betweenSteps(() -> Rooms.downgrade(room(a[1])));
            return true;
        case "room-radius": // room-radius <kind> <0-100>: the work radius slider
            Engine.betweenSteps(() -> {
                RoomInstance r = room(a[1]);
                byte v = (byte) Integer.parseInt(a[2]);
                if (!Rooms.radius(r, v)) {
                    ((ROOM_RADIUSE) r.blueprintI()).radiusInstance(r).radiusRawSet(v);
                }
            });
            return true;
        case "room-industry": // room-industry <kind> <recipe>: the recipe picker
            Engine.betweenSteps(() -> {
                RoomInstance r = room(a[1]);
                int k = Integer.parseInt(a[2]);
                if (!Rooms.industry(r, k)) {
                    ((ROOM_PRODUCER_INSTANCE) r).setIndustry(k);
                    ((JOBMANAGER_HASER) r).getWork().resetResourceSearch();
                }
            });
            return true;
        case "room-consumption": // room-consumption <kind> <k>: a consumed resource on/off (library, admin, ...)
            Engine.betweenSteps(() -> {
                RoomInstance r = room(a[1]);
                RoomConsumption c = ((ROOM_CONSUMPTION_HASER) r.blueprintI()).consumption();
                c.enabledToggle(c.ins().get(Integer.parseInt(a[2])), (ROOM_IDATA_INSTANCE) r, r);
            });
            return true;
        case "farm-change": // farm-change: the first farm to the first other crop
            Engine.betweenSteps(() -> {
                RoomInstance r = room("farm");
                for (ROOM_FARM f : SETT.ROOMS().FARMS) {
                    if (f != r.blueprintI()) {
                        Rooms.farmChange(r, f);
                        return;
                    }
                }
            });
            return true;
        case "pasture-slaughter": // pasture-slaughter: "slaughter all"
            Engine.betweenSteps(() -> Rooms.slaughterAll(room("pasture")));
            return true;
        case "export-resource": // export-resource <res>
            Engine.betweenSteps(() -> Rooms.exportResource(room("export"), res(a[1])));
            return true;
        case "export-fetching": // export-fetching 1|0
            Engine.betweenSteps(() -> ((ExportInstance) room("export")).fetchingSet(on(a[1])));
            return true;
        case "export-prio": // export-prio: toggles
            Engine.betweenSteps(() -> Rooms.exportPrio(room("export")));
            return true;
        case "hauler-resource": // hauler-resource <res>
            Engine.betweenSteps(() -> Rooms.haulerResource(room("hauler"), res(a[1])));
            return true;
        case "hauler-fetching": // hauler-fetching 1|0
            Engine.betweenSteps(() -> Rooms.haulerFetching(room("hauler"), on(a[1])));
            return true;
        case "hauler-prio": // hauler-prio: toggles
            Engine.betweenSteps(() -> Rooms.haulerPrio(room("hauler")));
            return true;
        case "hauler-storing": // hauler-storing 1|0
            Engine.betweenSteps(() -> Rooms.haulerStoring(room("hauler"), on(a[1])));
            return true;
        case "import-allocate": // import-allocate <res>
            Engine.betweenSteps(() -> Rooms.importAllocate(room("import"), res(a[1])));
            return true;
        case "station-allocate": // station-allocate <res> <crates>
            Engine.betweenSteps(() -> Rooms.stationAllocate(room("station"), res(a[1]), Integer.parseInt(a[2])));
            return true;
        case "transport-fetching": // transport-fetching 1|0
            Engine.betweenSteps(() -> Rooms.transportFetching(room("transport"), on(a[1])));
            return true;
        case "transport-prio": // transport-prio 1|0: the panel's priority button
            Engine.betweenSteps(() -> Rooms.transportPrio(room("transport"), on(a[1])));
            return true;
        case "transport-resource": // transport-resource <res>: the cart's resource
            Engine.betweenSteps(() -> Rooms.transportResource(room("transport"), res(a[1])));
            return true;
        case "stockpile-fetching": // stockpile-fetching 1|0
            Engine.betweenSteps(() -> Rooms.stockpileFetching(room("stockpile"), on(a[1])));
            return true;
        case "stockpile-prio": // stockpile-prio: toggles
            Engine.betweenSteps(() -> ((StockpileInstance) room("stockpile")).prioritizeToggle());
            return true;
        case "stockpile-storing": // stockpile-storing 1|0
            Engine.betweenSteps(() -> Rooms.stockpileStoring(room("stockpile"), on(a[1])));
            return true;
        case "stockpile-crates": // stockpile-crates <res> <crates>
            Engine.betweenSteps(() -> Rooms.stockpileCrates(room("stockpile"), res(a[1]), Integer.parseInt(a[2])));
            return true;
        case "stockpile-special": // stockpile-special <res> <limit>
            Engine.betweenSteps(() -> Rooms.stockpileSpecial(room("stockpile"), res(a[1]), Integer.parseInt(a[2])));
            return true;
        case "supply-fetching": // supply-fetching: toggles
            Engine.betweenSteps(() -> Rooms.supplyFetching(room("supply")));
            return true;
        case "supply-prio": // supply-prio: toggles
            Engine.betweenSteps(() -> Rooms.supplyPrio(room("supply")));
            return true;
        case "supply-allowed": // supply-allowed <res>: toggles
            Engine.betweenSteps(() -> Rooms.supplyAllowed(room("supply"), res(a[1])));
            return true;
        case "canteen-uses": // canteen-uses <res>: toggles a food
            Engine.betweenSteps(() -> Rooms.canteenUses(room("canteen"), res(a[1])));
            return true;
        case "distribution-uses": // distribution-uses eatery|tavern|market <res>: toggles
            Engine.betweenSteps(() -> {
                RoomInstance r = room(a[1]);
                ((RoomDistributionIns) r).distributionNlueData().usesToggle(res(a[2]), r);
            });
            return true;
        case "punish-race": // punish-race <race>: the cannibal room's races
            Engine.betweenSteps(() -> {
                PUNISHMENT_SERVICE s = SETT.ROOMS().CANNIBAL;
                Race race = RACES.all().get(Integer.parseInt(a[1]));
                if (!Rooms.punishRace(s, race)) {
                    s.punishEnabled().toggle(race);
                }
            });
            return true;
        case "prisoner-punish": // prisoner-punish <punishment|-1>: the first prisoner's punishment, or cancelled
            Engine.betweenSteps(() -> {
                int pi = Integer.parseInt(a[1]);
                PUNISHMENT p = pi < 0 ? null : CRIME_PUNISHMENTS.ALL().get(pi);
                Humanoid h = prisoner();
                if (!Rooms.prisonerPunish(h, p)) {
                    AIModule_Prisoner.DATA().punishmentSet.set(h.ai(), p);
                    h.interrupt();
                }
            });
            return true;
        case "prison-fetch": // prison-fetch prison|stockade <res>: toggles a food
            Engine.betweenSteps(() -> Rooms.prisonFetch(room(a[1]), res(a[2])));
            return true;
        case "guard-duty": // guard-duty <division index in the player's army> 1|0
            Engine.betweenSteps(() -> SETT.ROOMS().GUARD.activeDuty
                    .set(GAME.ARMIES().player().divisions().get(Integer.parseInt(a[1])), on(a[2])));
            return true;
        case "gate-lock": // gate-lock 1|0: a click on the first gate tile
            Engine.betweenSteps(() -> {
                for (int ty = 0; ty < SETT.THEIGHT; ty++) {
                    for (int tx = 0; tx < SETT.TWIDTH; tx++) {
                        Room r = SETT.ROOMS().map.get(tx, ty);
                        if (r != null && r.blueprint() instanceof ROOM_GATE
                                && SETT.ROOMS().fData.item.get(tx, ty) != null) {
                            ((ROOM_GATE) r.blueprint()).lock(tx, ty, on(a[1]));
                            return;
                        }
                    }
                }
                throw new IllegalStateException("SOSDET rig: no gate in this save");
            });
            return true;
        case "hospital-fetch": // hospital-fetch <k> 1|0
            Engine.betweenSteps(() -> Rooms.hospitalFetch(room("hospital"), Integer.parseInt(a[1]), on(a[2])));
            return true;
        case "breeder-prosecute": // breeder-prosecute 1|0
            Engine.betweenSteps(() -> Rooms.breederProsecute(SETT.ROOMS().BREEDERS.get(0), on(a[1])));
            return true;
        case "breeder-limit-total": // breeder-limit-total <n>
            Engine.betweenSteps(() -> Rooms.breederLimitTotal(SETT.ROOMS().BREEDERS.get(0), Integer.parseInt(a[1])));
            return true;
        case "breeder-limit-species": // breeder-limit-species <n>
            Engine.betweenSteps(() -> Rooms.breederLimitSpecies(SETT.ROOMS().BREEDERS.get(0),
                    Integer.parseInt(a[1])));
            return true;
        case "trainer-limit": // trainer-limit <n>: the first barracks type's recruits limit
            Engine.betweenSteps(() -> Rooms.trainerLimit(SETT.ROOMS().BARRACKS.get(0), Integer.parseInt(a[1])));
            return true;
        case "move-new": // move-new <kind> <slot> <source kind>: the "closest source" button's new order
            Engine.betweenSteps(() -> Rooms.moveOrderNew(room(a[1]), Integer.parseInt(a[2]), room(a[3])));
            return true;
        case "move-set": // move-set <kind> <slot> <source kind>: the source picker
            Engine.betweenSteps(() -> Rooms.moveOrderSet(room(a[1]), Integer.parseInt(a[2]), room(a[3])));
            return true;
        case "move-res": // move-res <kind> <slot> all|none|<res>: the order's resource buttons
            Engine.betweenSteps(() -> {
                int op = a[3].equals("all") ? Rooms.RES_ALL : a[3].equals("none") ? Rooms.RES_NONE : Rooms.RES_TOGGLE;
                Rooms.moveOrderRes(room(a[1]), Integer.parseInt(a[2]), op, op == Rooms.RES_TOGGLE ? res(a[3]) : null);
            });
            return true;
        case "move-limit": // move-limit <kind> <slot> <0-100>: the pull limit slider
            Engine.betweenSteps(() -> Rooms.moveOrderLimit(room(a[1]), Integer.parseInt(a[2]),
                    Integer.parseInt(a[3])));
            return true;
        case "move-remove": // move-remove <kind> <slot>
            Engine.betweenSteps(() -> Rooms.moveOrderRemove(room(a[1]), Integer.parseInt(a[2])));
            return true;
        case "move-paste": // move-paste <kind>: "paste settings" from the first room of the kind onto the second
            Engine.betweenSteps(() -> {
                RoomInstance from = room(a[1]);
                RoomInstance to = from.blueprintI().getInstance(1);
                Rooms.movePaste(from, to.mX(), to.mY());
            });
            return true;
        default:
            return false;
        }
    }

    private static boolean on(String s) {
        return s.equals("1");
    }

    private static RESOURCE res(String s) {
        int i = Integer.parseInt(s);
        return i < 0 ? null : RESOURCES.ALL().get(i);
    }

    /** The first prisoner, by entity index. */
    private static Humanoid prisoner() {
        for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
            if (e instanceof Humanoid && ((Humanoid) e).indu().hType() == HTYPES.PRISONER()) {
                return (Humanoid) e;
            }
        }
        throw new IllegalStateException("SOSDET rig: no prisoner in this save");
    }

    /** The first room of the first blueprint of this kind that has one. */
    private static RoomInstance room(String kind) {
        for (RoomBlueprint b : blueprints(kind)) {
            if (b instanceof RoomBlueprintIns<?> && ((RoomBlueprintIns<?>) b).instancesSize() > 0) {
                return ((RoomBlueprintIns<?>) b).getInstance(0);
            }
        }
        throw new IllegalStateException("SOSDET rig: no " + kind + " room in this save");
    }

    private static List<RoomBlueprint> blueprints(String kind) {
        List<RoomBlueprint> l = new ArrayList<>();
        switch (kind) {
        case "stockpile": l.add(SETT.ROOMS().STOCKPILE); break;
        case "export": l.add(SETT.ROOMS().EXPORT); break;
        case "import": l.add(SETT.ROOMS().IMPORT); break;
        case "supply": l.add(SETT.ROOMS().SUPPLY); break;
        case "hauler": l.add(SETT.ROOMS().HAULER); break;
        case "transport": l.add(SETT.ROOMS().TRANSPORT); break;
        case "station": l.add(SETT.ROOMS().STATION); break;
        case "hospital": l.add(SETT.ROOMS().HOSPITAL); break;
        case "prison": l.add(SETT.ROOMS().PRISON); break;
        case "stockade": l.add(SETT.ROOMS().STOCKADE); break;
        case "builder": l.add(SETT.ROOMS().BUILDER); break;
        case "farm": for (RoomBlueprint b : SETT.ROOMS().FARMS) l.add(b); break;
        case "pasture": for (RoomBlueprint b : SETT.ROOMS().PASTURES) l.add(b); break;
        case "hunter": for (RoomBlueprint b : SETT.ROOMS().HUNTERS) l.add(b); break;
        case "refiner": for (RoomBlueprint b : SETT.ROOMS().REFINERS) l.add(b); break;
        case "workshop": for (RoomBlueprint b : SETT.ROOMS().WORKSHOPS) l.add(b); break;
        case "canteen": for (RoomBlueprint b : SETT.ROOMS().CANTEENS) l.add(b); break;
        case "eatery": for (RoomBlueprint b : SETT.ROOMS().EATERIES) l.add(b); break;
        case "tavern": for (RoomBlueprint b : SETT.ROOMS().TAVERNS) l.add(b); break;
        case "market": for (RoomBlueprint b : SETT.ROOMS().MARKET) l.add(b); break;
        case "library": for (RoomBlueprint b : SETT.ROOMS().LIBRARIES) l.add(b); break;
        default:
            throw new IllegalArgumentException("SOSDET rig: no room kind " + kind);
        }
        return l;
    }
}

package sosdet.game.cmd;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import game.battle.div.Div;
import game.faction.FACTIONS;
import game.faction.FResources.RTYPE;
import init.race.Race;
import init.resources.RBIT;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import init.resources.ResG;
import init.type.CRIME_PUNISHMENTS;
import init.type.CRIME_PUNISHMENTS.PUNISHMENT;
import init.type.HTYPES;
import settlement.entity.humanoid.Humanoid;
import settlement.entity.humanoid.ai.types.prisoner.AIModule_Prisoner;
import settlement.main.SETT;
import settlement.misc.job.JOBMANAGER_HASER;
import settlement.room.food.farm.ROOM_FARM;
import settlement.room.food.pasture.PastureInstance;
import settlement.room.industry.module.INDUSTRY_HASER;
import settlement.room.industry.module.IndustryResource;
import settlement.room.industry.module.ROOM_IDATA_INSTANCE;
import settlement.room.industry.module.ROOM_PRODUCER_INSTANCE;
import settlement.room.industry.module.consumption.RoomConsumption;
import settlement.room.industry.module.consumption.RoomConsumption.ROOM_CONSUMPTION_HASER;
import settlement.room.infra.export.ExportInstance;
import settlement.room.infra.gate.ROOM_GATE;
import settlement.room.infra.importt.ImportInstance;
import settlement.room.infra.logistics.MoveOrderPull;
import settlement.room.infra.logistics.MoveOrderPull.MoveOrderPullInstance;
import settlement.room.infra.stockpile.StockpileInstance;
import settlement.room.law.PUNISHMENT_SERVICE;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomBlueprintIns;
import settlement.room.main.RoomInstance;
import settlement.room.main.TmpArea;
import settlement.room.main.furnisher.Furnisher;
import settlement.room.main.job.JobPositions;
import settlement.room.main.job.ROOM_RADIUS.ROOM_RADIUSE;
import settlement.room.military.training.ROOM_M_TRAINER;
import settlement.room.service.breeder.ROOM_BREEDER;
import settlement.room.service.food.eatery.RoomDistribution.RoomDistributionIns;
import snake2d.util.datatypes.COORDINATE;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;

/**
 * Room instances: their settings, upgrades and industries (ids 2000-2999).
 *
 * <p>Many room settings live in package-private classes, methods or fields of the game. The appliers reach those
 * through the reflective handles below, one per member, resolved when this class loads (AGENTS.md: reflection before
 * a change to the game's code). Resolving them only loads the game classes; none is initialized or touched.
 */
public final class Rooms {

    // ---------------------------------------------------------------- ids (never reused)

    static final int ACTIVATE = 2000;
    static final int REMOVE = 2001;
    static final int UPGRADE = 2002;
    static final int DOWNGRADE = 2003;
    static final int RADIUS = 2004;
    static final int INDUSTRY = 2005;
    static final int CONSUMPTION = 2006;
    static final int FARM_CHANGE = 2007;
    static final int SLAUGHTER_ALL = 2008;
    static final int EXPORT_RESOURCE = 2009;
    static final int EXPORT_FETCHING = 2010;
    static final int EXPORT_PRIO = 2011;
    static final int HAULER_RESOURCE = 2012;
    static final int HAULER_FETCHING = 2013;
    static final int HAULER_PRIO = 2014;
    static final int HAULER_STORING = 2015;
    static final int IMPORT_ALLOCATE = 2016;
    static final int STATION_ALLOCATE = 2017;
    static final int TRANSPORT_FETCHING = 2018;
    static final int TRANSPORT_PRIO = 2019;
    static final int TRANSPORT_RESOURCE = 2020;
    static final int STOCKPILE_FETCHING = 2021;
    static final int STOCKPILE_PRIO = 2022;
    static final int STOCKPILE_STORING = 2023;
    static final int STOCKPILE_CRATES = 2024;
    static final int STOCKPILE_SPECIAL = 2025;
    static final int SUPPLY_FETCHING = 2026;
    static final int SUPPLY_PRIO = 2027;
    static final int SUPPLY_ALLOWED = 2028;
    static final int CANTEEN_USES = 2029;
    static final int DISTRIBUTION_USES = 2030;
    static final int PUNISH_RACE = 2031;
    static final int PRISONER_PUNISH = 2032;
    static final int PRISON_FETCH = 2033;
    static final int GUARD_DUTY = 2034;
    static final int GATE_LOCK = 2035;
    static final int HOSPITAL_FETCH = 2036;
    static final int BREEDER_PROSECUTE = 2037;
    static final int BREEDER_LIMIT_TOTAL = 2038;
    static final int BREEDER_LIMIT_SPECIES = 2039;
    static final int TRAINER_LIMIT = 2040;
    static final int MOVE_ORDER_NEW = 2041;
    static final int MOVE_ORDER_SET = 2042;
    static final int MOVE_ORDER_REMOVE = 2043;
    static final int MOVE_ORDER_RES = 2044;
    static final int MOVE_ORDER_LIMIT = 2045;
    static final int MOVE_PASTE = 2046;

    /** {@link #moveOrderRes} operations, as the pull order panel's buttons: all, none, one toggled. */
    public static final int RES_ALL = 0;
    public static final int RES_NONE = 1;
    public static final int RES_TOGGLE = 2;

    // ---------------------------------------------------------------- reflective handles (one per member)

    private static final String V = "settlement.room.";
    private static final Method FARM_CHANGE_TO = method(type(V + "food.farm.FarmInstance"), "changeTo", ROOM_FARM.class);
    private static final Method PASTURE_SLAUGHTER = method(PastureInstance.class, "slaughterAll");
    private static final Method EXPORT_RESOURCE_SET = method(ExportInstance.class, "resourceSet", RESOURCE.class);
    private static final Method EXPORT_PRIO_SET = method(ExportInstance.class, "prioSet");
    private static final Class<?> HAULER = type(V + "infra.hauler.HaulerInstance");
    private static final Method HAULER_SET_RESOURCE = method(HAULER, "setResource", RESOURCE.class);
    private static final Method HAULER_FETCHING_SET = method(HAULER, "fetchingSet", boolean.class);
    private static final Method HAULER_PRIO_SET = method(HAULER, "prioSet");
    private static final Method HAULER_STORING_SET = method(HAULER, "storingSet", boolean.class);
    private static final Method IMPORT_ALLOCATE_RES = method(ImportInstance.class, "allocate", RESOURCE.class);
    private static final Method STATION_ALLOCATE_RES = method(type(V + "infra.station.StationInstance"), "allocate",
            RESOURCE.class, int.class);
    private static final Class<?> TRANSPORT = type(V + "infra.transport.TransportInstance");
    private static final Method TRANSPORT_FETCHING_SET = method(TRANSPORT, "fetchingSet", boolean.class);
    private static final Field TRANSPORT_PRIO_FIELD = field(TRANSPORT, "prio");
    private static final Field TRANSPORT_CART = field(TRANSPORT, "data");
    private static final Method CART_RESOURCE_SET = method(type(V + "infra.transport.Cart"), "resourceSet",
            RESOURCE.class, TRANSPORT);
    private static final Method STOCKPILE_ALLOCATE_CRATE = method(StockpileInstance.class, "allocateCrate",
            RESOURCE.class, int.class);
    private static final Method STOCKPILE_SET_SPECIAL = method(StockpileInstance.class, "setSpecialAmount",
            RESOURCE.class, int.class);
    private static final Method STOCKPILE_STORING_SET = method(StockpileInstance.class, "storingSet", boolean.class);
    private static final Method STOCKPILE_FETCHING_SET = method(StockpileInstance.class, "fetchingSet", boolean.class);
    private static final Class<?> SUPPLY = type(V + "military.supply.SupplyInstance");
    private static final Method SUPPLY_FETCHING_TOGGLE = method(SUPPLY, "fetchingToggle");
    private static final Method SUPPLY_PRIO_TOGGLE = method(SUPPLY, "prioritizeToggle");
    private static final Method SUPPLY_ALLOWED_TOGGLE = method(SUPPLY, "allowedToggle", RESOURCE.class);
    private static final Method SUPPLY_RESET = method(SUPPLY, "reset");
    private static final Method CANTEEN_USES_TOGGLE = method(type(V + "service.food.canteen.CanteenInstance"),
            "usesToggle", ResG.class);
    private static final Field HOSPITAL_FETCH_FIELD = field(type(V + "health.hospital.HospitalInstance"), "fetch");
    private static final Field BREEDER_PROSECUTE_FIELD = field(ROOM_BREEDER.class, "prosecute");
    private static final Field BREEDER_LIMIT_TOTAL_FIELD = field(ROOM_BREEDER.class, "limitTotal");
    private static final Field BREEDER_LIMIT_SPECIES_FIELD = field(ROOM_BREEDER.class, "limitSpecies");
    private static final Field TRAINER_LIMIT_FIELD = field(ROOM_M_TRAINER.class, "trainingLimit");
    private static final Field PRISON_FETCH_FIELD = field(type(V + "law.prison.PrisonInstance"), "fetch");
    private static final Field STOCKADE_FETCH_FIELD = field(type(V + "law.stockade.StockInstance"), "fetch");

    // ---------------------------------------------------------------- the commands

    static final List<Command> ALL = List.of(
            new Command(ACTIVATE, "roomActivate", r -> {
                RoomInstance ins = Args.room(r);
                boolean a = r.b();
                if (ins == null) {
                    return false;
                }
                ins.activate(a);
                return true;
            }),
            new Command(REMOVE, "roomRemove", r -> {
                RoomInstance ins = Args.room(r);
                int tx = r.i();
                int ty = r.i();
                boolean scatter = r.b();
                boolean forced = r.b();
                if (ins == null || !ins.is(tx, ty)) {
                    return false;
                }
                TmpArea a = ins.remove(tx, ty, scatter, ins, forced);
                if (a != null) {
                    a.clear();
                }
                return true;
            }),
            new Command(UPGRADE, "roomUpgrade", r -> {
                RoomInstance ins = Args.room(r);
                boolean all = r.b();
                if (ins == null) {
                    return false;
                }
                applyUpgrade(ins, all);
                return true;
            }),
            new Command(DOWNGRADE, "roomDowngrade", r -> {
                RoomInstance ins = Args.room(r);
                if (ins == null) {
                    return false;
                }
                applyDowngrade(ins);
                return true;
            }),
            new Command(RADIUS, "roomRadius", r -> {
                RoomInstance ins = Args.room(r);
                int radius = r.i();
                if (ins == null || !(ins.blueprintI() instanceof ROOM_RADIUSE)) {
                    return false;
                }
                ((ROOM_RADIUSE) ins.blueprintI()).radiusInstance(ins).radiusRawSet((byte) radius);
                return true;
            }),
            new Command(INDUSTRY, "roomIndustry", r -> {
                RoomInstance ins = Args.room(r);
                int k = r.i();
                if (!(ins instanceof ROOM_PRODUCER_INSTANCE)) {
                    return false;
                }
                if (ins.blueprintI() instanceof INDUSTRY_HASER
                        && !((INDUSTRY_HASER) ins.blueprintI()).industries().get(k).lockable().passes(FACTIONS.player())) {
                    return false;
                }
                ((ROOM_PRODUCER_INSTANCE) ins).setIndustry(k);
                if (ins instanceof JOBMANAGER_HASER) {
                    ((JOBMANAGER_HASER) ins).getWork().resetResourceSearch();
                }
                return true;
            }),
            new Command(CONSUMPTION, "roomConsumption", r -> {
                RoomInstance ins = Args.room(r);
                int k = r.i();
                if (ins == null || !(ins.blueprintI() instanceof ROOM_CONSUMPTION_HASER)) {
                    return false;
                }
                RoomConsumption c = ((ROOM_CONSUMPTION_HASER) ins.blueprintI()).consumption();
                c.enabledToggle(c.ins().get(k), (ROOM_IDATA_INSTANCE) ins, ins);
                return true;
            }),
            new Command(FARM_CHANGE, "farmChange", r -> {
                RoomInstance ins = room(r, FARM_CHANGE_TO.getDeclaringClass());
                RoomBlueprint to = Args.blueprint(r);
                if (ins == null || !(to instanceof ROOM_FARM) || to == ins.blueprintI()) {
                    return false;
                }
                invoke(FARM_CHANGE_TO, ins, to);
                return true;
            }),
            new Command(SLAUGHTER_ALL, "pastureSlaughterAll", r -> call(r, PASTURE_SLAUGHTER)),
            new Command(EXPORT_RESOURCE, "exportResource", r -> callRes(r, EXPORT_RESOURCE_SET)),
            new Command(EXPORT_FETCHING, "exportFetching", r -> {
                RoomInstance ins = room(r, ExportInstance.class);
                boolean f = r.b();
                if (ins == null) {
                    return false;
                }
                ((ExportInstance) ins).fetchingSet(f);
                return true;
            }),
            new Command(EXPORT_PRIO, "exportPrio", r -> call(r, EXPORT_PRIO_SET)),
            new Command(HAULER_RESOURCE, "haulerResource", r -> callRes(r, HAULER_SET_RESOURCE)),
            new Command(HAULER_FETCHING, "haulerFetching", r -> callBool(r, HAULER_FETCHING_SET)),
            new Command(HAULER_PRIO, "haulerPrio", r -> call(r, HAULER_PRIO_SET)),
            new Command(HAULER_STORING, "haulerStoring", r -> callBool(r, HAULER_STORING_SET)),
            new Command(IMPORT_ALLOCATE, "importAllocate", r -> callRes(r, IMPORT_ALLOCATE_RES)),
            new Command(STATION_ALLOCATE, "stationAllocate", r -> callResInt(r, STATION_ALLOCATE_RES)),
            new Command(TRANSPORT_FETCHING, "transportFetching", r -> callBool(r, TRANSPORT_FETCHING_SET)),
            new Command(TRANSPORT_PRIO, "transportPrio", r -> {
                RoomInstance ins = room(r, TRANSPORT);
                boolean prio = r.b();
                if (ins == null) {
                    return false;
                }
                set(TRANSPORT_PRIO_FIELD, ins, prio);
                return true;
            }),
            new Command(TRANSPORT_RESOURCE, "transportResource", r -> {
                RoomInstance ins = room(r, TRANSPORT);
                RESOURCE res = Args.resource(r);
                if (ins == null) {
                    return false;
                }
                invoke(CART_RESOURCE_SET, get(TRANSPORT_CART, ins), res, ins);
                return true;
            }),
            new Command(STOCKPILE_FETCHING, "stockpileFetching", r -> callBool(r, STOCKPILE_FETCHING_SET)),
            new Command(STOCKPILE_PRIO, "stockpilePrio", r -> {
                RoomInstance ins = room(r, StockpileInstance.class);
                if (ins == null) {
                    return false;
                }
                ((StockpileInstance) ins).prioritizeToggle();
                return true;
            }),
            new Command(STOCKPILE_STORING, "stockpileStoring", r -> callBool(r, STOCKPILE_STORING_SET)),
            new Command(STOCKPILE_CRATES, "stockpileCrates", r -> callResInt(r, STOCKPILE_ALLOCATE_CRATE)),
            new Command(STOCKPILE_SPECIAL, "stockpileSpecial", r -> callResInt(r, STOCKPILE_SET_SPECIAL)),
            new Command(SUPPLY_FETCHING, "supplyFetching", r -> call(r, SUPPLY_FETCHING_TOGGLE)),
            new Command(SUPPLY_PRIO, "supplyPrio", r -> call(r, SUPPLY_PRIO_TOGGLE)),
            new Command(SUPPLY_ALLOWED, "supplyAllowed", r -> {
                RoomInstance ins = room(r, SUPPLY);
                RESOURCE res = Args.resource(r);
                if (ins == null || res == null) {
                    return false;
                }
                invoke(SUPPLY_ALLOWED_TOGGLE, ins, res);
                invoke(SUPPLY_RESET, ins);
                return true;
            }),
            new Command(CANTEEN_USES, "canteenUses", r -> {
                RoomInstance ins = room(r, CANTEEN_USES_TOGGLE.getDeclaringClass());
                RESOURCE res = Args.resource(r);
                if (ins == null || res == null || RESOURCES.EDI().get(res) == null) {
                    return false;
                }
                invoke(CANTEEN_USES_TOGGLE, ins, RESOURCES.EDI().get(res));
                return true;
            }),
            new Command(DISTRIBUTION_USES, "distributionUses", r -> {
                RoomInstance ins = Args.room(r);
                RESOURCE res = Args.resource(r);
                // only a resource the room serves: the game indexes its own list by it
                if (!(ins instanceof RoomDistributionIns) || res == null
                        || !((RoomDistributionIns) ins).distributionNlueData().all.contains(res)) {
                    return false;
                }
                ((RoomDistributionIns) ins).distributionNlueData().usesToggle(res, ins);
                return true;
            }),
            new Command(PUNISH_RACE, "punishRace", r -> {
                RoomBlueprint b = Args.blueprint(r);
                Race race = Args.race(r);
                if (!(b instanceof PUNISHMENT_SERVICE) || ((PUNISHMENT_SERVICE) b).punishEnabled() == null
                        || race == null) {
                    return false;
                }
                ((PUNISHMENT_SERVICE) b).punishEnabled().toggle(race);
                return true;
            }),
            new Command(PRISONER_PUNISH, "prisonerPunish", r -> {
                Humanoid h = Args.humanoid(r);
                int pi = r.i();
                if (h == null || h.indu().hType() != HTYPES.PRISONER()) {
                    return false;
                }
                PUNISHMENT p = pi < 0 ? null : CRIME_PUNISHMENTS.ALL().get(pi);
                if (p == null ? AIModule_Prisoner.DATA().punishmentSet.get(h.ai()) == null
                        : !p.available(AIModule_Prisoner.DATA().clas(h.indu()))) {
                    return true; // as the panel: nothing to cancel, or a punishment this prisoner's class can't get
                }
                AIModule_Prisoner.DATA().punishmentSet.set(h.ai(), p);
                h.interrupt();
                return true;
            }),
            new Command(PRISON_FETCH, "prisonFetch", r -> {
                RoomInstance ins = Args.room(r);
                RESOURCE res = Args.resource(r);
                Field fetch = PRISON_FETCH_FIELD.getDeclaringClass().isInstance(ins) ? PRISON_FETCH_FIELD
                        : STOCKADE_FETCH_FIELD.getDeclaringClass().isInstance(ins) ? STOCKADE_FETCH_FIELD : null;
                if (fetch == null || res == null) {
                    return false;
                }
                ((RBIT.RBITImp) get(fetch, ins)).toggle(res);
                ((JobPositions<?>) ((JOBMANAGER_HASER) ins).getWork()).resNotFound.clear();
                return true;
            }),
            new Command(GUARD_DUTY, "guardDuty", r -> {
                Div d = Args.div(r);
                boolean on = r.b();
                if (d == null) {
                    return false;
                }
                SETT.ROOMS().GUARD.activeDuty.set(d, on);
                return true;
            }),
            new Command(GATE_LOCK, "gateLock", r -> {
                RoomBlueprint b = Args.blueprint(r);
                int tx = r.i();
                int ty = r.i();
                boolean lock = r.b();
                if (!(b instanceof ROOM_GATE)) {
                    return false;
                }
                ((ROOM_GATE) b).lock(tx, ty, lock);
                return true;
            }),
            new Command(HOSPITAL_FETCH, "hospitalFetch", r -> {
                RoomInstance ins = room(r, HOSPITAL_FETCH_FIELD.getDeclaringClass());
                int k = r.i();
                boolean on = r.b();
                if (ins == null) {
                    return false;
                }
                boolean[] fetch = (boolean[]) get(HOSPITAL_FETCH_FIELD, ins);
                if (fetch == null || k < 0 || k >= fetch.length) {
                    return false;
                }
                fetch[k] = on;
                return true;
            }),
            new Command(BREEDER_PROSECUTE, "breederProsecute", r -> setBlueprintField(r, BREEDER_PROSECUTE_FIELD,
                    ROOM_BREEDER.class, true)),
            new Command(BREEDER_LIMIT_TOTAL, "breederLimitTotal", r -> setBlueprintField(r, BREEDER_LIMIT_TOTAL_FIELD,
                    ROOM_BREEDER.class, false)),
            new Command(BREEDER_LIMIT_SPECIES, "breederLimitSpecies", r -> setBlueprintField(r,
                    BREEDER_LIMIT_SPECIES_FIELD, ROOM_BREEDER.class, false)),
            new Command(TRAINER_LIMIT, "trainerLimit", r -> setBlueprintField(r, TRAINER_LIMIT_FIELD,
                    ROOM_M_TRAINER.class, false)),
            new Command(MOVE_ORDER_NEW, "moveOrderNew", r -> {
                RoomInstance ins = Args.room(r);
                int slot = r.i();
                RoomInstance src = Args.room(r);
                if (!hasSlot(ins, slot) || src == null) {
                    return false;
                }
                MoveOrderPullInstance m = (MoveOrderPullInstance) ins;
                m.moveOrdersPull()[slot] = new MoveOrderPull(src, m.moveOrderPullAccepted());
                m.moveOrdersPull()[slot].pullLimit = 0;
                return true;
            }),
            new Command(MOVE_ORDER_SET, "moveOrderSet", r -> {
                RoomInstance ins = Args.room(r);
                int slot = r.i();
                RoomInstance src = Args.room(r);
                if (!hasSlot(ins, slot) || src == null) {
                    return false;
                }
                MoveOrderPullInstance m = (MoveOrderPullInstance) ins;
                if (m.moveOrdersPull()[slot] == null) {
                    m.moveOrdersPull()[slot] = new MoveOrderPull(src, m.moveOrderPullAccepted());
                } else {
                    m.moveOrdersPull()[slot].destSet(src);
                }
                return true;
            }),
            new Command(MOVE_ORDER_REMOVE, "moveOrderRemove", r -> {
                RoomInstance ins = Args.room(r);
                int slot = r.i();
                if (!hasSlot(ins, slot)) {
                    return false;
                }
                ((MoveOrderPullInstance) ins).moveOrdersPull()[slot] = null;
                return true;
            }),
            new Command(MOVE_ORDER_RES, "moveOrderRes", r -> {
                MoveOrderPull o = order(r);
                int op = r.i();
                RESOURCE res = Args.resource(r);
                if (o == null) {
                    return false;
                }
                switch (op) {
                case RES_ALL:
                    o.resbits.or(RBIT.ALL);
                    return true;
                case RES_NONE:
                    o.resbits.clear();
                    return true;
                case RES_TOGGLE:
                    if (res == null) {
                        return false;
                    }
                    o.resbits.toggle(res);
                    return true;
                default:
                    throw new IllegalStateException("SOSDET: pull order resource operation " + op);
                }
            }),
            new Command(MOVE_ORDER_LIMIT, "moveOrderLimit", r -> {
                MoveOrderPull o = order(r);
                int limit = r.i();
                if (o == null) {
                    return false;
                }
                o.pullLimit = (byte) limit;
                return true;
            }),
            new Command(MOVE_PASTE, "movePaste", r -> {
                RoomInstance from = Args.room(r);
                int tx = r.i();
                int ty = r.i();
                if (!(from instanceof MoveOrderPullInstance)) {
                    return false;
                }
                return applyMovePaste((MoveOrderPullInstance) from, tx, ty);
            }));

    private Rooms() {
    }

    // ---------------------------------------------------------------- hooks: room instances

    /** Hook in {@code RoomInstance.activate(boolean)}: the room panel's on/off button and the table's bulk switch. */
    public static boolean activate(RoomInstance ins, boolean a) {
        return roomBool(ACTIVATE, ins, a);
    }

    /**
     * Hook in {@code RoomInstance.remove}: the room panel's delete button (and any other removal outside the step).
     * The hook then returns the game's own "nothing removed" area; the applier removes the room at its step.
     */
    public static boolean remove(RoomInstance ins, int tx, int ty, boolean scatter, boolean forced) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(new CmdWriter(REMOVE), ins).i(tx).i(ty).b(scatter).b(forced));
        return true;
    }

    /**
     * Hook at the call sites in {@code ModuleUpgradable}: the upgrade button ({@code all} false) and the table's
     * "upgrade all" for one room ({@code all} true). The upgrade and the resources it costs are one command.
     */
    public static boolean upgrade(RoomInstance ins, boolean all) {
        return roomBool(UPGRADE, ins, all);
    }

    /** Hook in {@code ModuleUpgradable.downGrade}: one level down, and maintenance for the resources it no longer has. */
    public static boolean downgrade(RoomInstance ins) {
        return room(DOWNGRADE, ins);
    }

    /** Hook at the call site in {@code ModuleRadius}: the work radius slider (export, hauler, builder, ...). */
    public static boolean radius(RoomInstance ins, byte radius) {
        return roomInt(RADIUS, ins, radius);
    }

    /** Hook at the call site in {@code ModuleIndustry}: the recipe picker (refiner, workshop, hunter, pasture). */
    public static boolean industry(RoomInstance ins, int k) {
        return roomInt(INDUSTRY, ins, k);
    }

    /** Hook in {@code RoomConsumption.enabledToggle}: a resource the room may consume (admin, library, ...). */
    public static boolean consumption(IndustryResource res, RoomInstance ins) {
        return roomInt(CONSUMPTION, ins, res.index());
    }

    /** Hook in {@code FarmInstance.changeTo}: the farm panel's crop picker. */
    public static boolean farmChange(RoomInstance ins, ROOM_FARM to) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.blueprint(Args.room(new CmdWriter(FARM_CHANGE), ins), to));
        return true;
    }

    /** Hook in {@code PastureInstance.slaughterAll}: the pasture panel's "slaughter all". */
    public static boolean slaughterAll(RoomInstance ins) {
        return room(SLAUGHTER_ALL, ins);
    }

    // ---------------------------------------------------------------- hooks: logistics rooms

    /** Hook in {@code ExportInstance.resourceSet}. */
    public static boolean exportResource(RoomInstance ins, RESOURCE res) {
        return roomRes(EXPORT_RESOURCE, ins, res);
    }

    /** Hook in {@code ExportInstance.fetchingSet}. */
    public static boolean exportFetching(RoomInstance ins, boolean f) {
        return roomBool(EXPORT_FETCHING, ins, f);
    }

    /** Hook in {@code ExportInstance.prioSet} (a toggle). */
    public static boolean exportPrio(RoomInstance ins) {
        return room(EXPORT_PRIO, ins);
    }

    /** Hook in {@code HaulerInstance.setResource}. */
    public static boolean haulerResource(RoomInstance ins, RESOURCE res) {
        return roomRes(HAULER_RESOURCE, ins, res);
    }

    /** Hook in {@code HaulerInstance.fetchingSet}. */
    public static boolean haulerFetching(RoomInstance ins, boolean f) {
        return roomBool(HAULER_FETCHING, ins, f);
    }

    /** Hook in {@code HaulerInstance.prioSet} (a toggle). */
    public static boolean haulerPrio(RoomInstance ins) {
        return room(HAULER_PRIO, ins);
    }

    /** Hook in {@code HaulerInstance.storingSet}. */
    public static boolean haulerStoring(RoomInstance ins, boolean s) {
        return roomBool(HAULER_STORING, ins, s);
    }

    /** Hook in {@code ImportInstance.allocate}. */
    public static boolean importAllocate(RoomInstance ins, RESOURCE res) {
        return roomRes(IMPORT_ALLOCATE, ins, res);
    }

    /** Hook in {@code StationInstance.allocate}: a resource's crates. */
    public static boolean stationAllocate(RoomInstance ins, RESOURCE res, int am) {
        return roomResInt(STATION_ALLOCATE, ins, res, am);
    }

    /** Hook in {@code TransportInstance.fetchingSet}. */
    public static boolean transportFetching(RoomInstance ins, boolean f) {
        return roomBool(TRANSPORT_FETCHING, ins, f);
    }

    /** Hook in the transport panel ({@code transport.Gui}): its write of {@code TransportInstance.prio}. */
    public static boolean transportPrio(RoomInstance ins, boolean prio) {
        return roomBool(TRANSPORT_PRIO, ins, prio);
    }

    /** Hook in the transport panel at its call of {@code Cart.resourceSet} (Cart is menu-unsafe, ADR 0002). */
    public static boolean transportResource(RoomInstance ins, RESOURCE res) {
        return roomRes(TRANSPORT_RESOURCE, ins, res);
    }

    /** Hook in {@code StockpileInstance.fetchingSet}. */
    public static boolean stockpileFetching(RoomInstance ins, boolean f) {
        return roomBool(STOCKPILE_FETCHING, ins, f);
    }

    /** Hook in {@code StockpileInstance.prioritizeToggle}. */
    public static boolean stockpilePrio(RoomInstance ins) {
        return room(STOCKPILE_PRIO, ins);
    }

    /** Hook in {@code StockpileInstance.storingSet}. */
    public static boolean stockpileStoring(RoomInstance ins, boolean s) {
        return roomBool(STOCKPILE_STORING, ins, s);
    }

    /** Hook in {@code StockpileInstance.allocateCrate}: a resource's crates. */
    public static boolean stockpileCrates(RoomInstance ins, RESOURCE res, int amount) {
        return roomResInt(STOCKPILE_CRATES, ins, res, amount);
    }

    /** Hook in {@code StockpileInstance.setSpecialAmount}: a resource's limit. */
    public static boolean stockpileSpecial(RoomInstance ins, RESOURCE res, int amount) {
        return roomResInt(STOCKPILE_SPECIAL, ins, res, amount);
    }

    /** Hook in {@code SupplyInstance.fetchingToggle}. */
    public static boolean supplyFetching(RoomInstance ins) {
        return room(SUPPLY_FETCHING, ins);
    }

    /** Hook in {@code SupplyInstance.prioritizeToggle}. */
    public static boolean supplyPrio(RoomInstance ins) {
        return room(SUPPLY_PRIO, ins);
    }

    /**
     * Hook at the supply panel's two call sites of {@code allowedToggle} and {@code reset}: one resource allowed or
     * not, for one room. Not in {@code reset} itself, which the supply tally also calls.
     */
    public static boolean supplyAllowed(RoomInstance ins, RESOURCE res) {
        return roomRes(SUPPLY_ALLOWED, ins, res);
    }

    // ---------------------------------------------------------------- hooks: services, law, health, military

    /** Hook in {@code CanteenInstance.usesToggle}: a food the canteen serves. */
    public static boolean canteenUses(RoomInstance ins, RESOURCE res) {
        return roomRes(CANTEEN_USES, ins, res);
    }

    /** Hook in {@code RoomDistribution.usesToggle}: a resource an eatery, tavern or market hands out. */
    public static boolean distributionUses(RESOURCE res, RoomInstance ins) {
        return roomRes(DISTRIBUTION_USES, ins, res);
    }

    /** Hook at the call site in {@code ModulePunish}: whether a race's prisoners may be punished in this room type. */
    public static boolean punishRace(PUNISHMENT_SERVICE service, Race race) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.blueprint(new CmdWriter(PUNISH_RACE), (RoomBlueprint) service), race));
        return true;
    }

    /**
     * Hook at the prison and stockade panels' call sites: a prisoner's punishment set (or cancelled with null), and
     * the prisoner interrupted to act on it.
     */
    public static boolean prisonerPunish(Humanoid h, PUNISHMENT p) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.entity(new CmdWriter(PRISONER_PUNISH), h).i(p == null ? -1 : p.index()));
        return true;
    }

    /** Hook at the prison and stockade panels' food buttons: a food fetched for the prisoners. */
    public static boolean prisonFetch(RoomInstance ins, RESOURCE res) {
        return roomRes(PRISON_FETCH, ins, res);
    }

    /** Hook in {@code ROOM_GUARD.activeDuty.set}: a division on guard duty (army screen, guard panel). */
    public static boolean guardDuty(Div d, boolean on) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.div(new CmdWriter(GUARD_DUTY), d).b(on));
        return true;
    }

    /** Hook in {@code ROOM_GATE.lock}: a click on a gate. */
    public static boolean gateLock(ROOM_GATE gate, int tx, int ty, boolean lock) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.blueprint(new CmdWriter(GATE_LOCK), gate).i(tx).i(ty).b(lock));
        return true;
    }

    /** Hook in the hospital panel ({@code hospital.Gui}): whether the room fetches medicine {@code k}. */
    public static boolean hospitalFetch(RoomInstance ins, int k, boolean on) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(new CmdWriter(HOSPITAL_FETCH), ins).i(k).b(on));
        return true;
    }

    /** Hook in the breeder panel: its write of {@code ROOM_BREEDER.prosecute}. */
    public static boolean breederProsecute(ROOM_BREEDER b, boolean on) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.blueprint(new CmdWriter(BREEDER_PROSECUTE), b).b(on));
        return true;
    }

    /** Hook in the breeder panel: its write of {@code ROOM_BREEDER.limitTotal}. */
    public static boolean breederLimitTotal(ROOM_BREEDER b, int limit) {
        return blueprintInt(BREEDER_LIMIT_TOTAL, b, limit);
    }

    /** Hook in the breeder panel: its write of {@code ROOM_BREEDER.limitSpecies}. */
    public static boolean breederLimitSpecies(ROOM_BREEDER b, int limit) {
        return blueprintInt(BREEDER_LIMIT_SPECIES, b, limit);
    }

    /** Hook in the training panel ({@code training.Gui}): its write of {@code ROOM_M_TRAINER.trainingLimit}. */
    public static boolean trainerLimit(ROOM_M_TRAINER<?> b, int limit) {
        return blueprintInt(TRAINER_LIMIT, b, limit);
    }

    // ---------------------------------------------------------------- hooks: pull orders (MoveOrderPullUI)

    /** Hook in {@code MoveOrderPullUI}'s "closest source" button: a new order in {@code slot}, limit 0. */
    public static boolean moveOrderNew(RoomInstance ins, int slot, RoomInstance src) {
        return moveOrder(MOVE_ORDER_NEW, ins, slot, src);
    }

    /** Hook in {@code MoveOrderPullUI}'s source pickers (map and list): the order's source, or a new order. */
    public static boolean moveOrderSet(RoomInstance ins, int slot, RoomInstance src) {
        return moveOrder(MOVE_ORDER_SET, ins, slot, src);
    }

    /** Hook in {@code MoveOrderPullUI}'s remove button. */
    public static boolean moveOrderRemove(RoomInstance ins, int slot) {
        return roomInt(MOVE_ORDER_REMOVE, ins, slot);
    }

    /** Hook in {@code MoveOrderPullUI}'s resource buttons: all ({@link #RES_ALL}), none, or one toggled. */
    public static boolean moveOrderRes(RoomInstance ins, int slot, int op, RESOURCE res) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.resource(Args.room(new CmdWriter(MOVE_ORDER_RES), ins).i(slot).i(op), res));
        return true;
    }

    /** Hook in {@code MoveOrderPullUI}'s pull limit slider: its write of {@code MoveOrderPull.pullLimit}. */
    public static boolean moveOrderLimit(RoomInstance ins, int slot, int limit) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(new CmdWriter(MOVE_ORDER_LIMIT), ins).i(slot).i(limit));
        return true;
    }

    /** Hook in {@code MoveOrderPullUI$Paster.place}: one tile of "paste settings" from room {@code from}. */
    public static boolean movePaste(RoomInstance from, int tx, int ty) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(new CmdWriter(MOVE_PASTE), from).i(tx).i(ty));
        return true;
    }

    // ---------------------------------------------------------------- capture helpers

    private static boolean room(int id, RoomInstance ins) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(new CmdWriter(id), ins));
        return true;
    }

    private static boolean roomBool(int id, RoomInstance ins, boolean v) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(new CmdWriter(id), ins).b(v));
        return true;
    }

    private static boolean roomInt(int id, RoomInstance ins, int v) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(new CmdWriter(id), ins).i(v));
        return true;
    }

    private static boolean roomRes(int id, RoomInstance ins, RESOURCE res) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.resource(Args.room(new CmdWriter(id), ins), res));
        return true;
    }

    private static boolean roomResInt(int id, RoomInstance ins, RESOURCE res, int v) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.resource(Args.room(new CmdWriter(id), ins), res).i(v));
        return true;
    }

    private static boolean blueprintInt(int id, RoomBlueprint b, int v) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.blueprint(new CmdWriter(id), b).i(v));
        return true;
    }

    private static boolean moveOrder(int id, RoomInstance ins, int slot, RoomInstance src) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(Args.room(new CmdWriter(id), ins).i(slot), src));
        return true;
    }

    // ---------------------------------------------------------------- appliers

    /** What {@code ModuleUpgradable}'s upgrade button, or its "upgrade all" for one room, does. */
    private static void applyUpgrade(RoomInstance ins, boolean all) {
        RoomBlueprintIns<?> b = ins.blueprintI();
        Furnisher f = b.constructor();
        int up = ins.upgrade();
        if (up >= b.upgrades().max()) {
            return;
        }
        if (all) { // "upgrade all" skips a room it can't afford or isn't allowed yet
            if (!b.upgrades().requires(up + 1).passes(FACTIONS.player())) {
                return;
            }
            for (int ri = 0; ri < f.resources(); ri++) {
                int am = ins.resAmount(ri, up + 1) - ins.resAmount(ri, up);
                if (SETT.ROOMS().STOCKPILE.tally().amountReservable.get(f.resource(ri)) < am) {
                    return;
                }
            }
        }
        for (int ri = 0; ri < f.resources(); ri++) {
            int am = ins.resAmount(ri, up + 1) - ins.resAmount(ri, up);
            if (all || am > 0) {
                f.resource(ri).remove(am, RTYPE.CONSTRUCTION);
            }
        }
        ins.upgradeSet(up + 1);
    }

    /** What {@code ModuleUpgradable.downGrade} does. */
    private static void applyDowngrade(RoomInstance ins) {
        if (ins.upgrade() <= 0) {
            return;
        }
        ins.upgradeSet(ins.upgrade() - 1);
        for (COORDINATE c : ins.body()) {
            if (ins.is(c) && SETT.MAINTENANCE().isser.is(c)) {
                RESOURCE res = SETT.MAINTENANCE().resource.get(c);
                if (res != null && !hasRes(ins, res)) {
                    SETT.MAINTENANCE().maintain(c.x(), c.y());
                }
            }
        }
    }

    private static boolean hasRes(RoomInstance ins, RESOURCE res) {
        Furnisher f = ins.blueprintI().constructor();
        for (int ri = 0; ri < f.resources(); ri++) {
            if (f.resource(ri) == res) {
                return f.resourceHas(ri, ins.upgrade());
            }
        }
        return false;
    }

    /** What {@code MoveOrderPullUI$Paster.place} does for one tile, after the tool's own placability check. */
    private static boolean applyMovePaste(MoveOrderPullInstance current, int tx, int ty) {
        Room r = SETT.ROOMS().map.get(tx, ty);
        if (!(r instanceof MoveOrderPullInstance) || r == current || r.blueprint() != ((Room) current).blueprint()) {
            return false;
        }
        MoveOrderPullInstance i = (MoveOrderPullInstance) r;
        i.copyFrom(current);
        if (r.mX(tx, ty) != tx && r.mY(tx, ty) != ty) {
            return true; // the game's own condition, kept as it is
        }
        for (int oi = 0; oi < current.moveOrdersPull().length && oi < i.moveOrdersPull().length; oi++) {
            i.moveOrdersPull()[oi] = null;
            MoveOrderPull o = current.moveOrdersPull()[oi];
            if (o != null && o.source() != null) {
                i.moveOrdersPull()[oi] = new MoveOrderPull(o.sourceI(), i.moveOrderPullAccepted());
                i.moveOrdersPull()[oi].pullLimit = o.pullLimit;
            }
        }
        return true;
    }

    private static boolean hasSlot(RoomInstance ins, int slot) {
        return ins instanceof MoveOrderPullInstance && slot >= 0
                && slot < ((MoveOrderPullInstance) ins).moveOrdersPull().length;
    }

    /** A pull order by its room and slot; null when the room or the order is gone. */
    private static MoveOrderPull order(CmdReader r) {
        RoomInstance ins = Args.room(r);
        int slot = r.i();
        return hasSlot(ins, slot) ? ((MoveOrderPullInstance) ins).moveOrdersPull()[slot] : null;
    }

    /** A room of the given class (often package-private in the game); null when it is gone. */
    private static RoomInstance room(CmdReader r, Class<?> type) {
        RoomInstance ins = Args.room(r);
        return type.isInstance(ins) ? ins : null;
    }

    private static boolean call(CmdReader r, Method m) {
        RoomInstance ins = room(r, m.getDeclaringClass());
        if (ins == null) {
            return false;
        }
        invoke(m, ins);
        return true;
    }

    private static boolean callBool(CmdReader r, Method m) {
        RoomInstance ins = room(r, m.getDeclaringClass());
        boolean v = r.b();
        if (ins == null) {
            return false;
        }
        invoke(m, ins, v);
        return true;
    }

    /** A setter taking a resource; null ("none") is passed on as the panel passes it. */
    private static boolean callRes(CmdReader r, Method m) {
        RoomInstance ins = room(r, m.getDeclaringClass());
        RESOURCE res = Args.resource(r);
        if (ins == null) {
            return false;
        }
        invoke(m, ins, res);
        return true;
    }

    private static boolean callResInt(CmdReader r, Method m) {
        RoomInstance ins = room(r, m.getDeclaringClass());
        RESOURCE res = Args.resource(r);
        int v = r.i();
        if (ins == null || res == null) {
            return false;
        }
        invoke(m, ins, res, v);
        return true;
    }

    private static boolean setBlueprintField(CmdReader r, Field f, Class<?> type, boolean bool) {
        RoomBlueprint b = Args.blueprint(r);
        Object v = bool ? (Object) r.b() : (Object) r.i();
        if (!type.isInstance(b)) {
            return false;
        }
        set(f, b, v);
        return true;
    }

    // ---------------------------------------------------------------- reflection (class load and UI-rate calls only)

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, Rooms.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SOSDET: no game class " + name + " (V71.44 expected)", e);
        }
    }

    private static Method method(Class<?> c, String name, Class<?>... params) {
        try {
            Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: no method " + c.getName() + "." + name + " (V71.44 expected)", e);
        }
    }

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("SOSDET: no field " + c.getName() + "." + name + " (V71.44 expected)", e);
        }
    }

    private static void invoke(Method m, Object target, Object... args) {
        try {
            m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("SOSDET: " + m + " failed", cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot call " + m, e);
        }
    }

    private static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + f, e);
        }
    }

    private static void set(Field f, Object target, Object value) {
        try {
            f.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot write " + f, e);
        }
    }
}

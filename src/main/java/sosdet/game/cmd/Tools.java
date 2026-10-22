package sosdet.game.cmd;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import init.type.HGROUP;
import init.type.WGROUP;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.job.JOBS;
import settlement.job.Job;
import settlement.job.JobBuildRoad;
import settlement.job.JobBuildStructure;
import settlement.job.JobClears;
import settlement.main.SETT;
import settlement.room.home.house.HomeInstance;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.RoomInstance;
import settlement.room.main.furnisher.FurnisherItemGroup;
import settlement.room.main.placement.RoomPlacer;
import settlement.room.main.util.RoomState;
import settlement.stats.STATS;
import settlement.tilemap.terrain.TBuilding;
import snake2d.util.datatypes.AREA;
import snake2d.util.datatypes.RECTANGLE;
import snake2d.util.datatypes.Rec;
import snake2d.util.sets.LIST;
import sosdet.Hooks;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import view.main.VIEW;
import view.sett.ui.bottom.RoomUpgrader;
import view.tool.PLACABLE;
import view.tool.PLACER_TYPE;
import view.tool.PlacableFixed;
import view.tool.PlacableMulti;

/**
 * Map tools: designations dragged over an area, single-item rooms, the throne, home and workplace assignment (ids
 * 6000-6999).
 *
 * <p>Each command is the tool's commit, never its preview: the hook sits where the click or the drag's release acts, and
 * carries every input the commit reads from the issuer's UI (the dragged area, the placer shape, overwrite and the
 * other tool options, rotation, size, furniture group, wall material, auto walls, the odd-jobber cursor). The applier
 * sets those inputs, runs the same loop the tool runs, and puts this PC's own values back.
 *
 * <p>An area room is planned on this PC only and lifted out of the simulation around its steps ({@link PlanStash}, ADR
 * 0003); its Build is one command with the whole plan ({@link #roomBuild}). Not here yet: refurnishing a room, the room
 * copier, saved prints and the mass copier (ADR 0003, later milestones).
 */
public final class Tools {

    static final int MULTI = 6000;
    static final int PLAN_MODE = 6001;
    static final int ROOM_ITEM = 6002;
    static final int THRONE = 6003;
    static final int HOME_ASSIGN = 6004;
    static final int WORK_GROUPS = 6005;
    static final int HOME_ODD = 6006;
    static final int ROOM_BUILD = 6007;

    /** Tool keys of {@link #MULTI} outside {@link #multiTools()}: tools the view owns. */
    private static final int UPGRADER = -1;
    private static final int ROADIFY = -2;
    private static final int UNROADIFY = -3;
    private static final int NOT_A_COMMAND = Integer.MIN_VALUE;

    /** The bottom bar's road matching tool and its undo: anonymous classes of V71.44's {@code Options}. */
    private static final String ROADIFY_CLASS = "view.sett.ui.bottom.Options$6";
    private static final String UNROADIFY_CLASS = "view.sett.ui.bottom.Options$5";
    private static final String HOME_ODD_CLASS = "view.sett.ui.home.UIHomeOdd";

    static final List<Command> ALL = List.of(
            new Command(MULTI, "toolArea", Tools::applyMulti),
            new Command(PLAN_MODE, "planMode", r -> {
                SETT.JOBS().planMode.set(r.b());
                return true;
            }),
            new Command(ROOM_ITEM, "roomItem", Tools::applyRoomItem),
            new Command(THRONE, "throneMove", Tools::applyThrone),
            new Command(HOME_ASSIGN, "homeAssign", Tools::applyHomeAssign),
            new Command(WORK_GROUPS, "workGroups", Tools::applyWorkGroups),
            new Command(HOME_ODD, "homeOddjobbers", Tools::applyHomeOdd),
            new Command(ROOM_BUILD, "roomBuild", Tools::applyRoomBuild));

    private Tools() {
    }

    // ------------------------------------------------------------------ hooks (this PC's player, outside the step)

    /**
     * Hook in {@code PlacableMultiTool.place}, the release of a drag with an area tool: job designations, dormant,
     * activate, repair, maintenance, diagonals, room dismantling, room upgrades, road matching. A tool it doesn't know
     * (the room placer's plan, the copier's selection, a tool another area hooks per tile) runs as before.
     */
    public static boolean multiPlace(PlacableMulti tool, PLACER_TYPE type, AREA area) {
        if (!Commands.capturing() || area.area() <= 0) {
            return false;
        }
        int key = multiKey(tool);
        if (key == NOT_A_COMMAND) {
            return false;
        }
        CmdWriter w = new CmdWriter(MULTI).i(key).i(typeIndex(type)).b(Job.overwrite)
                .b(get(roadConvert(), SETT.JOBS().roads)).i(getInt(foodFilter(), SETT.JOBS().clearss.food.placer()));
        RECTANGLE b = area.body();
        TileArea.write(w, b.x1(), b.y1(), b.width(), b.height(), area::is);
        Commands.capture(w);
        return true;
    }

    /** Hook at the bottom bar's planning-mode button (its call site: {@code JOBS$1.set} also runs inside the step). */
    public static boolean planMode(boolean on) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(PLAN_MODE).b(on));
        return true;
    }

    /**
     * Hook in {@code PlacerItemSingle.place} at the item's first tile: a room of one item (a well, a statue, an
     * artillery piece), from the fixed tool or the single-item area tool. Group, size and rotation are the issuer's tool
     * state; wall material and auto walls the issuer's room placer state.
     */
    public static boolean roomItem(RoomBlueprintImp blue, FurnisherItemGroup group, int size, int rot, int x1, int y1,
            TBuilding structure, boolean autoWalls) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.blueprint(new CmdWriter(ROOM_ITEM), blue).i(groupIndex(blue, group)).i(size).i(rot).i(x1)
                .i(y1).i(buildingIndex(structure)).b(autoWalls));
        return true;
    }

    /** Hook in the throne's {@code Placer.place} at its first tile: the construction site of a new throne room. */
    public static boolean throne(int x1, int y1, int rot) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(THRONE).i(x1).i(y1).i(rot));
        return true;
    }

    /**
     * Hook in {@code UIHomeAssign.place}: who may live in the house under a tile. One command per house; the drag's
     * other tiles of that house are dropped here (vanilla sets the same value again for each).
     */
    public static boolean homeAssign(HGROUP.HTypeBits bits, int tx, int ty, AREA area) {
        if (!Commands.capturing()) {
            return false;
        }
        if (firstTileOfRoom(tx, ty, area)) {
            int n = HGROUP.all().size();
            CmdWriter w = new CmdWriter(HOME_ASSIGN).i(tx).i(ty).i(n);
            for (int k = 0; k < n; k += 32) {
                int word = 0;
                for (int i = k; i < Math.min(k + 32, n); i++) {
                    word |= bits.is(i) ? 1 << (i - k) : 0;
                }
                w.i(word);
            }
            Commands.capture(w);
        }
        return true;
    }

    /** Hook in {@code UIRoomRaceAssign.place}: the work groups a workplace prefers. One command per room. */
    public static boolean workGroups(WGROUP.HTypeBits bits, int tx, int ty, AREA area) {
        if (!Commands.capturing()) {
            return false;
        }
        if (firstTileOfRoom(tx, ty, area)) {
            int n = WGROUP.all().size();
            CmdWriter w = new CmdWriter(WORK_GROUPS).i(tx).i(ty).i(n);
            for (int k = 0; k < n; k += 32) {
                int word = 0;
                for (int i = k; i < Math.min(k + 32, n); i++) {
                    word |= bits.is(i) ? 1 << (i - k) : 0;
                }
                w.i(word);
            }
            Commands.capture(w);
        }
        return true;
    }

    /** Hook in {@code UIHomeOdd.place} at a house's service tile: jobless subjects move in, from the issuer's cursor. */
    public static boolean homeOdd(int tx, int ty, int cursor) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(HOME_ODD).i(tx).i(ty).i(cursor));
        return true;
    }

    /**
     * Hook in {@code RoomPlacer.create}, after its own check that the plan has no problem: Build on a new area room. The
     * whole plan becomes one command (ADR 0003) and nothing is created now; the UI then clears the plan as after a
     * vanilla build. A refurnished room ({@code refurnish}: the placer holds the old room's state) still builds at once,
     * until its own milestone.
     */
    public static boolean roomBuild(RoomPlacer placer, boolean refurnish) {
        if (refurnish || !Commands.capturing() || !placer.blueprint().constructor().usesArea()) {
            return false;
        }
        CmdWriter w = new CmdWriter(ROOM_BUILD);
        Hooks.planStash().capture(w);
        Commands.capture(w);
        return true;
    }

    // ------------------------------------------------------------------ appliers (every PC, inside the step)

    private static boolean applyMulti(CmdReader r) {
        int key = r.i();
        PLACER_TYPE type = PLACER_TYPE.all.get(r.i());
        boolean overwrite = r.b();
        boolean convert = r.b();
        int food = r.i();
        Area area = new Area(TileArea.read(r));
        PlacableMulti tool = multiTool(key);

        Field fConvert = roadConvert();
        Field fFood = foodFilter();
        PlacableMulti foodTool = SETT.JOBS().clearss.food.placer();
        boolean ownOverwrite = Job.overwrite;
        boolean ownConvert = get(fConvert, SETT.JOBS().roads);
        int ownFood = getInt(fFood, foodTool);
        Job.overwrite = overwrite;
        set(fConvert, SETT.JOBS().roads, convert);
        setInt(fFood, foodTool, food);
        try {
            // PlacableMultiTool.place, V71.44
            tool.finishChecking(area);
            if (tool.isPlacable(area, type) != null) {
                return true;
            }
            for (int y = area.body().y1(); y < area.body().y2(); y++) {
                for (int x = area.body().x1(); x < area.body().x2(); x++) {
                    if (!area.is(x, y) || tool.isPlacable(x, y, area, type) != null) {
                        continue;
                    }
                    tool.place(x, y, area, type);
                }
            }
            tool.finishPlacing(area);
            return true;
        } finally {
            Job.overwrite = ownOverwrite;
            set(fConvert, SETT.JOBS().roads, ownConvert);
            setInt(fFood, foodTool, ownFood);
        }
    }

    private static boolean applyRoomItem(CmdReader r) {
        RoomBlueprint b = Args.blueprint(r);
        int group = r.i();
        int size = r.i();
        int rot = r.i();
        int x1 = r.i();
        int y1 = r.i();
        TBuilding structure = building(r.i());
        boolean autoWalls = r.b();
        if (!(b instanceof RoomBlueprintImp) || ((RoomBlueprintImp) b).constructor() == null) {
            throw new IllegalStateException("SOSDET: roomItem for blueprint " + b + ", which has no furniture");
        }
        RoomBlueprintImp blue = (RoomBlueprintImp) b;

        // The shared room placer, staged as the issuer's UI staged it; this PC's own blueprint, rotation, size, wall
        // material and auto walls go back after, and its own plan is rebuilt after the steps (ADR 0003).
        Hooks.planStash().meet();
        RoomPlacer placer = SETT.ROOMS().placement.placer;
        RoomBlueprintImp ownBlue = placer.blueprint();
        TBuilding ownStructure = placer.structure.get();
        boolean ownWalls = placer.autoWalls.getBool();
        if (ownBlue != blue) {
            placer.init(blue, 0);
        }
        PlacableFixed item = placer.item(group);
        int ownRot = item.rot();
        int ownSize = item.size();
        item.rotSet(rot);
        item.sizeSet(size);
        if (structure != null) {
            placer.structure.set(structure);
        }
        placer.autoWalls.set(autoWalls);
        try {
            return placeFixed(item, x1, y1);
        } finally {
            item.rotSet(ownRot);
            item.sizeSet(ownSize);
            if (ownStructure != null) {
                placer.structure.set(ownStructure);
            }
            placer.autoWalls.set(ownWalls);
            if (ownBlue != blue) {
                placer.init(ownBlue, 0);
            }
        }
    }

    /**
     * Build on an area room: the issuer's plan rebuilt in the shared room placer, then created if it still has no problem
     * at this step ({@code SFrame}'s create action, V71.44); skipped on every PC alike otherwise. This PC's own plan is
     * recorded first and rebuilt after the steps; its wall material and wall options go back at once.
     */
    private static boolean applyRoomBuild(CmdReader r) {
        PlanRecord plan = PlanRecord.read(r);
        Hooks.planStash().meet();
        RoomPlacer placer = SETT.ROOMS().placement.placer;
        TBuilding ownStructure = placer.structure.get();
        boolean ownWalls = placer.autoWalls.getBool();
        boolean ownOnWalls = placer.buildOnWalls.is();
        try {
            if (!Plans.rebuildCommand(plan) || placer.createProblem() != null) {
                return false;
            }
            placer.create();
            return true;
        } finally {
            placer.init(null, 0); // a skipped plan's tiles; after create() the placer is empty already
            if (ownStructure != null) {
                placer.structure.set(ownStructure);
            }
            placer.autoWalls.set(ownWalls);
            placer.buildOnWalls.set(ownOnWalls);
        }
    }

    private static boolean applyThrone(CmdReader r) {
        int x1 = r.i();
        int y1 = r.i();
        int rot = r.i();
        PlacableFixed p = (PlacableFixed) SETT.ROOMS().THRONE.placer;
        int ownRot = p.rot();
        p.rotSet(rot);
        try {
            return placeFixed(p, x1, y1);
        } finally {
            p.rotSet(ownRot);
        }
    }

    /** {@code UIHomeAssign.settingSet}, V71.44: a house, or a house still under construction. */
    private static boolean applyHomeAssign(CmdReader r) {
        int tx = r.i();
        int ty = r.i();
        HGROUP.HTypeBitsImp bits = new HGROUP.HTypeBitsImp(false);
        int n = groupCount(r, HGROUP.all().size());
        for (int k = 0; k < n; k += 32) {
            int word = r.i();
            for (int i = k; i < Math.min(k + 32, n); i++) {
                if ((word & (1 << (i - k))) != 0) {
                    bits.set(HGROUP.all().get(i));
                }
            }
        }
        HomeInstance h = SETT.ROOMS().HOME.getter.get(tx, ty);
        if (h != null) {
            h.settingSet(bits);
            return true;
        }
        RoomState s = SETT.ROOMS().construction.state(tx, ty);
        if (s instanceof HomeInstance.State) {
            ((HomeInstance.State) s).egroup.copy(bits);
            return true;
        }
        return false;
    }

    /** {@code UIRoomRaceAssign.place}, V71.44. */
    private static boolean applyWorkGroups(CmdReader r) {
        int tx = r.i();
        int ty = r.i();
        WGROUP.HTypeBitsImp bits = new WGROUP.HTypeBitsImp(false);
        int n = groupCount(r, WGROUP.all().size());
        for (int k = 0; k < n; k += 32) {
            int word = r.i();
            for (int i = k; i < Math.min(k + 32, n); i++) {
                if ((word & (1 << (i - k))) != 0) {
                    bits.set(WGROUP.all().get(i));
                }
            }
        }
        RoomInstance ins = SETT.ROOMS().map.instance.get(tx, ty);
        if (ins == null || ins.blueprintI().employment() == null) {
            return false;
        }
        ins.employees().prefferedSet(bits);
        return true;
    }

    /** {@code UIHomeOdd.place}, V71.44, from the issuer's cursor; the issuer's tool gets the moved cursor back. */
    private static boolean applyHomeOdd(CmdReader r) {
        int tx = r.i();
        int ty = r.i();
        int ie = r.i();
        HomeInstance h = SETT.ROOMS().HOME.getter.get(tx, ty);
        if (h == null || tx != h.serviceX() || ty != h.serviceY()) {
            return false;
        }
        HGROUP.HTypeBits t = h.availability();
        if (t != null && h.occupants() < h.occupantsMax()) {
            ENTITY[] ee = SETT.ENTITIES().getAllEnts();
            for (int i = 0; i < ee.length; i++) {
                if (ie >= ee.length) {
                    ie = 0;
                }
                ENTITY e = ee[ie];
                if (e instanceof Humanoid) {
                    Humanoid a = (Humanoid) e;
                    if (STATS.WORK().EMPLOYED.get(a) == null && t.is(a)) {
                        STATS.HOME().GETTER.set(a, h);
                        t = h.availability();
                        if (t == null || h.occupants() >= h.occupantsMax()) {
                            break;
                        }
                    }
                }
                ie++;
            }
        }
        if (Hooks.issuedHere()) {
            PLACABLE current = VIEW.s().tools.placer.getCurrent();
            if (current != null && current.getClass().getName().equals(HOME_ODD_CLASS)) {
                setInt(field(current.getClass(), "ie"), current, ie);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the tools' own loops and targets

    /**
     * {@code PlacableFixedTool.click}, V71.44, from the footprint's first tile: every tile checked, then every tile
     * placed. False when the footprint no longer fits (a held button's repeat, or the map changed), on every PC alike.
     * {@code PlacableFixed.init} is left out: neither placer run here overrides it.
     */
    private static boolean placeFixed(PlacableFixed p, int x1, int y1) {
        if (p.placableWhole(x1, y1) != null) {
            return false;
        }
        int w = p.width();
        int h = p.height();
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                if (p.placable(x1 + dx, y1 + dy, dx, dy) != null) {
                    return false;
                }
            }
        }
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                p.place(x1 + dx, y1 + dy, dx, dy);
            }
        }
        p.afterPlaced(x1, y1);
        return true;
    }

    /**
     * The area tools the settlement owns on every PC, in a fixed order, each followed by its undo: the job tools, the
     * clearing, road, structure, fortification and fence placers, maintenance, diagonals and room dismantling. Built
     * per call (a few hundred entries, once per command), so nothing is kept between sessions.
     */
    static List<PlacableMulti> multiTools() {
        List<PlacableMulti> l = new ArrayList<>();
        JOBS j = SETT.JOBS();
        add(l, j.tool_clear);
        add(l, j.tool_activate);
        add(l, j.tool_dormant);
        add(l, j.tool_remove_all);
        add(l, j.tool_remove_smartl);
        add(l, j.tool_repair);
        for (PLACABLE p : j.clears) {
            add(l, p);
        }
        JobClears c = j.clearss;
        for (Job job : new Job[] {c.stone, c.wood, c.food, c.water, c.returnwater, c.tunnel, c.caveFill, c.structure,
                c.road}) {
            add(l, job.placer());
        }
        add(l, c.woodAndRock);
        add(l, c.hunt);
        add(l, c.huntundo);
        for (JobBuildRoad road : j.roads.all) {
            add(l, road.placer());
        }
        for (JobBuildStructure s : j.build_structure) {
            add(l, s.wall.placer());
            add(l, s.ceiling.placer());
            add(l, s.combo);
            add(l, s.convert);
        }
        for (Job f : j.build_fort.all) {
            add(l, f.placer());
        }
        add(l, j.build_fort.build_stairs.placer());
        for (Job f : j.fences) {
            add(l, f.placer());
        }
        add(l, SETT.MAINTENANCE().enablePlacer);
        add(l, SETT.TERRAIN().diagonal.placer);
        add(l, SETT.TERRAIN().diagonal.undo);
        add(l, SETT.ROOMS().DELETE);
        return l;
    }

    private static void add(List<PlacableMulti> l, PLACABLE p) {
        if (!(p instanceof PlacableMulti) || indexOf(l, p) >= 0) {
            return;
        }
        l.add((PlacableMulti) p);
        add(l, p.getUndo());
    }

    private static int indexOf(List<PlacableMulti> l, PLACABLE p) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) == p) {
                return i;
            }
        }
        return -1;
    }

    private static int multiKey(PlacableMulti tool) {
        if (tool instanceof RoomUpgrader) {
            return UPGRADER;
        }
        String cls = tool.getClass().getName();
        if (cls.equals(ROADIFY_CLASS)) {
            return ROADIFY;
        }
        if (cls.equals(UNROADIFY_CLASS)) {
            return UNROADIFY;
        }
        int i = indexOf(multiTools(), tool);
        return i < 0 ? NOT_A_COMMAND : i;
    }

    private static PlacableMulti multiTool(int key) {
        switch (key) {
        case UPGRADER:
            return new RoomUpgrader(); // its fields are scratch, refilled by every drag's check
        case ROADIFY:
            return roadMatching(false);
        case UNROADIFY:
            return roadMatching(true);
        default:
            List<PlacableMulti> all = multiTools();
            if (key < 0 || key >= all.size()) {
                throw new IllegalStateException("SOSDET: area tool " + key + " of " + all.size() + " (another engine?)");
            }
            return all.get(key);
        }
    }

    /**
     * The bottom bar's road matching ({@code Options$6}) and its undo ({@code Options$5}), V71.44: anonymous classes
     * the engine can't construct, so their two lines here. Sets {@code floorundernot} on each tile that differs.
     */
    private static PlacableMulti roadMatching(boolean notUnder) {
        return new PlacableMulti("") {
            @Override
            public CharSequence isPlacable(int tx, int ty, AREA area, PLACER_TYPE type) {
                return SETT.FLOOR().floorundernot.is(tx, ty) == notUnder ? E : null;
            }

            @Override
            public void place(int tx, int ty, AREA area, PLACER_TYPE type) {
                SETT.FLOOR().floorundernot.set(tx, ty, notUnder);
            }
        };
    }

    private static int typeIndex(PLACER_TYPE type) {
        for (int i = 0; i < PLACER_TYPE.all.size(); i++) {
            if (PLACER_TYPE.all.get(i) == type) {
                return i;
            }
        }
        throw new IllegalStateException("SOSDET: placer type " + type + " is not in PLACER_TYPE.all");
    }

    private static int groupIndex(RoomBlueprintImp blue, FurnisherItemGroup group) {
        LIST<FurnisherItemGroup> all = blue.constructor().pgroups();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == group) {
                return i;
            }
        }
        throw new IllegalStateException("SOSDET: furniture group " + group.name() + " is not in " + blue.key);
    }

    static int buildingIndex(TBuilding b) {
        LIST<TBuilding> all = SETT.TERRAIN().BUILDINGS.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == b) {
                return i;
            }
        }
        return -1;
    }

    static TBuilding building(int i) {
        return i < 0 ? null : SETT.TERRAIN().BUILDINGS.all().get(i);
    }

    private static int groupCount(CmdReader r, int here) {
        int n = r.i();
        if (n != here) {
            throw new IllegalStateException("SOSDET: a command for " + n + " groups, this game has " + here);
        }
        return n;
    }

    /** True unless a tile of the drag before this one (row by row) lies in the same room: one command per room. */
    private static boolean firstTileOfRoom(int tx, int ty, AREA area) {
        RoomInstance room = SETT.ROOMS().map.instance.get(tx, ty);
        if (room == null) {
            return true;
        }
        RECTANGLE b = room.body();
        for (int y = Math.max(b.y1(), area.body().y1()); y <= ty; y++) {
            for (int x = Math.max(b.x1(), area.body().x1()); x < Math.min(b.x2(), area.body().x2()); x++) {
                if (y == ty && x >= tx) {
                    return true;
                }
                if (area.is(x, y) && SETT.ROOMS().map.get(x, y) == room) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the issuer's tool options (reflection)

    /** {@code JobBuildRoads.convert}, V71.44: the road tool's "convert existing roads" button. */
    private static Field roadConvert() {
        return field(JobBuildRoad.JobBuildRoads.class, "convert");
    }

    /** {@code gi} of the forage tool ({@code JobClears.food.placer()}), V71.44: the crop it marks, -1 for all. */
    private static Field foodFilter() {
        return field(SETT.JOBS().clearss.food.placer().getClass(), "gi");
    }

    /**
     * A private field of the game, V71.44. Resolved per use, not once at start-up: a tool command is a rare UI action,
     * and this class's static initializer must not touch game classes (the menu's script scan runs it).
     */
    static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("SOSDET: no field " + c.getName() + "." + name + " (game V71.44 expected)", e);
        }
    }

    private static boolean get(Field f, Object o) {
        try {
            return f.getBoolean(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + f, e);
        }
    }

    private static int getInt(Field f, Object o) {
        try {
            return f.getInt(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + f, e);
        }
    }

    private static void set(Field f, Object o, boolean v) {
        try {
            f.setBoolean(o, v);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot write " + f, e);
        }
    }

    static void setInt(Field f, Object o, int v) {
        try {
            f.setInt(o, v);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot write " + f, e);
        }
    }

    // ------------------------------------------------------------------ the area argument

    /** A decoded {@link TileArea} as the game's AREA, for the tool loop and the placers' checks. */
    static final class Area implements AREA {

        private final TileArea tiles;
        private final Rec body;

        Area(TileArea tiles) {
            this.tiles = tiles;
            body = new Rec(tiles.x1, tiles.x1 + tiles.width, tiles.y1, tiles.y1 + tiles.height);
        }

        @Override
        public boolean is(int tile) {
            return is(tile % SETT.TWIDTH, tile / SETT.TWIDTH);
        }

        @Override
        public boolean is(int tx, int ty) {
            return tiles.is(tx, ty);
        }

        @Override
        public RECTANGLE body() {
            return body;
        }

        @Override
        public int area() {
            return tiles.count;
        }
    }
}

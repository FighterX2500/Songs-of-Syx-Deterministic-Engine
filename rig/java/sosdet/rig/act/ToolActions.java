package sosdet.rig.act;

import java.util.ArrayList;
import java.util.List;

import init.type.HGROUP;
import init.type.WGROUP;
import settlement.main.SETT;
import settlement.room.home.house.HomeInstance;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.RoomBlueprintIns;
import settlement.room.main.RoomInstance;
import settlement.room.main.placement.RoomPlacer;
import settlement.room.main.throne.THRONE;
import snake2d.util.datatypes.AREA;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.datatypes.DIR;
import snake2d.util.datatypes.RECTANGLE;
import snake2d.util.datatypes.Rec;
import sosdet.game.cmd.Tools;
import sosdet.rig.Rig;
import view.sett.ui.bottom.RoomUpgrader;
import view.tool.PLACER_TYPE;
import view.tool.PlacableFixed;
import view.tool.PlacableMulti;

/**
 * The rig's scripted player: tool actions, each the call the game's UI makes (SOS-34). Targets resolve the same way in
 * every run of a save: the n-th instance of a blueprint, the n-th house met row by row, the first free spot ring by
 * ring around the throne.
 */
public final class ToolActions {

    private ToolActions() {
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        case "planmode": // planmode 1|0: the bottom bar's planning-mode button
            boolean on = a[1].equals("1");
            Rig.later(() -> {
                if (!Tools.planMode(on)) {
                    SETT.JOBS().planMode.set(on);
                }
            });
            return true;
        case "toolarea": // toolarea <tool> <blueprint key> <n>: a square drag of an area tool over that room
            String tool = a[1];
            String key = a[2];
            int n = Integer.parseInt(a[3]);
            Rig.later(() -> multi(tool(tool), room(key, n).body()));
            return true;
        case "homeassign": // homeassign <n> all|none: the homes tool dragged over the n-th house
            int home = Integer.parseInt(a[1]);
            boolean all = a[2].equals("all");
            Rig.later(() -> {
                Box area = new Box(house(home).body());
                HGROUP.HTypeBitsImp bits = new HGROUP.HTypeBitsImp(all);
                for (int y = area.body.y1(); y < area.body.y2(); y++) {
                    for (int x = area.body.x1(); x < area.body.x2(); x++) {
                        if (SETT.ROOMS().HOME.getter.get(x, y) != null && !Tools.homeAssign(bits, x, y, area)) {
                            throw new IllegalStateException("SOSDET rig: homeassign was not captured");
                        }
                    }
                }
            });
            return true;
        case "workgroups": // workgroups <blueprint key> <n> all|none: the work groups tool dragged over that room
            String wkey = a[1];
            int wn = Integer.parseInt(a[2]);
            boolean wall = a[3].equals("all");
            Rig.later(() -> {
                RoomInstance room = room(wkey, wn);
                Box area = new Box(room.body());
                WGROUP.HTypeBitsImp bits = new WGROUP.HTypeBitsImp(wall);
                for (int y = area.body.y1(); y < area.body.y2(); y++) {
                    for (int x = area.body.x1(); x < area.body.x2(); x++) {
                        if (SETT.ROOMS().map.get(x, y) == room && !Tools.workGroups(bits, x, y, area)) {
                            throw new IllegalStateException("SOSDET rig: workgroups was not captured");
                        }
                    }
                }
            });
            return true;
        case "homeodd": // homeodd <n>: the odd-jobber mover on the n-th house's service tile, cursor 0
            int odd = Integer.parseInt(a[1]);
            Rig.later(() -> {
                HomeInstance h = house(odd);
                if (!Tools.homeOdd(h.serviceX(), h.serviceY(), 0)) {
                    throw new IllegalStateException("SOSDET rig: homeodd was not captured");
                }
            });
            return true;
        case "roomitem": // roomitem <blueprint key> <group>: a one-item room at the first free spot around the throne
            String ikey = a[1];
            int group = Integer.parseInt(a[2]);
            Rig.later(() -> {
                RoomPlacer placer = SETT.ROOMS().placement.placer;
                placer.init((RoomBlueprintImp) blueprint(ikey), 0);
                click(placer.item(group));
                placer.init(null, 0); // the construction tool closing
            });
            return true;
        case "throne": // throne: the throne mover at the first free spot around the throne
            Rig.later(() -> click((PlacableFixed) SETT.ROOMS().THRONE.placer));
            return true;
        case "plan": // plan <blueprint key> <w> <h> [upgrade]: the room tool dragged over the first free w x h rectangle
            String pkey = a[1];
            int pw = Integer.parseInt(a[2]);
            int ph = Integer.parseInt(a[3]);
            int up = a.length > 4 ? Integer.parseInt(a[4]) : 0;
            Rig.later(() -> plan(pkey, pw, ph, up, null, false));
            return true;
        case "plan-items": // plan-items <n> [group]: n items of the plan's furniture group (default 0), each at the
            // first spot it fits
            int items = Integer.parseInt(a[1]);
            int igroup = a.length > 2 ? Integer.parseInt(a[2]) : 0;
            Rig.later(() -> planItems(items, igroup));
            return true;
        case "plan-door": // plan-door: the doorway tool on the first wall tile next to the plan where a doorway fits
            Rig.later(ToolActions::planDoor);
            return true;
        case "build": // build: the plan's Build button (SFrame), then the tool closing (Config.deactivateAction)
            Rig.later(ToolActions::build);
            return true;
        case "build-plan": // build-plan <key> <w> <h> over|apart: Build, and in the same frame a new plan, laid over
            // the built plan's corner or apart from it, so the built plan's command lands while the new plan is local
            String bkey = a[1];
            int bw = Integer.parseInt(a[2]);
            int bh = Integer.parseInt(a[3]);
            boolean over = a[4].equals("over");
            Rig.later(() -> {
                Rec built = new Rec(instance().body());
                build();
                plan(bkey, bw, bh, 0, built, over);
            });
            return true;
        case "plan-cancel": // plan-cancel: the room tool closing without a build
            Rig.later(() -> SETT.ROOMS().placement.placer.init(null, 0));
            return true;
        default:
            return false;
        }
    }

    // ------------------------------------------------------------------ area-room planning (ADR 0003)

    /**
     * Starts planning room type {@code key} and drags its area tool over a w x h rectangle: the first one, ring by ring
     * around the throne, whose every tile the tool accepts. With {@code built}: at its corner ({@code over}), or never
     * within two tiles of it.
     */
    private static void plan(String key, int w, int h, int upgrade, RECTANGLE built, boolean over) {
        RoomBlueprint b = blueprint(key);
        if (!(b instanceof RoomBlueprintImp) || ((RoomBlueprintImp) b).constructor() == null
                || !((RoomBlueprintImp) b).constructor().usesArea()) {
            throw new IllegalArgumentException("SOSDET rig: " + key + " is no area room");
        }
        RoomPlacer placer = SETT.ROOMS().placement.placer;
        placer.init((RoomBlueprintImp) b, 0);
        placer.setUpgrade(upgrade);
        PlacableMulti tool = (PlacableMulti) placer.area();
        if (built != null && over && fitsArea(tool, built.x1(), built.y1(), w, h)) {
            drag(tool, new Box(new Rec(built.x1(), built.x1() + w, built.y1(), built.y1() + h)));
            return;
        }
        COORDINATE c = THRONE.coo();
        for (int d = 1; d < 150; d++) {
            for (int y = c.y() - d; y <= c.y() + d; y++) {
                for (int x = c.x() - d; x <= c.x() + d; x++) {
                    boolean ring = y == c.y() - d || y == c.y() + d || x == c.x() - d || x == c.x() + d;
                    if (!ring || built != null && !over && near(built, x, y, w, h) || !fitsArea(tool, x, y, w, h)) {
                        continue;
                    }
                    drag(tool, new Box(new Rec(x, x + w, y, y + h)));
                    return;
                }
            }
        }
        throw new IllegalStateException("SOSDET rig: no free " + w + "x" + h + " area for " + key);
    }

    private static boolean near(RECTANGLE r, int x, int y, int w, int h) {
        return x < r.x2() + 2 && x + w > r.x1() - 2 && y < r.y2() + 2 && y + h > r.y1() - 2;
    }

    private static boolean fitsArea(PlacableMulti tool, int x1, int y1, int w, int h) {
        Box box = new Box(new Rec(x1, x1 + w, y1, y1 + h));
        for (int y = y1; y < y1 + h; y++) {
            for (int x = x1; x < x1 + w; x++) {
                if (tool.isPlacable(x, y, box, PLACER_TYPE.SQUARE) != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** PlacableMultiTool.place, V71.44: the room's area tool after a square drag (its plan stays on this PC). */
    private static void drag(PlacableMulti tool, Box box) {
        tool.finishChecking(box);
        if (tool.isPlacable(box, PLACER_TYPE.SQUARE) != null) {
            throw new IllegalStateException("SOSDET rig: the area tool refused " + box.body);
        }
        for (int y = box.body.y1(); y < box.body.y2(); y++) {
            for (int x = box.body.x1(); x < box.body.x2(); x++) {
                if (tool.isPlacable(x, y, box, PLACER_TYPE.SQUARE) == null) {
                    tool.place(x, y, box, PLACER_TYPE.SQUARE);
                }
            }
        }
        tool.finishPlacing(box);
    }

    /** PlacableFixedTool.click with the plan's furniture group, n times, each at the first spot row by row. */
    private static void planItems(int n, int group) {
        RoomPlacer placer = SETT.ROOMS().placement.placer;
        if (placer.blueprint() == null) {
            throw new IllegalStateException("SOSDET rig: plan-items without a plan");
        }
        PlacableFixed p = placer.item(group);
        RECTANGLE b = new Rec(instance().body());
        for (int k = 0; k < n; k++) {
            if (!placeFirst(p, b)) {
                throw new IllegalStateException("SOSDET rig: no spot for item " + k + " of " + p.name());
            }
        }
    }

    private static boolean placeFirst(PlacableFixed p, RECTANGLE b) {
        for (int y = b.y1(); y < b.y2(); y++) {
            for (int x = b.x1(); x < b.x2(); x++) {
                if (fits(p, x, y)) {
                    for (int dy = 0; dy < p.height(); dy++) {
                        for (int dx = 0; dx < p.width(); dx++) {
                            p.place(x + dx, y + dy, dx, dy);
                        }
                    }
                    p.afterPlaced(x, y);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The doorway tool on the first tile, row by row from the bottom (away from the furniture plan-items puts in the
     * top rows), beside a side of the plan (not only its corner) where it fits.
     */
    private static void planDoor() {
        RoomPlacer placer = SETT.ROOMS().placement.placer;
        Room.RoomInstanceImp ins = instance();
        RECTANGLE b = new Rec(ins.body());
        for (int y = b.y2(); y >= b.y1() - 1; y--) {
            for (int x = b.x1() - 1; x <= b.x2(); x++) {
                Box tile = new Box(new Rec(x, x + 1, y, y + 1));
                if (ins.is(x, y) || !besideSide(ins, x, y)
                        || placer.placerDoor.isPlacable(x, y, tile, PLACER_TYPE.SQUARE) != null) {
                    continue;
                }
                placer.placerDoor.place(x, y, tile, PLACER_TYPE.SQUARE);
                return;
            }
        }
        throw new IllegalStateException("SOSDET rig: no spot for a doorway");
    }

    private static boolean besideSide(Room.RoomInstanceImp ins, int x, int y) {
        for (int i = 0; i < DIR.ORTHO.size(); i++) {
            if (ins.is(x + DIR.ORTHO.get(i).x(), y + DIR.ORTHO.get(i).y())) {
                return true;
            }
        }
        return false;
    }

    /** SFrame's Build (create, V71.44), then the tool closing; in a session the create hook makes it one command. */
    private static void build() {
        RoomPlacer placer = SETT.ROOMS().placement.placer;
        CharSequence problem = placer.createProblem();
        if (problem != null) {
            throw new IllegalStateException("SOSDET rig: build: " + problem);
        }
        placer.create();
        boolean captured = instance().area() > 0; // a vanilla create leaves the placer empty
        placer.init(null, 0);
        if (!captured) {
            throw new IllegalStateException("SOSDET rig: build was not captured");
        }
    }

    /** The room placer's plan (its class is package-private in V71.44). */
    private static Room.RoomInstanceImp instance() {
        return (Room.RoomInstanceImp) SETT.ROOMS().placement.embryo;
    }

    private static PlacableMulti tool(String name) {
        switch (name) {
        case "dormant":
            return SETT.JOBS().tool_dormant;
        case "activate":
            return SETT.JOBS().tool_activate;
        case "repair":
            return SETT.JOBS().tool_repair;
        case "mainton":
            return (PlacableMulti) SETT.MAINTENANCE().enablePlacer;
        case "maintoff":
            return (PlacableMulti) SETT.MAINTENANCE().enablePlacer.getUndo();
        case "upgrade":
            return new RoomUpgrader();
        default:
            throw new IllegalArgumentException("SOSDET rig: no area tool " + name);
        }
    }

    /** PlacableMultiTool.place after a square drag: its hook makes the drag one command. */
    private static void multi(PlacableMulti tool, RECTANGLE body) {
        if (!Tools.multiPlace(tool, PLACER_TYPE.SQUARE, new Box(body))) {
            throw new IllegalStateException("SOSDET rig: " + tool.name() + " was not captured");
        }
    }

    /** PlacableFixedTool.click at the first spot the placer fits, ring by ring around the throne. */
    private static void click(PlacableFixed p) {
        COORDINATE c = THRONE.coo();
        for (int d = 1; d < 150; d++) {
            for (int y = c.y() - d; y <= c.y() + d; y++) {
                for (int x = c.x() - d; x <= c.x() + d; x++) {
                    boolean ring = y == c.y() - d || y == c.y() + d || x == c.x() - d || x == c.x() + d;
                    if (ring && fits(p, x, y)) {
                        for (int dy = 0; dy < p.height(); dy++) {
                            for (int dx = 0; dx < p.width(); dx++) {
                                p.place(x + dx, y + dy, dx, dy);
                            }
                        }
                        p.afterPlaced(x, y);
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException("SOSDET rig: no spot for " + p.name());
    }

    private static boolean fits(PlacableFixed p, int x1, int y1) {
        if (!SETT.IN_BOUNDS(x1, y1) || !SETT.IN_BOUNDS(x1 + p.width(), y1 + p.height())
                || p.placableWhole(x1, y1) != null) {
            return false;
        }
        for (int dy = 0; dy < p.height(); dy++) {
            for (int dx = 0; dx < p.width(); dx++) {
                if (p.placable(x1 + dx, y1 + dy, dx, dy) != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static RoomBlueprint blueprint(String key) {
        RoomBlueprint b = SETT.ROOMS().collection.tryGet(key);
        if (b == null) {
            throw new IllegalArgumentException("SOSDET rig: no room blueprint " + key);
        }
        return b;
    }

    private static RoomInstance room(String key, int n) {
        RoomBlueprint b = blueprint(key);
        if (!(b instanceof RoomBlueprintIns<?>) || ((RoomBlueprintIns<?>) b).instancesSize() <= n) {
            throw new IllegalArgumentException("SOSDET rig: no room " + key + " " + n);
        }
        return ((RoomBlueprintIns<?>) b).getInstance(n);
    }

    /** The n-th house met scanning the map row by row. */
    private static HomeInstance house(int n) {
        List<HomeInstance> seen = new ArrayList<>();
        for (int y = 0; y < SETT.THEIGHT; y++) {
            for (int x = 0; x < SETT.TWIDTH; x++) {
                HomeInstance h = SETT.ROOMS().HOME.getter.get(x, y);
                if (h != null && !seen.contains(h)) {
                    if (seen.size() == n) {
                        return h;
                    }
                    seen.add(h);
                }
            }
        }
        throw new IllegalArgumentException("SOSDET rig: no house " + n);
    }

    /** A filled rectangle, as a square drag gives it. */
    private static final class Box implements AREA {

        final Rec body;

        Box(RECTANGLE r) {
            body = new Rec(r);
        }

        @Override
        public boolean is(int tile) {
            return is(tile % SETT.TWIDTH, tile / SETT.TWIDTH);
        }

        @Override
        public boolean is(int tx, int ty) {
            return body.holdsPoint(tx, ty);
        }

        @Override
        public RECTANGLE body() {
            return body;
        }

        @Override
        public int area() {
            return body.width() * body.height();
        }
    }
}

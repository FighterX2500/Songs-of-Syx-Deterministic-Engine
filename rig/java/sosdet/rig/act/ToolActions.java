package sosdet.rig.act;

import java.util.ArrayList;
import java.util.List;

import init.type.HGROUP;
import init.type.WGROUP;
import settlement.main.SETT;
import settlement.room.home.house.HomeInstance;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.RoomBlueprintIns;
import settlement.room.main.RoomInstance;
import settlement.room.main.placement.RoomPlacer;
import settlement.room.main.throne.THRONE;
import snake2d.util.datatypes.AREA;
import snake2d.util.datatypes.COORDINATE;
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
        default:
            return false;
        }
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

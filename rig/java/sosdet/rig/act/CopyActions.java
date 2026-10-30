package sosdet.rig.act;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import game.GAME;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.room.main.Room;
import settlement.room.main.RoomInstance;
import settlement.room.main.copy.SavedPrints.SavedPrint;
import settlement.room.main.throne.THRONE;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.datatypes.Coo;
import snake2d.util.datatypes.RECTANGLE;
import snake2d.util.datatypes.Rec;
import sosdet.Hooks;
import sosdet.rig.Rig;
import view.main.VIEW;
import view.sett.ui.room.copy.UICopier;
import view.tool.PLACABLE;
import view.tool.PlacableFixed;
import view.tool.PlacableMulti;

/**
 * The rig's scripted player: the room copier, saved blueprints and the mass copier (SOS-36, commands 6100-6102), each
 * through the game's own tools: the source picked, the tool's click at the first free spot ring by ring around the
 * throne, the tool closed.
 */
public final class CopyActions {

    private CopyActions() {
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        case "wipeenemy": // wipeenemy <human slot>: on that human's PC every enemy soldier dies, so its local battle is won (SOS-93)
            if (Hooks.localHuman() == Integer.parseInt(a[1])) {
                for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
                    if (e instanceof Humanoid && ((Humanoid) e).indu().army() == GAME.ARMIES().enemy()) {
                        e.helloMyNameIsInigoMontoyaYouKilledMyFatherPrepareToDie();
                    }
                }
            }
            return true;
        case "divorders": // divorders <human slot>: on that human's PC every division gets a player's orders (SOS-109)
            if (Hooks.localHuman() == Integer.parseInt(a[1])) {
                for (game.battle.div.Div d : game.GAME.ARMIES().player().divisions()) {
                    sosdet.game.AiHooks.running(d, true);
                    sosdet.game.AiHooks.guard(d, true);
                    sosdet.game.AiHooks.mopUp(d, true);
                }
            }
            return true;
        case "copyroom": // copyroom <key prefix> [n] [rot]: the room copier on the n-th area room, placed at the first
            // free spot (6100)
            String ckey = a[1];
            int cn = a.length > 2 ? Integer.parseInt(a[2]) : 0;
            int crot = a.length > 3 ? Integer.parseInt(a[3]) : 0;
            Rig.later(() -> copyRoom(ckey, cn, crot));
            return true;
        case "printplace": // printplace <key prefix> [n] [rot]: a print saved from the n-th area room (made here, not
            // in the profile), placed at the first free spot (6101)
            String pkey = a[1];
            int pn = a.length > 2 ? Integer.parseInt(a[2]) : 0;
            int prot = a.length > 3 ? Integer.parseInt(a[3]) : 0;
            Rig.later(() -> printPlace(pkey, pn, prot));
            return true;
        case "masscopy": // masscopy <key prefix> [n] [rot]: the mass copier with the n-th area room selected, placed
            // where every tile of that room fits (6102)
            String mkey = a[1];
            int mn = a.length > 2 ? Integer.parseInt(a[2]) : 0;
            int mrot = a.length > 3 ? Integer.parseInt(a[3]) : 0;
            Rig.later(() -> massCopy(mkey, mn, mrot));
            return true;
        default:
            return false;
        }
    }

    /** UIRooms' copy hotkey ({@code ROOM_COPY.copy}) on the room, then the copier's click. */
    private static void copyRoom(String key, int n, int rot) {
        RoomInstance room = ToolActions.areaRoom(key, n);
        SETT.ROOMS().copy.copy(room.mX(), room.mY());
        PlacableFixed p = current("copyroom " + key);
        p.rotSet(rot);
        ToolActions.click(p);
        VIEW.s().tools.placer.deactivate();
        Rig.note("copyroom " + key + " " + n + ": " + room.blueprint().key + " at " + room.mX() + "," + room.mY());
    }

    /** The prints panel's place button ({@code SavedPrintsPlacer.place}) with a print of the room, then its click. */
    private static void printPlace(String key, int n, int rot) {
        RoomInstance room = ToolActions.areaRoom(key, n);
        if (!SETT.ROOMS().copy.prints.canAdd(room)) {
            throw new IllegalArgumentException("SOSDET rig: printplace: " + room.blueprint().key + " has no prints");
        }
        SavedPrint print;
        try {
            // SavedPrints.push without its write to the profile: SavedPrint(Room, mx, my), package-private in V71.44
            Constructor<SavedPrint> k = SavedPrint.class.getDeclaredConstructor(Room.class, int.class, int.class);
            k.setAccessible(true);
            print = k.newInstance(room, room.mX(), room.mY());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SOSDET rig: no SavedPrint(Room, int, int) (V71.44 expected)", e);
        }
        SETT.ROOMS().copy.savedPlacer.place(print);
        PlacableFixed p = current("printplace " + key);
        p.rotSet(rot);
        ToolActions.click(p);
        VIEW.s().tools.placer.deactivate();
        Rig.note("printplace " + key + " " + n + ": " + print.width + "x" + print.height + " of " + print.blue.key);
    }

    /**
     * The bottom bar's mass copier ({@code UICopier.activate}), its selection tool on every tile of the room, then the
     * second step's click ({@code PlacableFixedTool.click}) at the first centre where all the room's tiles fit.
     */
    private static void massCopy(String key, int n, int rot) {
        RoomInstance room = ToolActions.areaRoom(key, n);
        UICopier ui = VIEW.s().ui.copier;
        ui.activate();
        PLACABLE first = VIEW.s().tools.placer.getCurrent();
        if (!(first instanceof PlacableMulti)) {
            throw new IllegalStateException("SOSDET rig: masscopy: the copier opened " + first);
        }
        PlacableMulti select = (PlacableMulti) first;
        RECTANGLE b = new Rec(room.body());
        for (int y = b.y1(); y < b.y2(); y++) {
            for (int x = b.x1(); x < b.x2(); x++) {
                if (SETT.ROOMS().map.get(x, y) == room && select.isPlacable(x, y, null, null) == null) {
                    select.place(x, y, null, null);
                }
            }
        }
        PlacableFixed second;
        try {
            Field f = UICopier.class.getDeclaredField("second");
            f.setAccessible(true);
            second = (PlacableFixed) f.get(ui);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SOSDET rig: no UICopier.second (V71.44 expected)", e);
        }
        second.rotSet(rot);
        COORDINATE c = centre(room, b, rot);
        second.init(c.x(), c.y());
        int x1 = c.x() - second.width() / 2;
        int y1 = c.y() - second.height() / 2;
        for (int dy = 0; dy < second.height(); dy++) {
            for (int dx = 0; dx < second.width(); dx++) {
                second.place(x1 + dx, y1 + dy, dx, dy);
            }
        }
        second.afterPlaced(x1, y1);
        VIEW.s().tools.placer.deactivate();
        Rig.note("masscopy " + key + " " + n + ": " + room.blueprint().key + " at " + room.mX() + "," + room.mY()
                + " to " + c.x() + "," + c.y());
    }

    /**
     * The first centre, ring by ring around the throne, where every tile of the room, turned as {@code Dest.transform}
     * turns it (V71.44), is free for the mass copier's room copy and off the selection.
     */
    private static COORDINATE centre(RoomInstance room, RECTANGLE b, int rot) {
        COORDINATE t = THRONE.coo();
        for (int d = 1; d < 150; d++) {
            for (int cy = t.y() - d; cy <= t.y() + d; cy++) {
                for (int cx = t.x() - d; cx <= t.x() + d; cx++) {
                    boolean ring = cy == t.y() - d || cy == t.y() + d || cx == t.x() - d || cx == t.x() + d;
                    if (ring && fits(room, b, rot, cx, cy)) {
                        return new Coo(cx, cy);
                    }
                }
            }
        }
        throw new IllegalStateException("SOSDET rig: masscopy: no spot for " + room.blueprint().key);
    }

    private static boolean fits(RoomInstance room, RECTANGLE b, int rot, int cx, int cy) {
        for (int y = b.y1(); y < b.y2(); y++) {
            for (int x = b.x1(); x < b.x2(); x++) {
                if (SETT.ROOMS().map.get(x, y) != room) {
                    continue;
                }
                // Dest.transform turns (dx, dy) to (dy, -dx) rot times; a source tile's destination turns it back
                int dx = x - b.cX();
                int dy = y - b.cY();
                for (int i = 0; i < rot; i++) {
                    int k = dx;
                    dx = -dy;
                    dy = k;
                }
                int tx = cx + dx;
                int ty = cy + dy;
                if (!SETT.IN_BOUNDS(tx, ty) || b.holdsPoint(tx, ty)
                        || !SETT.ROOMS().copy.copier.isPlacable(x, y, tx, ty)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static PlacableFixed current(String action) {
        PLACABLE p = VIEW.s().tools.placer.getCurrent();
        if (!(p instanceof PlacableFixed)) {
            throw new IllegalStateException("SOSDET rig: " + action + ": the tool did not open (" + p + ")");
        }
        return (PlacableFixed) p;
    }
}

package sosdet.game.cmd;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import settlement.main.SETT;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.construction.ConstructionData;
import settlement.room.main.furnisher.FurnisherItem;
import settlement.room.main.placement.RoomPlacer;
import view.tool.PLACER_TYPE;
import view.tool.PlacableMulti;

/**
 * Rebuilds a {@link PlanRecord} into the game's room placer (ADR 0003), through the placer's own calls as the planning
 * UI makes them: the room type, upgrade, wall material and wall options; the area as a drag of the area tool; each
 * furniture item in the record's order; the planned doorways. Inside the step for a Build command (every PC alike),
 * outside it for this PC's own plan after a command used the placer.
 */
final class Plans {

    private static final int NO_TICK = Integer.MIN_VALUE;

    private Plans() {
    }

    /**
     * The command's plan, strictly: false (the command is skipped on every PC alike) when a tile is no longer placable
     * or a furniture item no longer fits. The placer then holds what was placed so far; the caller clears it.
     */
    static boolean rebuildCommand(PlanRecord r) {
        RoomBlueprintImp blue = blueprint(r);
        if (!blue.constructor().usesArea()) {
            throw new IllegalStateException("SOSDET: a room plan for " + blue.key + ", which has no area");
        }
        return rebuild(r, blue, true);
    }

    /**
     * This PC's own plan, after a command used the placer or a room took some of its tiles: tiles no longer placable
     * are dropped, with the furniture and doorways on them. The undo history of the plan is lost.
     */
    static void rebuildLocal(PlanRecord r) {
        rebuild(r, blueprint(r), false);
        clearHistory(PlanStores.placer());
    }

    private static boolean rebuild(PlanRecord r, RoomBlueprintImp blue, boolean strict) {
        RoomPlacer p = PlanStores.placer();
        p.init(blue, 0);
        p.setUpgrade(r.upgrade); // init's upgrade is reset by the instance's own init (V71.44); the UI sets it after
        if (r.structure >= 0) {
            p.structure.set(Tools.building(r.structure));
        }
        p.autoWalls.set(r.autoWalls);
        p.buildOnWalls.set(r.buildOnWalls);
        try {
            return area(p, r, strict) && furniture(blue, r, strict) && walls(r, strict);
        } finally {
            resetCaches(p);
        }
    }

    /** {@code PlacableMultiTool.place}, V71.44, with the room placer's area tool over the record's tiles. */
    private static boolean area(RoomPlacer p, PlanRecord r, boolean strict) {
        if (r.area.count == 0) {
            return true;
        }
        PlacableMulti tool = (PlacableMulti) p.area();
        Tools.Area a = new Tools.Area(r.area);
        tool.finishChecking(a);
        if (tool.isPlacable(a, PLACER_TYPE.SQUARE) != null) {
            return !strict;
        }
        for (int y = a.body().y1(); y < a.body().y2(); y++) {
            for (int x = a.body().x1(); x < a.body().x2(); x++) {
                if (!a.is(x, y)) {
                    continue;
                }
                if (tool.isPlacable(x, y, a, PLACER_TYPE.SQUARE) != null) {
                    if (strict) {
                        return false;
                    }
                    continue;
                }
                tool.place(x, y, a, PLACER_TYPE.SQUARE);
            }
        }
        tool.finishPlacing(a);
        return true;
    }

    /** Each item where it was, in the record's order: its sprite data depends on the order (and draws at random). */
    private static boolean furniture(RoomBlueprintImp blue, PlanRecord r, boolean strict) {
        Room.RoomInstanceImp ins = PlanStores.instance();
        for (int k = 0; k < r.items(); k++) {
            FurnisherItem it = blue.constructor().item(r.item(k));
            if (it == null || !fits(it, r.itemX(k), r.itemY(k), ins)) {
                if (strict) {
                    return false;
                }
                continue;
            }
            SETT.ROOMS().fData.itemSet(r.itemX(k), r.itemY(k), it, ins);
        }
        return true;
    }

    /** What {@code MapDataF.itemSet} needs, or it throws: every tile in the map, each of the item's tiles free in the plan. */
    private static boolean fits(FurnisherItem it, int x1, int y1, Room.RoomInstanceImp ins) {
        for (int y = 0; y < it.height(); y++) {
            for (int x = 0; x < it.width(); x++) {
                int tx = x1 + x;
                int ty = y1 + y;
                if (!SETT.IN_BOUNDS(tx, ty)) {
                    return false;
                }
                if (it.get(x, y) == null) {
                    continue;
                }
                if (!ins.is(tx, ty) || SETT.ROOMS().fData.itemIndexx.get(tx, ty) != 0
                        || SETT.ROOMS().fData.tile.get(tx, ty) != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The planned doorways: set last, as placing area tiles clears doorways next to them. */
    private static boolean walls(PlanRecord r, boolean strict) {
        Room.RoomInstanceImp ins = PlanStores.instance();
        for (int k = 0; k < r.wallTiles(); k++) {
            if (!ins.is(r.wallX(k), r.wallY(k))) {
                if (strict) {
                    return false;
                }
                continue;
            }
            ConstructionData.dWall.set(ins, r.wallX(k), r.wallY(k), r.wallBits(k));
        }
        return true;
    }

    private static RoomBlueprintImp blueprint(PlanRecord r) {
        RoomBlueprint b = r.blueprint < 0 || r.blueprint >= SETT.ROOMS().all().size() ? null
                : SETT.ROOMS().all().get(r.blueprint);
        if (!(b instanceof RoomBlueprintImp) || ((RoomBlueprintImp) b).constructor() == null) {
            throw new IllegalStateException("SOSDET: a room plan for blueprint " + r.blueprint + ", which has no furniture");
        }
        return (RoomBlueprintImp) b;
    }

    /**
     * The placer's caches keyed on {@code GAME.updateI()} (V71.44: {@code UtilStats}, {@code UtilExtraCost},
     * {@code PlacerDoor}): the UI or an earlier command in this step may have filled them for another plan, so
     * {@code createProblem()} and the UI read fresh values after a rebuild.
     */
    private static void resetCaches(RoomPlacer p) {
        Object stats = PlanStores.get(Tools.field(RoomPlacer.class, "resources"), p);
        Tools.setInt(Tools.field(stats.getClass(), "tick"), stats, NO_TICK);
        Tools.setInt(Tools.field(p.cost().getClass(), "tick"), p.cost(), NO_TICK);
        Object door = p.door; // PlacerDoor is package-private
        Tools.setInt(Tools.field(door.getClass(), "tick"), door, NO_TICK);
    }

    /** {@code UtilHistory.clear()}, V71.44: a rebuilt plan's undo steps would undo the rebuild, not the player's. */
    private static void clearHistory(RoomPlacer p) {
        Object history = PlanStores.get(Tools.field(RoomPlacer.class, "history"), p);
        try {
            Method clear = history.getClass().getDeclaredMethod("clear");
            clear.setAccessible(true);
            clear.invoke(history);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: no method " + history.getClass().getName()
                    + ".clear() (game V71.44 expected)", e);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: cannot call " + history.getClass().getName() + ".clear()", e);
        }
    }
}

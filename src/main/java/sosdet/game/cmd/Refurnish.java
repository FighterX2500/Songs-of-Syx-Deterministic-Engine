package sosdet.game.cmd;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import settlement.job.ROOM_JOBBER;
import settlement.main.SETT;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.RoomInstance;
import settlement.room.main.TmpArea;
import settlement.room.main.construction.ConstructionData;
import settlement.room.main.furnisher.Furnisher;
import settlement.room.main.furnisher.FurnisherItem;
import settlement.room.main.placement.PLACEMENT;
import settlement.room.main.placement.RoomPlacer;
import settlement.room.main.util.RoomState;
import snake2d.util.datatypes.COORDINATE;
import sosdet.Hooks;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import settlement.tilemap.terrain.TBuilding;
import view.main.VIEW;
import view.tool.PLACER_TYPE;
import view.tool.PlacableMulti;

/**
 * Refurnishing a room in a session (ADR 0003, owner 2026-10-23). The player plans over the room while it keeps working:
 * the plan is made from the room as vanilla's refurnish makes it, but the room is not removed, and the stash puts the
 * room's own tile values back around each step ({@link PlanStash}). Build is one command, 6008 roomRefurnish, that
 * every PC applies at one step as vanilla's refurnish would have ended: the room removed and taken over by the placer,
 * the plan's edits made (refunds included), the result created. Leaving the tool without Build changes nothing. Its
 * trash button removes a room as the room panel's delete button does, and a construction site as vanilla's trash does.
 */
public final class Refurnish {

    /** {@code RoomInstance.canRemoveAndRemoveAction}, V71.44: a room type's own refusal (decomposing bodies). */
    private static final Method CAN_REMOVE = canRemoveMethod();

    private Refurnish() {
    }

    // ------------------------------------------------------------------ hooks (this PC's player, outside the step)

    /**
     * Hook in {@code UIRoomPlacer.init(RoomInstanceImp)}, the refurnish button of a room or a construction site: in a
     * session (true), the room only gets planned over. A room type's own refusal comes first, with its notice, as in
     * vanilla. The UI then opens the area tool if a plan was made ({@link #planning()}).
     */
    public static boolean start(Room.RoomInstanceImp room) {
        if (!Commands.capturing()) {
            return false;
        }
        if (room instanceof RoomInstance && !canRemove((RoomInstance) room)) {
            return true;
        }
        Hooks.planStash().startRefurnish(room);
        return true;
    }

    /** Whether this PC's player is refurnishing a room. */
    public static boolean planning() {
        return Hooks.planStash().refurnished() != null;
    }

    /**
     * Hook in {@code Config.deactivateAction}, the room tool closing: in a session (true), a refurnish plan ends without
     * a Build (vanilla builds it), or with the trash button ({@code build} false) the room is removed.
     */
    public static boolean leave(boolean build) {
        if (!Commands.capturing()) {
            return false;
        }
        PlanStash stash = Hooks.planStash();
        Room.RoomInstanceImp room = stash.refurnished();
        if (room == null) {
            return false;
        }
        if (!build) {
            trash(room);
        }
        stash.endRefurnish();
        return true;
    }

    /**
     * Hook at the top of {@code RoomPlacer.init}: the UI starting another plan or clearing the placer ends a refurnish
     * plan first, so that the placer's own clearing never touches the room's tiles.
     */
    public static void placerInit() {
        if (Hooks.outsideStep()) {
            Hooks.planStash().placerInit();
        }
    }

    /**
     * Hook in {@code JobRoom}: the room a construction job at a tile belongs to. A plan over a construction site lies on
     * the site's tiles between steps while the site's jobs stay on the map, so the renderer and the UI find the plan
     * there, or nothing on a tile the plan dropped: they get the site. The step reads the map as vanilla: the plan is
     * lifted then, and it is this PC's alone.
     */
    public static Room jobRoom(int tx, int ty) {
        Room room = SETT.ROOMS().map.get(tx, ty);
        if (room instanceof ROOM_JOBBER || !Hooks.outsideStep()) {
            return room;
        }
        Room site = Hooks.planStash().siteAt(tx, ty);
        return site != null ? site : room;
    }

    /** From {@link Tools#roomBuild}: Build on a refurnish plan, the room and the whole plan as one command. */
    static void build(PlanStash stash) {
        CmdWriter w = Args.room(new CmdWriter(Tools.ROOM_REFURNISH), stash.refurnished());
        stash.capture(w);
        Commands.capture(w);
        stash.endRefurnish();
    }

    /**
     * The trash button: a room goes as the room panel's delete button removes it (command 2001, owner 2026-10-23); a
     * construction site, which has no such button, as vanilla's refurnish trash removes it (an empty plan).
     */
    private static void trash(Room.RoomInstanceImp room) {
        if (room instanceof RoomInstance) {
            Rooms.remove((RoomInstance) room, room.mX(), room.mY(), true, false);
            return;
        }
        CmdWriter w = Args.room(new CmdWriter(Tools.ROOM_REFURNISH), room);
        new PlanRecord(room.constructor().blue().index(), 0, -1, false, false, TileArea.of(0, 0, 0, 0, (x, y) -> false),
                new int[0], new int[0]).write(w);
        Commands.capture(w);
    }

    // ------------------------------------------------------------------ the plan over the room (this PC)

    /**
     * Makes the plan from the room as {@code UIRoomPlacer.init(RoomInstanceImp)} does (V71.44), without removing the
     * room: its tiles get the room data removal leaves (a room: every tile floored, every item built; a construction
     * site: its progress per tile), and the placer takes them over through the game's temporary area. Every write
     * lands in the tile stores the stash swaps; the lights a takeover removes stay outside the step (patch 97).
     */
    static void plan(Room.RoomInstanceImp room) {
        boolean site = !(room instanceof RoomInstance);
        for (COORDINATE c : room.body()) {
            if (!room.is(c.x(), c.y())) {
                continue;
            }
            if (site) { // ConstructionInstance.remove
                SETT.ROOMS().data.set(room, c, ConstructionData.dData.get(c.x(), c.y()));
                continue;
            }
            SETT.ROOMS().data.set(room, c, 0); // RoomInstance.remove
            if (SETT.ROOMS().fData.item.get(c) != null) {
                ConstructionData.dConstructed.set(room, c, 1);
            }
            ConstructionData.dFloored.set(room, c, 1);
        }
        RoomPlacer p = PlanStores.placer();
        TmpArea a = SETT.ROOMS().tmpArea(p);
        a.set(room, room.mX(), room.mY());
        p.reconstruct(a, room.upgrade(), 0, null, room.constructor().blue());
    }

    /** This PC's refurnish plan after a command used the placer: made from the room again, its edits lenient. */
    static void rebuildLocal(Room.RoomInstanceImp room, PlanRecord plan, PlanStash stash) {
        plan(room);
        RoomPlacer p = PlanStores.placer();
        edit(p, stash.recordPlacer(), plan, false);
        Plans.clearHistory(p);
    }

    /**
     * The plan's edits on the room the placer took over, through the placer's own calls in a fixed order: the dropped
     * furniture (its refund paid as the item tool's undo pays it), the dropped tiles (the shrink tool: floor cleared and
     * refunded), the plan's settings, its new tiles and new furniture, then its doorways on every tile. Strict for a
     * command (false when anything fails), lenient for this PC's own plan.
     */
    static boolean edit(RoomPlacer p, PlanRecord room, PlanRecord plan, boolean strict) {
        RefurnishEdits e = RefurnishEdits.of(room, plan);
        RoomBlueprintImp blue = p.blueprint();
        Furnisher f = blue.constructor();
        try {
            for (int k = 0; k < e.droppedItems(); k++) {
                FurnisherItem it = f.item(e.droppedItem(k));
                PlacableMulti undo = (PlacableMulti) p.item(it.group.index()).getUndo();
                undo.place(e.droppedX(k) + it.firstX(), e.droppedY(k) + it.firstY(), null, null);
            }
            shrink(p, e.droppedTiles);
            p.setUpgrade(plan.upgrade);
            if (plan.structure >= 0) {
                p.structure.set(Tools.building(plan.structure));
            }
            p.autoWalls.set(plan.autoWalls);
            p.buildOnWalls.set(plan.buildOnWalls);
            if (!Plans.area(p, e.added, strict) || !Plans.furniture(blue, e.added, strict)) {
                return false;
            }
            Room.RoomInstanceImp ins = PlanStores.instance();
            TileArea a = plan.area;
            for (int y = a.y1; y < a.y1 + a.height; y++) {
                for (int x = a.x1; x < a.x1 + a.width; x++) {
                    if (a.is(x, y) && ins.is(x, y)) {
                        ConstructionData.dWall.set(ins, x, y, 0); // the room's doorways the plan closed
                    }
                }
            }
            return Plans.walls(plan, strict);
        } finally {
            Plans.resetCaches(p);
        }
    }

    /** {@code PlacableMultiTool.place}, V71.44, with the room placer's shrink tool over the given tiles. */
    private static void shrink(RoomPlacer p, TileArea tiles) {
        if (tiles.count == 0) {
            return;
        }
        PlacableMulti tool = (PlacableMulti) p.area().getUndo();
        Tools.Area a = new Tools.Area(tiles);
        tool.finishChecking(a);
        for (int y = a.body().y1(); y < a.body().y2(); y++) {
            for (int x = a.body().x1(); x < a.body().x2(); x++) {
                if (a.is(x, y) && tool.isPlacable(x, y, a, PLACER_TYPE.SQUARE) == null) {
                    tool.place(x, y, a, PLACER_TYPE.SQUARE);
                }
            }
        }
        tool.finishPlacing(a);
    }

    /** The refurnished room is gone (a command removed it): the room tool closes, as its plan has ended. */
    static void closeTool() {
        if (VIEW.s().ui.placer.isActive()) {
            VIEW.s().tools.placer.deactivate();
        }
    }

    // ------------------------------------------------------------------ the applier (every PC, inside the step)

    /**
     * 6008 roomRefurnish: as {@code UIRoomPlacer.init(RoomInstanceImp)} and the Build that ends it (V71.44). Skipped on
     * every PC alike when the room is gone or another room took the plan's new tiles. If the plan still can't be built
     * once the room is removed (a neighbour changed within the command's delay), the placer is cleared with refunds,
     * as vanilla ends a refurnish that has a problem (owner 2026-10-23); it then counts as skipped. An empty plan is the
     * trash button on a construction site, which ends the same way but counts as applied.
     */
    static boolean apply(CmdReader r) {
        Room.RoomInstanceImp room = Args.anyRoom(r);
        PlanRecord plan = PlanRecord.read(r);
        PlanStash stash = Hooks.planStash();
        stash.meet();
        if (room == null || room.constructor() == null || !room.constructor().usesArea()
                || room.constructor().blue().index() != plan.blueprint || !newTilesFree(room, plan)) {
            return false;
        }
        RoomPlacer p = PlanStores.placer();
        TBuilding ownStructure = p.structure.get();
        boolean ownWalls = p.autoWalls.getBool();
        boolean ownOnWalls = p.buildOnWalls.is();
        try {
            RoomBlueprintImp b = room.constructor().blue();
            RoomState state = room.makeState(room.mX(), room.mY(), false);
            int up = room.upgrade();
            int deg = room.degrader(room.mX(), room.mY()) == null ? 0 : room.degrader(room.mX(), room.mY()).getData();
            TmpArea a = room.remove(room.mX(), room.mY(), false, p, false);
            if (a == null) {
                return false;
            }
            if (a.area() <= 0) {
                a.clear();
                return false;
            }
            p.reconstruct(a, up, deg, state, b);
            if (plan.area.count == 0) {
                return true; // the trash button on a construction site: the finally clears it with refunds
            }
            if (!edit(p, stash.recordPlacer(), plan, true) || p.createProblem() != null) {
                return false; // the finally clears the plan with refunds
            }
            p.create();
            return true;
        } finally {
            p.init(null, 0);
            if (ownStructure != null) {
                p.structure.set(ownStructure);
            }
            p.autoWalls.set(ownWalls);
            p.buildOnWalls.set(ownOnWalls);
        }
    }

    /** The plan's tiles outside the room: still free and placable, so that removing the room is safe. */
    private static boolean newTilesFree(Room.RoomInstanceImp room, PlanRecord plan) {
        RoomBlueprintImp blue = room.constructor().blue();
        TileArea a = plan.area;
        for (int y = a.y1; y < a.y1 + a.height; y++) {
            for (int x = a.x1; x < a.x1 + a.width; x++) {
                if (!a.is(x, y) || room.is(x, y)) {
                    continue;
                }
                if (PLACEMENT.placable(x, y, blue, plan.buildOnWalls) != null
                        || blue.constructor().placable(x, y, null, null) != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean canRemove(RoomInstance room) {
        try {
            return (Boolean) CAN_REMOVE.invoke(room, room.mX(), room.mY(), false, room, false);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: cannot call " + CAN_REMOVE, e);
        }
    }

    private static Method canRemoveMethod() {
        try {
            Method m = RoomInstance.class.getDeclaredMethod("canRemoveAndRemoveAction", int.class, int.class,
                    boolean.class, Object.class, boolean.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: no method RoomInstance.canRemoveAndRemoveAction (game V71.44 expected)",
                    e);
        }
    }
}

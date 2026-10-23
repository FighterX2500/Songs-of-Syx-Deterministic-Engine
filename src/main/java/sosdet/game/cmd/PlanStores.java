package sosdet.game.cmd;

import java.lang.reflect.Field;

import settlement.main.SETT;
import settlement.room.main.MapDataF;
import settlement.room.main.ROOMS;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.RoomsMap;
import settlement.room.main.furnisher.Furnisher;
import settlement.room.main.placement.RoomPlacer;
import snake2d.util.datatypes.RECTANGLE;
import snake2d.util.sets.Bitmap1D;

/**
 * The game side of {@link PlanStash}: the shared tile stores that hold the room placer's plan, read and written as raw
 * arrays (V71.44: {@code RoomsMap.roomI}, {@code MapRoomData.Data.dataI}, and {@code MapDataF}'s furniture bytes and
 * bits). Their setters can't be used: {@code RoomsMap.set} and {@code clear} throw on a mismatch and wipe the other
 * stores. The fields are resolved once per session, the arrays again whenever the game's room stores change (a new
 * game); a lift reads them per tile without allocating.
 */
final class PlanStores {

    private final Field fRoom = Tools.field(RoomsMap.class, "roomI");
    private final Field fData = Tools.field(dataClass(), "dataI");
    private final Field fItem = Tools.field(MapDataF.class, "itemI");
    private final Field fTile = Tools.field(MapDataF.class, "tileI");
    private final Field fSprite = Tools.field(MapDataF.class, "spriteDataI");
    private final Field fSprite2 = Tools.field(MapDataF.class, "spriteDataI2");
    private final Field fMaster = Tools.field(MapDataF.class, "masterTileI");
    private final Field fCandle = Tools.field(MapDataF.class, "candleI");

    /** The room stores the arrays below belong to. */
    private ROOMS rooms;
    private int[] room;
    private int[] data;
    private byte[] item;
    private byte[] tile;
    private byte[] sprite;
    private byte[] sprite2;
    private Bitmap1D master;
    private Bitmap1D candle;

    /** Whether this PC's player has the room placer open on a room type. */
    boolean planning() {
        return placer().blueprint() != null;
    }

    /**
     * Reads the plan's tiles into {@code t}, row by row; with {@code lift}, each tile then holds what the simulation
     * holds without a plan: no room, no room data, no furniture (as {@code RoomsMap.clear} leaves a tile).
     */
    void read(PlanTiles t, boolean lift) {
        if (!planning()) {
            return;
        }
        read(t, instance(), lift);
    }

    /** As {@link #read(PlanTiles, boolean)}, for the tiles of any room (a refurnished room's own layer). */
    void read(PlanTiles t, Room.RoomInstanceImp ins, boolean lift) {
        arrays();
        int ri = ins.index();
        RECTANGLE b = ins.body();
        int y2 = Math.min(b.y2(), SETT.THEIGHT);
        int x2 = Math.min(b.x2(), SETT.TWIDTH);
        for (int y = Math.max(b.y1(), 0); y < y2; y++) {
            for (int x = Math.max(b.x1(), 0); x < x2; x++) {
                int i = x + y * SETT.TWIDTH;
                if (room[i] != ri) {
                    continue;
                }
                t.add(i, room[i], data[i], item[i], tile[i], sprite[i], sprite2[i], master.get(i), candle.get(i));
                if (lift) {
                    room[i] = 0;
                    data[i] = 0;
                    item[i] = 0;
                    tile[i] = 0;
                    sprite[i] = 0;
                    sprite2[i] = 0;
                    master.set(i, false);
                    candle.set(i, false);
                }
            }
        }
    }

    /** True when no room took any of the lifted tiles during the steps. */
    boolean free(PlanTiles t) {
        arrays();
        for (int k = 0; k < t.count(); k++) {
            if (room[t.tile(k)] != 0) {
                return false;
            }
        }
        return true;
    }

    /** Puts the lifted tiles back as they were. */
    void writeBack(PlanTiles t) {
        arrays();
        for (int k = 0; k < t.count(); k++) {
            int i = t.tile(k);
            room[i] = t.room(k);
            data[i] = t.data(k);
            item[i] = t.item(k);
            tile[i] = t.itemTile(k);
            sprite[i] = t.sprite(k);
            sprite2[i] = t.sprite2(k);
            master.set(i, t.master(k));
            candle.set(i, t.candle(k));
        }
    }

    /** The record of the plan whose tiles {@code t} holds, with the placer's own state. */
    PlanRecord record(PlanTiles t) {
        RoomPlacer p = placer();
        RoomBlueprintImp blue = p.blueprint();
        Furnisher f = blue.constructor();
        return PlanRecord.of(t, SETT.TWIDTH, blue.index(), instance().upgrade(), Tools.buildingIndex(p.structure.get()),
                p.autoWalls.getBool(), p.buildOnWalls.is(), new PlanRecord.Furniture() {
                    @Override
                    public int firstX(int i) {
                        return f.item(i).firstX();
                    }

                    @Override
                    public int firstY(int i) {
                        return f.item(i).firstY();
                    }
                });
    }

    /** Whether the room still stands: the room store still maps its index to it. */
    static boolean exists(Room.RoomInstanceImp room) {
        return SETT.ROOMS().map.getByIndex(room.index()) == room;
    }

    static RoomPlacer placer() {
        return SETT.ROOMS().placement.placer;
    }

    /** The placer's plan: a singleton room of the hidden blueprint {@code _PLACEMENT} (V71.44, package-private). */
    static Room.RoomInstanceImp instance() {
        return (Room.RoomInstanceImp) SETT.ROOMS().placement.embryo;
    }

    private void arrays() {
        ROOMS r = SETT.ROOMS();
        if (r == rooms) {
            return;
        }
        room = (int[]) get(fRoom, r.map);
        data = (int[]) get(fData, r.data);
        item = (byte[]) get(fItem, r.fData);
        tile = (byte[]) get(fTile, r.fData);
        sprite = (byte[]) get(fSprite, r.fData);
        sprite2 = (byte[]) get(fSprite2, r.fData);
        master = (Bitmap1D) get(fMaster, r.fData);
        candle = (Bitmap1D) get(fCandle, r.fData);
        rooms = r;
    }

    /** {@code MapRoomData.Data}, package-private in V71.44: the class of {@code ROOMS.data}. */
    private static Class<?> dataClass() {
        try {
            return Class.forName("settlement.room.main.MapRoomData$Data", false, RoomsMap.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SOSDET: no class settlement.room.main.MapRoomData$Data (game V71.44 expected)",
                    e);
        }
    }

    static Object get(Field f, Object o) {
        try {
            return f.get(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + f, e);
        }
    }
}

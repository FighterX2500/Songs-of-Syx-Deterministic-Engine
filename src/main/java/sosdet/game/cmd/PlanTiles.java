package sosdet.game.cmd;

/**
 * The plan tiles lifted out of the shared tile stores (ADR 0003): for each, its tile index and what the stores held
 * there (room index, room data, furniture bytes and bits). Preallocated for the largest plan, so a lift allocates
 * nothing. Game-free; the game adapter ({@link PlanStash}) reads and writes the stores.
 */
final class PlanTiles {

    /** The bits of the room data that hold the planned doorways ({@code ConstructionData.dWall}, V71.44). */
    private static final int WALL_SHIFT = 4;
    private static final int WALL_MASK = 0xFF;

    private final int[] tile;
    private final int[] room;
    private final int[] data;
    private final byte[] item;
    private final byte[] itemTile;
    private final byte[] sprite;
    private final byte[] sprite2;
    private final boolean[] master;
    private final boolean[] candle;
    private int count;

    PlanTiles(int capacity) {
        tile = new int[capacity];
        room = new int[capacity];
        data = new int[capacity];
        item = new byte[capacity];
        itemTile = new byte[capacity];
        sprite = new byte[capacity];
        sprite2 = new byte[capacity];
        master = new boolean[capacity];
        candle = new boolean[capacity];
    }

    /** Keeps one tile's values; tiles are added in scan order (row by row). */
    void add(int t, int roomI, int roomData, byte itemI, byte tileI, byte spriteData, byte spriteData2,
            boolean isMaster, boolean isCandle) {
        if (count == tile.length) {
            throw new IllegalStateException("SOSDET: a room plan of more than " + tile.length + " tiles");
        }
        tile[count] = t;
        room[count] = roomI;
        data[count] = roomData;
        item[count] = itemI;
        itemTile[count] = tileI;
        sprite[count] = spriteData;
        sprite2[count] = spriteData2;
        master[count] = isMaster;
        candle[count] = isCandle;
        count++;
    }

    void clear() {
        count = 0;
    }

    int count() {
        return count;
    }

    int tile(int i) {
        return tile[i];
    }

    int room(int i) {
        return room[i];
    }

    int data(int i) {
        return data[i];
    }

    byte item(int i) {
        return item[i];
    }

    byte itemTile(int i) {
        return itemTile[i];
    }

    byte sprite(int i) {
        return sprite[i];
    }

    byte sprite2(int i) {
        return sprite2[i];
    }

    boolean master(int i) {
        return master[i];
    }

    boolean candle(int i) {
        return candle[i];
    }

    /** The doorways planned from this tile, the bits of {@code ConstructionData.dWall}. */
    int wallBits(int i) {
        return (data[i] >>> WALL_SHIFT) & WALL_MASK;
    }
}

package sosdet.game.cmd;

import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;

/**
 * A whole area-room plan (ADR 0003): what the room placer holds when the player presses Build, and what a local plan
 * is rebuilt from after a command used the placer inside the step. Game-free, so the codec is tested on its own.
 *
 * <p>Encoded as: blueprint (index in {@code SETT.ROOMS().all()}), upgrade, structure (index in
 * {@code SETT.TERRAIN().BUILDINGS.all()}, -1 for none), auto walls, build on walls, the area ({@link TileArea}), the
 * furniture count and each item as origin x, origin y, item index (the furnisher's {@code item(i)}, from 1), in the
 * scan order of their master tiles, then the count of plan tiles with wall bits and each as x, y, bits (the 8 bits of
 * {@code ConstructionData.dWall}: the doorways planned towards that tile's neighbours), in scan order. The furniture
 * order is part of the record: sprite data depends on the order items are placed (ADR 0003).
 */
final class PlanRecord {

    /** The furniture item indices a furnisher can hold (one byte per tile in {@code MapDataF}). */
    private static final int MAX_ITEM = 255;
    /** The wall bits of {@code ConstructionData.dWall}, V71.44. */
    private static final int MAX_WALL_BITS = 0xFF;

    final int blueprint;
    final int upgrade;
    final int structure;
    final boolean autoWalls;
    final boolean buildOnWalls;
    final TileArea area;
    /** Origin x, origin y and item index of each furniture item, three ints per item. */
    private final int[] items;
    /** X, y and wall bits of each plan tile that has any, three ints per tile. */
    private final int[] walls;

    PlanRecord(int blueprint, int upgrade, int structure, boolean autoWalls, boolean buildOnWalls, TileArea area,
            int[] items, int[] walls) {
        if (items.length % 3 != 0 || walls.length % 3 != 0) {
            throw new IllegalArgumentException("SOSDET: a plan's items (" + items.length + " ints) and walls ("
                    + walls.length + " ints) come in threes");
        }
        this.blueprint = blueprint;
        this.upgrade = upgrade;
        this.structure = structure;
        this.autoWalls = autoWalls;
        this.buildOnWalls = buildOnWalls;
        this.area = area;
        this.items = items;
        this.walls = walls;
        for (int k = 0; k < items(); k++) {
            check("item index", item(k), 1, MAX_ITEM);
        }
        for (int k = 0; k < wallTiles(); k++) {
            check("wall bits", wallBits(k), 1, MAX_WALL_BITS);
        }
    }

    /** Where a furniture item's master tile lies from its origin (the furnisher's {@code item(i).firstX/firstY}). */
    interface Furniture {
        int firstX(int item);

        int firstY(int item);
    }

    /**
     * The record of a plan whose tiles {@code t} holds, in scan order, on a map {@code mapWidth} tiles wide: the area is
     * their bounding rectangle, the furniture comes from their master tiles, the walls from their room data.
     */
    static PlanRecord of(PlanTiles t, int mapWidth, int blueprint, int upgrade, int structure, boolean autoWalls,
            boolean buildOnWalls, Furniture furniture) {
        int n = t.count();
        int x1 = Integer.MAX_VALUE;
        int y1 = Integer.MAX_VALUE;
        int x2 = -1;
        int y2 = -1;
        int masters = 0;
        int walled = 0;
        for (int i = 0; i < n; i++) {
            int x = t.tile(i) % mapWidth;
            int y = t.tile(i) / mapWidth;
            x1 = Math.min(x1, x);
            y1 = Math.min(y1, y);
            x2 = Math.max(x2, x);
            y2 = Math.max(y2, y);
            masters += t.master(i) ? 1 : 0;
            walled += t.wallBits(i) != 0 ? 1 : 0;
        }
        if (n == 0) {
            x1 = y1 = 0;
        }
        int w = x2 - x1 + 1;
        int h = y2 - y1 + 1;
        boolean[] in = new boolean[w * h];
        int[] items = new int[masters * 3];
        int[] walls = new int[walled * 3];
        int k = 0;
        int m = 0;
        for (int i = 0; i < n; i++) {
            int x = t.tile(i) % mapWidth;
            int y = t.tile(i) / mapWidth;
            in[(x - x1) + (y - y1) * w] = true;
            if (t.master(i)) {
                int item = t.item(i) & 0xFF;
                items[k++] = x - furniture.firstX(item);
                items[k++] = y - furniture.firstY(item);
                items[k++] = item;
            }
            if (t.wallBits(i) != 0) {
                walls[m++] = x;
                walls[m++] = y;
                walls[m++] = t.wallBits(i);
            }
        }
        int ox = x1;
        int oy = y1;
        TileArea area = TileArea.of(x1, y1, w, h, (x, y) -> in[(x - ox) + (y - oy) * w]);
        return new PlanRecord(blueprint, upgrade, structure, autoWalls, buildOnWalls, area, items, walls);
    }

    int items() {
        return items.length / 3;
    }

    int itemX(int k) {
        return items[k * 3];
    }

    int itemY(int k) {
        return items[k * 3 + 1];
    }

    int item(int k) {
        return items[k * 3 + 2];
    }

    int wallTiles() {
        return walls.length / 3;
    }

    int wallX(int k) {
        return walls[k * 3];
    }

    int wallY(int k) {
        return walls[k * 3 + 1];
    }

    int wallBits(int k) {
        return walls[k * 3 + 2];
    }

    void write(CmdWriter w) {
        w.i(blueprint).i(upgrade).i(structure).b(autoWalls).b(buildOnWalls);
        area.write(w);
        w.i(items());
        for (int v : items) {
            w.i(v);
        }
        w.i(wallTiles());
        for (int v : walls) {
            w.i(v);
        }
    }

    static PlanRecord read(CmdReader r) {
        int blueprint = r.i();
        int upgrade = r.i();
        int structure = r.i();
        boolean autoWalls = r.b();
        boolean buildOnWalls = r.b();
        TileArea area = TileArea.read(r);
        int[] items = new int[check("furniture count", r.i(), 0, area.count) * 3];
        for (int k = 0; k < items.length; k++) {
            items[k] = r.i();
        }
        int[] walls = new int[check("wall tile count", r.i(), 0, area.count) * 3];
        for (int k = 0; k < walls.length; k++) {
            walls[k] = r.i();
        }
        return new PlanRecord(blueprint, upgrade, structure, autoWalls, buildOnWalls, area, items, walls);
    }

    private static int check(String what, int v, int min, int max) {
        if (v < min || v > max) {
            throw new IllegalArgumentException("SOSDET: a room plan with " + what + " " + v + " (" + min + " to " + max
                    + ")");
        }
        return v;
    }
}

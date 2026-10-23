package sosdet.game.cmd;

import java.util.Arrays;

/**
 * What a refurnish's Build changes in the room (ADR 0003), from the room as the placer takes it over and the player's
 * plan: the room's furniture and tiles the plan no longer has, and the plan's tiles and furniture the room doesn't have.
 * What both have is kept, so it stays built and floored: furniture of the same item at the same origin, and tiles in
 * both areas. (Vanilla counts an item removed and put back in place as new; here it is kept.) Game-free.
 */
final class RefurnishEdits {

    /** The room's furniture the plan dropped: origin x, origin y and item index, three ints each, in the room's order. */
    final int[] droppedItems;
    /** The room's tiles the plan dropped. */
    final TileArea droppedTiles;
    /** The plan's new tiles and new furniture (in the plan's order), with the plan's settings and all its doorways. */
    final PlanRecord added;

    private RefurnishEdits(int[] droppedItems, TileArea droppedTiles, PlanRecord added) {
        this.droppedItems = droppedItems;
        this.droppedTiles = droppedTiles;
        this.added = added;
    }

    static RefurnishEdits of(PlanRecord room, PlanRecord plan) {
        TileArea r = room.area;
        TileArea p = plan.area;
        TileArea droppedTiles = TileArea.of(r.x1, r.y1, r.width, r.height, (x, y) -> r.is(x, y) && !p.is(x, y));
        TileArea newTiles = TileArea.of(p.x1, p.y1, p.width, p.height, (x, y) -> p.is(x, y) && !r.is(x, y));
        int[] walls = new int[plan.wallTiles() * 3];
        for (int k = 0; k < plan.wallTiles(); k++) {
            walls[k * 3] = plan.wallX(k);
            walls[k * 3 + 1] = plan.wallY(k);
            walls[k * 3 + 2] = plan.wallBits(k);
        }
        PlanRecord added = new PlanRecord(plan.blueprint, plan.upgrade, plan.structure, plan.autoWalls,
                plan.buildOnWalls, newTiles, missing(plan, room), walls);
        return new RefurnishEdits(missing(room, plan), droppedTiles, added);
    }

    int droppedItems() {
        return droppedItems.length / 3;
    }

    int droppedX(int k) {
        return droppedItems[k * 3];
    }

    int droppedY(int k) {
        return droppedItems[k * 3 + 1];
    }

    int droppedItem(int k) {
        return droppedItems[k * 3 + 2];
    }

    /** The furniture of {@code from} that {@code other} doesn't have, in {@code from}'s order. */
    private static int[] missing(PlanRecord from, PlanRecord other) {
        int[] out = new int[from.items() * 3];
        int n = 0;
        for (int k = 0; k < from.items(); k++) {
            if (has(other, from.itemX(k), from.itemY(k), from.item(k))) {
                continue;
            }
            out[n++] = from.itemX(k);
            out[n++] = from.itemY(k);
            out[n++] = from.item(k);
        }
        return Arrays.copyOf(out, n);
    }

    private static boolean has(PlanRecord r, int x, int y, int item) {
        for (int k = 0; k < r.items(); k++) {
            if (r.itemX(k) == x && r.itemY(k) == y && r.item(k) == item) {
                return true;
            }
        }
        return false;
    }
}

package sosdet.game.cmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What a refurnish's Build changes in the room (ADR 0003): dropped, kept and added furniture and tiles. */
class RefurnishEditsTest {

    private static final int BLUE = 42;

    /** A 4 by 3 room at 10,20 with items 5 at 10,20 and 7 at 12,20 and 9 at 10,22. */
    private static PlanRecord room() {
        return new PlanRecord(BLUE, 0, 3, true, false, TileArea.of(10, 20, 4, 3, (x, y) -> true),
                new int[] {10, 20, 5, 12, 20, 7, 10, 22, 9}, new int[0]);
    }

    @Test
    void anUnchangedPlanChangesNothing() {
        RefurnishEdits e = RefurnishEdits.of(room(), room());
        assertEquals(0, e.droppedItems());
        assertEquals(0, e.droppedTiles.count);
        assertEquals(0, e.added.area.count);
        assertEquals(0, e.added.items());
    }

    @Test
    void droppedFurnitureAndTilesKeepTheRoomsOrder() {
        // The plan drops the right column (13,*), item 5 and item 9; it keeps item 7
        PlanRecord plan = new PlanRecord(BLUE, 0, 3, true, false, TileArea.of(10, 20, 3, 3, (x, y) -> true),
                new int[] {12, 20, 7}, new int[0]);
        RefurnishEdits e = RefurnishEdits.of(room(), plan);
        assertEquals(2, e.droppedItems());
        assertEquals(5, e.droppedItem(0));
        assertEquals(10, e.droppedX(0));
        assertEquals(20, e.droppedY(0));
        assertEquals(9, e.droppedItem(1));
        assertEquals(22, e.droppedY(1));
        assertEquals(3, e.droppedTiles.count);
        for (int y = 20; y < 23; y++) {
            assertTrue(e.droppedTiles.is(13, y));
            assertFalse(e.droppedTiles.is(12, y));
        }
        assertEquals(0, e.added.area.count);
        assertEquals(0, e.added.items());
    }

    @Test
    void newTilesAndFurnitureKeepThePlansOrderAndSettings() {
        // One more row below, a new item 4 there, then a new item 11 inside; walls on two tiles; upgrade 1, no walls
        PlanRecord plan = new PlanRecord(BLUE, 1, -1, false, true, TileArea.of(10, 20, 4, 4, (x, y) -> true),
                new int[] {10, 23, 4, 10, 20, 5, 12, 20, 7, 10, 22, 9, 11, 21, 11},
                new int[] {10, 23, 0b0100, 13, 21, 0b0001});
        RefurnishEdits e = RefurnishEdits.of(room(), plan);
        assertEquals(0, e.droppedItems());
        assertEquals(0, e.droppedTiles.count);
        assertEquals(4, e.added.area.count);
        for (int x = 10; x < 14; x++) {
            assertTrue(e.added.area.is(x, 23));
            assertFalse(e.added.area.is(x, 22), "a kept tile is not added again");
        }
        assertEquals(2, e.added.items());
        assertEquals(4, e.added.item(0));
        assertEquals(23, e.added.itemY(0));
        assertEquals(11, e.added.item(1));
        assertEquals(BLUE, e.added.blueprint);
        assertEquals(1, e.added.upgrade);
        assertEquals(-1, e.added.structure);
        assertFalse(e.added.autoWalls);
        assertTrue(e.added.buildOnWalls);
        assertEquals(2, e.added.wallTiles(), "every doorway of the plan, on kept tiles too");
        assertEquals(13, e.added.wallX(1));
        assertEquals(0b0001, e.added.wallBits(1));
    }

    @Test
    void movedOrTurnedFurnitureIsDroppedAndAddedAgain() {
        // Item 7 moves one tile left; item 9 at its spot becomes item 8 (another rotation or size)
        PlanRecord plan = new PlanRecord(BLUE, 0, 3, true, false, TileArea.of(10, 20, 4, 3, (x, y) -> true),
                new int[] {10, 20, 5, 11, 20, 7, 10, 22, 8}, new int[0]);
        RefurnishEdits e = RefurnishEdits.of(room(), plan);
        assertEquals(2, e.droppedItems());
        assertEquals(7, e.droppedItem(0));
        assertEquals(12, e.droppedX(0));
        assertEquals(9, e.droppedItem(1));
        assertEquals(2, e.added.items());
        assertEquals(7, e.added.item(0));
        assertEquals(11, e.added.itemX(0));
        assertEquals(8, e.added.item(1));
    }
}

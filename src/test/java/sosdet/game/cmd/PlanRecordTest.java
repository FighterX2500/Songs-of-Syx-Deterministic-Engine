package sosdet.game.cmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;

/** A whole area-room plan as a command argument, and as this PC's own plan is recorded from its lifted tiles (ADR 0003). */
class PlanRecordTest {

    private static final int MAP = 100;
    /** Tools.ROOM_BUILD */
    private static final int ROOM_BUILD = 6007;

    /** Every furniture item of these tests has its master tile one tile right of its origin. */
    private static final PlanRecord.Furniture ONE_RIGHT = new PlanRecord.Furniture() {
        @Override
        public int firstX(int item) {
            return 1;
        }

        @Override
        public int firstY(int item) {
            return 0;
        }
    };

    private static PlanRecord roundTrip(PlanRecord r) {
        CmdWriter out = new CmdWriter(ROOM_BUILD);
        r.write(out);
        CmdReader in = new CmdReader(out.bytes());
        PlanRecord back = PlanRecord.read(in);
        in.end();
        return back;
    }

    private static void add(PlanTiles t, int x, int y, int data, int item, boolean master) {
        t.add(x + y * MAP, 7, data, (byte) item, (byte) 1, (byte) 3, (byte) 4, master, false);
    }

    /** An L-shaped plan: a 3 by 2 block and one tile below its left end, with two items and two doorways. */
    private static PlanTiles lShape() {
        PlanTiles t = new PlanTiles(16);
        add(t, 10, 20, 0, 0, false);
        add(t, 11, 20, 0b0011_0000, 5, true); // item 5's master; two orthogonal doorways
        add(t, 12, 20, 0, 5, false);
        add(t, 10, 21, 0, 0, false);
        add(t, 11, 21, 0, 0, false);
        add(t, 12, 21, 0b1000_0000_0000, 200, true); // item 200's master; a diagonal doorway
        add(t, 10, 22, 0b0010, 0, false); // constructed bit only: no doorway
        return t;
    }

    @Test
    void aPlanIsRecordedFromItsLiftedTilesInScanOrder() {
        PlanRecord r = PlanRecord.of(lShape(), MAP, 42, 2, 3, true, false, ONE_RIGHT);
        assertEquals(42, r.blueprint);
        assertEquals(2, r.upgrade);
        assertEquals(3, r.structure);
        assertTrue(r.autoWalls);
        assertFalse(r.buildOnWalls);
        assertEquals(10, r.area.x1);
        assertEquals(20, r.area.y1);
        assertEquals(3, r.area.width);
        assertEquals(3, r.area.height);
        assertEquals(7, r.area.count);
        assertTrue(r.area.is(10, 22));
        assertFalse(r.area.is(11, 22));
        assertEquals(2, r.items());
        assertEquals(10, r.itemX(0), "origin: the master tile less the item's first tile");
        assertEquals(20, r.itemY(0));
        assertEquals(5, r.item(0));
        assertEquals(11, r.itemX(1));
        assertEquals(21, r.itemY(1));
        assertEquals(200, r.item(1));
        assertEquals(2, r.wallTiles());
        assertEquals(11, r.wallX(0));
        assertEquals(20, r.wallY(0));
        assertEquals(0b0000_0011, r.wallBits(0));
        assertEquals(12, r.wallX(1));
        assertEquals(21, r.wallY(1));
        assertEquals(0b1000_0000, r.wallBits(1));
    }

    @Test
    void aPlanRoundTripsThroughItsCommand() {
        PlanRecord r = roundTrip(PlanRecord.of(lShape(), MAP, 42, 2, -1, false, true, ONE_RIGHT));
        assertEquals(42, r.blueprint);
        assertEquals(2, r.upgrade);
        assertEquals(-1, r.structure, "no wall material");
        assertFalse(r.autoWalls);
        assertTrue(r.buildOnWalls);
        assertEquals(7, r.area.count);
        for (int y = 19; y < 24; y++) {
            for (int x = 9; x < 14; x++) {
                boolean in = (y == 20 || y == 21) && x >= 10 && x <= 12 || y == 22 && x == 10;
                assertEquals(in, r.area.is(x, y), x + "," + y);
            }
        }
        assertEquals(2, r.items());
        assertEquals(200, r.item(1));
        assertEquals(11, r.itemX(1));
        assertEquals(2, r.wallTiles());
        assertEquals(0b1000_0000, r.wallBits(1));
    }

    @Test
    void anEmptyPlanKeepsItsRoomType() {
        PlanRecord r = roundTrip(PlanRecord.of(new PlanTiles(4), MAP, 9, 0, 1, true, true, ONE_RIGHT));
        assertEquals(9, r.blueprint);
        assertEquals(0, r.area.count);
        assertEquals(0, r.items());
        assertEquals(0, r.wallTiles());
    }

    @Test
    void aCorruptPlanFailsLoudly() {
        TileArea two = TileArea.of(0, 0, 2, 1, (x, y) -> true);
        assertThrows(IllegalArgumentException.class, () -> new PlanRecord(1, 0, -1, true, true, two, new int[] {0, 0, 0},
                new int[0]), "item index 0 is no item");
        assertThrows(IllegalArgumentException.class, () -> new PlanRecord(1, 0, -1, true, true, two, new int[0],
                new int[] {0, 0, 256}), "more wall bits than dWall holds");
        assertThrows(IllegalArgumentException.class, () -> new PlanRecord(1, 0, -1, true, true, two, new int[] {1, 2},
                new int[0]), "items come in threes");
        CmdWriter w = new CmdWriter(ROOM_BUILD).i(1).i(0).i(-1).b(true).b(true);
        two.write(w);
        w.i(3); // three items on two tiles
        CmdReader r = new CmdReader(w.bytes());
        assertThrows(IllegalArgumentException.class, () -> PlanRecord.read(r));
    }

    @Test
    void liftedTilesAreKeptUpToCapacity() {
        PlanTiles t = new PlanTiles(2);
        t.add(5, 3, 0b1_0101_0000, (byte) -1, (byte) 2, (byte) 3, (byte) 4, true, true);
        t.add(6, 3, 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, false, false);
        assertEquals(2, t.count());
        assertEquals(5, t.tile(0));
        assertEquals(3, t.room(0));
        assertEquals(0b1_0101, t.wallBits(0));
        assertEquals(255, t.item(0) & 0xFF, "furniture bytes are unsigned item indices");
        assertTrue(t.master(0));
        assertTrue(t.candle(0));
        assertThrows(IllegalStateException.class,
                () -> t.add(7, 3, 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, false, false));
        t.clear();
        assertEquals(0, t.count());
    }
}

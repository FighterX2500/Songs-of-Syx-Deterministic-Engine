package sosdet.game.cmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;

/** A tool's dragged area as a command argument (ADR 0002, SOS-34 tools). */
class TileAreaTest {

    private static TileArea roundTrip(int x1, int y1, int w, int h, TileArea.Tiles tiles, int bytes) {
        CmdWriter out = new CmdWriter(6000);
        TileArea.write(out, x1, y1, w, h, tiles);
        byte[] p = out.bytes();
        assertEquals(2 + bytes, p.length, "payload size");
        CmdReader in = new CmdReader(p);
        TileArea a = TileArea.read(in);
        in.end();
        return a;
    }

    @Test
    void aFullRectangleIsItsCornerAndSize() {
        TileArea a = roundTrip(100, 200, 20, 20, (x, y) -> true, 17);
        assertEquals(400, a.count);
        assertTrue(a.is(100, 200));
        assertTrue(a.is(119, 219));
        assertFalse(a.is(120, 219), "x2 is exclusive");
        assertFalse(a.is(99, 200));
        assertFalse(a.is(100, 220));
    }

    @Test
    void anyOtherShapeKeepsEveryTileOfItsRectangle() {
        // an oval of radius 10, as PLACER_TYPE.OVAL drags it: 21 by 21, 441 bits in 14 ints
        TileArea.Tiles oval = (x, y) -> (x - 60) * (x - 60) + (y - 40) * (y - 40) <= 100;
        TileArea a = roundTrip(50, 30, 21, 21, oval, 17 + 14 * 4);
        int count = 0;
        for (int y = 25; y < 56; y++) {
            for (int x = 45; x < 76; x++) {
                assertEquals(oval.is(x, y) && x >= 50 && y >= 30 && x < 71 && y < 51, a.is(x, y), x + "," + y);
                count += a.is(x, y) ? 1 : 0;
            }
        }
        assertEquals(count, a.count);
    }

    @Test
    void anEmptyAreaAndACorruptSizeAreHandled() {
        TileArea a = roundTrip(0, 0, 0, 0, (x, y) -> true, 17);
        assertEquals(0, a.count);
        assertFalse(a.is(0, 0));
        assertThrows(IllegalArgumentException.class, () -> TileArea.write(new CmdWriter(6000), 0, 0, -1, 5, (x, y) -> true));
        byte[] huge = new CmdWriter(6000).i(0).i(0).i(1 << 15).i(1 << 15).b(true).bytes();
        CmdReader r = new CmdReader(huge);
        assertThrows(IllegalArgumentException.class, () -> TileArea.read(r));
    }
}

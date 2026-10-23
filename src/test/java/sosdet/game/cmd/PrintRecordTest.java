package sosdet.game.cmd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;

/** A saved blueprint's contents as a command argument (ADR 0003, SOS-36 copier tools). */
class PrintRecordTest {

    /** A 3 by 4 print as {@code SavedPrint(Room, mx, my)} writes one: a 1 by 2 room in a ring of walls and openings. */
    private static final int[] CELLS = {
        0b010, 0b010, 0b010,
        0b100, 0b1 | 0b100 | 0b1000 | (7 << 4), 0b010,
        0b010, 0b1 | 0b100, 0b010,
        0b010, 0b100, 0b010,
    };

    private static PrintRecord roundTrip(PrintRecord p) {
        CmdWriter out = new CmdWriter(6101);
        p.write(out);
        byte[] bytes = out.bytes();
        assertEquals(2 + 12 + 4 * p.width * p.height, bytes.length, "payload size");
        CmdReader in = new CmdReader(bytes);
        PrintRecord back = PrintRecord.read(in);
        in.end();
        return back;
    }

    @Test
    void aPrintComesBackCellForCell() {
        PrintRecord p = roundTrip(new PrintRecord(42, 3, 4, CELLS));
        assertEquals(42, p.blueprint);
        assertEquals(3, p.width);
        assertEquals(4, p.height);
        assertArrayEquals(CELLS, p.cells());
    }

    @Test
    void theRecordKeepsItsOwnCopy() {
        int[] cells = CELLS.clone();
        PrintRecord p = new PrintRecord(1, 3, 4, cells);
        cells[4] = 0;
        p.cells()[4] = 0;
        assertArrayEquals(CELLS, p.cells());
    }

    @Test
    void aCorruptPrintIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PrintRecord(1, 0, 4, new int[0]), "no width");
        assertThrows(IllegalArgumentException.class, () -> new PrintRecord(1, 3, 3, CELLS), "cells and size differ");
        assertThrows(IllegalArgumentException.class, () -> new PrintRecord(1, 1, 1, new int[] {0b010}), "no room");
        assertThrows(IllegalArgumentException.class, () -> new PrintRecord(1, 1, 1, new int[] {1 | (1 << 20)}),
                "bits beyond an item index");
        byte[] huge = new CmdWriter(6101).i(1).i(1 << 15).i(1 << 15).bytes();
        assertThrows(IllegalArgumentException.class, () -> PrintRecord.read(new CmdReader(huge)), "a huge size");
    }
}

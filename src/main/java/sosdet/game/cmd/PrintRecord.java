package sosdet.game.cmd;

import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;

/**
 * The contents of a saved blueprint ({@code SavedPrints.SavedPrint}, V71.44) as a command argument (ADR 0003): a print
 * lives in the issuer's profile, which no other PC has, so it travels in the command. Game-free, so the codec is tested
 * on its own.
 *
 * <p>Encoded as: blueprint (index in {@code SETT.ROOMS().all()}), width, height, then one int per cell, row by row, as
 * the print stores it: bit 1 room, bit 2 wall, bit 4 roof or opening, bit 8 solid, and the furnisher's item index plus
 * one in bits 4 to 19 on an item's master tile.
 */
final class PrintRecord {

    /** The bits a print cell can hold ({@code SavedPrint.item} reads {@code 0x0FFFF0}). */
    private static final int CELL_BITS = 0x0FFFFF;
    private static final int ROOM = 1;
    /** More cells than any print holds (a room on V71's largest map, 1024 by 1024, plus its border): corrupt. */
    private static final int MAX_CELLS = 1026 * 1026;

    final int blueprint;
    final int width;
    final int height;
    private final int[] cells;

    PrintRecord(int blueprint, int width, int height, int[] cells) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_CELLS) {
            throw new IllegalArgumentException("SOSDET: a print of " + width + " by " + height + " cells");
        }
        if (cells.length != width * height) {
            throw new IllegalArgumentException("SOSDET: a print of " + width + " by " + height + " with "
                    + cells.length + " cells");
        }
        boolean room = false;
        for (int i = 0; i < cells.length; i++) {
            if ((cells[i] & ~CELL_BITS) != 0) {
                throw new IllegalArgumentException("SOSDET: print cell " + i + " holds " + cells[i]);
            }
            room |= (cells[i] & ROOM) != 0;
        }
        if (!room) {
            throw new IllegalArgumentException("SOSDET: a print of " + width + " by " + height + " with no room tile");
        }
        this.blueprint = blueprint;
        this.width = width;
        this.height = height;
        this.cells = cells.clone();
    }

    void write(CmdWriter w) {
        w.i(blueprint).i(width).i(height);
        for (int c : cells) {
            w.i(c);
        }
    }

    static PrintRecord read(CmdReader r) {
        int blueprint = r.i();
        int width = r.i();
        int height = r.i();
        if (width <= 0 || height <= 0 || (long) width * height > MAX_CELLS) {
            throw new IllegalArgumentException("SOSDET: a print of " + width + " by " + height + " cells");
        }
        int[] cells = new int[width * height];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = r.i();
        }
        return new PrintRecord(blueprint, width, height, cells);
    }

    /** The cells, row by row, as {@code SavedPrint} stores them. */
    int[] cells() {
        return cells.clone();
    }
}

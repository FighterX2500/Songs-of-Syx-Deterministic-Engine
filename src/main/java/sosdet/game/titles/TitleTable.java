package sosdet.game.titles;

import java.util.Arrays;

/**
 * Each human's titles' strength (SOSGSGMP spec §5 Titles, owner 2026-11-26): for every title the human picked, the
 * number of races the picking player's profile earned it with, which vanilla scales a title by (0.5 to 1.0). Set from the
 * human's start on the PC that builds or adds the human, saved with the world, so every PC reads the same numbers. A
 * human with nothing set reads 0 everywhere: vanilla's base, as every title read before this table. Plain Java.
 */
public final class TitleTable {

    /** Faction slots, as the game's {@code FACTIONS.MAX()}. */
    public static final int SLOTS = 64;
    public static final int VERSION = 1;

    /** Per slot, per title index: the race count; null for a slot with nothing set. */
    private final int[][] races = new int[SLOTS][];

    /**
     * The human in {@code slot} picked {@code titles} (title indices), each earned with {@code races[i]} races, out of
     * {@code titleCount} titles; every other title reads 0. Replaces what the slot had.
     */
    public void set(int slot, int[] titles, int[] races, int titleCount) {
        checkSlot(slot);
        if (titles.length != races.length) {
            throw new IllegalArgumentException("SOSDET: " + titles.length + " titles and " + races.length + " race counts");
        }
        int[] row = new int[titleCount];
        for (int i = 0; i < titles.length; i++) {
            if (titles[i] < 0 || titles[i] >= titleCount) {
                throw new IllegalArgumentException("SOSDET: title " + titles[i] + " of " + titleCount);
            }
            if (races[i] < 0) {
                throw new IllegalArgumentException("SOSDET: title " + titles[i] + " earned with " + races[i] + " races");
            }
            row[titles[i]] = races[i];
        }
        this.races[slot] = row;
    }

    /** The race count of title {@code title} for the human in {@code slot}; 0 when none was set. */
    public int races(int slot, int title) {
        checkSlot(slot);
        int[] row = races[slot];
        return row == null || title < 0 || title >= row.length ? 0 : row[title];
    }

    /** Nothing set for any slot: a world from before the table. */
    public boolean empty() {
        for (int[] row : races) {
            if (row != null) {
                return false;
            }
        }
        return true;
    }

    /** The table as ints: the version, the slots set, then for each its slot, its length and its counts. */
    public int[] toInts() {
        int n = 2;
        int set = 0;
        for (int[] row : races) {
            if (row != null) {
                n += 2 + row.length;
                set++;
            }
        }
        int[] v = new int[n];
        int k = 0;
        v[k++] = VERSION;
        v[k++] = set;
        for (int s = 0; s < SLOTS; s++) {
            if (races[s] != null) {
                v[k++] = s;
                v[k++] = races[s].length;
                for (int r : races[s]) {
                    v[k++] = r;
                }
            }
        }
        return v;
    }

    /** {@link #toInts}' table; refuses another version or a malformed list. */
    public static TitleTable fromInts(int[] v) {
        if (v.length < 2 || v[0] != VERSION) {
            throw new IllegalArgumentException("SOSDET: a title table of version " + (v.length == 0 ? "none" : v[0])
                    + ", this engine reads " + VERSION);
        }
        TitleTable t = new TitleTable();
        int k = 2;
        int last = -1;
        for (int i = 0; i < v[1]; i++) {
            if (k + 2 > v.length) {
                throw new IllegalArgumentException("SOSDET: a title table cut short at slot " + i);
            }
            int slot = v[k++];
            int len = v[k++];
            if (slot <= last || slot >= SLOTS || len < 0 || k + len > v.length) {
                throw new IllegalArgumentException("SOSDET: a title table's slot " + slot + " of " + len + " titles");
            }
            t.races[slot] = Arrays.copyOfRange(v, k, k + len);
            k += len;
            last = slot;
        }
        if (k != v.length) {
            throw new IllegalArgumentException("SOSDET: a title table with " + (v.length - k) + " ints left over");
        }
        return t;
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            throw new IllegalArgumentException("SOSDET: slot " + slot);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TitleTable t && Arrays.deepEquals(races, t.races);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(races);
    }
}

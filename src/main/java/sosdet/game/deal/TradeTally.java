package sosdet.game.deal;

import java.io.IOException;
import java.util.Arrays;

import sosdet.hash.HashLog;

/**
 * The market trade between humans (SOS-135): for each seller, buyer and tradable, the items sold and the denari that
 * moved for them, this year, last year and since the start. A sale counts as the buyer's market purchase from the
 * seller: the price less the buyer's tariff, which is what reaches the seller (SOS-77). Deals made in the diplomacy
 * window don't count. World state: every PC adds the same sales at the same steps, it is saved with the world
 * ({@code GameTraded}) and it is in the world hash. Entries are kept sorted by key in plain arrays, so a read (the
 * Goods tab, every frame) allocates nothing. Plain Java, no game classes.
 */
public final class TradeTally {

    /** What a figure covers. */
    public static final int THIS_YEAR = 0, LAST_YEAR = 1, ALL_TIME = 2;

    private static final int VERSION = 1;
    /** Keys are {@code (seller * SLOTS + buyer) * SLOTS + tradable}; slots are faction indexes. */
    private static final int SLOTS = 1024;
    /** Per entry: items, then denari, for each of the three periods. */
    private static final int FIGURES = 6;
    private static final int LONGS = 1 + FIGURES;

    /** {@code size} keys, ascending, so the save and the hash are the same on every PC. */
    private long[] keys = new long[16];
    /** {@code FIGURES} per key, in the keys' order. */
    private long[] figures = new long[16 * FIGURES];
    private int size;
    /** The year the THIS_YEAR figures count. */
    private int year;

    /** A sale in year {@code now}: {@code items} of the tradable {@code tradable} for {@code denari}. */
    public void add(int seller, int buyer, int tradable, int items, int denari, int now) {
        long k = key(seller, buyer, tradable);
        if (items <= 0) {
            return;
        }
        roll(now);
        int i = Arrays.binarySearch(keys, 0, size, k);
        if (i < 0) {
            i = insert(-i - 1, k);
        }
        int at = i * FIGURES;
        long d = Math.max(denari, 0);
        figures[at + 2 * THIS_YEAR] += items;
        figures[at + 2 * THIS_YEAR + 1] += d;
        figures[at + 2 * ALL_TIME] += items;
        figures[at + 2 * ALL_TIME + 1] += d;
    }

    /** The items of {@code tradable} {@code seller} sold {@code buyer} in {@code period}, read in year {@code now}. */
    public long items(int seller, int buyer, int tradable, int period, int now) {
        return read(seller, buyer, tradable, period, now, 0);
    }

    /** The denari {@code seller} got from {@code buyer} for {@code tradable} in {@code period}, read in year {@code now}. */
    public long denari(int seller, int buyer, int tradable, int period, int now) {
        return read(seller, buyer, tradable, period, now, 1);
    }

    private long read(int seller, int buyer, int tradable, int period, int now, int which) {
        int i = Arrays.binarySearch(keys, 0, size, key(seller, buyer, tradable));
        if (i < 0) {
            return 0;
        }
        int at = i * FIGURES + which;
        if (period == ALL_TIME) {
            return figures[at + 2 * ALL_TIME];
        }
        if (now == year) {
            return figures[at + 2 * period];
        }
        return now == year + 1 && period == LAST_YEAR ? figures[at + 2 * THIS_YEAR] : 0;
    }

    /** A removed human's trade, both ways, is gone with them, as their tariffs are. */
    public void dropHuman(int slot) {
        int n = 0;
        for (int i = 0; i < size; i++) {
            long k = keys[i];
            if (k / SLOTS / SLOTS == slot || k / SLOTS % SLOTS == slot) {
                continue;
            }
            keys[n] = k;
            System.arraycopy(figures, i * FIGURES, figures, n * FIGURES, FIGURES);
            n++;
        }
        size = n;
    }

    /** True when no sale was ever counted: such a tally saves and hashes as none. */
    public boolean empty() {
        return size == 0;
    }

    private void roll(int now) {
        if (now == year) {
            return;
        }
        for (int i = 0; i < size; i++) {
            int at = i * FIGURES;
            for (int w = 0; w < 2; w++) {
                figures[at + 2 * LAST_YEAR + w] = now == year + 1 ? figures[at + 2 * THIS_YEAR + w] : 0;
                figures[at + 2 * THIS_YEAR + w] = 0;
            }
        }
        year = now;
    }

    private int insert(int i, long k) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            figures = Arrays.copyOf(figures, size * 2 * FIGURES);
        }
        System.arraycopy(keys, i, keys, i + 1, size - i);
        System.arraycopy(figures, i * FIGURES, figures, (i + 1) * FIGURES, (size - i) * FIGURES);
        keys[i] = k;
        Arrays.fill(figures, i * FIGURES, (i + 1) * FIGURES, 0);
        size++;
        return i;
    }

    private static long key(int seller, int buyer, int tradable) {
        if (seller < 0 || seller >= SLOTS || buyer < 0 || buyer >= SLOTS || seller == buyer || tradable < 0
                || tradable >= SLOTS) {
            throw new IllegalArgumentException("SOSDET: a sale of tradable " + tradable + " from slot " + seller
                    + " to slot " + buyer);
        }
        return ((long) seller * SLOTS + buyer) * SLOTS + tradable;
    }

    // ---------------------------------------------------------------- save and hash

    /** The tally as longs, its version first. */
    public long[] toLongs() {
        long[] v = new long[3 + LONGS * size];
        v[0] = VERSION;
        v[1] = year;
        v[2] = size;
        for (int i = 0; i < size; i++) {
            v[3 + LONGS * i] = keys[i];
            System.arraycopy(figures, i * FIGURES, v, 4 + LONGS * i, FIGURES);
        }
        return v;
    }

    /** A tally from {@link #toLongs}. */
    public static TradeTally fromLongs(long[] v) throws IOException {
        if (v.length < 3 || v[0] != VERSION) {
            throw new IOException("SOSDET: a trade tally of version " + (v.length == 0 ? "none" : v[0])
                    + ", this engine reads " + VERSION);
        }
        long n = v[2];
        if (n < 0 || v.length != 3 + LONGS * n) {
            throw new IOException("SOSDET: a trade tally of " + v.length + " longs for " + n + " entries");
        }
        TradeTally t = new TradeTally();
        t.year = (int) v[1];
        long last = -1;
        for (int i = 0; i < n; i++) {
            int at = 3 + LONGS * i;
            long k = v[at];
            if (k <= last || k / SLOTS / SLOTS >= SLOTS || k / SLOTS / SLOTS == k / SLOTS % SLOTS) {
                throw new IOException("SOSDET: a trade tally entry " + Arrays.toString(Arrays.copyOfRange(v, at, at + LONGS)));
            }
            last = k;
            int j = t.insert(t.size, k);
            System.arraycopy(v, at + 1, t.figures, j * FIGURES, FIGURES);
        }
        return t;
    }

    /** Mixes the whole tally into {@code h}. */
    public long hash(long h) {
        h = HashLog.mix(h, VERSION);
        h = HashLog.mix(h, year);
        h = HashLog.mix(h, size);
        for (int i = 0; i < size; i++) {
            h = HashLog.mix(h, keys[i]);
            for (int f = 0; f < FIGURES; f++) {
                h = HashLog.mix(h, figures[i * FIGURES + f]);
            }
        }
        return h;
    }
}

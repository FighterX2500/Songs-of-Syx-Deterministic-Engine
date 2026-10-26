package sosdet.game.deal;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import sosdet.hash.HashLog;

/**
 * The tariffs between humans (SOS-77, mod plan goods-tariffs.md): each human's tariff on what another human sells them,
 * and what it brought in this year and last. The buyer's tariff is cut from what the seller gets and paid to the
 * buyer's treasury (owner, 2026-10-27). World state: every PC changes it at the same steps, it is saved with the world
 * ({@code GameTariffs}) and it is in the world hash. Plain Java, no game classes.
 */
public final class TariffBook {

    /** A tariff nobody set: the same for every trade stance between humans (owner, 2026-10-27). */
    public static final int DEFAULT = 20;
    public static final int MAX = 80;
    public static final int STEP = 5;

    private static final int VERSION = 1;
    /** Pairs are keyed {@code setter * SLOTS + other}; slots are faction indexes. */
    private static final int SLOTS = 1024;

    /** By pair, in key order, so the save and the hash are the same on every PC. */
    private final TreeMap<Integer, Entry> pairs = new TreeMap<>();
    /** The year {@link Entry#thisYear} counts. */
    private int year;

    private static final class Entry {
        /** The percent set, or -1 for {@link #DEFAULT}. */
        int rate = -1;
        int thisYear;
        int lastYear;

        boolean empty() {
            return rate < 0 && thisYear == 0 && lastYear == 0;
        }
    }

    /** Why {@code percent} can't be a tariff, as a sentence; null when it can. */
    public static String refusal(int percent) {
        if (percent < 0 || percent > MAX) {
            return "A tariff is from 0% to " + MAX + "%.";
        }
        return percent % STEP == 0 ? null : "A tariff goes in steps of " + STEP + "%.";
    }

    /** {@code setter}'s tariff on what {@code other} sells them, in percent. */
    public int rate(int setter, int other) {
        Entry e = pairs.get(key(setter, other));
        return e == null || e.rate < 0 ? DEFAULT : e.rate;
    }

    /** Sets {@code setter}'s tariff on {@code other}. Check {@link #refusal} first. */
    public void set(int setter, int other, int percent) {
        String why = refusal(percent);
        if (why != null) {
            throw new IllegalArgumentException("SOSDET: " + why + " (" + percent + ")");
        }
        entry(setter, other).rate = percent;
    }

    /**
     * {@code buyer}'s cut of a sale by {@code seller} at {@code price} in year {@code now}: the buyer's tariff of the
     * price, rounded down, counted as the buyer's revenue. The seller gets {@code price} less it.
     */
    public int cut(int buyer, int seller, int price, int now) {
        if (price <= 0) {
            return 0;
        }
        int c = (int) ((long) price * rate(buyer, seller) / 100);
        if (c > 0) {
            roll(now);
            entry(buyer, seller).thisYear += c;
        }
        return c;
    }

    /** What {@code buyer}'s tariff on {@code seller} brought in, in year {@code now} or the one before it. */
    public int revenue(int buyer, int seller, int now, boolean lastYear) {
        Entry e = pairs.get(key(buyer, seller));
        if (e == null) {
            return 0;
        }
        if (now == year) {
            return lastYear ? e.lastYear : e.thisYear;
        }
        if (now == year + 1 && lastYear) {
            return e.thisYear;
        }
        return 0;
    }

    /** What all of {@code buyer}'s tariffs brought in, in year {@code now}. */
    public int revenue(int buyer, int now) {
        if (now != year) {
            return 0;
        }
        int sum = 0;
        for (Map.Entry<Integer, Entry> e : pairs.entrySet()) {
            if (e.getKey() / SLOTS == buyer) {
                sum += e.getValue().thisYear;
            }
        }
        return sum;
    }

    /** A removed human's tariffs, both ways, are gone: it trades no more. */
    public void dropHuman(int slot) {
        pairs.keySet().removeIf(k -> k / SLOTS == slot || k % SLOTS == slot);
    }

    /** True when nothing was set or earned: such a book saves and hashes as none. */
    public boolean empty() {
        return pairs.isEmpty();
    }

    private void roll(int now) {
        if (now == year) {
            return;
        }
        for (Entry e : pairs.values()) {
            e.lastYear = now == year + 1 ? e.thisYear : 0;
            e.thisYear = 0;
        }
        year = now;
        pairs.values().removeIf(Entry::empty);
    }

    private Entry entry(int a, int b) {
        return pairs.computeIfAbsent(key(a, b), k -> new Entry());
    }

    private static int key(int a, int b) {
        if (a < 0 || a >= SLOTS || b < 0 || b >= SLOTS || a == b) {
            throw new IllegalArgumentException("SOSDET: a tariff between slots " + a + " and " + b);
        }
        return a * SLOTS + b;
    }

    // ---------------------------------------------------------------- save and hash

    /** The book as ints, its version first. */
    public int[] toInts() {
        int[] v = new int[3 + 4 * pairs.size()];
        int n = 0;
        v[n++] = VERSION;
        v[n++] = year;
        v[n++] = pairs.size();
        for (Map.Entry<Integer, Entry> e : pairs.entrySet()) {
            v[n++] = e.getKey();
            v[n++] = e.getValue().rate;
            v[n++] = e.getValue().thisYear;
            v[n++] = e.getValue().lastYear;
        }
        return v;
    }

    /** A book from {@link #toInts}. */
    public static TariffBook fromInts(int[] v) throws IOException {
        if (v.length < 3 || v[0] != VERSION) {
            throw new IOException("SOSDET: a tariff book of version " + (v.length == 0 ? "none" : v[0]) + ", this engine reads "
                    + VERSION);
        }
        int n = v[2];
        if (n < 0 || v.length != 3 + 4 * n) {
            throw new IOException("SOSDET: a tariff book of " + v.length + " ints for " + n + " pairs");
        }
        TariffBook b = new TariffBook();
        b.year = v[1];
        for (int i = 0; i < n; i++) {
            int at = 3 + 4 * i;
            int k = v[at];
            if (k < 0 || k / SLOTS == k % SLOTS || (v[at + 1] != -1 && refusal(v[at + 1]) != null)) {
                throw new IOException("SOSDET: a tariff book entry " + Arrays.toString(Arrays.copyOfRange(v, at, at + 4)));
            }
            Entry e = new Entry();
            e.rate = v[at + 1];
            e.thisYear = v[at + 2];
            e.lastYear = v[at + 3];
            b.pairs.put(k, e);
        }
        return b;
    }

    /** Mixes the whole book into {@code h}. */
    public long hash(long h) {
        for (int i : toInts()) {
            h = HashLog.mix(h, i);
        }
        return h;
    }
}

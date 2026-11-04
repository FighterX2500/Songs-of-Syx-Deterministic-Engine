package sosdet.game.deal;

import java.io.IOException;
import java.util.Arrays;

import sosdet.hash.HashLog;

/**
 * The stances passed to a human's NPC vassals (SOS-140, owner 2026-11-04): while humans A and B are trade partners or
 * colleagues, B has at least that stance with each NPC vassal of A, both ways. An alliance never passes, and a war
 * between B and the vassal blocks it. When the source ends, a passed stance goes back to the one B and the vassal had
 * before; one they had on their own stays. A passed stance that anyone else changes (the vassal's court cancelling it,
 * B's own diplomacy) is theirs from then on: it isn't passed again until its source ends.
 *
 * <p>Stances are codes: {@link #WAR}, {@link #NEUTRAL}, {@link #TRADE}, {@link #PACT}, and {@link #ABOVE} for any
 * stance above a colleague's (ally, vassal, overlord). World state: every PC decides it at the same steps, it is saved
 * with the world ({@code GamePassed}) and in the world hash. Plain Java, no game classes; {@link #decide} allocates
 * nothing, as it runs in every world part.
 */
public final class StanceBook {

    public static final int WAR = -1;
    public static final int NEUTRAL = 0;
    public static final int TRADE = 1;
    public static final int PACT = 2;
    public static final int ABOVE = 3;
    /** {@link #decide}'s answer when the stance stays as it is. */
    public static final int KEEP = -2;

    private static final int VERSION = 1;
    /** Pairs are keyed {@code human * SLOTS + vassal}; slots are faction indexes. */
    private static final int SLOTS = 1024;
    private static final int FIELDS = 4;

    /** Sorted keys, and per entry: the stance before, the stance passed, and 1 once someone else changed it. */
    private int[] keys = new int[64];
    private int[] before = new int[64];
    private int[] passed = new int[64];
    private boolean[] released = new boolean[64];
    private int size;

    /** What passes from a stance between two humans: a trade partner's or a colleague's, else nothing. */
    public static int passes(int stance) {
        return stance == TRADE || stance == PACT ? stance : NEUTRAL;
    }

    /**
     * In the world part: the stance to set between {@code human} and {@code vassal}, or {@link #KEEP}. {@code want} is
     * what passes to them now ({@link #NEUTRAL} when nothing does: the vassal has no human overlord, or its stance with
     * {@code human} passes nothing), {@code now} their stance now.
     */
    public int decide(int human, int vassal, int want, int now) {
        int i = find(human, vassal);
        if (i >= 0 && released[i]) {
            if (want == NEUTRAL) {
                remove(i); // the source ended: the next one passes afresh
            }
            return KEEP;
        }
        if (i >= 0 && now != passed[i]) {
            released[i] = true; // someone else changed it: theirs now
            return KEEP;
        }
        if (now == WAR) {
            return KEEP;
        }
        if (i >= 0) {
            if (want <= before[i]) {
                int back = before[i];
                remove(i);
                return back;
            }
            if (want != passed[i]) {
                passed[i] = want;
                return want;
            }
            return KEEP;
        }
        if (want > now) {
            add(human, vassal, now, want);
            return want;
        }
        return KEEP;
    }

    /** True when the book holds {@code human} and {@code vassal}: {@link #decide} has them in hand. */
    public boolean has(int human, int vassal) {
        return find(human, vassal) >= 0;
    }

    public int size() {
        return size;
    }

    /** The human of the {@code i}-th pair, in key order. */
    public int human(int i) {
        return keys[i] / SLOTS;
    }

    /** The vassal of the {@code i}-th pair, in key order. */
    public int vassal(int i) {
        return keys[i] % SLOTS;
    }

    /** A removed human's or a gone faction's pairs go, with no stance set. */
    public void drop(int slot) {
        for (int i = size - 1; i >= 0; i--) {
            if (human(i) == slot || vassal(i) == slot) {
                remove(i);
            }
        }
    }

    public boolean empty() {
        return size == 0;
    }

    private int find(int human, int vassal) {
        return size == 0 ? -1 : Math.max(-1, Arrays.binarySearch(keys, 0, size, key(human, vassal)));
    }

    private void add(int human, int vassal, int was, int to) {
        int k = key(human, vassal);
        int at = -(Arrays.binarySearch(keys, 0, size, k) + 1);
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            before = Arrays.copyOf(before, size * 2);
            passed = Arrays.copyOf(passed, size * 2);
            released = Arrays.copyOf(released, size * 2);
        }
        System.arraycopy(keys, at, keys, at + 1, size - at);
        System.arraycopy(before, at, before, at + 1, size - at);
        System.arraycopy(passed, at, passed, at + 1, size - at);
        System.arraycopy(released, at, released, at + 1, size - at);
        keys[at] = k;
        before[at] = was;
        passed[at] = to;
        released[at] = false;
        size++;
    }

    private void remove(int i) {
        System.arraycopy(keys, i + 1, keys, i, size - i - 1);
        System.arraycopy(before, i + 1, before, i, size - i - 1);
        System.arraycopy(passed, i + 1, passed, i, size - i - 1);
        System.arraycopy(released, i + 1, released, i, size - i - 1);
        size--;
    }

    private static int key(int human, int vassal) {
        if (human < 0 || human >= SLOTS || vassal < 0 || vassal >= SLOTS) {
            throw new IllegalArgumentException("SOSDET: no stance pair of human " + human + " and vassal " + vassal);
        }
        return human * SLOTS + vassal;
    }

    /** The book as ints, for the save: the version, the count, then each pair's key, before, passed and released. */
    public int[] toInts() {
        int[] v = new int[2 + FIELDS * size];
        v[0] = VERSION;
        v[1] = size;
        for (int i = 0; i < size; i++) {
            int at = 2 + FIELDS * i;
            v[at] = keys[i];
            v[at + 1] = before[i];
            v[at + 2] = passed[i];
            v[at + 3] = released[i] ? 1 : 0;
        }
        return v;
    }

    /** A book from {@link #toInts}. */
    public static StanceBook fromInts(int[] v) throws IOException {
        if (v.length < 2 || v[0] != VERSION) {
            throw new IOException("SOSDET: a stance book of version " + (v.length == 0 ? "none" : v[0]) + ", this engine reads "
                    + VERSION);
        }
        int n = v[1];
        if (n < 0 || v.length != 2 + FIELDS * n) {
            throw new IOException("SOSDET: a stance book of " + v.length + " ints for " + n + " pairs");
        }
        StanceBook b = new StanceBook();
        for (int i = 0; i < n; i++) {
            int at = 2 + FIELDS * i;
            int k = v[at];
            if (k < 0 || (i > 0 && k <= v[at - FIELDS])) {
                throw new IOException("SOSDET: a stance book's pairs out of order at " + i);
            }
            b.add(k / SLOTS, k % SLOTS, v[at + 1], v[at + 2]);
            b.released[i] = v[at + 3] != 0;
        }
        return b;
    }

    /** Mixes the book into {@code h}, allocating nothing. */
    public long hash(long h) {
        h = HashLog.mix(h, size);
        for (int i = 0; i < size; i++) {
            h = HashLog.mix(h, keys[i]);
            h = HashLog.mix(h, before[i]);
            h = HashLog.mix(h, passed[i]);
            h = HashLog.mix(h, released[i] ? 1 : 0);
        }
        return h;
    }
}

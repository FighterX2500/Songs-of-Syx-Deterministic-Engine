package sosdet.game.carve;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sosdet.hash.HashLog;

/**
 * Every carved city-state still on the map, in the order they were carved: the undo log saved with the world as
 * {@code SOSDET_CARVES} (SOSGSGMP docs/plan/hot-join.md §3.5). An un-carve takes out only what its own carve added and no
 * later carve also uses, so city-states can go in any order. World state: every PC holds the same book, and it is in the
 * world hash. Plain Java, no game classes.
 */
public final class CarveBook {

    private static final int VERSION = 1;

    private final List<CarveRecord> records = new ArrayList<>();

    public boolean empty() {
        return records.isEmpty();
    }

    /** The number of records; with {@link #get}, read without allocating (asked every frame). */
    public int size() {
        return records.size();
    }

    public CarveRecord get(int i) {
        return records.get(i);
    }

    public List<CarveRecord> all() {
        return Collections.unmodifiableList(records);
    }

    public void add(CarveRecord c) {
        if (find(c.region) != null) {
            throw new IllegalArgumentException("SOSDET: region " + c.region + " is carved already");
        }
        records.add(c);
    }

    /** The carve that made {@code region}, or null. */
    public CarveRecord find(int region) {
        for (CarveRecord c : records) {
            if (c.region == region) {
                return c;
            }
        }
        return null;
    }

    public void remove(CarveRecord c) {
        if (!records.remove(c)) {
            throw new IllegalArgumentException("SOSDET: region " + c.region + " isn't in the carve book");
        }
    }

    /** The step bits {@code c} added at {@code tile} that no other carve in the book added too: what un-carving takes out. */
    public int bitsToTakeOut(CarveRecord c, int tile) {
        int bits = c.addedBits(tile);
        for (CarveRecord o : records) {
            if (o != c) {
                bits &= ~o.addedBits(tile);
            }
        }
        return bits;
    }

    /** True when {@code c} made {@code tile} a road and no other carve in the book did too. */
    public boolean roadToTakeOut(CarveRecord c, int tile) {
        if (!c.roads.contains(tile)) {
            return false;
        }
        for (CarveRecord o : records) {
            if (o != c && o.roads.contains(tile)) {
                return false;
            }
        }
        return true;
    }

    public int[] toInts() {
        List<int[]> parts = new ArrayList<>();
        int n = 2;
        for (CarveRecord c : records) {
            int[] p = c.toInts();
            parts.add(p);
            n += 1 + p.length;
        }
        int[] v = new int[n];
        v[0] = VERSION;
        v[1] = records.size();
        int at = 2;
        for (int[] p : parts) {
            v[at++] = p.length;
            System.arraycopy(p, 0, v, at, p.length);
            at += p.length;
        }
        return v;
    }

    /** A book from {@link #toInts}. */
    public static CarveBook fromInts(int[] v) throws IOException {
        if (v.length < 2 || v[0] != VERSION) {
            throw new IOException("SOSDET: a carve book of version " + (v.length == 0 ? "none" : v[0]) + ", this engine reads "
                    + VERSION);
        }
        int n = v[1];
        if (n < 0) {
            throw new IOException("SOSDET: a carve book of " + n + " records");
        }
        CarveBook b = new CarveBook();
        int at = 2;
        for (int i = 0; i < n; i++) {
            if (at >= v.length || v[at] < 0 || v[at] > v.length - at - 1) {
                throw new IOException("SOSDET: a carve book's record " + i + " runs past its end");
            }
            int len = v[at++];
            b.add(CarveRecord.fromInts(v, at, len));
            at += len;
        }
        if (at != v.length) {
            throw new IOException("SOSDET: a carve book of " + v.length + " ints holds " + at);
        }
        return b;
    }

    /** Mixes the book into {@code h}. Allocates; the world hash calls it only when the book isn't empty. */
    public long hash(long h) {
        h = HashLog.mix(h, records.size());
        for (CarveRecord c : records) {
            h = c.hash(h);
        }
        return h;
    }
}

package sosdet.game.station;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The divisions of a host's city army (army 0) that hold a stationed ally's soldiers during a fight in that city (mod
 * spec §9, SOS-234, ADR 0011): for each, the ally's slot and the world division they came from (the army's index and
 * iteration, and the division's place in it), where the survivors go back. City state: saved with the host's city
 * ({@code GameStationedDivs}), so a viewer's copy of the city has it too. Plain Java, no game classes.
 */
public final class StationedDivs {

    private static final int VERSION = 1;
    private static final int FIELDS = 4;

    /** By the division's index in army 0: the ally's slot, -1 for a division of the host's own. */
    private final int[] owner;
    private final int[] army;
    private final int[] iteration;
    private final int[] place;
    private int size;

    /** A registry for an army of {@code slots} divisions, none stationed. */
    public StationedDivs(int slots) {
        if (slots <= 0) {
            throw new IllegalArgumentException("SOSDET: an army of " + slots + " divisions");
        }
        owner = new int[slots];
        army = new int[slots];
        iteration = new int[slots];
        place = new int[slots];
        Arrays.fill(owner, -1);
    }

    public int slots() {
        return owner.length;
    }

    /** True when army 0's division {@code div} holds a stationed ally's soldiers; false for any other index. No allocation. */
    public boolean stationed(int div) {
        return div >= 0 && div < owner.length && owner[div] >= 0;
    }

    /** The ally whose soldiers division {@code div} holds, or -1. */
    public int owner(int div) {
        return stationed(div) ? owner[div] : -1;
    }

    public int army(int div) {
        return army[check(div)];
    }

    public int iteration(int div) {
        return iteration[check(div)];
    }

    /** The division's place in its world army. */
    public int place(int div) {
        return place[check(div)];
    }

    /** Division {@code div} holds the soldiers of the ally {@code ally}, from the world division at {@code place} of that army. */
    public void put(int div, int ally, int armyIndex, int armyIteration, int divPlace) {
        if (div < 0 || div >= owner.length || ally < 0 || armyIndex < 0 || divPlace < 0) {
            throw new IllegalArgumentException("SOSDET: stationed division " + div + " of slot " + ally + ", army "
                    + armyIndex + ", place " + divPlace);
        }
        if (owner[div] >= 0) {
            throw new IllegalStateException("SOSDET: division " + div + " holds stationed soldiers already");
        }
        owner[div] = ally;
        army[div] = armyIndex;
        iteration[div] = armyIteration;
        place[div] = divPlace;
        size++;
    }

    /** Division {@code div} is the host's own again. */
    public void clear(int div) {
        check(div);
        owner[div] = -1;
        size--;
    }

    public int size() {
        return size;
    }

    public boolean empty() {
        return size == 0;
    }

    private int check(int div) {
        if (!stationed(div)) {
            throw new IllegalArgumentException("SOSDET: division " + div + " holds no stationed soldiers");
        }
        return div;
    }

    /**
     * The men a world division keeps after a fight in the host's city: those it has now less the {@code dead}, never
     * fewer than none. Men who joined it during the fight stay.
     */
    public static int survivors(int menNow, int dead) {
        return Math.max(0, menNow - Math.max(0, dead));
    }

    /** A stationed division's fight as its {@code STATION_RESOLVE} input carries it. */
    public record Resolve(int army, int iteration, int place, int dead, double experience) {

        /** The input's bytes. */
        public byte[] toBytes() {
            return ByteBuffer.allocate(BYTES).putInt(army).putInt(iteration).putInt(place).putInt(dead)
                    .putDouble(experience).array();
        }

        /** From {@link #toBytes}. */
        public static Resolve of(byte[] data) {
            if (data.length != BYTES) {
                throw new IllegalArgumentException("SOSDET: a stationed division's fight of " + data.length + " bytes, not "
                        + BYTES);
            }
            ByteBuffer b = ByteBuffer.wrap(data);
            return new Resolve(b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getDouble());
        }

        private static final int BYTES = 24;
    }

    /** The registry as ints, its version and size first, then each stationed division in index order. */
    public int[] toInts() {
        int[] v = new int[3 + (FIELDS + 1) * size];
        v[0] = VERSION;
        v[1] = owner.length;
        v[2] = size;
        int n = 3;
        for (int d = 0; d < owner.length; d++) {
            if (owner[d] >= 0) {
                v[n++] = d;
                v[n++] = owner[d];
                v[n++] = army[d];
                v[n++] = iteration[d];
                v[n++] = place[d];
            }
        }
        return v;
    }

    /** A registry from {@link #toInts}. */
    public static StationedDivs fromInts(int[] v) throws IOException {
        if (v.length < 3 || v[0] != VERSION) {
            throw new IOException("SOSDET: stationed divisions of version " + (v.length == 0 ? "none" : v[0])
                    + ", this engine reads " + VERSION);
        }
        int n = v[2];
        if (v[1] <= 0 || n < 0 || v.length != 3 + (FIELDS + 1) * n) {
            throw new IOException("SOSDET: stationed divisions of " + v.length + " ints for " + n + " of " + v[1]);
        }
        StationedDivs s = new StationedDivs(v[1]);
        for (int i = 0; i < n; i++) {
            int at = 3 + (FIELDS + 1) * i;
            try {
                s.put(v[at], v[at + 1], v[at + 2], v[at + 3], v[at + 4]);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IOException("SOSDET: a stationed division " + Arrays.toString(Arrays.copyOfRange(v, at, at + 5)), e);
            }
        }
        return s;
    }
}

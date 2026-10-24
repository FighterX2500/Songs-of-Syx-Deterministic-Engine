package sosdet.ai;

import java.nio.ByteBuffer;

/**
 * Who commands each battle division and each army's artillery (SOS-15): a PC as the mod numbers it (the host is 0), or
 * the host's AI general ({@link AiEntry#AI}). The host sets it through its log, so every PC changes it at the same
 * step. An order or AI change from a source that does not command its division is skipped, on every PC alike. Until
 * the first table, and after a cleared one, nothing is restricted, as before.
 */
public final class Commanders {

    /** No table: every order and every change of the general applies. */
    public static final int UNSET = -2;

    /** By battle division, then by army for the artillery; null when unset. Replaced whole, never changed in place. */
    private volatile int[] byDivision;
    private volatile int[] artilleryByArmy;
    /** This PC's number, and the army whose divisions it commands first (0, the player's, when none). */
    private int localPc;
    private volatile int ownArmy;
    private long skipped;

    /** The log payload for a table; null and null clear it. */
    public static byte[] payload(int[] byDivision, int[] artilleryByArmy) {
        if (byDivision == null && artilleryByArmy == null) {
            return AiEntry.ints(0, 0);
        }
        if (byDivision == null || artilleryByArmy == null || byDivision.length == 0 || artilleryByArmy.length == 0
                || byDivision.length % artilleryByArmy.length != 0) {
            throw new IllegalArgumentException("SOSDET: a commanders table needs every division and every army's"
                    + " artillery, the divisions split evenly by army (divisions "
                    + (byDivision == null ? "null" : byDivision.length) + ", armies "
                    + (artilleryByArmy == null ? "null" : artilleryByArmy.length) + ")");
        }
        int[] all = new int[2 + byDivision.length + artilleryByArmy.length];
        all[0] = byDivision.length;
        all[1] = artilleryByArmy.length;
        for (int i = 0; i < byDivision.length; i++) {
            all[2 + i] = check(byDivision[i], "division " + i);
        }
        for (int a = 0; a < artilleryByArmy.length; a++) {
            all[2 + byDivision.length + a] = check(artilleryByArmy[a], "army " + a + "'s artillery");
        }
        return AiEntry.ints(all);
    }

    private static int check(int pc, String what) {
        if (pc < AiEntry.AI) {
            throw new IllegalArgumentException("SOSDET: commander " + pc + " for " + what + " (a PC from 0, or AI -1)");
        }
        return pc;
    }

    /** A {@link AiEntry#COMMANDERS} entry, at its step. */
    void apply(AiEntry e) {
        int divisions = e.intAt(0);
        int armies = e.intAt(1);
        if (divisions == 0) {
            byDivision = null;
            artilleryByArmy = null;
        } else {
            int[] d = new int[divisions];
            int[] a = new int[armies];
            for (int i = 0; i < divisions; i++) {
                d[i] = e.intAt(2 + i);
            }
            for (int i = 0; i < armies; i++) {
                a[i] = e.intAt(2 + divisions + i);
            }
            byDivision = d;
            artilleryByArmy = a;
        }
        ownArmy = armyOf(localPc);
    }

    void localPc(int pc) {
        localPc = pc;
        ownArmy = armyOf(pc);
    }

    public boolean set() {
        return byDivision != null;
    }

    /** Whether an entry from {@code pc} may change division {@code div}. */
    public boolean allows(int pc, int div) {
        int[] d = byDivision;
        return d == null || div >= 0 && div < d.length && d[div] == pc;
    }

    /** Whether an entry from {@code pc} may change a piece of army {@code army}'s artillery. */
    public boolean allowsArtillery(int pc, int army) {
        int[] a = artilleryByArmy;
        return a == null || army >= 0 && army < a.length && a[army] == pc;
    }

    /** The UI on this PC: whether its player may select division {@code div}. */
    public boolean commandsHere(int div) {
        return allows(localPc, div);
    }

    public boolean commandsArtilleryHere(int army) {
        return allowsArtillery(localPc, army);
    }

    /** The army this PC's player commands: the one holding the first division it commands; 0 when none. */
    public int ownArmy() {
        return ownArmy;
    }

    /** Division {@code div}'s commander, or {@link #UNSET}. */
    public int of(int div) {
        int[] d = byDivision;
        return d == null ? UNSET : d[div];
    }

    public void skip() {
        skipped++;
    }

    /** Entries skipped because their source did not command their division or artillery. */
    public long skipped() {
        return skipped;
    }

    /** Mixed into the state hash while a table is set (the same on every PC). */
    public long hash() {
        int[] d = byDivision;
        int[] a = artilleryByArmy;
        long h = 0xcbf29ce484222325L;
        if (d == null) {
            return h;
        }
        for (int v : d) {
            h = (h ^ v) * 0x100000001b3L;
        }
        for (int v : a) {
            h = (h ^ v) * 0x100000001b3L;
        }
        return h;
    }

    /** The table for a resync state: the counts, then the payload ints. */
    void writeTo(ByteBuffer b) {
        int[] d = byDivision;
        int[] a = artilleryByArmy;
        b.putLong(skipped);
        b.putInt(d == null ? 0 : d.length).putInt(a == null ? 0 : a.length);
        if (d != null) {
            for (int v : d) {
                b.putInt(v);
            }
            for (int v : a) {
                b.putInt(v);
            }
        }
    }

    int bytes() {
        int[] d = byDivision;
        return 8 + 8 + (d == null ? 0 : 4 * (d.length + artilleryByArmy.length));
    }

    void readFrom(ByteBuffer b) {
        skipped = b.getLong();
        int divisions = b.getInt();
        int armies = b.getInt();
        if (divisions == 0) {
            byDivision = null;
            artilleryByArmy = null;
        } else {
            int[] d = new int[divisions];
            int[] a = new int[armies];
            for (int i = 0; i < divisions; i++) {
                d[i] = b.getInt();
            }
            for (int i = 0; i < armies; i++) {
                a[i] = b.getInt();
            }
            byDivision = d;
            artilleryByArmy = a;
        }
        ownArmy = armyOf(localPc);
    }

    private int armyOf(int pc) {
        int[] d = byDivision;
        if (d == null) {
            return 0;
        }
        int perArmy = d.length / artilleryByArmy.length;
        for (int i = 0; i < d.length; i++) {
            if (d[i] == pc) {
                return i / perArmy;
            }
        }
        return 0;
    }
}

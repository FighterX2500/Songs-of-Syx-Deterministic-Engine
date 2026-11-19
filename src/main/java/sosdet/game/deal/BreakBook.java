package sosdet.game.deal;

import java.io.IOException;

import sosdet.hash.HashLog;

/**
 * Who made the last stance change between each human and each NPC, for the betrayal of a war (SOS-227, owner
 * 2026-11-18). Vanilla scales a war declaration's betrayal by the treaty's freshness, the time since the last stance
 * change, whoever made it, so a war on an NPC that had just cancelled its treaties counted as a fresh betrayal.
 *
 * <p>The book marks a pair when a change the human didn't make lowered their stance: an NPC's court cancelling a
 * treaty, an NPC's war. Any later change clears the mark, and a change the human makes never sets it. A war the human
 * declares on a marked NPC with no treaty left between them costs no betrayal ({@link #warIsFree}); any other war,
 * a treaty the human broke first included, costs vanilla's.
 *
 * <p>World state: every PC changes it at the same steps, it is saved with the world ({@code GameBreaks}) and in the
 * world hash. Pairs are keyed {@code human * slots + npc}; slots are faction indexes. Plain Java, no game classes; only
 * the save allocates.
 */
public final class BreakBook {

    private static final int VERSION = 1;

    private final int slots;
    private final boolean[] broke;
    private int size;

    public BreakBook(int slots) {
        if (slots <= 0) {
            throw new IllegalArgumentException("SOSDET: a break book of " + slots + " slots");
        }
        this.slots = slots;
        this.broke = new boolean[slots * slots];
    }

    /**
     * True when a change from the stance before to the one after lowers it: war is below every other stance, and the
     * others go by vanilla's loyalty (neutral 0, trade 0.1, colleagues 0.4, allies, vassals and overlords 1).
     */
    public static boolean lowers(boolean wasWar, double wasLoyalty, boolean war, double loyalty) {
        if (wasWar) {
            return false;
        }
        return war || loyalty < wasLoyalty;
    }

    /** A stance change between {@code human} and {@code npc}, by anyone: marked when it {@code lowered} it, else cleared. */
    public void changed(int human, int npc, boolean lowered) {
        set(key(human, npc), lowered);
    }

    /** A stance change {@code human} made with {@code npc}: never a break, whatever it was. */
    public void madeByHuman(int human, int npc) {
        set(key(human, npc), false);
    }

    /** True when the last change between {@code human} and {@code npc} was one the human didn't make, lowering it. */
    public boolean broke(int human, int npc) {
        return broke[key(human, npc)];
    }

    /**
     * The rule: a war {@code human} declares on {@code npc} costs no betrayal when the NPC broke their stance last and
     * left no treaty ({@code neutral}: their stance before the war is neutral).
     */
    public boolean warIsFree(int human, int npc, boolean neutral) {
        return neutral && broke(human, npc);
    }

    /** A gone faction's pairs, as a human or as an NPC: a slot taken again starts with none. */
    public void forget(int slot) {
        if (slot < 0 || slot >= slots) {
            throw new IllegalArgumentException("SOSDET: no break book slot " + slot);
        }
        for (int i = 0; i < slots; i++) {
            set(slot * slots + i, false);
            set(i * slots + slot, false);
        }
    }

    public boolean empty() {
        return size == 0;
    }

    /** Mixes every marked pair into {@code h}, in key order. Allocates nothing. */
    public long hash(long h) {
        for (int k = 0; k < broke.length; k++) {
            if (broke[k]) {
                h = HashLog.mix(h, k);
            }
        }
        return h;
    }

    /** The book as ints, for the save: the version, the count, then each marked pair's key, in key order. */
    public int[] toInts() {
        int[] v = new int[2 + size];
        v[0] = VERSION;
        v[1] = size;
        int at = 2;
        for (int k = 0; k < broke.length; k++) {
            if (broke[k]) {
                v[at++] = k;
            }
        }
        return v;
    }

    /** A book of {@code slots} from {@link #toInts}. */
    public static BreakBook fromInts(int slots, int[] v) throws IOException {
        if (v.length < 2 || v[0] != VERSION) {
            throw new IOException("SOSDET: a break book of version " + (v.length == 0 ? "none" : v[0]) + ", this engine reads "
                    + VERSION);
        }
        int n = v[1];
        if (n < 0 || v.length != 2 + n) {
            throw new IOException("SOSDET: a break book of " + v.length + " ints for " + n + " pairs");
        }
        BreakBook b = new BreakBook(slots);
        for (int i = 0; i < n; i++) {
            int k = v[2 + i];
            if (k < 0 || k >= b.broke.length || (i > 0 && k <= v[1 + i])) {
                throw new IOException("SOSDET: a break book's pair " + k + " out of order or range at " + i);
            }
            b.set(k, true);
        }
        return b;
    }

    private void set(int k, boolean on) {
        if (broke[k] != on) {
            broke[k] = on;
            size += on ? 1 : -1;
        }
    }

    private int key(int human, int npc) {
        if (human < 0 || human >= slots || npc < 0 || npc >= slots) {
            throw new IllegalArgumentException("SOSDET: no stance pair of human " + human + " and NPC " + npc);
        }
        return human * slots + npc;
    }
}

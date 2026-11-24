package sosdet.game;

import java.util.List;

import sosdet.ArmyOrder;

/**
 * The latest world army orders and creations (SOS-249), every faction's, in the order given in the step's world part.
 * Every PC gives the same orders at the same steps, so every PC records the same. A record for the logs only: it is not
 * saved, not hashed and never read by the simulation. Recording writes into preallocated arrays and allocates nothing
 * (it runs in the step). Plain Java.
 *
 * <p>A stop and another order of the same army at the same step make one entry, the order: the game's move, siege,
 * raid, intercept and teleport stop the army first. So a stop that really follows another order of the same army
 * within one step is not recorded either (a known limit).
 *
 * <p>A resync drops the entries of the steps after it ({@link #cut}), which the world part runs again. Numbers are
 * never given twice, so a reader that read past the cut reads only the new entries; a PC that had run past the resync
 * step has a gap in its numbers there.
 */
public final class ArmyOrders {

    public static final int CAPACITY = 8192;
    private static final ArmyOrder.Kind[] KINDS = ArmyOrder.Kind.values();
    private static final int STOP = ArmyOrder.Kind.STOP.ordinal();

    /** The number each slot holds; 0 for none, or one a resync dropped. */
    private final long[] seqAt = new long[CAPACITY];
    private final long[] step = new long[CAPACITY];
    private final int[] army = new int[CAPACITY];
    private final int[] faction = new int[CAPACITY];
    private final int[] kind = new int[CAPACITY];
    private final int[] x = new int[CAPACITY];
    private final int[] y = new int[CAPACITY];
    private final int[] target = new int[CAPACITY];
    /** The number given last; 0 before the first. */
    private long last;

    /** In the step: one order (see the class comment for a stop next to another order of the same army). */
    public void record(long atStep, int a, int f, ArmyOrder.Kind k, int tx, int ty, int t) {
        int p = (int) (last % CAPACITY);
        boolean sameArmy = last > 0 && seqAt[p] == last && army[p] == a && step[p] == atStep;
        int i;
        if (sameArmy && k == ArmyOrder.Kind.STOP && kind[p] != STOP) {
            return; // the stop the order before began with (a teleport's)
        } else if (sameArmy && kind[p] == STOP && k != ArmyOrder.Kind.CREATE) {
            i = p; // the order the stop before began: it takes the stop's place and number
        } else {
            i = (int) (++last % CAPACITY);
            seqAt[i] = last;
        }
        step[i] = atStep;
        army[i] = a;
        faction[i] = f;
        kind[i] = k.ordinal();
        x[i] = tx;
        y[i] = ty;
        target[i] = t;
    }

    /**
     * A resync to the end of {@code toStep} (SOS-249 review): drops the entries of later steps, which the world part
     * runs again; numbering goes on after the last number given.
     */
    public void cut(long toStep) {
        for (long s = last; s > 0 && s > last - CAPACITY; s--) {
            int i = (int) (s % CAPACITY);
            if (seqAt[i] != s) {
                continue; // dropped by an earlier resync
            }
            if (step[i] <= toStep) {
                return;
            }
            seqAt[i] = 0;
        }
    }

    /** The number given last; 0 before the first. It may be one a resync dropped since. */
    public long last() {
        return last;
    }

    /**
     * Between steps: adds to {@code out} the entries after {@code afterSeq} that are still kept, oldest first, and
     * returns how many numbers after it were already overwritten (a number a resync dropped counts too).
     */
    public long read(long afterSeq, List<ArmyOrder> out) {
        long from = Math.max(afterSeq + 1, last - CAPACITY + 1);
        long lost = Math.max(0, from - (afterSeq + 1));
        for (long s = Math.max(from, 1); s <= last; s++) {
            int i = (int) (s % CAPACITY);
            if (seqAt[i] == s) {
                out.add(new ArmyOrder(s, step[i], army[i], faction[i], KINDS[kind[i]], x[i], y[i], target[i]));
            }
        }
        return lost;
    }
}

package sosdet.game;

import java.util.List;

import sosdet.ArmyOrder;

/**
 * The latest world army orders and creations (SOS-249), every faction's, in the order given in the step's world part.
 * Every PC gives the same orders at the same steps, so every PC records the same. A record for the logs only: it is not
 * saved, not hashed and never read by the simulation. Recording writes into preallocated arrays and allocates nothing
 * (it runs in the step). Plain Java.
 */
public final class ArmyOrders {

    public static final int CAPACITY = 8192;
    private static final ArmyOrder.Kind[] KINDS = ArmyOrder.Kind.values();

    private final long[] step = new long[CAPACITY];
    private final int[] army = new int[CAPACITY];
    private final int[] faction = new int[CAPACITY];
    private final int[] kind = new int[CAPACITY];
    private final int[] x = new int[CAPACITY];
    private final int[] y = new int[CAPACITY];
    private final int[] target = new int[CAPACITY];
    /** The sequence number of the latest entry; 0 before the first. */
    private long last;

    /**
     * In the step: one order. A stop right after another order of the same army at the same step is not recorded: the
     * game's move, siege, raid, intercept and teleport stop the army first, so it is part of that order.
     */
    public void record(long atStep, int a, int f, ArmyOrder.Kind k, int tx, int ty, int t) {
        if (k == ArmyOrder.Kind.STOP && last > 0) {
            int p = (int) (last % CAPACITY);
            if (army[p] == a && step[p] == atStep && kind[p] != ArmyOrder.Kind.STOP.ordinal()) {
                return;
            }
        }
        int i = (int) (++last % CAPACITY);
        step[i] = atStep;
        army[i] = a;
        faction[i] = f;
        kind[i] = k.ordinal();
        x[i] = tx;
        y[i] = ty;
        target[i] = t;
    }

    /** The sequence number of the latest entry; 0 before the first. */
    public long last() {
        return last;
    }

    /**
     * Between steps: adds to {@code out} the entries after {@code afterSeq} that are still kept, oldest first, and
     * returns how many after it were already overwritten.
     */
    public long read(long afterSeq, List<ArmyOrder> out) {
        long from = Math.max(afterSeq + 1, last - CAPACITY + 1);
        long lost = Math.max(0, from - (afterSeq + 1));
        for (long s = Math.max(from, 1); s <= last; s++) {
            int i = (int) (s % CAPACITY);
            out.add(new ArmyOrder(s, step[i], army[i], faction[i], KINDS[kind[i]], x[i], y[i], target[i]));
        }
        return lost;
    }
}

package sosdet.txn;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * The world part's changes to the city, in the order it made them (ADR 0005 §3). They apply in the step's city tail,
 * right after the world part, so the queue is empty between steps and a save never holds one. Each applies exactly
 * once: one whose ID is not past the last applied changes nothing (a world reloaded behind the city).
 */
public final class TxnQueue {

    /** Transactions one step may make: the sequence number's bits in an ID. */
    static final int SEQ_BITS = 20;

    private final ArrayList<Txn> pool = new ArrayList<>();
    private int size;
    private long step = -1;
    private int seq;
    private long lastApplied = -1;
    private long applied;
    private long skipped;
    private int maxInStep;
    private Txn current;

    /** A new transaction made at this world step, to fill in. */
    public Txn add(long atStep) {
        if (atStep != step) {
            step = atStep;
            seq = 0;
        }
        if (seq == 1 << SEQ_BITS) {
            throw new IllegalStateException("SOSDET: more than " + (1 << SEQ_BITS) + " transactions in step " + atStep);
        }
        if (size == pool.size()) {
            pool.add(new Txn());
        }
        Txn t = pool.get(size++);
        t.id = (atStep << SEQ_BITS) | seq++;
        return t;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Applies every queued transaction in order, each once, and empties the queue. */
    /** The transaction being applied, while the game code it calls runs; null otherwise. */
    public Txn applying() {
        return current;
    }

    public void apply(Consumer<Txn> applier) {
        maxInStep = Math.max(maxInStep, size);
        for (int k = 0; k < size; k++) {
            Txn t = pool.get(k);
            if (t.id <= lastApplied) {
                skipped++;
            } else {
                current = t;
                try {
                    applier.accept(t);
                } finally {
                    current = null;
                }
                lastApplied = t.id;
                applied++;
            }
            t.clear();
        }
        size = 0;
    }

    /**
     * A load drops what the old world made, and the reloaded world numbers its steps' transactions from the start
     * again; the city's last applied ID stays (ADR 0005 §5).
     */
    public void dropQueued() {
        for (int k = 0; k < size; k++) {
            pool.get(k).clear();
        }
        size = 0;
        step = -1;
    }

    /** A load: the loaded city holds every transaction of the steps up to {@code atStep}, and none after. */
    public void appliedThrough(long atStep) {
        dropQueued();
        lastApplied = ((atStep + 1) << SEQ_BITS) - 1;
    }

    public long applied() {
        return applied;
    }

    public long skipped() {
        return skipped;
    }

    public int maxInStep() {
        return maxInStep;
    }

    public void resetStats() {
        applied = 0;
        skipped = 0;
        maxInStep = 0;
    }
}

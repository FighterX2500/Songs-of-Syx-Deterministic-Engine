package sosdet.ai;

import java.util.List;

import sosdet.Applied;

/**
 * The latest AI log entries applied or skipped (SOS-249): one entry for each the log's {@code stepBegin} takes, every
 * kind, the general's and the players', in the order applied. Every PC applies the same entries at the same steps, so
 * every PC records the same. A record for the logs only: it is not saved, not hashed and never read by the simulation.
 * Recording writes into preallocated arrays and allocates nothing (it runs in the step). Plain Java.
 */
public final class AppliedLog {

    public static final int CAPACITY = 16384;

    private final long[] step = new long[CAPACITY];
    private final int[] logSeq = new int[CAPACITY];
    private final int[] kind = new int[CAPACITY];
    private final int[] pc = new int[CAPACITY];
    private final int[] div = new int[CAPACITY];
    private final int[] command = new int[CAPACITY];
    private final boolean[] skipped = new boolean[CAPACITY];
    /** The sequence number of the latest entry; 0 before the first. */
    private long last;

    /** In the step, before the entry applies: records it, not skipped, and returns its number. */
    long record(long atStep, AiEntry e) {
        int i = (int) (++last % CAPACITY);
        step[i] = atStep;
        logSeq[i] = e.seq;
        kind[i] = e.kind;
        pc[i] = e.pc;
        div[i] = e.div;
        command[i] = e.kind == AiEntry.COMMAND ? e.commandId() : -1;
        skipped[i] = false;
        return last;
    }

    /** In the step: the entry numbered {@code seq} was skipped on every PC alike; nothing if it was overwritten. */
    public void skip(long seq) {
        if (seq > 0 && seq <= last && seq > last - CAPACITY) {
            skipped[(int) (seq % CAPACITY)] = true;
        }
    }

    /** The sequence number of the latest entry; 0 before the first. While an entry applies, that entry's. */
    public long last() {
        return last;
    }

    /**
     * Between steps: adds to {@code out} the entries after {@code afterSeq} that are still kept, oldest first, and
     * returns how many after it were already overwritten.
     */
    public long read(long afterSeq, List<Applied> out) {
        long from = Math.max(afterSeq + 1, last - CAPACITY + 1);
        long lost = Math.max(0, from - (afterSeq + 1));
        for (long s = Math.max(from, 1); s <= last; s++) {
            int i = (int) (s % CAPACITY);
            out.add(new Applied(s, step[i], logSeq[i], kind[i], pc[i], div[i], command[i], skipped[i]));
        }
        return lost;
    }
}

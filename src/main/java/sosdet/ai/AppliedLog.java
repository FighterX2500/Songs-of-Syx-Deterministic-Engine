package sosdet.ai;

import java.util.List;

import sosdet.Applied;

/**
 * The latest AI log entries applied or skipped (SOS-249): one entry for each the log's {@code stepBegin} takes, every
 * kind, the general's and the players', in the order applied. Every PC applies the same entries at the same steps, so
 * every PC records the same entries; only whether one was skipped may differ (see {@link Applied#skipped}). A record
 * for the logs only: it is not saved, not hashed and never read by the simulation. Recording writes into preallocated
 * arrays and allocates nothing (it runs in the step). Plain Java.
 *
 * <p>A resync drops the entries of the steps after it ({@link #cut}), which the PC applies again. Numbers are never
 * given twice, so a reader that read past the cut reads only the new entries; a PC that had run past the resync step
 * has a gap in its numbers there.
 */
public final class AppliedLog {

    public static final int CAPACITY = 16384;

    /** The number each slot holds; 0 for none, or one a resync dropped. */
    private final long[] seqAt = new long[CAPACITY];
    private final long[] step = new long[CAPACITY];
    private final int[] logSeq = new int[CAPACITY];
    private final int[] kind = new int[CAPACITY];
    private final int[] pc = new int[CAPACITY];
    private final int[] div = new int[CAPACITY];
    private final int[] command = new int[CAPACITY];
    private final boolean[] skipped = new boolean[CAPACITY];
    /** The number given last; 0 before the first. */
    private long last;

    /** In the step, before the entry applies: records it, not skipped, and returns its number. */
    long record(long atStep, AiEntry e) {
        int i = (int) (++last % CAPACITY);
        seqAt[i] = last;
        step[i] = atStep;
        logSeq[i] = e.seq;
        kind[i] = e.kind;
        pc[i] = e.pc;
        div[i] = e.div;
        command[i] = e.kind == AiEntry.COMMAND ? e.commandId() : -1;
        skipped[i] = false;
        return last;
    }

    /** In the step: the entry numbered {@code seq} was skipped; nothing for -1, or one overwritten or dropped. */
    public void skip(long seq) {
        if (seq > 0 && seqAt[(int) (seq % CAPACITY)] == seq) {
            skipped[(int) (seq % CAPACITY)] = true;
        }
    }

    /**
     * A resync to the end of {@code toStep} (SOS-249 review): drops the entries of later steps, which this PC applies
     * again; numbering goes on after the last number given.
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
    public long read(long afterSeq, List<Applied> out) {
        long from = Math.max(afterSeq + 1, last - CAPACITY + 1);
        long lost = Math.max(0, from - (afterSeq + 1));
        for (long s = Math.max(from, 1); s <= last; s++) {
            int i = (int) (s % CAPACITY);
            if (seqAt[i] == s) {
                out.add(new Applied(s, step[i], logSeq[i], kind[i], pc[i], div[i], command[i], skipped[i]));
            }
        }
        return lost;
    }
}

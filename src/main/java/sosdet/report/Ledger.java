package sosdet.report;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The world's own changes to the city that the report it reads doesn't show yet (ADR 0005 §3). A transaction made at
 * world step N applies at the end of step N, so the first report to show it is one taken after N. Until that report
 * applies, the world part reads the report's value plus the change. So a report of any age, however long it is in
 * flight at a high speed (SOS-90), is never spent twice. Changes are whole numbers (credits and goods), kept as longs so the sums are exact.
 */
final class Ledger {

    private long[] steps = new long[64];
    private int[] slots = new int[64];
    private long[] deltas = new long[64];
    private int head;
    private int size;
    private long[] pending = new long[0];

    void add(long step, int slot, long delta) {
        if (delta == 0) {
            return;
        }
        if (size == steps.length) {
            grow();
        }
        int k = (head + size) % steps.length;
        steps[k] = step;
        slots[k] = slot;
        deltas[k] = delta;
        size++;
        if (slot >= pending.length) {
            pending = Arrays.copyOf(pending, Math.max(slot + 1, pending.length * 2));
        }
        pending[slot] += delta;
    }

    /** A report taken at {@code takenAt} now applies: the changes made before that step are in it. */
    void reportApplied(long takenAt) {
        while (size > 0 && steps[head] < takenAt) {
            pending[slots[head]] -= deltas[head];
            head = (head + 1) % steps.length;
            size--;
        }
    }

    long pending(int slot) {
        return slot < pending.length ? pending[slot] : 0;
    }

    int size() {
        return size;
    }

    /** The open changes in order: their count, then step, slot and change each. */
    byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(4 + size * (8 + 4 + 8));
        b.putInt(size);
        for (int k = 0; k < size; k++) {
            int i = (head + k) % steps.length;
            b.putLong(steps[i]).putInt(slots[i]).putLong(deltas[i]);
        }
        return b.array();
    }

    /** Replaces the open changes with these ({@link #toBytes}). */
    void fromBytes(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        clear();
        try {
            int n = b.getInt();
            if (n < 0) {
                throw new IllegalArgumentException("SOSDET: a ledger of " + n + " changes");
            }
            for (int k = 0; k < n; k++) {
                add(b.getLong(), b.getInt(), b.getLong());
            }
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("SOSDET: ledger cut short (" + bytes.length + " bytes)", e);
        }
        if (b.hasRemaining()) {
            throw new IllegalArgumentException("SOSDET: " + b.remaining() + " bytes after the ledger");
        }
    }

    void clear() {
        head = 0;
        size = 0;
        Arrays.fill(pending, 0);
    }

    private void grow() {
        int n = steps.length;
        long[] s = new long[n * 2];
        int[] sl = new int[n * 2];
        long[] d = new long[n * 2];
        for (int k = 0; k < size; k++) {
            int from = (head + k) % n;
            s[k] = steps[from];
            sl[k] = slots[from];
            d[k] = deltas[from];
        }
        steps = s;
        slots = sl;
        deltas = d;
        head = 0;
    }
}

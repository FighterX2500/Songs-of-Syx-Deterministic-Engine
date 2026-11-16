package sosdet.report;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Each playing human's last report the world read and the world's open changes to it, as a world save holds them
 * (SOSDET_FROZEN, format 2). Read only when a resume freezes a missing player's human at the start: no PC sends that
 * human's report then, so every PC reads this one. By slot; a slot without one has none.
 */
public final class LastReports {

    private final byte[][] report;
    private final byte[][] ledger;

    public LastReports(int slots) {
        report = new byte[slots][];
        ledger = new byte[slots][];
    }

    public void put(int slot, byte[] reportBytes, byte[] ledgerBytes) {
        if (reportBytes == null || ledgerBytes == null) {
            throw new IllegalArgumentException("SOSDET: a last report of slot " + slot + " without its report or ledger");
        }
        report[slot] = reportBytes;
        ledger[slot] = ledgerBytes;
    }

    /** The slot's {report, ledger}, or null for none. */
    public byte[][] get(int slot) {
        return report[slot] == null ? null : new byte[][] {report[slot], ledger[slot]};
    }

    public void remove(int slot) {
        report[slot] = null;
        ledger[slot] = null;
    }

    public void clear() {
        Arrays.fill(report, null);
        Arrays.fill(ledger, null);
    }

    /** Their count, then slot, report and ledger each, the byte arrays by length, in slot order. */
    public byte[] toBytes() {
        int n = 0;
        int size = 4;
        for (int slot = 0; slot < report.length; slot++) {
            if (report[slot] != null) {
                n++;
                size += 4 + 4 + report[slot].length + 4 + ledger[slot].length;
            }
        }
        ByteBuffer b = ByteBuffer.allocate(size);
        b.putInt(n);
        for (int slot = 0; slot < report.length; slot++) {
            if (report[slot] != null) {
                b.putInt(slot).putInt(report[slot].length).put(report[slot]).putInt(ledger[slot].length).put(ledger[slot]);
            }
        }
        return b.array();
    }

    /** Replaces them with these ({@link #toBytes}); refused when cut short, out of range or followed by more. */
    public void fromBytes(byte[] bytes) {
        byte[][] r = new byte[report.length][];
        byte[][] l = new byte[ledger.length][];
        ByteBuffer b = ByteBuffer.wrap(bytes);
        try {
            int n = b.getInt();
            if (n < 0 || n > r.length) {
                throw new IllegalArgumentException("SOSDET: " + n + " last reports");
            }
            for (int i = 0; i < n; i++) {
                int slot = b.getInt();
                if (slot < 0 || slot >= r.length || r[slot] != null) {
                    throw new IllegalArgumentException("SOSDET: a last report of slot " + slot);
                }
                r[slot] = bytes(b);
                l[slot] = bytes(b);
            }
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("SOSDET: last reports cut short (" + bytes.length + " bytes)", e);
        }
        if (b.hasRemaining()) {
            throw new IllegalArgumentException("SOSDET: " + b.remaining() + " bytes after the last reports");
        }
        System.arraycopy(r, 0, report, 0, r.length);
        System.arraycopy(l, 0, ledger, 0, l.length);
    }

    private static byte[] bytes(ByteBuffer b) {
        int n = b.getInt();
        if (n < 0 || n > b.remaining()) {
            throw new IllegalArgumentException("SOSDET: a last report's part of " + n + " bytes");
        }
        byte[] out = new byte[n];
        b.get(out);
        return out;
    }
}

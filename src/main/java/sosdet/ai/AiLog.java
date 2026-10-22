package sosdet.ai;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The AI log (hook B15; ADR 0003, amended in SOS-23).
 *
 * <p>Host: the general's changes are captured from its own thread. At the start of step N the simulation thread
 * stamps them N + {@link #DELAY_STEPS}, numbers them, and writes them to the outgoing log. Peer: the host's log
 * comes in; the peer never runs past the host's last confirmed step + {@link #DELAY_STEPS}. Every PC, host included,
 * applies each entry at its step, in sequence order, from the same bytes.
 *
 * <p>The log as bytes: a header (magic, format version, delay, start ID), then records of a tag, a length and a body:
 * an entry (step, sequence, kind, division, payload) or a confirmation (the host's last drained step).
 */
public final class AiLog {

    /** Steps between the host taking a change and every PC applying it: 0.5 s. */
    public static final int DELAY_STEPS = 30;
    static final int FORMAT_VERSION = 1;
    static final int MAGIC = 0x53444149; // "SDAI"
    static final int HEADER_BYTES = 4 + 4 + 4 + 8;
    static final byte ENTRY = 1;
    static final byte CONFIRM = 2;

    private final AiMode mode;
    private final long startId;
    private final AiApplier applier;

    private final ConcurrentLinkedQueue<AiEntry> captured = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<AiEntry> pending = new ArrayDeque<>();
    private volatile Thread generalThread;
    /** Host: the next sequence number to give. Peer: the next one expected. */
    private int nextSeq;
    /** Host: the last step drained. Peer: the host's last confirmed step. */
    private long confirmed;
    private long applied;
    private final long[] appliedByKind = new long[AiEntry.KINDS];

    private ByteBuffer out = ByteBuffer.allocate(1 << 12);
    private boolean headerWritten;
    private ByteBuffer in = ByteBuffer.allocate(1 << 12);
    private boolean headerRead;

    private AiLog(AiMode mode, long startId, AiApplier applier) {
        this.mode = mode;
        this.startId = startId;
        this.applier = applier;
    }

    public static AiLog inStep() {
        return new AiLog(AiMode.IN_STEP, 0, null);
    }

    /** The host's log; {@code startId} names the start state, and a peer refuses a log made from another. */
    public static AiLog host(long startId, AiApplier applier) {
        return new AiLog(AiMode.HOST, startId, require(applier));
    }

    public static AiLog peer(long startId, AiApplier applier) {
        return new AiLog(AiMode.PEER, startId, require(applier));
    }

    private static AiApplier require(AiApplier applier) {
        if (applier == null) {
            throw new IllegalArgumentException("SOSDET: an AI log needs an applier");
        }
        return applier;
    }

    public AiMode mode() {
        return mode;
    }

    /** The game adapter that applies the entries (host and peer); null in-step. */
    public AiApplier applier() {
        return applier;
    }

    // ------------------------------------------------------------ the general's thread (host)

    /** The host's general thread is running its job (it calls this from there). */
    public void generalThreadIs(Thread thread) {
        if (mode == AiMode.HOST) {
            generalThread = thread;
        }
    }

    /** True on the host's general thread: its changes go into the log instead of the live state. */
    public boolean capturing() {
        return mode == AiMode.HOST && Thread.currentThread() == generalThread;
    }

    /** A change from the general's thread; applied later, at its stamped step. */
    public void capture(int kind, int div, byte[] payload) {
        if (mode != AiMode.HOST) {
            throw new IllegalStateException("SOSDET: AI capture in mode " + mode);
        }
        captured.add(new AiEntry(-1, -1, kind, div, payload.clone()));
    }

    // ------------------------------------------------------------ the step (simulation thread)

    /** Whether a peer may run this step: never past the host's last confirmed step + the delay. */
    public boolean mayRun(long step) {
        return mode != AiMode.PEER || step <= confirmed + DELAY_STEPS;
    }

    /** Start of a step: the host stamps what the general captured; every PC applies the entries due now. */
    public void stepBegin(long step) {
        if (mode == AiMode.IN_STEP) {
            return;
        }
        if (mode == AiMode.HOST) {
            AiEntry e;
            while ((e = captured.poll()) != null) {
                AiEntry stamped = e.stamped(step + DELAY_STEPS, nextSeq++);
                writeEntry(stamped);
                pending.add(stamped);
            }
            confirmed = step;
        }
        while (!pending.isEmpty() && pending.peekFirst().step <= step) {
            AiEntry due = pending.pollFirst();
            if (due.step != step) {
                throw new IllegalStateException("SOSDET: AI entry " + due.seq + " for step " + due.step
                        + " is still pending at step " + step);
            }
            applier.apply(due);
            applied++;
            appliedByKind[due.kind]++;
        }
    }

    // ------------------------------------------------------------ the log as bytes

    /** Host: the log written since the last call (the header first), ending with the last confirmed step. */
    public byte[] logOut() {
        if (mode != AiMode.HOST) {
            throw new IllegalStateException("SOSDET: logOut in mode " + mode);
        }
        ensureHeader();
        ensureOut(1 + 4 + 8);
        out.put(CONFIRM).putInt(8).putLong(confirmed);
        byte[] bytes = Arrays.copyOf(out.array(), out.position());
        out.clear();
        return bytes;
    }

    /** Peer: more of the host's log; any split of the bytes works, a partial record waits for the rest. */
    public void logIn(byte[] bytes) {
        if (mode != AiMode.PEER) {
            throw new IllegalStateException("SOSDET: logIn in mode " + mode);
        }
        if (in.remaining() < bytes.length) {
            ByteBuffer bigger = ByteBuffer.allocate(Math.max(in.capacity() * 2, in.position() + bytes.length));
            in.flip();
            bigger.put(in);
            in = bigger;
        }
        in.put(bytes);
        in.flip();
        try {
            if (!headerRead) {
                if (in.remaining() < HEADER_BYTES) {
                    return;
                }
                readHeader();
            }
            while (in.remaining() >= 5) {
                in.mark();
                byte tag = in.get();
                int length = in.getInt();
                if (in.remaining() < length) {
                    in.reset();
                    break;
                }
                if (tag == ENTRY) {
                    readEntry();
                } else if (tag == CONFIRM) {
                    confirmed = Math.max(confirmed, in.getLong());
                } else {
                    throw new IllegalStateException("SOSDET: AI log record tag " + tag);
                }
            }
        } finally {
            in.compact();
        }
    }

    private void readHeader() {
        int magic = in.getInt();
        int version = in.getInt();
        int delay = in.getInt();
        long id = in.getLong();
        if (magic != MAGIC || version != FORMAT_VERSION) {
            throw new IllegalStateException("SOSDET: not an AI log of format " + FORMAT_VERSION + " (magic "
                    + Integer.toHexString(magic) + ", version " + version + ")");
        }
        if (delay != DELAY_STEPS) {
            throw new IllegalStateException("SOSDET: AI log delay " + delay + ", this engine uses " + DELAY_STEPS);
        }
        if (id != startId) {
            throw new IllegalStateException("SOSDET: AI log made from another start (" + Long.toHexString(id) + ", expected "
                    + Long.toHexString(startId) + ")");
        }
        headerRead = true;
    }

    private void readEntry() {
        long step = in.getLong();
        int seq = in.getInt();
        int kind = in.get();
        int div = in.getShort();
        byte[] payload = new byte[in.getInt()];
        in.get(payload);
        if (seq < nextSeq) {
            return; // a repeated entry changes nothing
        }
        if (seq > nextSeq) {
            throw new IllegalStateException("SOSDET: AI log entry " + nextSeq + " missing (got " + seq + ")");
        }
        nextSeq++;
        pending.add(new AiEntry(step, seq, kind, div, payload));
    }

    private void writeEntry(AiEntry e) {
        ensureHeader();
        int body = 8 + 4 + 1 + 2 + 4 + e.payloadLength();
        ensureOut(1 + 4 + body);
        out.put(ENTRY).putInt(body).putLong(e.step).putInt(e.seq).put((byte) e.kind).putShort((short) e.div)
                .putInt(e.payloadLength());
        e.payloadTo(out);
    }

    private void ensureHeader() {
        if (!headerWritten) {
            ensureOut(HEADER_BYTES);
            out.putInt(MAGIC).putInt(FORMAT_VERSION).putInt(DELAY_STEPS).putLong(startId);
            headerWritten = true;
        }
    }

    private void ensureOut(int bytes) {
        if (out.remaining() < bytes) {
            ByteBuffer bigger = ByteBuffer.allocate(Math.max(out.capacity() * 2, out.position() + bytes));
            out.flip();
            bigger.put(out);
            out = bigger;
        }
    }

    // ------------------------------------------------------------ counts

    public long applied() {
        return applied;
    }

    public long applied(int kind) {
        return appliedByKind[kind];
    }

    /** Entries known but not applied yet. */
    public int pending() {
        return pending.size() + captured.size();
    }
}

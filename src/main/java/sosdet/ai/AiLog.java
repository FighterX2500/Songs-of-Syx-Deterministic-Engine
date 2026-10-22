package sosdet.ai;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The host's log of battle changes (hooks B15 and B16; ADR 0003, amended in SOS-23): the AI general's, and the
 * players' orders and speed changes (SOS-32).
 *
 * <p>Host: the general's changes are captured from its own thread; the host's player's orders from the game's UI,
 * outside the step; a peer's player's orders arrive through {@link #ordersIn}. At the start of step N the simulation
 * thread stamps them N + {@link #DELAY_STEPS}, numbers them, and writes them to the outgoing log. Peer: its player's
 * orders go out through {@link #ordersOut}; the host's log comes in, and the peer never runs past the host's last
 * confirmed step + {@link #DELAY_STEPS}. Every PC, host included, applies each entry at its step, in sequence order,
 * from the same bytes.
 *
 * <p>The log as bytes: a header (magic, format version, delay, start ID), then records of a tag, a length and a body:
 * an entry (step, sequence, kind, division, PC, payload) or a confirmation (the host's last drained step).
 */
public final class AiLog {

    /** Steps between the host taking a change and every PC applying it: 0.5 s at 1x. */
    public static final int DELAY_STEPS = 30;
    static final int FORMAT_VERSION = 3;
    static final int MAGIC = 0x53444149; // "SDAI"
    static final int HEADER_BYTES = 4 + 4 + 4 + 8;
    static final byte ENTRY = 1;
    static final byte CONFIRM = 2;
    /** Applied entries kept for a desync report, the general's and the players' each. */
    static final int RECENT = 32;
    /** Applied entries kept for a resync to an earlier step: a peer is never more than the delay past the host. */
    static final int HISTORY_STEPS = 4 * DELAY_STEPS;

    private final AiMode mode;
    private final long startId;
    private final AiApplier applier;

    private final ConcurrentLinkedQueue<AiEntry> captured = new ConcurrentLinkedQueue<>();
    /**
     * Host: the general's changes since the last step, one per order slot (kind and division), in the order each slot
     * was first changed. The general's loop can write one slot thousands of times a step; only the last value is
     * logged. Guarded by itself.
     */
    private final LinkedHashMap<Integer, AiEntry> aiBatch = new LinkedHashMap<>();
    private long coalesced;
    private final ArrayDeque<AiEntry> pending = new ArrayDeque<>();
    private volatile Thread generalThread;
    /** Host: the next sequence number to give. Peer: the next one expected. */
    private int nextSeq;
    /** Host: the last step drained. Peer: the host's last confirmed step. */
    private long confirmed;
    private long applied;
    /** The mod's number for this PC (the host is 0), and the issuer of the entry being applied, if any. */
    private int localPc;
    private int applyingPc = NOT_APPLYING;
    /** This PC's last captured command until it applies, and how many repeats of it were dropped. */
    private byte[] lastCommand;
    private long repeatsDropped;
    private static final int NOT_APPLYING = Integer.MIN_VALUE;
    private long appliedOrders;
    private final long[] appliedByKind = new long[AiEntry.KINDS];
    private final Recent recentAi = new Recent();
    private final Recent recentOrders = new Recent();
    /** Peer: entries applied in the last {@link #HISTORY_STEPS}, oldest first. */
    private final ArrayDeque<AiEntry> history = new ArrayDeque<>();
    /** The players' orders: a peer's going out, the host's coming in from each peer. */
    private final Orders orders;

    private ByteBuffer out = ByteBuffer.allocate(1 << 12);
    private boolean headerWritten;
    private ByteBuffer in = ByteBuffer.allocate(1 << 12);
    private boolean headerRead;

    private AiLog(AiMode mode, long startId, AiApplier applier) {
        this.mode = mode;
        this.startId = startId;
        this.applier = applier;
        this.orders = new Orders(startId);
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
        AiEntry e = new AiEntry(-1, -1, kind, div, AiEntry.AI, payload.clone());
        synchronized (aiBatch) {
            if (aiBatch.put(kind << 16 | (div & 0xFFFF), e) != null) {
                coalesced++; // the slot keeps its place; the newer value replaces the older one
            }
        }
    }

    // ------------------------------------------------------------ the players' orders (B16)

    /** True when a player's order is captured instead of applied: with a log (host or peer), and not the general. */
    public boolean capturingOrders() {
        return mode != AiMode.IN_STEP && Thread.currentThread() != generalThread;
    }

    /**
     * This PC's player gave an order: the host logs it like the general's changes (its player is PC 0); a peer sends it
     * to the host through {@link #ordersOut}. Either way it is applied at the step the host stamps.
     */
    public void captureOrder(int kind, int div, byte[] payload) {
        if (kind == AiEntry.COMMAND) {
            // A slider dragged or a button held repeats the same command every frame until it applies; once is enough.
            if (Arrays.equals(payload, lastCommand)) {
                repeatsDropped++;
                return;
            }
            lastCommand = payload.clone();
        }
        if (mode == AiMode.HOST) {
            captured.add(new AiEntry(-1, -1, kind, div, 0, payload.clone()));
        } else if (mode == AiMode.PEER) {
            orders.add(kind, div, payload);
        } else {
            throw new IllegalStateException("SOSDET: an order to capture in mode " + mode);
        }
    }

    /** Peer: its player's orders given since the last call, for the mod to send to the host. */
    public byte[] ordersOut() {
        if (mode != AiMode.PEER) {
            throw new IllegalStateException("SOSDET: ordersOut in mode " + mode);
        }
        return orders.out();
    }

    /** Host: more of peer {@code pc}'s orders, in any pieces; a repeated order changes nothing. */
    public void ordersIn(int pc, byte[] bytes) {
        if (mode != AiMode.HOST) {
            throw new IllegalStateException("SOSDET: ordersIn in mode " + mode);
        }
        if (pc <= 0) {
            throw new IllegalArgumentException("SOSDET: orders from PC " + pc + " (the host's own player is 0)");
        }
        orders.in(pc, bytes, captured);
    }

    // ------------------------------------------------------------ the step (simulation thread)

    /** Whether a peer may run this step: never past the host's last confirmed step + the delay. */
    public boolean mayRun(long step) {
        return mode != AiMode.PEER || step <= confirmed + DELAY_STEPS;
    }

    /** Start of a step: the host stamps what was captured; every PC applies the entries due now. */
    public void stepBegin(long step) {
        if (mode == AiMode.IN_STEP) {
            return;
        }
        if (mode == AiMode.HOST) {
            synchronized (aiBatch) {
                for (AiEntry b : aiBatch.values()) {
                    AiEntry stamped = b.stamped(step + DELAY_STEPS, nextSeq++);
                    writeEntry(stamped);
                    pending.add(stamped);
                }
                aiBatch.clear();
            }
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
                throw new IllegalStateException("SOSDET: AI log entry " + due.seq + " for step " + due.step
                        + " is still pending at step " + step);
            }
            applyingPc = due.pc;
            try {
                applier.apply(due);
            } finally {
                applyingPc = NOT_APPLYING;
            }
            if (due.kind == AiEntry.COMMAND && lastCommand != null && due.pc == localPc
                    && Arrays.equals(due.payload(), lastCommand)) {
                lastCommand = null; // applied: the same command may be given again
            }
            (due.pc == AiEntry.AI ? recentAi : recentOrders).add(due);
            applied++;
            appliedByKind[due.kind]++;
            if (due.pc != AiEntry.AI) {
                appliedOrders++;
            }
            if (mode == AiMode.PEER) {
                history.add(due);
            }
        }
        while (!history.isEmpty() && history.peekFirst().step < step - HISTORY_STEPS) {
            history.pollFirst();
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
        AiEntry e = getEntry(in);
        if (e.seq < nextSeq) {
            return; // a repeated entry changes nothing
        }
        if (e.seq > nextSeq) {
            throw new IllegalStateException("SOSDET: AI log entry " + nextSeq + " missing (got " + e.seq + ")");
        }
        nextSeq++;
        pending.add(e);
    }

    private void writeEntry(AiEntry e) {
        ensureHeader();
        int body = entryBytes(e);
        ensureOut(1 + 4 + body);
        out.put(ENTRY).putInt(body);
        putEntry(out, e);
    }

    private static int entryBytes(AiEntry e) {
        return 8 + 4 + 1 + 2 + 2 + 4 + e.payloadLength();
    }

    private static void putEntry(ByteBuffer b, AiEntry e) {
        b.putLong(e.step).putInt(e.seq).put((byte) e.kind).putShort((short) e.div).putShort((short) e.pc)
                .putInt(e.payloadLength());
        e.payloadTo(b);
    }

    private static AiEntry getEntry(ByteBuffer b) {
        long step = b.getLong();
        int seq = b.getInt();
        int kind = b.get();
        int div = b.getShort();
        int pc = b.getShort();
        byte[] payload = new byte[b.getInt()];
        b.get(payload);
        return new AiEntry(step, seq, kind, div, pc, payload);
    }

    // ------------------------------------------------------------ resync (B14)

    /**
     * The log's part of a resync state at the current step: the counts, and the entries due after it (the host's
     * pending ones). Empty in-step.
     */
    public byte[] resyncOut() {
        if (mode == AiMode.IN_STEP) {
            return new byte[0];
        }
        if (mode != AiMode.HOST) {
            throw new IllegalStateException("SOSDET: only the host saves a resync (mode " + mode + ")");
        }
        int bytes = 4 + 8 + 8 + 8 * AiEntry.KINDS + 4;
        for (AiEntry e : pending) {
            bytes += entryBytes(e);
        }
        ByteBuffer b = ByteBuffer.allocate(bytes);
        b.putInt(nextSeq).putLong(confirmed).putLong(applied);
        for (long n : appliedByKind) {
            b.putLong(n);
        }
        b.putInt(pending.size());
        for (AiEntry e : pending) {
            putEntry(b, e);
        }
        return b.array();
    }

    /**
     * Every PC loads a resync. The host's log is the source and stays as it is. A peer continues from the host's
     * counts with the entries due after the resync step: the host's, plus any later ones this peer already had
     * (applied past that step or still pending).
     */
    public void resyncIn(byte[] bytes) {
        if (mode == AiMode.IN_STEP) {
            if (bytes.length != 0) {
                throw new IllegalStateException("SOSDET: a resync with AI log entries, but the AI runs in-step here");
            }
            return;
        }
        if (mode == AiMode.HOST) {
            return; // the log came from here, and the host held at the resync step until its own load
        }
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int hostNextSeq = b.getInt();
        confirmed = Math.max(confirmed, b.getLong());
        applied = b.getLong();
        for (int k = 0; k < appliedByKind.length; k++) {
            appliedByKind[k] = b.getLong();
        }
        List<AiEntry> due = new ArrayList<>();
        for (int n = b.getInt(); n > 0; n--) {
            due.add(getEntry(b));
        }
        for (AiEntry e : history) {
            if (e.seq >= hostNextSeq) {
                due.add(e);
            }
        }
        for (AiEntry e : pending) {
            if (e.seq >= hostNextSeq) {
                due.add(e);
            }
        }
        due.sort((x, y) -> Integer.compare(x.seq, y.seq));
        pending.clear();
        pending.addAll(due);
        history.clear();
        nextSeq = Math.max(nextSeq, hostNextSeq);
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

    /** Host: the general's changes replaced by a later change to the same slot before their step. */
    public long coalesced() {
        synchronized (aiBatch) {
            return coalesced;
        }
    }

    /** This PC's commands dropped as repeats of the one it gave last, before that one applied. */
    public long commandRepeatsDropped() {
        return repeatsDropped;
    }

    /** Players' commands skipped because their target was gone at their step. */
    public long commandsSkipped() {
        return applier == null ? 0 : applier.commandsSkipped();
    }

    /** The mod's number for this PC: 0 on the host, from 1 on a peer. */
    public void localPc(int pc) {
        if (pc < 0) {
            throw new IllegalArgumentException("SOSDET: PC " + pc);
        }
        localPc = pc;
    }

    /** While an entry is being applied: whether this PC's player gave it (feedback such as sounds only for them). */
    public boolean issuedHere() {
        return applyingPc != NOT_APPLYING && applyingPc == localPc;
    }

    public long applied() {
        return applied;
    }

    /** The players' orders applied (speed changes included). */
    public long appliedOrders() {
        return appliedOrders;
    }

    public long applied(int kind) {
        return appliedByKind[kind];
    }

    /** The general's last entries applied, oldest first, one line each (for a desync report). */
    public List<String> recent() {
        return recentAi.lines();
    }

    /** The players' last orders applied, oldest first, one line each (for a desync report). */
    public List<String> recentOrders() {
        return recentOrders.lines();
    }

    /** Entries known but not applied yet. */
    public int pending() {
        synchronized (aiBatch) {
            return pending.size() + captured.size() + aiBatch.size();
        }
    }

    /** The last {@link #RECENT} entries applied, in a ring. */
    private static final class Recent {
        private final AiEntry[] ring = new AiEntry[RECENT];
        private long count;

        void add(AiEntry e) {
            ring[(int) (count++ % RECENT)] = e;
        }

        List<String> lines() {
            List<String> lines = new ArrayList<>();
            for (long i = Math.max(0, count - RECENT); i < count; i++) {
                lines.add(ring[(int) (i % RECENT)].line());
            }
            return lines;
        }
    }
}

package sosdet.hash;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The state hash (hook B13): every {@link #interval()} steps, at the end of the step, a hash of the battle state in
 * named sections, kept for the last {@link #RING} hashes. Other PCs' hashes come in as bytes and are compared at the
 * same steps; the first difference is kept as a {@link Desync}.
 *
 * <p>The hashes as bytes: a header (magic, format version, interval, sections, divisions), then one record per hash:
 * step, epoch, total, the sections, one hash per division's soldiers. The epoch counts resyncs: hashes taken before
 * the last resync are never compared with those taken after it.
 *
 * <p>Taking a hash ({@link #take}) runs in the step: no allocation, except once for the ring and on a desync.
 */
public final class HashLog {

    /** Steps between hashes by default: 0.5 s. */
    public static final int DEFAULT_INTERVAL = 30;
    /** Hashes kept for comparing with late ones: 32 s at the default interval. */
    public static final int RING = 64;

    public static final int CLOCK = 0;
    public static final int SOLDIERS = 1;
    public static final int DIVISIONS = 2;
    public static final int PROJECTILES = 3;
    public static final int ENTITIES = 4;
    public static final int SECTIONS = 5;
    static final String[] SECTION_NAMES = {"clock", "soldiers", "divisions", "projectiles", "entities"};

    static final int FORMAT_VERSION = 2;
    static final int MAGIC = 0x53444853; // "SDHS"
    static final int HEADER_BYTES = 4 * 5;
    static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private int interval = DEFAULT_INTERVAL;
    private int divisions = -1;
    // the ring, allocated at the first hash
    private long[] steps;
    private long[][] sections;
    private long[][] divisionHashes;
    private int taken;
    private long lastStep;
    private int epoch;

    private ByteBuffer out;
    private boolean headerWritten;
    private final List<Remote> remotes = new ArrayList<>();
    private Desync desync;

    private long compared;
    private long missed;
    private long totalNanos;
    private long maxNanos;

    /** FNV-1a over the eight bytes of a value. */
    public static long mix(long h, long v) {
        for (int b = 0; b < 8; b++) {
            h ^= (v >>> (8 * b)) & 0xff;
            h *= FNV_PRIME;
        }
        return h;
    }

    /** FNV-1a over bytes. */
    public static long mix(long h, byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            h ^= bytes[i] & 0xff;
            h *= FNV_PRIME;
        }
        return h;
    }

    public static long start() {
        return FNV_OFFSET;
    }

    // ------------------------------------------------------------ taking hashes (simulation thread)

    /** Hash every this many steps; before the first hash only. */
    public void every(int steps) {
        if (steps < 1) {
            throw new IllegalArgumentException("SOSDET: hash interval " + steps);
        }
        if (taken > 0) {
            throw new IllegalStateException("SOSDET: the hash interval is set before the first hash (" + taken + " taken)");
        }
        interval = steps;
    }

    public int interval() {
        return interval;
    }

    public boolean due(long step) {
        return step > 0 && step % interval == 0;
    }

    /** Hashes the state at the end of {@code step}; {@code clock} is the engine's own counters, already mixed. */
    public void take(long step, long clock, StateSource source) {
        long t0 = System.nanoTime();
        if (steps == null) {
            allocate(source.divisions());
        }
        int slot = taken % RING;
        long[] sec = sections[slot];
        long[] div = divisionHashes[slot];
        Arrays.fill(div, FNV_OFFSET);
        sec[CLOCK] = clock;
        source.hash(sec, div);
        long soldiers = FNV_OFFSET;
        for (long d : div) {
            soldiers = mix(soldiers, d);
        }
        sec[SOLDIERS] = soldiers;
        steps[slot] = step;
        taken++;
        lastStep = step;
        writeRecord(slot);
        for (int i = 0; i < remotes.size(); i++) {
            Remote r = remotes.get(i);
            if (taken == 1) {
                drain(r); // records that came before this PC's first hash, when their size was not known yet
            }
            compareWaiting(r);
        }
        long nanos = System.nanoTime() - t0;
        totalNanos += nanos;
        maxNanos = Math.max(maxNanos, nanos);
    }

    private void allocate(int divs) {
        if (divs < 0) {
            throw new IllegalArgumentException("SOSDET: " + divs + " divisions");
        }
        divisions = divs;
        steps = new long[RING];
        sections = new long[RING][SECTIONS];
        divisionHashes = new long[RING][divs];
        out = ByteBuffer.allocate(HEADER_BYTES + RING * recordBytes());
    }

    /** The total hash at a step still in the ring. */
    public long total(long step) {
        int slot = slotOf(step);
        if (slot < 0) {
            throw new IllegalArgumentException("SOSDET: no hash for step " + step + " (hashed every " + interval
                    + " steps, the last " + RING + " kept; last " + lastStep + ")");
        }
        return total(sections[slot]);
    }

    private static long total(long[] sec) {
        long h = FNV_OFFSET;
        for (long s : sec) {
            h = mix(h, s);
        }
        return h;
    }

    private int slotOf(long step) {
        if (steps == null || !due(step) || step > lastStep) {
            return -1;
        }
        long back = (lastStep - step) / interval;
        if (back >= Math.min(taken, RING)) {
            return -1;
        }
        int slot = (int) ((taken - 1 - back) % RING);
        return steps[slot] == step ? slot : -1;
    }

    // ------------------------------------------------------------ the hashes as bytes

    private int recordBytes() {
        return 8 + 4 + 8 + 8 * SECTIONS + 8 * divisions;
    }

    /** The hashes taken since the last call (the header first); empty before the first hash. */
    public byte[] out() {
        if (steps == null) {
            return new byte[0];
        }
        byte[] bytes = Arrays.copyOf(out.array(), out.position());
        out.clear();
        return bytes;
    }

    private void writeRecord(int slot) {
        if (!headerWritten) {
            out.putInt(MAGIC).putInt(FORMAT_VERSION).putInt(interval).putInt(SECTIONS).putInt(divisions);
            headerWritten = true;
        }
        if (out.remaining() < recordBytes()) {
            return; // nobody has read this PC's hashes for a whole ring (single player): they count as missed there
        }
        out.putLong(steps[slot]).putInt(epoch).putLong(total(sections[slot]));
        for (long s : sections[slot]) {
            out.putLong(s);
        }
        for (long d : divisionHashes[slot]) {
            out.putLong(d);
        }
    }

    /** Another PC's hashes, in any pieces; each is compared as soon as this PC has the same step. */
    public void in(int pc, byte[] bytes) {
        Remote r = remote(pc);
        r.add(bytes);
        drain(r);
    }

    /** Compares (or keeps for later) every whole record of this PC's that has arrived. */
    private void drain(Remote r) {
        long[] rec;
        while ((rec = r.next()) != null) {
            if (rec[1] < epoch) {
                continue; // taken before the last resync
            }
            if (rec[0] > lastStep || rec[1] > epoch) {
                r.waiting.add(rec); // this PC hasn't got there yet (or hasn't loaded the resync yet)
            } else {
                compare(r.pc, rec);
            }
        }
    }

    private Remote remote(int pc) {
        for (Remote r : remotes) {
            if (r.pc == pc) {
                return r;
            }
        }
        Remote r = new Remote(pc);
        remotes.add(r);
        return r;
    }

    private void compareWaiting(Remote r) {
        while (!r.waiting.isEmpty()) {
            long[] rec = r.waiting.peekFirst();
            if (rec[1] < epoch) {
                r.waiting.pollFirst();
            } else if (rec[1] == epoch && rec[0] <= lastStep) {
                compare(r.pc, r.waiting.pollFirst());
            } else {
                return;
            }
        }
    }

    private void compare(int pc, long[] rec) {
        int slot = slotOf(rec[0]);
        if (slot < 0) {
            missed++; // older than the ring, or a step this PC never hashed
            return;
        }
        compared++;
        if (desync != null || rec[2] == total(sections[slot])) {
            return;
        }
        long[] remoteSections = Arrays.copyOfRange(rec, 3, 3 + SECTIONS);
        int[] diff = new int[divisions];
        int n = 0;
        for (int d = 0; d < divisions; d++) {
            if (divisionHashes[slot][d] != rec[3 + SECTIONS + d]) {
                diff[n++] = d;
            }
        }
        desync = new Desync(rec[0], pc, lastStep, sections[slot], remoteSections, Arrays.copyOf(diff, n));
    }

    /** One other PC's incoming hashes: bytes not yet read, and records for steps this PC has not reached. */
    private final class Remote {
        final int pc;
        ByteBuffer in = ByteBuffer.allocate(1 << 12);
        boolean headerRead;
        int theirDivisions;
        final ArrayDeque<long[]> waiting = new ArrayDeque<>();

        Remote(int pc) {
            this.pc = pc;
        }

        void add(byte[] bytes) {
            if (in.remaining() < bytes.length) {
                ByteBuffer bigger = ByteBuffer.allocate(Math.max(in.capacity() * 2, in.position() + bytes.length));
                in.flip();
                bigger.put(in);
                in = bigger;
            }
            in.put(bytes);
        }

        /** The next whole record (step, epoch, total, sections, divisions), or null. */
        long[] next() {
            in.flip();
            try {
                if (!headerRead) {
                    if (in.remaining() < HEADER_BYTES) {
                        return null;
                    }
                    readHeader();
                }
                if (divisions < 0) {
                    return null; // records are compared once this PC took its own first hash
                }
                if (theirDivisions != divisions) {
                    throw new IllegalStateException("SOSDET: PC " + pc + " has " + theirDivisions + " divisions, this PC "
                            + divisions);
                }
                if (in.remaining() < recordBytes()) {
                    return null;
                }
                long[] rec = new long[3 + SECTIONS + divisions];
                rec[0] = in.getLong();
                rec[1] = in.getInt();
                for (int i = 2; i < rec.length; i++) {
                    rec[i] = in.getLong();
                }
                return rec;
            } finally {
                in.compact();
            }
        }

        private void readHeader() {
            int magic = in.getInt();
            int version = in.getInt();
            int theirInterval = in.getInt();
            int theirSections = in.getInt();
            theirDivisions = in.getInt();
            if (magic != MAGIC || version != FORMAT_VERSION) {
                throw new IllegalStateException("SOSDET: PC " + pc + " sent hashes not of format " + FORMAT_VERSION
                        + " (magic " + Integer.toHexString(magic) + ", version " + version + ")");
            }
            if (theirInterval != interval || theirSections != SECTIONS) {
                throw new IllegalStateException("SOSDET: PC " + pc + " hashes every " + theirInterval + " steps in "
                        + theirSections + " sections; this PC every " + interval + " in " + SECTIONS);
            }
            headerRead = true;
        }
    }

    // ------------------------------------------------------------ resync (B14)

    /**
     * A resync at {@code step}: this PC's hashes after it are dropped (a peer that had run ahead hashes those steps
     * again), the desync is forgotten, and from now on only hashes of the new epoch are compared.
     */
    public void rewind(long step, int newEpoch) {
        if (newEpoch <= epoch) {
            throw new IllegalArgumentException("SOSDET: resync epoch " + newEpoch + " after epoch " + epoch);
        }
        while (taken > 0 && steps[(taken - 1) % RING] > step) {
            taken--;
        }
        lastStep = step;
        epoch = newEpoch;
        desync = null;
        for (int i = 0; i < remotes.size(); i++) {
            compareWaiting(remotes.get(i)); // drops what is older than the new epoch
        }
    }

    public int epoch() {
        return epoch;
    }

    // ------------------------------------------------------------ results and counts

    /** The first step that differed from another PC, or null. */
    public Desync desync() {
        return desync;
    }

    public long taken() {
        return taken;
    }

    /** Other PCs' hashes compared with this PC's. */
    public long compared() {
        return compared;
    }

    /** Other PCs' hashes that came too late to compare (older than the ring). */
    public long missed() {
        return missed;
    }

    public double meanMs() {
        return taken == 0 ? 0 : totalNanos / 1e6 / taken;
    }

    public double maxMs() {
        return maxNanos / 1e6;
    }
}

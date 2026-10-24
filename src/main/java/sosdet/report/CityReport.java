package sosdet.report;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import sosdet.hash.HashLog;

/**
 * One city's report to the world (ADR 0005 §2): the city values the world part reads, taken in the city part at one
 * step and read by the world part from a later one, with the inputs the city part made since its last report. Its
 * bytes carry a protocol version; a whole value is written in a few bytes, any other one in 9, and every value reads
 * back bit for bit.
 */
public final class CityReport {

    static final int MAGIC = 0x53444352; // "SDCR"
    public static final int PROTOCOL_VERSION = 4;

    /** The faction whose city this is. */
    public final int faction;
    /** The step the city part took it at. */
    public final long takenAt;
    /** The first step whose world part reads it. */
    public final long appliesAt;
    final ReportLayout layout;
    private final double[] values;
    /** What the city part did to the world since its last report, in order (B1, B2). */
    public final List<Input> inputs;

    public CityReport(ReportLayout layout, int faction, long takenAt, long appliesAt, double[] values) {
        this(layout, faction, takenAt, appliesAt, values, Collections.emptyList());
    }

    public CityReport(ReportLayout layout, int faction, long takenAt, long appliesAt, double[] values,
            List<Input> inputs) {
        if (values.length != layout.size()) {
            throw new IllegalArgumentException(
                    "SOSDET: a report of " + values.length + " values, the layout holds " + layout.size());
        }
        if (appliesAt < takenAt) {
            throw new IllegalArgumentException("SOSDET: a report taken at " + takenAt + " applies at " + appliesAt);
        }
        this.layout = layout;
        this.faction = faction;
        this.takenAt = takenAt;
        this.appliesAt = appliesAt;
        this.values = values.clone();
        this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
    }

    public double get(Field f, int i) {
        return values[layout.slot(f, i)];
    }

    public double get(Field f, int i, int j) {
        return values[layout.slot(f, i, j)];
    }

    public ReportLayout layout() {
        return layout;
    }

    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + values.length * 2);
        int[] sizes = layout.sizes();
        ByteBuffer head = ByteBuffer.allocate(4 + 4 + 4 + 8 + 8 + 4 + 4 + sizes.length * 4 + 4);
        head.putInt(MAGIC).putInt(PROTOCOL_VERSION).putInt(faction).putLong(takenAt).putLong(appliesAt)
                .putInt(Field.values().length).putInt(sizes.length);
        for (int n : sizes) {
            head.putInt(n);
        }
        head.putInt(values.length);
        out.write(head.array(), 0, head.position());
        for (double v : values) {
            long n = (long) v;
            if (Double.doubleToRawLongBits(v) == Double.doubleToRawLongBits(n) && Math.abs(n) < (1L << 52)) {
                varint(out, ((n << 1) ^ (n >> 63)) << 1); // zigzag, low bit 0: a whole number
            } else {
                varint(out, 1);
                long bits = Double.doubleToRawLongBits(v);
                for (int s = 56; s >= 0; s -= 8) {
                    out.write((int) (bits >>> s));
                }
            }
        }
        varint(out, inputs.size());
        for (Input in : inputs) {
            varint(out, in.kind.ordinal());
            varint(out, ((long) in.arg << 1) ^ (in.arg >> 31));
            varint(out, in.length());
            out.write(in.raw(), 0, in.length());
        }
        return out.toByteArray();
    }

    /** Reads a report back; refused when its version, its fields or its sizes are not this layout's. */
    public static CityReport fromBytes(byte[] bytes, ReportLayout layout) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        try {
            int magic = b.getInt();
            int version = b.getInt();
            if (magic != MAGIC || version != PROTOCOL_VERSION) {
                throw new IllegalArgumentException("SOSDET: not a city report of version " + PROTOCOL_VERSION
                        + " (magic " + Integer.toHexString(magic) + ", version " + version + ")");
            }
            int faction = b.getInt();
            long takenAt = b.getLong();
            long appliesAt = b.getLong();
            int fields = b.getInt();
            int[] sizes = new int[b.getInt()];
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = b.getInt();
            }
            if (fields != Field.values().length || !layout.sameSizes(sizes)) {
                throw new IllegalArgumentException("SOSDET: a city report of " + fields + " fields and sizes "
                        + Arrays.toString(sizes) + ", this game has " + Field.values().length + " and "
                        + Arrays.toString(layout.sizes()));
            }
            double[] values = new double[b.getInt()];
            for (int i = 0; i < values.length; i++) {
                long code = varint(b);
                if ((code & 1) == 0) {
                    long z = code >>> 1;
                    values[i] = (z >>> 1) ^ -(z & 1);
                } else {
                    values[i] = Double.longBitsToDouble(b.getLong());
                }
            }
            int n = (int) varint(b);
            List<Input> inputs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int kind = (int) varint(b);
                if (kind < 0 || kind >= InputKind.values().length) {
                    throw new IllegalArgumentException("SOSDET: a city report input of kind " + kind);
                }
                long z = varint(b);
                int arg = (int) ((z >>> 1) ^ -(z & 1));
                byte[] data = new byte[(int) varint(b)];
                b.get(data);
                inputs.add(new Input(InputKind.values()[kind], arg, data));
            }
            if (b.hasRemaining()) {
                throw new IllegalArgumentException("SOSDET: " + b.remaining() + " bytes after the city report");
            }
            return new CityReport(layout, faction, takenAt, appliesAt, values, inputs);
        } catch (BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("SOSDET: city report cut short (" + bytes.length + " bytes)", e);
        }
    }

    private static void varint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static long varint(ByteBuffer b) {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            byte x = b.get();
            v |= (long) (x & 0x7F) << shift;
            if (x >= 0) {
                return v;
            }
        }
        throw new IllegalArgumentException("SOSDET: a city report value of more than 10 bytes");
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CityReport)) {
            return false;
        }
        CityReport r = (CityReport) o;
        if (r.faction != faction || r.takenAt != takenAt || r.appliesAt != appliesAt || r.values.length != values.length
                || !r.layout.sameSizes(layout.sizes())) {
            return false;
        }
        for (int i = 0; i < values.length; i++) {
            if (Double.doubleToRawLongBits(r.values[i]) != Double.doubleToRawLongBits(values[i])) {
                return false;
            }
        }
        return r.inputs.equals(inputs);
    }

    /** The report's step and every value's bits, for the state hash. */
    public long hash() {
        long h = HashLog.mix(HashLog.mix(HashLog.mix(HashLog.start(), faction), takenAt), appliesAt);
        for (double v : values) {
            h = HashLog.mix(h, Double.doubleToRawLongBits(v));
        }
        for (Input in : inputs) {
            h = HashLog.mix(HashLog.mix(HashLog.mix(h, in.kind.ordinal()), in.arg), in.raw(), 0, in.length());
        }
        return h;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(appliesAt) + Arrays.hashCode(values);
    }
}

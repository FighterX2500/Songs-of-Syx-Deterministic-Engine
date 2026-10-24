package sosdet.report;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * City reports as a byte stream between PCs (ADR 0005 §6): each one a record of its length, then its bytes
 * ({@link CityReport#toBytes}). The mod sends them in any pieces; a record cut short waits for the rest.
 */
public final class ReportFeed {

    /** Bigger than any report: a record claiming more is a broken stream. */
    static final int MAX_RECORD = 1 << 26;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private ByteBuffer in = ByteBuffer.allocate(1 << 12);

    /** A report this PC took, to go out. */
    public void write(byte[] report) {
        out.write(report.length >>> 24);
        out.write(report.length >>> 16);
        out.write(report.length >>> 8);
        out.write(report.length);
        out.write(report, 0, report.length);
    }

    /** The records written since the last call. */
    public byte[] drain() {
        byte[] bytes = out.toByteArray();
        out.reset();
        return bytes;
    }

    /** More of another PC's stream: the reports it completes, in order. */
    public List<byte[]> read(byte[] bytes) {
        if (in.remaining() < bytes.length) {
            ByteBuffer bigger = ByteBuffer.allocate(Math.max(in.capacity() * 2, in.position() + bytes.length));
            in.flip();
            bigger.put(in);
            in = bigger;
        }
        in.put(bytes);
        in.flip();
        List<byte[]> reports = new ArrayList<>();
        try {
            while (in.remaining() >= 4) {
                in.mark();
                int length = in.getInt();
                if (length < 0 || length > MAX_RECORD) {
                    throw new IllegalArgumentException("SOSDET: a city report record of " + length + " bytes");
                }
                if (in.remaining() < length) {
                    in.reset();
                    break;
                }
                byte[] r = new byte[length];
                in.get(r);
                reports.add(r);
            }
        } finally {
            in.compact();
        }
        return reports;
    }

    /** A load: what was on its way belongs to the game before it. */
    public void clear() {
        out.reset();
        in.clear();
    }
}

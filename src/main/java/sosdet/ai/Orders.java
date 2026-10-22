package sosdet.ai;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

/**
 * A peer's player's orders on their way to the host (B16): the peer writes them, the host reads each peer's stream.
 * Every order carries the peer's own sequence number, so a repeated order changes nothing.
 *
 * <p>The stream as bytes: a header (magic, format version, start ID), then records of a length and a body (sequence,
 * kind, division, payload).
 */
final class Orders {

    static final int FORMAT_VERSION = 2;
    static final int MAGIC = 0x5344_4f52; // "SDOR"
    static final int HEADER_BYTES = 4 + 4 + 8;

    private final long startId;
    // peer: what goes out
    private ByteBuffer out = ByteBuffer.allocate(1 << 10);
    private boolean headerWritten;
    private int nextOut;
    // host: what comes in, per peer
    private final List<Peer> peers = new ArrayList<>();

    Orders(long startId) {
        this.startId = startId;
    }

    /** Peer: one more order of this PC's player. */
    void add(int kind, int div, byte[] payload) {
        if (!headerWritten) {
            ensure(HEADER_BYTES);
            out.putInt(MAGIC).putInt(FORMAT_VERSION).putLong(startId);
            headerWritten = true;
        }
        int body = 4 + 1 + 2 + 4 + payload.length;
        ensure(4 + body);
        out.putInt(body).putInt(nextOut++).put((byte) kind).putShort((short) div).putInt(payload.length).put(payload);
    }

    /** Peer: the bytes written since the last call. */
    byte[] out() {
        byte[] bytes = Arrays.copyOf(out.array(), out.position());
        out.clear();
        return bytes;
    }

    private void ensure(int bytes) {
        if (out.remaining() < bytes) {
            ByteBuffer bigger = ByteBuffer.allocate(Math.max(out.capacity() * 2, out.position() + bytes));
            out.flip();
            bigger.put(out);
            out = bigger;
        }
    }

    /** Host: more of a peer's stream; each new whole order goes into {@code into}, marked with the peer. */
    void in(int pc, byte[] bytes, Queue<AiEntry> into) {
        Peer p = peer(pc);
        if (p.in.remaining() < bytes.length) {
            ByteBuffer bigger = ByteBuffer.allocate(Math.max(p.in.capacity() * 2, p.in.position() + bytes.length));
            p.in.flip();
            bigger.put(p.in);
            p.in = bigger;
        }
        p.in.put(bytes);
        p.in.flip();
        try {
            if (!p.headerRead) {
                if (p.in.remaining() < HEADER_BYTES) {
                    return;
                }
                int magic = p.in.getInt();
                int version = p.in.getInt();
                long id = p.in.getLong();
                if (magic != MAGIC || version != FORMAT_VERSION || id != startId) {
                    throw new IllegalStateException("SOSDET: PC " + pc + " sent orders not of format " + FORMAT_VERSION
                            + " for this start (magic " + Integer.toHexString(magic) + ", version " + version + ", start "
                            + Long.toHexString(id) + ")");
                }
                p.headerRead = true;
            }
            while (p.in.remaining() >= 4) {
                p.in.mark();
                int body = p.in.getInt();
                if (p.in.remaining() < body) {
                    p.in.reset();
                    break;
                }
                int seq = p.in.getInt();
                int kind = p.in.get();
                int div = p.in.getShort();
                byte[] payload = new byte[p.in.getInt()];
                p.in.get(payload);
                if (seq < p.next) {
                    continue; // a repeated order changes nothing
                }
                if (seq > p.next) {
                    throw new IllegalStateException("SOSDET: order " + p.next + " from PC " + pc + " missing (got " + seq + ")");
                }
                p.next++;
                into.add(new AiEntry(-1, -1, kind, div, pc, payload));
            }
        } finally {
            p.in.compact();
        }
    }

    private Peer peer(int pc) {
        for (Peer p : peers) {
            if (p.pc == pc) {
                return p;
            }
        }
        Peer p = new Peer(pc);
        peers.add(p);
        return p;
    }

    private static final class Peer {
        final int pc;
        ByteBuffer in = ByteBuffer.allocate(1 << 10);
        boolean headerRead;
        int next;

        Peer(int pc) {
            this.pc = pc;
        }
    }
}

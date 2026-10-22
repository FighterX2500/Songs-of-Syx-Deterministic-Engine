package sosdet.ai;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Reads back a command's payload written by {@link CmdWriter}: the id, then the arguments in the same order. */
public final class CmdReader {

    private final ByteBuffer b;
    private final int id;

    public CmdReader(byte[] payload) {
        if (payload.length < 2) {
            throw new IllegalArgumentException("SOSDET: a command payload of " + payload.length + " bytes");
        }
        b = ByteBuffer.wrap(payload);
        id = b.getShort() & 0xFFFF;
    }

    /** The command's id, read from a payload without decoding the rest. */
    public static int idOf(byte[] payload) {
        return payload.length < 2 ? -1 : ByteBuffer.wrap(payload).getShort() & 0xFFFF;
    }

    public int id() {
        return id;
    }

    public int i() {
        return b.getInt();
    }

    public boolean b() {
        byte v = b.get();
        if (v != 0 && v != 1) {
            throw new IllegalStateException("SOSDET: command " + id + " holds a boolean of " + v);
        }
        return v == 1;
    }

    public double d() {
        return b.getDouble();
    }

    public String s() {
        byte[] bytes = new byte[b.getInt()];
        b.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Fails unless every byte was read: an applier that reads less than its hook wrote is a programming error. */
    public void end() {
        if (b.hasRemaining()) {
            throw new IllegalStateException("SOSDET: command " + id + " has " + b.remaining() + " bytes left unread");
        }
    }
}

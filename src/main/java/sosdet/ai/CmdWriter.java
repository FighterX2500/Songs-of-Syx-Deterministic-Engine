package sosdet.ai;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Encodes one player's command (ADR 0002): its id first, then its arguments in the order the command's applier reads
 * them back with {@link CmdReader}. Game objects are written as their stable indices by the game adapter.
 */
public final class CmdWriter {

    private ByteBuffer b = ByteBuffer.allocate(32);

    public CmdWriter(int id) {
        if (id < 0 || id > 0xFFFF) {
            throw new IllegalArgumentException("SOSDET: command id " + id);
        }
        b.putShort((short) id);
    }

    public CmdWriter i(int v) {
        room(4).putInt(v);
        return this;
    }

    public CmdWriter b(boolean v) {
        room(1).put((byte) (v ? 1 : 0));
        return this;
    }

    public CmdWriter d(double v) {
        room(8).putDouble(v);
        return this;
    }

    public CmdWriter s(String v) {
        byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
        room(4 + bytes.length).putInt(bytes.length).put(bytes);
        return this;
    }

    /** The command's payload: its id and arguments. */
    public byte[] bytes() {
        return Arrays.copyOf(b.array(), b.position());
    }

    private ByteBuffer room(int bytes) {
        if (b.remaining() < bytes) {
            ByteBuffer bigger = ByteBuffer.allocate(Math.max(b.capacity() * 2, b.position() + bytes));
            b.flip();
            bigger.put(b);
            b = bigger;
        }
        return b;
    }
}

package sosdet;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * What a world resync carries besides the host's world sections (ADR 0005 §5, SOS-38), as versioned bytes: the step,
 * the hash epoch, the world part's draws counted for the hash, and the world's open changes to the city (the ledger).
 * The city keeps everything else: its transactions applied, its reports, its inputs on their way.
 */
final class WorldResync {

    static final int MAGIC = 0x53445752; // "SDWR"
    static final int FORMAT_VERSION = 1;

    final long step;
    final int hashEpoch;
    final long worldDraws;
    final byte[] ledger;

    WorldResync(long step, int hashEpoch, long worldDraws, byte[] ledger) {
        this.step = step;
        this.hashEpoch = hashEpoch;
        this.worldDraws = worldDraws;
        this.ledger = ledger.clone();
    }

    byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 8 + 4 + 8 + 4 + ledger.length);
        b.putInt(MAGIC).putInt(FORMAT_VERSION).putLong(step).putInt(hashEpoch).putLong(worldDraws).putInt(ledger.length)
                .put(ledger);
        return b.array();
    }

    static WorldResync fromBytes(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        try {
            int magic = b.getInt();
            int version = b.getInt();
            if (magic != MAGIC || version != FORMAT_VERSION) {
                throw new IllegalArgumentException("SOSDET: not a world resync state of format " + FORMAT_VERSION
                        + " (magic " + Integer.toHexString(magic) + ", version " + version + ")");
            }
            long step = b.getLong();
            int epoch = b.getInt();
            long draws = b.getLong();
            byte[] ledger = new byte[b.getInt()];
            b.get(ledger);
            if (b.hasRemaining()) {
                throw new IllegalArgumentException("SOSDET: " + b.remaining() + " bytes after the world resync state");
            }
            return new WorldResync(step, epoch, draws, ledger);
        } catch (BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("SOSDET: world resync state cut short (" + bytes.length + " bytes)", e);
        }
    }
}

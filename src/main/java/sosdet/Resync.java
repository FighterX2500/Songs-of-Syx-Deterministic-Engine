package sosdet;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * What a game save leaves out at a resync step (hook B14, requirements §3), as versioned bytes: the step, the seed
 * the random stream is reseeded from every step, the hash interval and epoch, the simulation draws counted for the
 * hash, the AI log's part (entries due after the step) and the game adapter's part (state vanilla doesn't save).
 */
final class Resync {

    static final int MAGIC = 0x53445253; // "SDRS"
    static final int FORMAT_VERSION = 1;

    final long step;
    final int seed;
    final int hashInterval;
    final int hashEpoch;
    final long draws;
    final byte[] ai;
    final byte[] game;

    Resync(long step, int seed, int hashInterval, int hashEpoch, long draws, byte[] ai, byte[] game) {
        this.step = step;
        this.seed = seed;
        this.hashInterval = hashInterval;
        this.hashEpoch = hashEpoch;
        this.draws = draws;
        this.ai = ai.clone();
        this.game = game.clone();
    }

    byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 8 + 4 + 4 + 4 + 8 + 4 + ai.length + 4 + game.length);
        b.putInt(MAGIC).putInt(FORMAT_VERSION).putLong(step).putInt(seed).putInt(hashInterval).putInt(hashEpoch)
                .putLong(draws).putInt(ai.length).put(ai).putInt(game.length).put(game);
        return b.array();
    }

    static Resync fromBytes(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        try {
            int magic = b.getInt();
            int version = b.getInt();
            if (magic != MAGIC || version != FORMAT_VERSION) {
                throw new IllegalArgumentException("SOSDET: not a resync state of format " + FORMAT_VERSION + " (magic "
                        + Integer.toHexString(magic) + ", version " + version + ")");
            }
            long step = b.getLong();
            int seed = b.getInt();
            int interval = b.getInt();
            int epoch = b.getInt();
            long draws = b.getLong();
            byte[] ai = new byte[b.getInt()];
            b.get(ai);
            byte[] game = new byte[b.getInt()];
            b.get(game);
            if (b.hasRemaining()) {
                throw new IllegalArgumentException("SOSDET: " + b.remaining() + " bytes after the resync state");
            }
            return new Resync(step, seed, interval, epoch, draws, ai, game);
        } catch (BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("SOSDET: resync state cut short (" + bytes.length + " bytes)", e);
        }
    }
}

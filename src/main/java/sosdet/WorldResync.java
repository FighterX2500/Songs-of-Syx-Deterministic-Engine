package sosdet;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * What a world resync carries besides the host's world sections (ADR 0005 §5, SOS-38), as versioned bytes: the step,
 * the hash epoch, the world part's draws counted for the hash, and the world's open changes to each human's city (one
 * ledger per human slot, SOS-52). The city keeps everything else: its transactions applied, its reports, its inputs on
 * their way.
 */
final class WorldResync {

    static final int MAGIC = 0x53445752; // "SDWR"
    /** 2: one ledger per human slot (SOS-52); 1 carried the host's human's only. */
    static final int FORMAT_VERSION = 2;

    final long step;
    final int hashEpoch;
    final long worldDraws;
    /** The human slots, in slot order; {@code ledgers[i]} is slot {@code slots[i]}'s. */
    final int[] slots;
    final byte[][] ledgers;

    WorldResync(long step, int hashEpoch, long worldDraws, int[] slots, byte[][] ledgers) {
        if (slots.length == 0 || slots.length != ledgers.length) {
            throw new IllegalArgumentException("SOSDET: a world resync needs one ledger per human slot (" + slots.length
                    + " slots, " + ledgers.length + " ledgers)");
        }
        this.step = step;
        this.hashEpoch = hashEpoch;
        this.worldDraws = worldDraws;
        this.slots = slots.clone();
        this.ledgers = new byte[ledgers.length][];
        for (int i = 0; i < ledgers.length; i++) {
            this.ledgers[i] = ledgers[i].clone();
        }
    }

    /** The ledger of the human in {@code slot}. */
    byte[] ledger(int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return ledgers[i];
            }
        }
        throw new IllegalArgumentException("SOSDET: the world resync has no human slot " + slot);
    }

    byte[] toBytes() {
        int size = 4 + 4 + 8 + 4 + 8 + 4;
        for (byte[] l : ledgers) {
            size += 4 + 4 + l.length;
        }
        ByteBuffer b = ByteBuffer.allocate(size);
        b.putInt(MAGIC).putInt(FORMAT_VERSION).putLong(step).putInt(hashEpoch).putLong(worldDraws).putInt(slots.length);
        for (int i = 0; i < slots.length; i++) {
            b.putInt(slots[i]).putInt(ledgers[i].length).put(ledgers[i]);
        }
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
            int n = b.getInt();
            if (n <= 0 || n > b.remaining() / 8) {
                throw new IllegalArgumentException("SOSDET: a world resync state with " + n + " human slots");
            }
            int[] slots = new int[n];
            byte[][] ledgers = new byte[n][];
            for (int i = 0; i < n; i++) {
                slots[i] = b.getInt();
                ledgers[i] = new byte[b.getInt()];
                b.get(ledgers[i]);
            }
            if (b.hasRemaining()) {
                throw new IllegalArgumentException("SOSDET: " + b.remaining() + " bytes after the world resync state");
            }
            return new WorldResync(step, epoch, draws, slots, ledgers);
        } catch (BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("SOSDET: world resync state cut short (" + bytes.length + " bytes)", e);
        }
    }
}

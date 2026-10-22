package sosdet.ai;

import java.nio.ByteBuffer;

/**
 * One change the AI general made: which division, what kind, and the payload, stamped with the step every PC
 * applies it at. Immutable.
 */
public final class AiEntry {

    /** An order, as the game saves it (path, destination, task). */
    public static final int PATH = 0, DEST = 1, TASK = 2;
    /** A division setting; the payload is one int. */
    public static final int GUARD = 3, RUNNING = 4, FIRE_AT_WILL = 5, FORMATION = 6;
    /** An artillery target; the payload is four ints: the artillery's tile and the target tile. */
    public static final int ARTILLERY = 7;
    static final int KINDS = 8;
    private static final String[] KIND_NAMES = {"path", "dest", "task", "guard", "running", "fireAtWill", "formation", "artillery"};

    /** Step the entry applies at; -1 while only captured. */
    public final long step;
    /** Order of the entry in the host's log, from 0. */
    public final int seq;
    public final int kind;
    /** Division index, or -1 for artillery. */
    public final int div;
    private final byte[] payload;

    AiEntry(long step, int seq, int kind, int div, byte[] payload) {
        if (kind < 0 || kind >= KINDS) {
            throw new IllegalArgumentException("SOSDET: AI entry kind " + kind);
        }
        this.step = step;
        this.seq = seq;
        this.kind = kind;
        this.div = div;
        this.payload = payload;
    }

    /** The payload bytes (a copy). */
    public byte[] payload() {
        return payload.clone();
    }

    int payloadLength() {
        return payload.length;
    }

    void payloadTo(ByteBuffer to) {
        to.put(payload);
    }

    /** The i-th int of an int payload (settings, artillery). */
    public int intAt(int i) {
        return ByteBuffer.wrap(payload).getInt(i * 4);
    }

    /** An int payload for settings and artillery. */
    public static byte[] ints(int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }

    AiEntry stamped(long at, int sequence) {
        return new AiEntry(at, sequence, kind, div, payload);
    }

    /** One line for a desync report: step, sequence, kind, division, the first payload value of a setting. */
    String line() {
        return "step " + step + ", #" + seq + ", " + KIND_NAMES[kind] + ", division " + div
                + (kind >= GUARD && kind <= FORMATION ? " = " + intAt(0) : "");
    }
}

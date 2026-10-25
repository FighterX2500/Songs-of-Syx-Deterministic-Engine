package sosdet.ai;

import java.nio.ByteBuffer;

/**
 * One change to the battle in the host's log: made by the AI general or ordered by a player (which PC), what kind,
 * which division, and the payload, stamped with the step every PC applies it at. Immutable.
 */
public final class AiEntry {

    /** An order, as the game saves it (path, destination, task). */
    public static final int PATH = 0, DEST = 1, TASK = 2;
    /** A division setting; the payload is one int. */
    public static final int GUARD = 3, RUNNING = 4, FIRE_AT_WILL = 5, FORMATION = 6, HOLD_FIRE = 8, AMMO = 9,
            MUSTER = 10, MOP_UP = 11;
    /**
     * An artillery piece, found again by one of its tiles (the first two ints). ARTILLERY: target x, y and the game's
     * two flags; ARTILLERY_MUSTER and ARTILLERY_FIRE: one flag.
     */
    public static final int ARTILLERY = 7, ARTILLERY_MUSTER = 12, ARTILLERY_FIRE = 13;
    /** Divisions placed during deployment (Armies.initAndTeleport); the payload is their indices. */
    public static final int TELEPORT = 14;
    /** The speed every PC agrees on, in thousandths (it sets the step length); not a battle change. */
    public static final int SPEED = 15;
    /** A player's command (ADR 0002): the payload is the command's id and its arguments ({@link CmdWriter}). */
    public static final int COMMAND = 16;
    /** Who commands each division and each army's artillery from this step ({@link Commanders}); not a battle change. */
    public static final int COMMANDERS = 17;
    /**
     * The host found a desync (SOS-53): every PC stops at this entry's step. The payload is the differing PC, then the
     * desync's step as two ints (high, low); not a battle change.
     */
    public static final int HOLD = 18;
    /**
     * The host removes a human (SOS-66): from this entry's step that human is retired on every PC. The payload is the
     * human's slot, then the step its last report every PC reads was taken at, as two ints (high, low); not a battle
     * change.
     */
    public static final int REMOVE = 19;
    static final int KINDS = 20;
    public static String kindName(int kind) {
        return KIND_NAMES[kind];
    }

    private static final String[] KIND_NAMES = {"path", "dest", "task", "guard", "running", "fireAtWill", "formation",
        "artillery", "holdFire", "ammo", "muster", "mopUp", "artilleryMuster", "artilleryFire", "teleport", "speed",
        "command", "commanders", "hold", "remove"};

    /** The general, as opposed to a player's PC. */
    public static final int AI = -1;

    /** Step the entry applies at; -1 while only captured. */
    public final long step;
    /** Order of the entry in the host's log, from 0. */
    public final int seq;
    public final int kind;
    /** Division index, or -1 for artillery and speed. */
    public final int div;
    /** Who gave it: {@link #AI}, or the player's PC as the mod numbers it (the host's own player is 0). */
    public final int pc;
    private final byte[] payload;

    AiEntry(long step, int seq, int kind, int div, int pc, byte[] payload) {
        if (kind < 0 || kind >= KINDS) {
            throw new IllegalArgumentException("SOSDET: AI log entry kind " + kind);
        }
        this.step = step;
        this.seq = seq;
        this.kind = kind;
        this.div = div;
        this.pc = pc;
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

    /** The i-th int of an int payload (settings, artillery, speed). */
    public int intAt(int i) {
        return ByteBuffer.wrap(payload).getInt(i * 4);
    }

    /** How many ints an int payload holds. */
    public int ints() {
        return payload.length / 4;
    }

    /** An int payload for settings, artillery, deployment and speed. */
    public static byte[] ints(int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }

    AiEntry stamped(long at, int sequence) {
        return new AiEntry(at, sequence, kind, div, pc, payload);
    }

    boolean isSetting() {
        return kind >= GUARD && kind <= FORMATION || kind >= HOLD_FIRE && kind <= MOP_UP;
    }

    /** One line for a desync report: step, sequence, kind, division, the value of a setting or the speed. */
    String line() {
        return "step " + step + ", #" + seq + ", " + KIND_NAMES[kind] + ", division " + div
                + (isSetting() ? " = " + intAt(0) : kind == SPEED ? " = " + intAt(0) / 1000.0 + "x" : "")
                + (kind == COMMAND ? " " + CmdReader.idOf(payload) : "")
                + (pc == AI ? "" : ", PC " + pc);
    }
}

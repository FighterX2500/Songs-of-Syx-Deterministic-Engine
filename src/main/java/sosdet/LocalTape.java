package sosdet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;

import sosdet.ai.AiEntry;

/**
 * What a local battle's PC does in each step, for the PCs that watch it (plan 0017, SOS-109): the rounds it ran and the
 * orders it applied. A watcher runs the same steps later, at its own pace, from this tape, so it never needs the
 * fighter to wait. The orders of the player apply at the next step on the fighter too, from {@link #add}, so the step
 * they apply at is on the tape. Protocol version 1. Game thread.
 */
final class LocalTape {

    static final int MAGIC = 0x53445454; // "SDTT"
    static final int VERSION = 1;

    /** One step of the tape: {@code rounds} is 0 for a paused battle. */
    static final class Step {
        final long step;
        final int rounds;
        final AiEntry[] orders;

        Step(long step, int rounds, AiEntry[] orders) {
            this.step = step;
            this.rounds = rounds;
            this.orders = orders;
        }
    }

    private static final AiEntry[] NONE = {};

    /** The fighter: the orders since the last step, oldest first. */
    private final ArrayList<AiEntry> queued = new ArrayList<>();
    /** The fighter: what each viewer slot hasn't taken yet; null for a slot that doesn't watch. */
    private final ByteArrayOutputStream[] out;
    /** Watches that ended: no more steps, but what wasn't sent yet still goes, as the watcher runs behind the battle. */
    private final boolean[] ending;
    /** The watcher: the steps it hasn't run yet, oldest first. */
    private final ArrayDeque<Step> in = new ArrayDeque<>();
    /** The last step of the tape this PC has. */
    private long reached = -1;

    LocalTape(int slots) {
        out = new ByteArrayOutputStream[slots];
        ending = new boolean[slots];
    }

    /** The fighter, between steps: an order for the next step. A slider dragged repeats a command: once is enough. */
    void add(int kind, int div, int pc, byte[] payload) {
        if (kind == AiEntry.COMMAND && !queued.isEmpty()) {
            AiEntry last = queued.get(queued.size() - 1);
            if (last.kind == AiEntry.COMMAND && java.util.Arrays.equals(last.payload(), payload)) {
                return;
            }
        }
        queued.add(AiEntry.order(kind, div, pc, payload));
    }

    /** The orders for the step that begins now; {@link #clearQueued} after they apply. */
    ArrayList<AiEntry> queued() {
        return queued;
    }

    void clearQueued() {
        queued.clear();
    }

    /** The fighter: the human in {@code viewer} starts watching: the tape goes to it from the next step. */
    void watch(int viewer) {
        out[viewer] = new ByteArrayOutputStream();
        ending[viewer] = false;
    }

    /** The fighter: no more of the tape for the human in {@code viewer}, once {@link #out} has taken what's written. */
    void unwatch(int viewer) {
        ending[viewer] = true;
    }

    /** The fighter, at each step while someone watches: this step, for each viewer. */
    void record(long step, int rounds) {
        for (int viewer = 0; viewer < out.length; viewer++) {
            if (out[viewer] != null && !ending[viewer]) {
                write(out[viewer], step, rounds);
            }
        }
    }

    private void write(ByteArrayOutputStream b, long step, int rounds) {
        try {
            DataOutputStream d = new DataOutputStream(b);
            d.writeLong(step);
            d.writeInt(rounds);
            d.writeInt(queued.size());
            for (int i = 0; i < queued.size(); i++) {
                AiEntry e = queued.get(i);
                d.writeInt(e.kind);
                d.writeInt(e.div);
                byte[] payload = e.payload();
                d.writeInt(payload.length);
                d.write(payload);
            }
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: cannot write a battle's tape", e);
        }
    }

    /** The fighter: the tape for {@code viewer} since the last call, for that viewer's {@link #in}; empty if none. */
    byte[] out(int viewer) {
        ByteArrayOutputStream b = out[viewer];
        if (ending[viewer]) {
            out[viewer] = null;
            ending[viewer] = false;
        }
        if (b == null || b.size() == 0) {
            return new byte[0];
        }
        byte[] steps = b.toByteArray();
        b.reset();
        ByteArrayOutputStream whole = new ByteArrayOutputStream(steps.length + 8);
        try {
            DataOutputStream d = new DataOutputStream(whole);
            d.writeInt(MAGIC);
            d.writeInt(VERSION);
            d.write(steps);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: cannot write a battle's tape", e);
        }
        return whole.toByteArray();
    }

    /** The watcher: more of the tape, in order; the orders apply as if from the battle's PC. */
    void in(byte[] bytes, int pc) {
        try (DataInputStream d = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = d.readInt();
            int version = d.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IllegalStateException("SOSDET: not a battle's tape of version " + VERSION + " (magic "
                        + Integer.toHexString(magic) + ", version " + version + ")");
            }
            while (d.available() > 0) {
                long step = d.readLong();
                int rounds = d.readInt();
                int n = d.readInt();
                AiEntry[] orders = n == 0 ? NONE : new AiEntry[n];
                for (int i = 0; i < n; i++) {
                    int kind = d.readInt();
                    int div = d.readInt();
                    byte[] payload = new byte[d.readInt()];
                    d.readFully(payload);
                    orders[i] = AiEntry.order(kind, div, pc, payload);
                }
                if (reached >= 0 && step != reached + 1) {
                    throw new IllegalStateException("SOSDET: a battle's tape goes from step " + reached + " to " + step);
                }
                reached = step;
                in.add(new Step(step, rounds, orders));
            }
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: a battle's tape is cut short", e);
        }
    }

    /** The watcher: true when the tape holds {@code step}. */
    boolean has(long step) {
        return reached >= step;
    }

    /** The watcher, at {@code step}: that step of the tape, in order, every step once. */
    Step take(long step) {
        Step s = in.poll();
        if (s == null || s.step != step) {
            throw new IllegalStateException("SOSDET: step " + step + " of a battle's tape, but it has "
                    + (s == null ? "none" : "step " + s.step));
        }
        return s;
    }

    /** The watcher: the last step of the tape it has, or -1. */
    long reached() {
        return reached;
    }

    /** A new watch or battle starts from an empty tape. */
    void clear() {
        queued.clear();
        in.clear();
        reached = -1;
        java.util.Arrays.fill(out, null);
        java.util.Arrays.fill(ending, false);
    }
}

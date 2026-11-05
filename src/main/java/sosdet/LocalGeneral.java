package sosdet;

import java.util.ArrayList;
import java.util.HashMap;

import sosdet.ai.AiEntry;

/**
 * The AI general of a local battle on its fighter's PC (SOS-150): vanilla's general runs on its own thread, as the
 * host's does in a field battle (B15), and its changes wait here until the next step begins. Then they go on the
 * battle's tape, so the fighter and every watcher apply the same bytes at the same step; a watcher runs no general. A
 * later change to the same division's same kind replaces the earlier one in its place, as the AI log's batch does;
 * changes with no division (artillery) are all kept. The general's thread writes, the game thread drains.
 */
final class LocalGeneral {

    /** The general's thread while it runs a local battle's general; null otherwise. */
    private volatile Thread thread;
    private final ArrayList<AiEntry> batch = new ArrayList<>();
    private final HashMap<Integer, Integer> slots = new HashMap<>();
    /** Changes replaced by a later one to the same slot before their step. */
    private long coalesced;

    /** The general's job starts on {@code t}: its changes are captured from now on. */
    void threadIs(Thread t) {
        thread = t;
    }

    /** True on the general's thread. */
    boolean is(Thread t) {
        return t == thread;
    }

    /** A change from the general's thread; {@code payload} is copied, as the general's writer reuses its buffer. */
    synchronized void capture(int kind, int div, byte[] payload) {
        AiEntry e = AiEntry.order(kind, div, AiEntry.AI, payload);
        if (div < 0) {
            batch.add(e);
            return;
        }
        Integer slot = kind << 16 | (div & 0xFFFF);
        Integer at = slots.get(slot);
        if (at == null) {
            slots.put(slot, batch.size());
            batch.add(e);
        } else {
            batch.set(at, e);
            coalesced++;
        }
    }

    /** The game thread, as a step begins: the captured changes, oldest first, onto {@code tape}. */
    synchronized void drainInto(LocalTape tape) {
        for (int i = 0; i < batch.size(); i++) {
            AiEntry e = batch.get(i);
            tape.add(e.kind, e.div, e.pc, e.payload());
        }
        batch.clear();
        slots.clear();
    }

    /** The battle is over: the thread captures nothing more, and what it left is dropped. */
    synchronized void end() {
        thread = null;
        batch.clear();
        slots.clear();
    }

    synchronized long coalesced() {
        return coalesced;
    }

    synchronized int pending() {
        return batch.size();
    }
}

package sosdet.game.cmd;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sosdet.Hooks;
import sosdet.ai.AiEntry;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;

/**
 * Every player command the engine knows (ADR 0002), by id. The hooks in the game's code ask an area class (Laws,
 * Military, ...) whether to capture; the area class encodes the call and hands it here. Each area owns an id range so
 * areas can grow independently: laws 1000, rooms 2000, employment 3000, military 4000, nobles and names 5000, tools
 * 6000 (the copier tools 6100), world 7000, diplomacy 8000 (deals between humans 8100), events 9000. An id is never reused.
 */
public final class Commands {

    private static final Map<Integer, Command> BY_ID = index(Laws.ALL, Rooms.ALL, Employment.ALL, Military.ALL,
            Names.ALL, Tools.ALL, CopyTools.ALL, World.ALL, Diplomacy.ALL, Events.ALL, WorldBattles.ALL,
            MapBattles.ALL, StepPrompts.ALL, HumanDeals.ALL);

    private Commands() {
    }

    @SafeVarargs
    private static Map<Integer, Command> index(List<Command>... areas) {
        Map<Integer, Command> m = new HashMap<>();
        for (List<Command> area : areas) {
            for (Command c : area) {
                if (m.put(c.id, c) != null) {
                    throw new IllegalStateException("SOSDET: two commands with id " + c.id);
                }
            }
        }
        return Map.copyOf(m);
    }

    /** True when a hooked call is this PC's player acting: it is then captured and must not run now. */
    static boolean capturing() {
        return Hooks.ordersCapturing();
    }

    /** Captures an encoded call; every PC applies it at the step the host stamps. */
    static void capture(CmdWriter w) {
        Hooks.orderCapture(AiEntry.COMMAND, -1, w.bytes());
    }

    /** Applies a command from the log, inside the step. Returns false if its target was gone (skipped everywhere). */
    public static boolean apply(byte[] payload) {
        CmdReader r = new CmdReader(payload);
        Command c = BY_ID.get(r.id());
        if (c == null) {
            throw new IllegalStateException("SOSDET: unknown command id " + r.id() + " (another engine version?)");
        }
        if (!c.applier.apply(r)) {
            return false;
        }
        r.end();
        return true;
    }

    /** A command's name for reports, or its number when unknown. */
    public static String name(int id) {
        Command c = BY_ID.get(id);
        return c == null ? "command " + id : c.name;
    }
}

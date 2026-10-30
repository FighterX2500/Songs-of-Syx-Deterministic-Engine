package sosdet.game;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Locale;

import game.debug.Profiler;

/**
 * The step profile (SOS-106): the game's own {@link Profiler}, given to each game part's update in the step, adds up
 * the time each class takes. The step loop (patch 10) times every game part as one entry, and the parts that log their
 * own pieces (rooms, entities, terrain...) show them nested. Timing only: nothing here is read by the simulation.
 *
 * <p>No allocation once each class has its slot: fixed arrays, and the slot numbers boxed once, when a class first
 * shows. A class started again inside itself counts once, from its outer start.
 */
public final class StepProfiler implements Profiler {

    private static final int MAX = 512;

    private final IdentityHashMap<Class<?>, Integer> slots = new IdentityHashMap<>();
    private final Class<?>[] classes = new Class<?>[MAX];
    private final int[] depth = new int[MAX];
    private final int[] open = new int[MAX];
    private final long[] started = new long[MAX];
    private final long[] total = new long[MAX];
    private final long[] calls = new long[MAX];
    private boolean inCitizen;
    private int count;
    private int level;
    private long steps;
    private long stepNanos;

    private int slot(Class<?> cl) {
        Integer i = slots.get(cl);
        if (i != null)
            return i;
        if (count == MAX)
            return -1; // more classes than slots: the rest go untimed
        classes[count] = cl;
        depth[count] = level;
        slots.put(cl, count);
        return count++;
    }

    @Override
    public void logStart(Class<?> cl) {
        int i = slot(cl);
        if (i < 0 || open[i]++ > 0)
            return;
        calls[i]++;
        started[i] = System.nanoTime();
        level++;
    }

    @Override
    public void logEnd(Class<?> cl) {
        Integer i = slots.get(cl);
        if (i == null || open[i] == 0 || --open[i] > 0)
            return;
        total[i] += System.nanoTime() - started[i];
        level--;
    }

    @Override
    public void log() {
        // the engine reports it: see report()
    }

    // SOS-108 spike: the parts of a citizen's update, keyed like the game's parts
    private static final class Citizen {}
    private static final class Move {}
    private static final class StateTick {}
    private static final class Collide {}
    private static final class Plan {}
    private static final class Person {}
    private static final class Search {}
    private static final Class<?>[] PARTS = {Citizen.class, Move.class, StateTick.class, Collide.class, Plan.class, Person.class, Search.class};

    /** SOS-108 spike: part 0 is a whole entity update; 6, a search, counts only inside one. */
    public void citizenPart(int part, boolean start) {
        if (part == 0)
            inCitizen = start;
        else if (part == 6 && !inCitizen)
            return;
        if (start)
            logStart(PARTS[part]);
        else
            logEnd(PARTS[part]);
    }

    /** One step ended, having taken {@code nanos} in all. */
    public void stepDone(long nanos) {
        steps++;
        stepNanos += nanos;
    }

    /** Each class's mean time per step, nested as first seen, and the step's mean; then starts counting again. */
    public String report() {
        StringBuilder b = new StringBuilder("step profile over ").append(steps).append(" steps: ");
        if (steps == 0)
            return b.append("none").toString();
        b.append(String.format(Locale.ROOT, "%.3f", stepNanos / 1e6 / steps)).append(" ms a step");
        for (int i = 0; i < count; i++) {
            if (total[i] / 1e6 / steps < 0.0005)
                continue; // under a microsecond a step: left out, so the line stays readable
            b.append(" | ");
            for (int d = 0; d < depth[i]; d++)
                b.append('-');
            b.append(name(classes[i])).append(' ').append(String.format(Locale.ROOT, "%.3f", total[i] / 1e6 / steps));
            if (calls[i] != steps)
                b.append(String.format(Locale.ROOT, " (%.1f a step)", (double) calls[i] / steps));
        }
        Arrays.fill(total, 0);
        Arrays.fill(calls, 0);
        steps = 0;
        stepNanos = 0;
        return b.toString();
    }

    /** A class's simple name, or for an anonymous one its outer class's and its number. */
    private static String name(Class<?> cl) {
        String n = cl.getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }
}

package sosdet.report;

import java.util.ArrayDeque;

/**
 * When reports are taken and read (ADR 0005 §2): the city part takes one every 30 steps, and the world part reads it
 * from 30 steps later, until the next one applies. A run's first world part has no report yet: it reads one taken at
 * once (the load report), so the world never reads the live city.
 */
public final class Reports {

    public static final int EVERY = 30;
    public static final int DELAY = 30;

    private CityReport current;
    private final ArrayDeque<CityReport> pending = new ArrayDeque<>();

    /** True at the steps the city part takes a report. */
    public static boolean takenAt(long step) {
        return step % EVERY == 0;
    }

    /** A report taken now, for {@link #DELAY} steps from now; the reports must come in the order they apply. */
    public void stamp(CityReport r) {
        CityReport last = pending.isEmpty() ? current : pending.peekLast();
        if (last != null && r.appliesAt <= last.appliesAt) {
            throw new IllegalStateException("SOSDET: a city report for step " + r.appliesAt + " after one for step "
                    + last.appliesAt);
        }
        pending.addLast(r);
    }

    /** The world part of a step begins: the reports due by now apply, the latest one last. */
    public void applyDue(long step) {
        while (!pending.isEmpty() && pending.peekFirst().appliesAt <= step) {
            current = pending.pollFirst();
        }
    }

    /** The report the world part reads now, or null before the first one. */
    public CityReport current() {
        return current;
    }

    /** True while a report taken for this step or later has yet to apply. */
    public boolean pending() {
        return !pending.isEmpty();
    }

    /** A load or resync: the city's reports start again. */
    public void clear() {
        current = null;
        pending.clear();
    }
}

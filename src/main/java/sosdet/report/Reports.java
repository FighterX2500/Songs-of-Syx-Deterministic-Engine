package sosdet.report;

import java.util.ArrayDeque;
import java.util.function.Consumer;

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
    private final Ledger ledger = new Ledger();

    /** True at the steps the city part takes a report. */
    public static boolean takenAt(long step) {
        return step % EVERY == 0;
    }

    /**
     * The last step whose world part can run with the reports taken up to {@code takenAt} (ADR 0005 §4): the next
     * report is taken at the next multiple of {@link #EVERY} and applies {@link #DELAY} steps after it.
     */
    public static long lastReadableStep(long takenAt) {
        if (takenAt < 0) {
            throw new IllegalArgumentException("SOSDET: a report taken at step " + takenAt);
        }
        return (takenAt / EVERY + 1) * EVERY + DELAY - 1;
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
        applyDue(step, r -> { });
    }

    /** As {@link #applyDue(long)}; {@code applied} gets each report as it applies, for its inputs. */
    public void applyDue(long step, Consumer<CityReport> applied) {
        while (!pending.isEmpty() && pending.peekFirst().appliesAt <= step) {
            current = pending.pollFirst();
            ledger.reportApplied(current.takenAt);
            applied.accept(current);
        }
    }

    /** What the world part reads: the current report's value plus the world's own changes it doesn't show yet. */
    public double value(Field f, int i) {
        return current.get(f, i) + ledger.pending(current.layout.slot(f, i));
    }

    public double value(Field f, int i, int j) {
        return current.get(f, i, j) + ledger.pending(current.layout.slot(f, i, j));
    }

    /** A transaction made at this world step changes a value the world part reads, from the next read on. */
    public void change(long step, Field f, int i, long delta) {
        ledger.add(step, current.layout.slot(f, i), delta);
    }

    public void change(long step, Field f, int i, int j, long delta) {
        ledger.add(step, current.layout.slot(f, i, j), delta);
    }

    /** Changes the world part still adds to the report it reads. */
    public int open() {
        return ledger.size();
    }

    /** The report the world part reads now, or null before the first one. */
    public CityReport current() {
        return current;
    }

    /** True while a report taken for this step or later has yet to apply. */
    public boolean pending() {
        return !pending.isEmpty();
    }

    /** The world's open changes, for a world resync: every PC continues with the host's. */
    public byte[] ledgerOut() {
        return ledger.toBytes();
    }

    public void ledgerIn(byte[] bytes) {
        ledger.fromBytes(bytes);
    }

    /** A load or resync: the city's reports start again. */
    public void clear() {
        current = null;
        pending.clear();
        ledger.clear();
    }
}

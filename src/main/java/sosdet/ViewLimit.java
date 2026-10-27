package sosdet;

/**
 * The viewer's limits (mod spec §10, plan 0012 M2, SOS-82): the view ends when the viewer's PC hasn't caught up with
 * the owner's city a minute after its view load, or, once caught up, stays behind for over a minute. Caught up means
 * at most {@link #DEFAULT_STEPS} steps behind the host's confirmed step, where the catch-up speed stops.
 *
 * <p>Measured on the viewer's PC only, on its own clock, between steps: it decides only whether that PC asks the host
 * to end the view ({@link Engine#viewLimit}). The end itself is a log entry the host stamps, applied at one step on
 * every PC, so this clock never reaches the simulation. Game thread; allocates nothing.
 */
final class ViewLimit {

    /** A minute (mod spec §10). */
    static final double DEFAULT_SECONDS = 60;
    /** Caught up: no more steps behind than the catch-up speed's threshold ({@code Hooks.VIEW_CATCH_UP_LAG}). */
    static final int DEFAULT_STEPS = Hooks.VIEW_CATCH_UP_LAG;

    private long catchUpNanos = seconds(DEFAULT_SECONDS);
    private long behindNanos = seconds(DEFAULT_SECONDS);
    private long steps = DEFAULT_STEPS;
    /** When catching up started (the view load set, the clock's nanoseconds); -1 while no view is measured. */
    private long started = -1;
    private long caughtUpAt = -1;
    /** Since when the viewer has been behind again after catching up; -1 while it isn't. */
    private long behindSince = -1;
    private int hit = View.OPEN;

    /** Test tooling: other limits than the spec's, for the rig to reach each one in a short run. */
    void limits(double catchUpSeconds, double behindSeconds, int behindSteps) {
        if (!(catchUpSeconds > 0) || !(behindSeconds > 0) || behindSteps < 0) {
            throw new IllegalArgumentException("SOSDET: view limits " + catchUpSeconds + " s, " + behindSeconds + " s, "
                    + behindSteps + " steps");
        }
        catchUpNanos = seconds(catchUpSeconds);
        behindNanos = seconds(behindSeconds);
        steps = behindSteps;
    }

    /** The viewer's view load is set at {@code now}: catching up starts. */
    void start(long now) {
        started = now;
        caughtUpAt = -1;
        behindSince = -1;
        hit = View.OPEN;
    }

    /** The view is over on this PC: nothing more is measured. */
    void stop() {
        started = -1;
    }

    /**
     * At {@code now}, {@code behind} steps behind the host: the limit hit ({@link View#NOT_CAUGHT_UP},
     * {@link View#FELL_BEHIND}), kept once hit, or {@link View#OPEN}.
     */
    int update(long now, long behind) {
        if (started < 0 || hit != View.OPEN) {
            return hit;
        }
        boolean caughtUp = behind <= steps;
        if (caughtUpAt < 0) {
            if (caughtUp) {
                caughtUpAt = now;
            } else if (now - started > catchUpNanos) {
                hit = View.NOT_CAUGHT_UP;
            }
            return hit;
        }
        if (caughtUp) {
            behindSince = -1;
        } else if (behindSince < 0) {
            behindSince = now;
        } else if (now - behindSince > behindNanos) {
            hit = View.FELL_BEHIND;
        }
        return hit;
    }

    /** The limit hit, or {@link View#OPEN}. */
    int hit() {
        return hit;
    }

    /** Seconds from the view load to catching up (the playtest log's {@code viewcaughtup}); -1 until then. */
    double caughtUpSeconds() {
        return started < 0 || caughtUpAt < 0 ? -1 : (caughtUpAt - started) / 1e9;
    }

    private static long seconds(double s) {
        return (long) (s * 1e9);
    }
}

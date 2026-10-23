package sosdet;

/**
 * The battle's worker jobs, run inside the step in a fixed order on a fixed beat (hook B8).
 *
 * <p>Vanilla runs them on their own threads on wall-clock intervals: orders, division centres and
 * trajectories every 1/60 s, the AI general every 1/120 s, status every 0.5 s. Here each runs at the
 * same rate in steps, counted from the session's step, so a save or a pause never shifts the beat.
 */
final class BattleJobs {

    /** The general's 1/120 s interval is two runs per 1/60 s step. */
    static final int GENERAL_RUNS_PER_STEP = 2;
    /** The status job's 0.5 s interval in steps. */
    static final int STATUS_EVERY_STEPS = 30;

    private boolean running;
    /** Time in each job since the stats were reset: orders, centres, trajectories, general, status. */
    final long[] nanos = new long[5];

    /** {@code generalInStep}: false when the general runs on the host's own thread, or not at all (peer). */
    void run(long step, Runnable orders, Runnable centres, Runnable trajectories, Runnable general, Runnable status,
            boolean generalInStep) {
        running = true;
        try {
            long t = System.nanoTime();
            orders.run();
            t = lap(0, t);
            centres.run();
            t = lap(1, t);
            trajectories.run();
            t = lap(2, t);
            for (int i = 0; generalInStep && i < GENERAL_RUNS_PER_STEP; i++) {
                general.run();
            }
            t = lap(3, t);
            if (step % STATUS_EVERY_STEPS == 0) {
                status.run();
            }
            lap(4, t);
        } finally {
            running = false;
        }
    }

    private long lap(int job, long since) {
        long now = System.nanoTime();
        nanos[job] += now - since;
        return now;
    }

    /**
     * What a battle thread's {@code working()} answers: true only while its job runs inside the step.
     * A job that loops "while working" then ends with the step, and nothing restarts the real thread.
     */
    boolean running() {
        return running;
    }
}

package sosdet;

/** The state of one deterministic run: from {@link Engine#begin()} to {@link Engine#end()}. */
final class Session {

    final StepClock clock = new StepClock();
    final Streams streams = new Streams();
    final BattleJobs jobs = new BattleJobs();
    final StepTimes times = new StepTimes();

    boolean worldHeld;
    /** 0 = loads run as in vanilla; otherwise loads run on the simulation stream seeded with this. */
    int loadSeed;
    long stepStartNanos;
    long pathJobs;
    long pathNanos;

    /** Every load starts the run again from step 0 (requirements §3: save and load at a step come in SOS-30). */
    void afterLoad() {
        clock.reset();
    }

    void resetStats() {
        times.reset();
        streams.resetCounts();
        pathJobs = 0;
        pathNanos = 0;
    }
}

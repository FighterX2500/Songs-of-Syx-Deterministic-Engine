package sosdet;

import sosdet.ai.AiLog;
import sosdet.game.GameHash;
import sosdet.hash.HashLog;
import sosdet.hash.StateSource;

/** The state of one deterministic run: from {@link Engine#begin()} to {@link Engine#end()}. */
final class Session {

    final StepClock clock = new StepClock();
    final Streams streams = new Streams();
    final BattleJobs jobs = new BattleJobs();
    final StepTimes times = new StepTimes();
    /** Where the AI general runs; in-step by default. */
    AiLog ai = AiLog.inStep();
    /** The state hash every 30 steps (B13); a new one after every load. */
    HashLog hashes = new HashLog();
    /** What the hash reads: the game, made at the first hash; tests put in their own. */
    StateSource source;

    boolean worldHeld;
    /** 0 = loads run as in vanilla; otherwise loads run on the simulation stream seeded with this. */
    int loadSeed;
    /** When the running step started (0 between steps); read by the rig's stall sampler on another thread. */
    volatile long stepStartNanos;
    long pathJobs;
    long pathNanos;

    /** Every load starts the run again from step 0 (requirements §3: save and load at a step come in SOS-30). */
    void afterLoad() {
        clock.reset();
        streams.loaded();
        int interval = hashes.interval();
        hashes = new HashLog();
        hashes.every(interval);
    }

    StateSource source() {
        if (source == null) {
            source = new GameHash();
        }
        return source;
    }

    void resetStats() {
        times.reset();
        streams.resetCounts();
        pathJobs = 0;
        pathNanos = 0;
    }
}

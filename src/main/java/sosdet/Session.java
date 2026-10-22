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

    /** The random stream is reseeded every step from this and the step (requirements §1); set at step 0. */
    int seed;
    /** A resync load in progress: applied when the load begins, then cleared. */
    Resync resync;
    /** The host's draw count at the resync, set once the load's own draws are over (-1: none pending). */
    long resyncDraws = -1;

    boolean worldHeld;
    /** 0 = loads run as in vanilla; otherwise loads run on the simulation stream seeded with this. */
    int loadSeed;
    /** When the running step started (0 between steps); read by the rig's stall sampler on another thread. */
    volatile long stepStartNanos;
    long pathJobs;
    long pathNanos;

    /** The seed of the random stream at a step: the same on every PC, whatever happened before. */
    int stepSeed(long step) {
        long h = HashLog.mix(HashLog.mix(HashLog.start(), seed), step);
        return (int) (h ^ (h >>> 32));
    }

    /**
     * A load starts the run again from step 0; a resync load continues at the host's step instead, with the engine
     * state the save leaves out (B14).
     */
    void afterLoad() {
        if (resync != null) {
            Resync r = resync;
            resync = null;
            clock.resetTo(r.step);
            seed = r.seed;
            resyncDraws = r.draws; // the load's own draws don't count: set when it ends
            hashes.rewind(r.step, r.hashEpoch);
            ai.resyncIn(r.ai);
            return;
        }
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

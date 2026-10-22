package sosdet;

import java.util.Random;

import snake2d.util.rnd.RND;
import sosdet.ai.AiApplier;
import sosdet.ai.AiMode;
import sosdet.hash.HashLog;

/**
 * What the engine's patches to the game call (docs/hooks.md). Each hook asks one question or reports one
 * event; the answers come from the session. With no session every answer is the vanilla behaviour.
 *
 * <p>Hot paths ({@link #random}, the step hooks): no allocation, no logging.
 */
public final class Hooks {

    /** Seeds for the game's random tile tables, which every process must fill the same way (SOS-23). */
    private static final long TILE_ORDER_SEED = 0x5050L;
    private static final long RANDOM_TABLE_SEED = 0x52414E30L;

    private Hooks() {
    }

    // ------------------------------------------------------------ GAME.update: the fixed step (B7)

    /** True with a session: whole fixed steps instead of frame-length sub-steps. */
    public static boolean fixedSteps() {
        return Engine.session() != null;
    }

    /** A paused frame updates nothing, so paused frames can't change the outcome. */
    public static boolean skipPausedFrame() {
        return Engine.session() != null;
    }

    /** No autosave during a session; saves at a step come through the API (SOS-30). */
    public static boolean autoSaveAllowed() {
        return Engine.session() == null;
    }

    /** Whole steps this frame's game time completes. */
    public static int stepsFor(double gameSeconds) {
        return Engine.session().clock.stepsFor(gameSeconds);
    }

    /**
     * Starts a step; false when the session may not run further (the frame's other steps are dropped): at its last
     * step, or as a peer that is already 30 steps past the host's last confirmed step. The AI log entries due at
     * this step are applied first, before anything else runs.
     */
    public static boolean stepBegin() {
        Session s = Engine.session();
        if (!s.ai.mayRun(s.clock.step() + 1)) {
            s.clock.hold();
            return false;
        }
        if (!s.clock.begin()) {
            return false;
        }
        s.streams.enter();
        s.stepStartNanos = System.nanoTime(); // timing only, never fed to the simulation
        s.ai.stepBegin(s.clock.step());
        return true;
    }

    /** Ends a step; every 30th step (by default) the state hash is taken first, still inside the step (B13). */
    public static void stepEnd() {
        Session s = Engine.session();
        long step = s.clock.step();
        if (s.hashes.due(step)) {
            long clock = HashLog.mix(HashLog.mix(HashLog.mix(HashLog.start(), step), s.streams.drawsSinceLoad()),
                    s.ai.applied());
            s.hashes.take(step, clock, s.source());
        }
        s.times.record(System.nanoTime() - s.stepStartNanos);
        s.stepStartNanos = 0;
        s.streams.leave();
    }

    /** True when the world map must not update in the city view (a session holding the world still). */
    public static boolean worldHeld() {
        Session s = Engine.session();
        return s != null && s.worldHeld;
    }

    /** True with a session: the battle state updates inside the step, not from the battle view's frame. */
    public static boolean battleStateInStep() {
        return Engine.session() != null;
    }

    // ------------------------------------------------------------ RND: the two streams (B10)

    /** The stream for this draw: the game's own generator (simulation) or a cosmetic one. */
    public static Random random(Random simulation) {
        Session s = Engine.session();
        return s == null ? simulation : s.streams.pick(simulation);
    }

    /** Visual-only code inside the step (tree sway, light flicker) draws cosmetic until {@link #cosmeticEnd()}. */
    public static void cosmeticBegin() {
        Session s = Engine.session();
        if (s != null) {
            s.streams.cosmeticBegin();
        }
    }

    public static void cosmeticEnd() {
        Session s = Engine.session();
        if (s != null) {
            s.streams.cosmeticEnd();
        }
    }

    // ------------------------------------------------------------ battle threads and paths (B8, B9)

    /** True when the battle jobs run now, inside this step, instead of on their own threads. */
    public static boolean battleJobsNow(double ds) {
        Session s = Engine.session();
        return s != null && ds > 0 && s.streams.inStep();
    }

    public static void runBattleJobs(Runnable orders, Runnable centres, Runnable trajectories, Runnable general,
            Runnable status) {
        Session s = Engine.session();
        s.jobs.run(s.clock.step(), orders, centres, trajectories, general, status, s.ai.mode() == AiMode.IN_STEP);
    }

    /**
     * True when a battle thread's own thread must not start: with a session its job runs inside the step. The one
     * exception is the AI general on the host, which runs on its own thread as in vanilla.
     */
    public static boolean threadRunsInStep(boolean general) {
        Session s = Engine.session();
        return s != null && !(general && s.ai.mode() == AiMode.HOST);
    }

    /** What a battle thread's {@code working()} answers; {@code vanilla} is its own answer. */
    public static boolean slaveWorking(boolean vanilla) {
        Session s = Engine.session();
        if (s == null || s.ai.capturing()) {
            return vanilla; // the host's general runs its own thread, as in vanilla
        }
        return s.jobs.running();
    }

    // ------------------------------------------------------------ the AI general on the host (B15)

    /** The general's job starts; on the host this is its own thread, whose changes are captured from now on. */
    public static void aiGeneralJob() {
        Session s = Engine.session();
        if (s != null) {
            s.ai.generalThreadIs(Thread.currentThread());
        }
    }

    /** True on the host's general thread: a change goes into the AI log instead of the live state. */
    public static boolean aiCapturing() {
        Session s = Engine.session();
        return s != null && s.ai.capturing();
    }

    /** A change from the host's general thread, applied by every PC at step N + 30. */
    public static void aiCapture(int kind, int div, byte[] payload) {
        Engine.session().ai.capture(kind, div, payload);
    }

    /** The session's game adapter for the AI (host or peer mode), which also encodes the general's orders. */
    public static AiApplier aiApplier() {
        return Engine.session().ai.applier();
    }

    /** True with a session: a path is computed at once on the calling thread, not on the path worker. */
    public static boolean pathsNow() {
        return Engine.session() != null;
    }

    public static void pathDone(long nanos) {
        Session s = Engine.session();
        if (s != null) {
            s.pathJobs++;
            s.pathNanos += nanos;
        }
    }

    // ------------------------------------------------------------ loading and graphics-thread jobs (SOS-23)

    /** A save starts loading on this thread. Every load restarts the session at step 0. */
    public static void loadBegin() {
        Session s = Engine.session();
        if (s == null) {
            return;
        }
        s.afterLoad();
        if (s.loadSeed != 0) {
            // Draws made while loading (prisoners' crimes) belong to the simulation, seeded the same everywhere.
            RND.setSeed(s.loadSeed);
            s.streams.enter();
        }
    }

    public static void loadEnd() {
        Session s = Engine.session();
        if (s != null && s.loadSeed != 0) {
            s.streams.leave();
        }
    }

    /** Whether a job handed to the graphics thread now comes from the simulation (so its draws are simulation draws). */
    public static boolean simulationThreadNow() {
        Session s = Engine.session();
        return s != null && s.streams.isSimulationThread();
    }

    /** The graphics thread runs a job the waiting simulation thread handed over; returns what {@link #glJobEnd} needs. */
    public static Thread glJobBegin(boolean fromSimulation) {
        Session s = Engine.session();
        return s != null && fromSimulation ? s.streams.lend() : null;
    }

    public static void glJobEnd(boolean fromSimulation, Thread previous) {
        Session s = Engine.session();
        if (s != null && fromSimulation) {
            s.streams.giveBack(previous);
        }
    }

    // ------------------------------------------------------------ local settings and the camera (SOS-23)

    /** A setting that changes the simulation: this PC's value without a session, the shared value with one. */
    public static int simulationValue(int local, int shared) {
        return Engine.session() == null ? local : shared;
    }

    /** Whether simulation code may look at this PC's camera (only without a session). */
    public static boolean cameraMatters() {
        return Engine.session() == null;
    }

    // ------------------------------------------------------------ fixed random tables (B11, SOS-23)

    /**
     * The generator for the game's tile visiting order, filled once at class load. Fixed, so every process
     * visits tiles in the same order; this holds with or without a session.
     */
    public static Random tileOrderRandom() {
        return new Random(TILE_ORDER_SEED);
    }

    /** The seed for random tile table number {@code table} (1, 2, ...), the same in every process. */
    public static long randomTableSeed(int table) {
        if (table < 1) {
            throw new IllegalArgumentException("SOSDET: random table " + table);
        }
        return RANDOM_TABLE_SEED + table;
    }
}

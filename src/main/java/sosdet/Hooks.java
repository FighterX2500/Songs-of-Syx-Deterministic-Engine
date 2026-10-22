package sosdet;

import java.util.Random;

import snake2d.util.rnd.RND;

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

    /** Starts a step; false when the session may not run further (the frame's other steps are dropped). */
    public static boolean stepBegin() {
        Session s = Engine.session();
        if (!s.clock.begin()) {
            return false;
        }
        s.streams.enter();
        s.stepStartNanos = System.nanoTime(); // timing only, never fed to the simulation
        return true;
    }

    public static void stepEnd() {
        Session s = Engine.session();
        s.times.record(System.nanoTime() - s.stepStartNanos);
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
        s.jobs.run(s.clock.step(), orders, centres, trajectories, general, status);
    }

    /** True with a session: the battle threads' own threads never start. */
    public static boolean battleThreadsOwned() {
        return Engine.session() != null;
    }

    /** What a battle thread's {@code working()} answers; {@code vanilla} is its own answer. */
    public static boolean slaveWorking(boolean vanilla) {
        Session s = Engine.session();
        return s == null ? vanilla : s.jobs.running();
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

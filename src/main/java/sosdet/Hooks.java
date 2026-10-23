package sosdet;

import java.util.Random;

import game.GAME;
import snake2d.util.rnd.RND;
import sosdet.ai.AiApplier;
import sosdet.ai.AiMode;
import sosdet.game.WorldStatics;
import sosdet.game.cmd.PlanStash;
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
    private static final int LOAD_VIEW_SEED = 0x56494557; // "VIEW"

    private Hooks() {
    }

    // ------------------------------------------------------------ GAME.update: the fixed step (B7)

    /** True with a session: whole fixed steps instead of frame-length sub-steps. */
    public static boolean fixedSteps() {
        return Engine.session() != null;
    }

    /**
     * With a session, clearing a formation clears its whole direction table. Formations are scratch objects the UI
     * shares with the simulation (the move preview and the invasion's deployment use one deployer); a table copied
     * whole, saved whole and grown without being recomputed would carry this PC's preview into the step (SOS-32).
     */
    public static boolean clearWholeFormations() {
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

    /** Whether order writes should be traced: the draw trace is on and this is the simulation thread in the step. */
    public static boolean tracingOrders() {
        Session s = Engine.session();
        return s != null && s.streams.trace != null && s.streams.inStep() && s.streams.isSimulationThread();
    }

    /** Debugging: a division's order slot was written in the step, with this content hash (see {@link DrawTrace}). */
    public static void traceOrder(int kind, int div, int contentHash) {
        Engine.session().streams.trace.orderSet(String.format(java.util.Locale.ROOT, "SET %s division %d = %08x",
                sosdet.ai.AiEntry.kindName(kind), div, contentHash));
    }

    /** The session's current step (the one running, or the last one run). */
    public static long step() {
        return Engine.session().clock.step();
    }

    /** The length of the step about to run, in game seconds: it follows the agreed speed, so read it every step. */
    public static double stepSeconds() {
        return Engine.session().clock.stepSeconds();
    }

    /**
     * Whole steps this frame's game time completes. {@code setSpeed} is this PC's game speed as the player set it
     * (never the throttled one): the host logs a change of it (B16).
     */
    public static int stepsFor(double gameSeconds, double setSpeed) {
        Session s = Engine.session();
        s.speedSeen(setSpeed);
        return s.clock.stepsFor(gameSeconds);
    }

    /** A speed change from the log, at its step: the step length from the next step on. */
    public static void agreedSpeed(double speed) {
        Engine.session().clock.agreedSpeed(speed);
    }

    /** Whole steps this frame's game time completes. */
    public static int stepsFor(double gameSeconds) {
        return Engine.session().clock.stepsFor(gameSeconds);
    }

    /**
     * Starts a step; false when the session may not run further (the frame's other steps are dropped): at its last
     * step, or as a peer that is already 30 steps past the host's last confirmed step. This PC's room plan is lifted out
     * of the simulation before the frame's first step (ADR 0003). The AI log entries due at this step are applied
     * first, before anything else runs.
     */
    public static boolean stepBegin() {
        Session s = Engine.session();
        if (!s.ai().mayRun(s.clock.step() + 1)) {
            s.clock.hold();
            return false;
        }
        if (!s.clock.begin()) {
            return false;
        }
        s.plans().lift();
        s.streams.enter();
        // Reseeded every step: the stream depends only on the seed and the step, so a resync needs no random state
        s.streams.reseed(s.stepSeed(s.clock.step()));
        if (s.streams.trace != null) {
            s.streams.trace.step(s.clock.step());
        }
        s.stepStartNanos = System.nanoTime(); // timing only, never fed to the simulation
        s.ai().stepBegin(s.clock.step());
        return true;
    }

    /** Ends a step; every 30th step (by default) the state hash is taken first, still inside the step (B13). */
    public static void stepEnd() {
        Session s = Engine.session();
        long step = s.clock.step();
        if (s.hashes.due(step)) {
            long clock = HashLog.mix(HashLog.mix(HashLog.mix(HashLog.start(), step), s.streams.drawsSinceLoad()),
                    s.ai().applied());
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
        s.jobs.run(s.clock.step(), orders, centres, trajectories, general, status, s.ai().mode() == AiMode.IN_STEP);
    }

    /**
     * True when a battle thread's own thread must not start: with a session its job runs inside the step. The one
     * exception is the AI general on the host, which runs on its own thread as in vanilla.
     */
    public static boolean threadRunsInStep(boolean general) {
        Session s = Engine.session();
        return s != null && !(general && s.ai().mode() == AiMode.HOST);
    }

    /** What a battle thread's {@code working()} answers; {@code vanilla} is its own answer. */
    public static boolean slaveWorking(boolean vanilla) {
        Session s = Engine.session();
        if (s == null || s.ai().capturing()) {
            return vanilla; // the host's general runs its own thread, as in vanilla
        }
        return s.jobs.running();
    }

    // ------------------------------------------------------------ the AI general on the host (B15)

    /** The general's job starts; on the host this is its own thread, whose changes are captured from now on. */
    public static void aiGeneralJob() {
        Session s = Engine.session();
        if (s != null) {
            s.ai().generalThreadIs(Thread.currentThread());
        }
    }

    /** True on the host's general thread: a change goes into the AI log instead of the live state. */
    public static boolean aiCapturing() {
        Session s = Engine.session();
        return s != null && s.ai().capturing();
    }

    /** A change from the host's general thread, applied by every PC at step N + 30. */
    public static void aiCapture(int kind, int div, byte[] payload) {
        Engine.session().ai().capture(kind, div, payload);
    }

    /**
     * True when a player's order is captured into the log instead of applied: with a log, outside the step, and not
     * on the general's thread (B16).
     */
    public static boolean ordersCapturing() {
        Session s = Engine.session();
        return s != null && !s.streams.inStep() && !s.loading && s.ai().capturingOrders();
    }

    /** A player's order on this PC: into the host's log, through it on a peer. */
    public static void orderCapture(int kind, int div, byte[] payload) {
        Engine.session().ai().captureOrder(kind, div, payload);
    }

    /** The session's game adapter for the AI (host or peer mode), which also encodes the general's orders. */
    public static AiApplier aiApplier() {
        return Engine.session().ai().applier();
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

    /** A save starts loading on this thread. Every load restarts the session at step 0, a resync load at its step. */
    public static void loadBegin() {
        Session s = Engine.session();
        if (s == null) {
            return;
        }
        s.plans().discard();
        if (s.plans().inGame()) { // unit tests have no game to reset
            WorldStatics.reset();
        }
        s.afterLoad();
        s.loading = true;
        if (s.loadSeed != 0) {
            // Draws made while loading (prisoners' crimes) belong to the simulation, seeded the same everywhere.
            RND.setSeed(s.loadSeed);
            s.streams.enter();
        }
    }

    public static void loadEnd() {
        Session s = Engine.session();
        if (s == null) {
            return;
        }
        s.loading = false;
        if (s.loadSeed != 0) {
            s.streams.leave();
        }
        if (s.resyncDraws >= 0) {
            s.streams.drawsSinceLoad(s.resyncDraws);
            s.resyncDraws = -1;
        }
    }

    /**
     * True with a session: a number box ({@code GInputInt}) keeps what was typed while it has the keyboard. The value
     * applies 30 steps later, so showing the current value turned a typed "12" into "02".
     */
    public static boolean keepTypedNumber() {
        return Engine.session() != null;
    }

    /**
     * Around the game's own save (menu, quick and auto saves) in a session: this PC's room plan stays out of it. A
     * second session on the same game registers again; both calls then find the plan already handled.
     */
    private static void gameSave(boolean before) {
        Session s = Engine.session();
        if (s == null) {
            return;
        }
        if (before) {
            s.plans().beforeGameSave();
        } else {
            s.plans().afterGameSave();
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

    // ------------------------------------------------------------ local UI state the simulation reads (SOS-32)

    /**
     * Whether the battle view is open. The simulation asks it inside the step (10 game files: which resources update,
     * births, entry, weather, disease...): with a session the answer is the same on every PC, whether a battle is
     * running. The UI asks it outside the step and gets this PC's own view, as in vanilla. (A desync class found by
     * timjrobinson/songs-of-syx-multiplayer.)
     */
    public static boolean battleView(boolean localViewOpen, boolean battleRunning) {
        Session s = Engine.session();
        return s != null && s.streams.isSimulationThread() ? battleRunning : localViewOpen;
    }

    /**
     * A frame's game update took this long (for the timing measurements; kept per session). Its steps are over: this
     * PC's room plan goes back for the UI and the renderer (ADR 0003), before the queued player actions run.
     */
    public static void frameUpdated(long nanos) {
        Session s = Engine.session();
        if (s != null) {
            s.lastFrameUpdateNanos = nanos;
            s.plans().restore();
            if (s.plans().inGame() && s.planSaver != GAME.saver()) { // game saves leave the room plan out (ADR 0003)
                s.planSaver = GAME.saver(); // a load makes a new game and saver
                GAME.saver().onBeforeSave(path -> gameSave(true));
                GAME.saver().onAfterSave(path -> gameSave(false));
            }
            Runnable action;
            while ((action = s.betweenSteps.poll()) != null) {
                action.run();
            }
        }
    }

    /** This PC's room plan and its stash around the steps (ADR 0003), for the room placer's commands. */
    public static PlanStash planStash() {
        return Engine.session().plans();
    }

    /** While a command or order is being applied: whether this PC's player gave it (ADR 0002, issuer feedback). */
    public static boolean issuedHere() {
        Session s = Engine.session();
        return s != null && s.ai().issuedHere();
    }

    /**
     * True with a session when the caller is not the simulation inside its step: the UI and rendering between steps,
     * or another thread (the host's AI general). Such a caller may read the simulation's caches but never refresh
     * them, move their cursors, or write the simulation's state while drawing (SOS-32).
     */
    public static boolean outsideStep() {
        Session s = Engine.session();
        return s != null && !s.streams.isSimulationThread();
    }

    /**
     * The room placement this PC's player is previewing, as the simulation reads it (farms, pastures, snow, overlays,
     * the environment map): never with a session, since only this PC has it.
     */
    public static boolean placementPreview(boolean localPreview) {
        return Engine.session() == null && localPreview;
    }

    // ------------------------------------------------------------ world state between steps (SOS-35)

    /**
     * Whether a region's building levels read from this PC's upgrade draft ({@code RDLevelsTmp}): as vanilla, except
     * that the simulation inside its step reads the region's real levels.
     */
    public static boolean localDraft(boolean draftOpen) {
        Session s = Engine.session();
        return draftOpen && (s == null || !s.streams.isSimulationThread());
    }

    /**
     * The key of a cache the simulation reads that vanilla keys on the render frame: that frame without a session,
     * the step ({@code GAME.updateI()}) with one, so the step recomputes it however often this PC drew.
     */
    public static int cacheKey(int renderFrame) {
        return Engine.session() == null ? renderFrame : GAME.updateI();
    }

    /** This PC's developer launcher flag, as the simulation reads it: never set with a session. */
    public static boolean developerFlag(boolean local) {
        return Engine.session() == null && local;
    }

    /** A value that changes the simulation: this PC's without a session, the shared one with one. */
    public static double simulationValue(double local, double shared) {
        return Engine.session() == null ? local : shared;
    }

    /**
     * True with a session: where vanilla's result depends on what this PC did before (a sum never cleared, a deal read
     * from the UI's scratch copy), the game takes the route that depends on the shared state alone.
     */
    public static boolean sharedStateOnly() {
        return Engine.session() != null;
    }

    /**
     * True with a session: GAME.Warn and GAME.Error only log, on every PC alike. In vanilla they pause the game or
     * throw depending on this PC's DEVELOPER and DEBUG launcher flags.
     */
    public static boolean uniformWarnings() {
        return Engine.session() != null;
    }

    /**
     * A load has built the game and its view and is about to load the save. Building them can draw differently per
     * process (what the first game in a process builds lazily), so the save's own load starts from one seed again.
     */
    public static void loadViewBuilt() {
        Session s = Engine.session();
        if (s != null && s.loadSeed != 0) {
            RND.setSeed(s.loadSeed ^ LOAD_VIEW_SEED);
        }
    }

    // ------------------------------------------------------------ fixed random tables (B11, SOS-23)

    /**
     * The generator for the game's tile visiting order, filled once at class load. Fixed, so every process
     * visits tiles in the same order; this holds with or without a session.
     */
    public static Random tileOrderRandom() {
        return new Random(TILE_ORDER_SEED);
    }

    /**
     * A generator for one class's static initializer, the same in every process: its table never differs between PCs,
     * and loading the class (whenever that happens) draws nothing from the simulation's stream (SOS-32).
     */
    public static Random tableRandom(Class<?> owner) {
        return new Random(RANDOM_TABLE_SEED ^ owner.getName().hashCode());
    }

    /** The seed for random tile table number {@code table} (1, 2, ...), the same in every process. */
    public static long randomTableSeed(int table) {
        if (table < 1) {
            throw new IllegalArgumentException("SOSDET: random table " + table);
        }
        return RANDOM_TABLE_SEED + table;
    }
}

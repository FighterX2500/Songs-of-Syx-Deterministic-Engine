package sosdet;

import sosdet.ai.AiEntry;
import sosdet.ai.AiLog;
import sosdet.ai.AiMode;
import java.nio.file.Path;

import sosdet.game.GameAi;
import sosdet.game.GameResync;
import sosdet.game.GameWorldLoad;
import sosdet.report.CityReport;
import sosdet.hash.Desync;
import sosdet.report.Shape;

/**
 * The engine's API for the mod and the test rig (requirements §3). The patched game calls {@link Hooks}.
 *
 * <p>Off by default: with no session, every hook gives the vanilla behaviour, so the mod changes
 * nothing until a session begins.
 */
public final class Engine {

    /** Length of one simulation step in game seconds. */
    public static final double STEP_SECONDS = StepClock.STEP_SECONDS;

    /**
     * The current session, or null. Static because the game calls its hooks statically. Approved by the
     * owner (2026-10-22, SOS-27) as the engine's only mutable static state. Reset at {@link #begin()},
     * on every load ({@link Hooks#loadBegin()}) and at {@link #end()}.
     */
    private static volatile Session session;
    /**
     * The last frame's game update time without a session (-1 before the first), so the timing evals can measure
     * vanilla the same way. Measurement only; the simulation never reads it. Approved by the owner (2026-10-23, SOS-37).
     */
    private static volatile long vanillaFrameUpdateNanos = -1;

    private Engine() {
    }

    /** Starts a session: fixed steps, split random streams, battle jobs in the step. It holds at step 0. */
    public static void begin() {
        if (session != null) {
            throw new IllegalStateException("SOSDET: a session is already running; end() it first");
        }
        session = new Session();
    }

    /** Ends the session; the game is vanilla again. */
    public static void end() {
        Session s = session;
        if (s != null) {
            s.paths.shutdown();
        }
        session = null;
    }

    public static boolean active() {
        return session != null;
    }

    /** The last step completed (0 before the first). */
    public static long step() {
        return required().clock.step();
    }

    /** Lets the simulation run up to and including this step, then hold (requirements §3). */
    public static void runUntil(long lastStep) {
        if (lastStep < 0) {
            throw new IllegalArgumentException("SOSDET: last step " + lastStep);
        }
        required().clock.runUntil(lastStep);
    }

    /** Lets the simulation run with no last step. */
    public static void runFreely() {
        required().clock.runUntil(StepClock.NO_LIMIT);
    }

    /** Holds the world map still in the city view, as a battle does (spec §4: battles pause the world). */
    public static void holdWorld(boolean held) {
        required().worldHeld = held;
    }

    /**
     * The seed of the simulation's random stream, which is reseeded every step from it and the step number. Every PC
     * uses the same; set it at step 0.
     */
    public static void seed(int seed) {
        Session s = required();
        if (s.clock.step() != 0) {
            throw new IllegalStateException("SOSDET: the seed is set before the run (step " + s.clock.step() + ")");
        }
        s.seed = seed;
    }

    /**
     * The speed every PC agreed on at the start (1x by default). It sets the step length: 1/60 s at 1x or slower,
     * 1/30 s below 2x, 1/16 s from 2x. Set it at step 0, the same on every PC; later the host's speed changes go through the log and
     * every PC switches at the stamped step. A PC's own game speed only sets its pace.
     */
    public static void speed(double agreed) {
        Session s = required();
        if (s.clock.step() != 0) {
            throw new IllegalStateException("SOSDET: the starting speed is set at step 0 (step " + s.clock.step() + ")");
        }
        s.clock.agreedSpeed(agreed);
        s.seenSpeed = agreed;
        s.pacer.wanted(agreed, System.nanoTime()); // the host player's speed, for the governor (SOS-42)
    }

    /** From the next load on, loading runs on the simulation stream seeded with this; 0 = vanilla loading. */
    public static void armLoadSeed(int seed) {
        required().loadSeed = seed;
    }

    /** Runs a battle's start (deploy) as step 0, inside the simulation (hook B12). */
    public static void atStepZero(Runnable start) {
        Session s = required();
        if (s.clock.step() != 0) {
            throw new IllegalStateException("SOSDET: step 0 is over (step " + s.clock.step() + ")");
        }
        s.streams.enter();
        s.streams.reseed(s.stepSeed(0));
        try {
            start.run();
        } finally {
            s.streams.leave();
        }
    }

    // ------------------------------------------------------------ resync: save and load at a step (B14)

    /**
     * Host, between steps: saves the game to {@code file} at the current step and returns the engine's own state at
     * that step (versioned bytes), then holds there. This PC's room plan is lifted out of the save (ADR 0003). Every PC, the host included, then loads both with
     * {@link #resyncLoad}: a freshly loaded game on every PC, so nothing the save leaves out can differ (owner,
     * 2026-10-22, after a host that ran on kept caches its reloaded peers rebuilt).
     */
    public static byte[] resyncSave(Path file) {
        Session s = required();
        refuseOwnCity(s, "resyncSave");
        if (s.streams.inStep()) {
            throw new IllegalStateException("SOSDET: a resync is saved between steps, not inside one");
        }
        long step = s.clock.step();
        boolean lift = !s.plans().lifted();
        if (lift) {
            s.plans().lift();
        }
        try {
            GameResync.save(file);
        } finally {
            if (lift) {
                s.plans().restore();
            }
        }
        s.clock.runUntil(step); // the host waits for its own load too
        return new Resync(step, s.seed, s.clock.agreedSpeed(), s.hashes.interval(), s.hashes.epoch() + 1,
                s.streams.drawsSinceLoad(), s.ai().resyncOut(), GameResync.capture()).toBytes();
    }

    /**
     * Every PC, the host included: loads the host's resync save and continues at its step, holding there until
     * {@link #runUntil}. Hashes from before the resync are no longer compared.
     * {@code afterSet} runs once the loaded game is set, before the engine restores what the save leaves out: it
     * opens the view the save needs (for a battle, {@code BattleState.setLoaded}).
     */
    public static void resyncLoad(Path file, byte[] state, Runnable afterSet) {
        Session s = required();
        refuseOwnCity(s, "resyncLoad");
        Resync r = Resync.fromBytes(state);
        if (r.hashInterval != s.hashes.interval()) {
            throw new IllegalStateException("SOSDET: the host hashes every " + r.hashInterval + " steps, this PC every "
                    + s.hashes.interval());
        }
        s.resync = r; // taken up when the load begins (Hooks.loadBegin)
        GameResync.load(file, afterSet, r.game);
    }

    private static void refuseOwnCity(Session s, String call) {
        if (s.ownCity) {
            throw new IllegalStateException("SOSDET: " + call + " reloads the whole game, this PC's city too; in own-city"
                    + " mode use worldResyncSave and worldResyncLoad");
        }
    }

    // ------------------------------------------------------------ own city (ADR 0005 §1, §5, §6; SOS-38)

    /**
     * Own-city mode: this PC's city is its own, and only the world is lockstep. Its city commands apply on this PC
     * only, its city part draws from its own stream, its reports go out through {@link #reportsOut}, and the hash
     * compares the world, the report the world reads, and the world part's draws. Set it at step 0, on every PC,
     * after {@link #localPc}. Off by default: co-op, every PC runs the same city.
     */
    public static void ownCity() {
        Session s = required();
        if (s.clock.step() != 0) {
            throw new IllegalStateException("SOSDET: own-city mode is set before the run (step " + s.clock.step() + ")");
        }
        s.ownCity = true;
        s.hashes.worldOnly(true);
        s.streams.traceWorldOnly = true;
    }

    /** Own-city mode: this PC's city reports taken since the last call, for the mod to send to every other PC. */
    public static byte[] reportsOut() {
        Session s = required();
        if (!s.ownCity) {
            throw new IllegalStateException("SOSDET: reportsOut in co-op; every PC takes the same reports itself");
        }
        return s.reportFeed.drain();
    }

    /**
     * Own-city mode: the world part reads PC {@code pc}'s city instead of this PC's own, from the reports that come in
     * through {@link #reportsIn}. The engine keeps the save's single player faction, so this is SOS-38's follower: a
     * PC whose own city differs runs the world from another PC's reports, and must get the same world. It waits at
     * each step for the report that step reads. Several cities in one world are the mod's work (plan 0002, decision 4).
     */
    public static void reportsFrom(int pc) {
        Session s = required();
        if (!s.ownCity) {
            throw new IllegalStateException("SOSDET: reportsFrom needs own-city mode");
        }
        if (pc < 0 || pc == s.ai().localPc()) {
            throw new IllegalArgumentException("SOSDET: reports from PC " + pc + " (this PC is " + s.ai().localPc() + ")");
        }
        s.reportsFrom = pc;
    }

    /** More of PC {@code pc}'s reports, in any pieces; a repeated report changes nothing. Feed them after the load. */
    public static void reportsIn(int pc, byte[] bytes) {
        Session s = required();
        if (pc != s.reportsFrom) {
            throw new IllegalArgumentException("SOSDET: reports from PC " + pc + ", the world reads PC " + s.reportsFrom
                    + "'s city");
        }
        for (byte[] r : s.reportFeed.read(bytes)) {
            s.reportReceived(CityReport.fromBytes(r, s.city().layout()));
        }
    }

    /**
     * Host, own-city mode, between steps: saves the world's sections to {@code file} for a world resync at the
     * current step ({@link GameWorldLoad#save}), and
     * returns the engine's world state at that step; then holds there. Every PC, the host included, then loads it with
     * {@link #worldResyncLoad}: only the world reloads, and every city goes on.
     */
    public static byte[] worldResyncSave(Path file) {
        Session s = required();
        if (!s.ownCity) {
            throw new IllegalStateException("SOSDET: a world resync is for own-city mode; co-op uses resyncSave");
        }
        if (s.streams.inStep()) {
            throw new IllegalStateException("SOSDET: a world resync is saved between steps, not inside one");
        }
        long step = s.clock.step();
        GameWorldLoad.save(file);
        s.clock.runUntil(step); // the host waits for its own load too
        return new WorldResync(step, s.hashes.epoch() + 1, s.streams.worldDraws(), s.reports.ledgerOut()).toBytes();
    }

    /**
     * Every PC, the host included, between steps at the resync step: loads the world's sections of the host's save
     * ({@link GameWorldLoad#KEYS}) into the running game, keeping this PC's city, and continues with the host's world
     * state, holding until {@link #runUntil}. Hashes from before the resync are no longer compared.
     */
    public static void worldResyncLoad(Path file, byte[] state) {
        Session s = required();
        WorldResync r = WorldResync.fromBytes(state);
        if (s.streams.inStep()) {
            throw new IllegalStateException("SOSDET: a world resync is loaded between steps, not inside one");
        }
        if (s.clock.step() != r.step) {
            throw new IllegalStateException("SOSDET: a world resync for step " + r.step + " loaded at step " + s.clock.step());
        }
        if (s.reports.current() == null) {
            throw new IllegalStateException("SOSDET: a world resync at step " + r.step + ", before the first city report:"
                    + " the load counts the soldiers away from it");
        }
        Path scratch;
        try {
            scratch = java.nio.file.Files.createTempFile("sosdet-city", ".bin");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("SOSDET: no scratch file for the city's part of the player faction", e);
        }
        s.worldLoading = true;
        try {
            GameWorldLoad.load(file, scratch);
        } finally {
            s.worldLoading = false;
        }
        s.worldLoads++;
        s.streams.worldDraws(r.worldDraws);
        s.reports.ledgerIn(r.ledger);
        s.hashes.rewind(r.step, r.hashEpoch);
        s.clock.runUntil(r.step);
    }

    // ------------------------------------------------------------ the AI general (requirements §3)

    /** The general runs inside every step: deterministic with no log, for single-PC replays and tests. */
    public static void aiInStep() {
        setAi(AiLog.inStep());
    }

    /**
     * This PC is the host (the default, with start ID 0): the general runs on its own thread, and its changes go into
     * the AI log, applied by every PC at step N + 30. Set it before the battle save loads or deploys (that starts the
     * general's thread). {@code startId} names the start state; peers refuse a log made from another.
     */
    public static void aiHost(long startId) {
        setAi(AiLog.host(startId, new GameAi()));
    }

    /** This PC is a peer: the general is off, and the host's AI log (see {@link #aiLogIn}) is applied instead. */
    public static void aiPeer(long startId) {
        setAi(AiLog.peer(startId, new GameAi()));
    }

    public static AiMode aiMode() {
        return required().ai().mode();
    }

    /** Host: the AI log written since the last call, for the mod to send to every peer. */
    public static byte[] aiLogOut() {
        return required().ai().logOut();
    }

    /** The mod's number for this PC: 0 on the host, from 1 on a peer (for feedback only the issuer gets). */
    public static void localPc(int pc) {
        required().ai().localPc(pc);
    }

    /**
     * Runs {@code action} once after this frame's game update, outside the step, where the game's UI acts: a player's
     * action there is captured as an order or command (the rig's scripted player). Call it on the game thread.
     */
    public static void betweenSteps(Runnable action) {
        required().betweenSteps.add(action);
    }

    /** Peer: its player's orders given since the last call, for the mod to send to the host. */
    public static byte[] ordersOut() {
        return required().ai().ordersOut();
    }

    /**
     * Host: more of peer {@code pc}'s orders (the mod's number for it, from 1; the host's own player is 0), in any
     * pieces. They go into the log like the general's changes and apply at the step the host stamps.
     */
    public static void ordersIn(int pc, byte[] bytes) {
        required().ai().ordersIn(pc, bytes);
    }

    /** Peer: more of the host's AI log, in any pieces. The peer runs at most 30 steps past what it confirms. */
    public static void aiLogIn(byte[] bytes) {
        required().ai().logIn(bytes);
    }

    private static void setAi(AiLog log) {
        Session s = required();
        if (s.clock.step() != 0) {
            throw new IllegalStateException("SOSDET: the AI mode is set before the run (step " + s.clock.step()
                    + "); changing host mid-battle comes with resync (SOS-30)");
        }
        s.ai(log);
    }

    // ------------------------------------------------------------ the state hash and desyncs (requirements §3, §4)

    /** Hash the state every this many steps (30 by default: 0.5 s). Every PC must use the same; set it at step 0. */
    public static void hashEvery(int steps) {
        Session s = required();
        if (s.clock.step() != 0) {
            throw new IllegalStateException("SOSDET: the hash interval is set before the run (step " + s.clock.step() + ")");
        }
        s.hashes.every(steps);
    }

    /** The state hash at a step: a multiple of the interval among the last 64 hashed. */
    public static long hash(long step) {
        return required().hashes.total(step);
    }

    /** The hashes taken since the last call, for the mod to send to the other PCs. */
    public static byte[] hashOut() {
        return required().hashes.out();
    }

    /**
     * Another PC's hashes ({@code pc}: the mod's number for it), in any pieces. Each is compared with this PC's at the
     * same step as soon as both exist; the first difference becomes {@link #desync()}.
     */
    public static void hashIn(int pc, byte[] bytes) {
        required().hashes.in(pc, bytes);
    }

    /**
     * How far another PC has got: the last step its hashes ({@link #hashIn}) have reached, or -1 before the first (and
     * before this PC's own first hash, one hash interval in). The host's pacing ({@link #pace}) reads it.
     */
    public static long peerStep(int pc) {
        return required().hashes.reached(pc);
    }

    /**
     * Host: run at most {@code lead} steps past the slowest of these PCs, learnt from their hashes, on top of
     * {@link #runUntil} (mod spec §4: the slowest PC caps the speed; SOS-42). The lead is at least one hash interval; 0
     * turns pacing off.
     */
    public static void pace(int[] pcs, long lead) {
        required().pacer.peers(pcs, lead);
    }

    /**
     * Every PC: its game speed follows the agreed speed as each change applies, and on a paced host the governor lowers
     * the agreed speed to what the slowest PC holds, and raises it back toward the host player's when it can (SOS-42).
     * Off by default, so test copies can run at speeds of their own.
     */
    public static void followAgreedSpeed() {
        Session s = required();
        s.followSpeed = true;
        s.pacer.govern(true);
    }

    /** The speed line every PC shows while a slow PC holds the speed down, or null (SOS-42). */
    public static String speedNotice() {
        return required().speedNoticeNow(System.nanoTime());
    }

    /** Host: frames the slowest PC held it in, their seconds, and the longest hold in seconds (SOS-42). */
    public static long heldFrames() {
        return required().pacer.heldFrames();
    }

    public static double heldSeconds() {
        return required().pacer.heldSeconds();
    }

    public static double longestHoldSeconds() {
        return required().pacer.longestHoldSeconds();
    }

    /** The first desync found, or null. */
    public static Desync desync() {
        return required().hashes.desync();
    }

    /** The desync report: the step, the parts of the state that differ, the AI log entries applied last. */
    public static String desyncReport() {
        Session s = required();
        Desync d = s.hashes.desync();
        if (d == null) {
            throw new IllegalStateException("SOSDET: no desync to report");
        }
        // player orders come with SOS-33
        return d.report(s.source(), s.ai().recent(), s.ai().recentOrders());
    }

    /**
     * Debugging: record where in the game's code every simulation draw comes from, for the last {@code keepSteps}
     * steps (0: off). Slow; for hunting a desync, not for play.
     */
    public static void traceDraws(int keepSteps) {
        Session s = required();
        s.streams.trace = keepSteps == 0 ? null : new DrawTrace(keepSteps);
    }

    /**
     * Debugging: the draws of steps {@code from} to {@code to} still kept, one call site per line. The same dump from
     * two PCs differs first at the draw that went differently.
     */
    public static String drawTrace(long from, long to) {
        DrawTrace t = required().streams.trace;
        return t == null ? "" : t.dump(from, to);
    }

    /**
     * How long the last frame's game update took, in nanoseconds (-1 before the first frame of the session; without a
     * session, vanilla's last frame). The current frame's update is still running when the caller asks.
     */
    public static long lastFrameUpdateNanos() {
        Session s = session;
        return s == null ? vanillaFrameUpdateNanos : s.lastFrameUpdateNanos;
    }

    static void vanillaFrameUpdated(long nanos) {
        vanillaFrameUpdateNanos = nanos;
    }

    /** When the running step started (System.nanoTime), or 0 between steps: for a stall sampler on another thread. */
    public static long stepStartNanos() {
        Session s = session;
        return s == null ? 0 : s.stepStartNanos;
    }

    /** Counters and step times since the session began or {@link #resetStats()}. */
    public static Stats stats() {
        Session s = required();
        StepTimes t = s.times;
        return new Stats(s.clock.step(), s.streams.simulationDraws(), s.streams.foreignDraws(), s.pathJobs,
                s.pathNanos, s.jobs.nanos.clone(), t.steps(), t.meanMs(), t.percentileMs(0.5), t.percentileMs(0.95), t.percentileMs(0.99),
                t.percentileMs(0.999), t.maxMs(), t.over100Ms(), t.over1S(), s.ai().mode(), s.ai().applied(),
                s.ai().applied(AiEntry.ARTILLERY), s.ai().pending(), s.ai().appliedOrders(), s.ai().applied(AiEntry.COMMAND),
                s.ai().commandsSkipped(), s.clock.agreedSpeed(),
                s.hashes.taken(), s.hashes.meanMs(), s.hashes.maxMs(),
                s.hashes.compared(), s.hashes.missed(), s.reportsTaken, s.reportNanos, s.reportBytes, s.reportMaxBytes,
                s.reportMisses, s.city == null ? 0 : s.city.layout().count(Shape.BOOST),
                s.city == null ? 0 : s.city.layout().count(Shape.VALUE), s.txns.applied(), s.txns.skipped(),
                s.txns.maxInStep(), s.inputsSent, s.reportsReceived);
    }

    public static void resetStats() {
        required().resetStats();
    }

    static Session session() {
        return session;
    }

    private static Session required() {
        Session s = session;
        if (s == null) {
            throw new IllegalStateException("SOSDET: no session; call Engine.begin() first");
        }
        return s;
    }
}

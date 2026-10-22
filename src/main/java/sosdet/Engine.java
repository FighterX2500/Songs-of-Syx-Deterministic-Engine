package sosdet;

import sosdet.ai.AiEntry;
import sosdet.ai.AiLog;
import sosdet.ai.AiMode;
import java.util.Collections;

import sosdet.game.GameAi;
import sosdet.hash.Desync;

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
        try {
            start.run();
        } finally {
            s.streams.leave();
        }
    }

    // ------------------------------------------------------------ the AI general (requirements §3)

    /** The general runs inside every step (the default): deterministic with no log. */
    public static void aiInStep() {
        setAi(AiLog.inStep());
    }

    /**
     * This PC is the host: the general runs on its own thread, and its changes go into the AI log, applied by every
     * PC at step N + 30. Set it before the battle save loads or deploys (that starts the general's thread).
     * {@code startId} names the start state; peers refuse a log made from another.
     */
    public static void aiHost(long startId) {
        setAi(AiLog.host(startId, new GameAi()));
    }

    /** This PC is a peer: the general is off, and the host's AI log (see {@link #aiLogIn}) is applied instead. */
    public static void aiPeer(long startId) {
        setAi(AiLog.peer(startId, new GameAi()));
    }

    public static AiMode aiMode() {
        return required().ai.mode();
    }

    /** Host: the AI log written since the last call, for the mod to send to every peer. */
    public static byte[] aiLogOut() {
        return required().ai.logOut();
    }

    /** Peer: more of the host's AI log, in any pieces. The peer runs at most 30 steps past what it confirms. */
    public static void aiLogIn(byte[] bytes) {
        required().ai.logIn(bytes);
    }

    private static void setAi(AiLog log) {
        Session s = required();
        if (s.clock.step() != 0) {
            throw new IllegalStateException("SOSDET: the AI mode is set before the run (step " + s.clock.step()
                    + "); changing host mid-battle comes with resync (SOS-30)");
        }
        s.ai = log;
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
        return d.report(s.source(), s.ai.recent(), Collections.<String>emptyList());
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
                s.pathNanos, t.steps(), t.meanMs(), t.percentileMs(0.5), t.percentileMs(0.95), t.percentileMs(0.99),
                t.percentileMs(0.999), t.maxMs(), t.over100Ms(), t.over1S(), s.ai.mode(), s.ai.applied(),
                s.ai.applied(AiEntry.ARTILLERY), s.ai.pending(), s.hashes.taken(), s.hashes.meanMs(), s.hashes.maxMs(),
                s.hashes.compared(), s.hashes.missed());
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

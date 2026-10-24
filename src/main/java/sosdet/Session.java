package sosdet;

import sosdet.ai.AiEntry;
import sosdet.ai.AiLog;
import sosdet.ai.AiMode;
import sosdet.game.GameAi;
import sosdet.game.GameHash;
import sosdet.game.GameInputs;
import sosdet.game.GameReport;
import sosdet.game.GameTxns;
import sosdet.game.SpeedLine;
import sosdet.game.cmd.BannerDraft;
import sosdet.game.cmd.PlanStash;
import sosdet.hash.HashLog;
import sosdet.hash.StateSource;
import sosdet.report.CityReport;
import sosdet.report.CitySource;
import sosdet.report.Input;
import sosdet.report.SentSupplies;
import sosdet.report.Reports;
import sosdet.txn.Txn;
import sosdet.txn.TxnQueue;

/** The state of one deterministic run: from {@link Engine#begin()} to {@link Engine#end()}. */
final class Session {

    /** Mixed into the world part's seed ("WORL"). */
    private static final long WORLD_STREAM = 0x574F524CL;
    /** Mixed into an own city's seed ("CITY"), with the PC's number. */
    private static final long CITY_STREAM = 0x43495459L;
    /** Mixed into the seed of a map battle's result, drawn after the load that ends the battle (SOS-32). */
    private static final long BATTLE_STREAM = 0x4241544CL;

    final StepClock clock = new StepClock();
    final Streams streams = new Streams();
    final BattleJobs jobs = new BattleJobs();
    final StepTimes times = new StepTimes();
    /**
     * Where the AI general runs: on the host's own thread by default (owner, 2026-10-22: inside the step its planning
     * stalls the simulation); in-step only when chosen. Made at first use, as the game adapter needs a running game.
     */
    private volatile AiLog ai;
    /** The state hash every 30 steps (B13); a new one after every load. */
    HashLog hashes = new HashLog();
    /** What the hash reads: the game, made at the first hash; tests put in their own. */
    StateSource source;
    /**
     * This PC's room plan, lifted out of the simulation around its steps (ADR 0003). Made at first use, as it needs a
     * running game; tests without one put in {@link PlanStash#off()}.
     */
    PlanStash plans;
    /** The game's saver that lifts the plan around its saves ({@link Hooks#frameUpdated}), registered once per game. */
    Object planSaver;
    /** This PC's player's banner edit in the profile, until Accept or Cancel (SOS-32). */
    final BannerDraft bannerDraft = new BannerDraft();
    /** The world-battle prompt open on this PC (and, opened by the step, on every PC): what it decides, and its close. */
    Object worldPrompt;
    Runnable worldPromptClose;
    /** Its default answer when its time runs out, the time it has waited, and the armies waiting while it is open (SOS-45). */
    Runnable worldPromptTimeout;
    final PromptTimer promptTimer = new PromptTimer();
    int[] worldPromptArmies = NO_ARMIES;
    static final int[] NO_ARMIES = new int[0];
    /** The simulation's yes/no questions not yet answered ({@code cmd.StepPrompts}): key, then the yes and no actions. */
    final java.util.Map<Long, Object[]> stepPrompts = new java.util.HashMap<>();
    /** The step the last question opened at, and how many opened in it: the next question's key. */
    long stepPromptStep = -1;
    int stepPromptsInStep;
    /** The kept question this PC's yes/no dialog shows, or -1: answered here, or by another PC's answer. */
    long stepPromptShown = -1;

    /** Host: the game speed last seen, so a change goes into the log once (B16). */
    double seenSpeed;
    /** Host: the pacing by the slowest PC and the speed governor (SOS-42). */
    final Pacer pacer = new Pacer(pc -> hashes.reached(pc));
    /** Every PC's game speed follows the agreed one, and the host's governor runs (SOS-42). */
    boolean followSpeed;
    /** The speed line the top bar shows (null: none), and when it goes (0: while the speed is held down). */
    volatile String speedNotice;
    volatile long speedNoticeUntil;
    private SpeedLine speedLine;
    /** The random stream is reseeded every step from this and the step (requirements §1); set at step 0. */
    int seed;
    /** A resync load in progress: applied when the load begins, then cleared. */
    Resync resync;
    /** The host's draw count at the resync, set once the load's own draws are over (-1: none pending). */
    long resyncDraws = -1;

    boolean worldHeld;
    /**
     * A map battle has ended and the game saved before it loads next (SOS-32): the steps hold until then, and the load
     * continues at the step the battle ended at.
     */
    boolean battleLeaving;
    /** The open battle's victory or defeat panel's close, kept for the conclude command, or null (SOS-32). */
    Runnable battleConclude;
    /** 0 = loads run as in vanilla; otherwise loads run on the simulation stream seeded with this. */
    int loadSeed;
    /** The last frame's game update time (-1 before the first); read for the timing measurements. */
    volatile long lastFrameUpdateNanos = -1;
    /** Actions to run after the frame's game update, outside the step, as the UI would (the rig's player actions). */
    final java.util.ArrayDeque<Runnable> betweenSteps = new java.util.ArrayDeque<>();
    /** A save is loading: its own resets of the game's state are never a player's commands (ADR 0002). */
    boolean loading;
    /** When the running step started (0 between steps); read by the rig's stall sampler on another thread. */
    volatile long stepStartNanos;
    long pathJobs;
    long pathNanos;
    /** Runs the path jobs a step queued, at its end (SOS-37). */
    final PathWorkers paths = new PathWorkers();
    /** The game's queue of path jobs, set by the first job a step queues; null when none is waiting. */
    PathQueue pathQueue;
    /** The part of the step running: 0 the city part, 1 the world part, -1 outside them (ADR 0005). */
    int part = -1;
    /** This PC's human: the slot of its faction (ADR 0004); slot 0 until the mod sets another. */
    int localHuman;
    /**
     * The world part's current human, the one the world is working on: each human in turn for its own world half, the
     * first human (slot 0, always a human) otherwise, for now (plan 0004, M1).
     */
    int currentHuman;
    /** Game time since the player's own faction update last ran in the city part; 0 after every load. */
    double playerUpdateAcc;
    /** The player's city reports, which the world part reads instead of the live city (ADR 0005 §2). */
    final Reports reports = new Reports();
    /** Where the reports come from: the game, made at the first report; tests put in their own. */
    CitySource city;
    long reportsTaken;
    /** World-part reads of a boost or faction value the report has no slot for: read live instead. */
    long reportMisses;
    long reportNanos;
    int reportBytes;
    int reportMaxBytes;
    /** What the city did to the world since its last report, for the next one (B1, B2). */
    final java.util.ArrayList<Input> cityInputs = new java.util.ArrayList<>();
    /** The supplies among them, which the city reads as sent until the world applies them. */
    final SentSupplies sentSupplies = new SentSupplies();
    /** Inputs the city's reports carried (ADR 0005 §2). */
    long inputsSent;
    /** World marks the city's scripted events set that its reports have yet to apply (B6). */
    final sosdet.report.EventMarks eventMarks = new sosdet.report.EventMarks();
    /** The world part's changes to the city, applied in the step's city tail (ADR 0005 §3). */
    final TxnQueue txns = new TxnQueue();
    /** What applies them: the game, made at first use; tests put in their own. */
    java.util.function.Consumer<Txn> txnApplier;

    /**
     * Own-city mode (ADR 0005 §1, §6): this PC's city is its own. Its commands apply on this PC only, its stream is
     * seeded apart from every other PC's, and the hash compares the world only. Off: co-op, every PC runs one city.
     */
    boolean ownCity;
    /** Whose city the world part reads: -1 this PC's own; otherwise the PC whose reports come in (SOS-38's follower). */
    int reportsFrom = -1;
    /** Own-city mode: this PC's reports going out, and the source PC's coming in. */
    final sosdet.report.ReportFeed reportFeed = new sosdet.report.ReportFeed();
    /** The step the last report from {@link #reportsFrom} was taken at (-1: none since the load). */
    long receivedTakenAt = -1;
    long reportsReceived;
    /** A world resync's load is running: its resets of the world never reach the city (SOS-38). */
    boolean worldLoading;
    /** The world resyncs loaded: a game cache made from the world before one starts again after it (SOS-32). */
    int worldLoads;

    /** The seed of the random stream at a step: the same on every PC, whatever happened before. */
    int stepSeed(long step) {
        long h = HashLog.mix(HashLog.mix(HashLog.start(), seed), step);
        return (int) (h ^ (h >>> 32));
    }

    /**
     * The seed of the city's stream at a step: in co-op the step's seed; an own city's also mixes in the PC, so no
     * two cities draw alike and nothing the city draws can stand in for a world draw.
     */
    int citySeed(long step) {
        if (!ownCity) {
            return stepSeed(step);
        }
        long h = HashLog.mix(HashLog.mix(HashLog.mix(HashLog.mix(HashLog.start(), seed), step), CITY_STREAM),
                ai().localPc());
        return (int) (h ^ (h >>> 32));
    }

    /** The seed of a map battle's result at a step, drawn outside the step after the load (SOS-32). */
    int battleSeed(long step) {
        long h = HashLog.mix(HashLog.mix(HashLog.mix(HashLog.start(), seed), step), BATTLE_STREAM);
        return (int) (h ^ (h >>> 32));
    }

    /** The seed of the world part's random stream at a step: never the city part's, whatever the city drew. */
    int worldSeed(long step) {
        long h = HashLog.mix(HashLog.mix(HashLog.mix(HashLog.start(), seed), step), WORLD_STREAM);
        return (int) (h ^ (h >>> 32));
    }

    /**
     * A load starts the run again from step 0; a resync load continues at the host's step instead, with the engine
     * state the save leaves out (B14).
     */
    void afterLoad() {
        playerUpdateAcc = 0;
        reports.clear(); // every PC reloads, so every PC takes its load report from the loaded city
        reportFeed.clear();
        receivedTakenAt = -1;
        txns.dropQueued();
        cityInputs.clear(); // the loaded world has the city's state as saved
        sentSupplies.clear();
        eventMarks.clear();
        battleConclude = null;
        if (battleLeaving) {
            // every PC left the battle at this step: the step, the hashes and the AI log go on (SOS-32)
            battleLeaving = false;
            resyncDraws = streams.drawsSinceLoad(); // the load's own draws don't count, as in a resync
            return;
        }
        if (resync != null) {
            Resync r = resync;
            resync = null;
            clock.resetTo(r.step);
            txns.appliedThrough(r.step); // the loaded city has every transaction up to the resync step
            clock.agreedSpeed(r.speed);
            seed = r.seed;
            resyncDraws = r.draws; // the load's own draws don't count: set when it ends
            hashes.rewind(r.step, r.hashEpoch);
            ai().resyncIn(r.ai);
            return;
        }
        clock.reset();
        txns.appliedThrough(-1);
        streams.loaded();
        int interval = hashes.interval();
        hashes = new HashLog();
        hashes.every(interval);
        hashes.worldOnly(ownCity);
    }

    /** Whether the world part of this step may run: a follower waits for the source's report it reads (ADR 0005 §4). */
    boolean worldMayRun(long step) {
        return reportsFrom < 0 || (receivedTakenAt >= 0 && step <= Reports.lastReadableStep(receivedTakenAt));
    }

    /** A report from {@link #reportsFrom}: it applies at its step as this PC's own would have. */
    void reportReceived(CityReport r) {
        if (r.faction != city().faction()) {
            throw new IllegalArgumentException("SOSDET: a city report of faction " + r.faction + ", the world reads "
                    + city().faction() + "'s");
        }
        if (r.takenAt <= receivedTakenAt) {
            return; // a repeat changes nothing
        }
        reports.stamp(r);
        receivedTakenAt = r.takenAt;
        reportsReceived++;
    }

    /**
     * The city part ends: a report of the city as it is now, which the world part reads from {@code appliesAt}. Its
     * bytes are counted, as another PC would receive them.
     */
    void takeReport(long step, long appliesAt) {
        long start = System.nanoTime();
        CitySource c = city();
        double[] v = new double[c.layout().size()];
        c.fill(v);
        CityReport r = new CityReport(c.layout(), c.faction(), step, appliesAt, v, cityInputs);
        inputsSent += cityInputs.size();
        cityInputs.clear();
        byte[] bytes = r.toBytes();
        reportBytes = bytes.length;
        reportMaxBytes = Math.max(reportMaxBytes, reportBytes);
        if (ownCity) {
            reportFeed.write(bytes);
        }
        if (reportsFrom < 0) {
            reports.stamp(r); // a follower's world reads the source's report instead
        }
        reportsTaken++;
        reportNanos += System.nanoTime() - start;
    }

    CitySource city() {
        if (city == null) {
            city = new GameReport();
        }
        return city;
    }

    AiLog ai() {
        AiLog a = ai;
        if (a == null) {
            synchronized (this) { // the general's thread may ask first
                if (ai == null) {
                    ai = AiLog.host(0, new GameAi());
                }
                a = ai;
            }
        }
        return a;
    }

    /**
     * This PC's game speed, every frame. In-step it is the agreed speed at once (one PC). The host logs a change, and
     * every PC switches at the stamped step. A peer's own speed only sets its pace.
     */
    void speedSeen(double speed) {
        if (speed <= 0 || speed == seenSpeed) {
            return; // paused, or no change
        }
        seenSpeed = speed;
        AiLog log = ai();
        if (log.mode() == AiMode.IN_STEP) {
            clock.agreedSpeed(speed);
        } else if (log.mode() == AiMode.HOST) {
            pacer.wanted(speed, System.nanoTime());
            logSpeed(speed, speed, -1);
        }
    }

    /** Host, every frame outside the step: the cap the slowest PC sets, and the governor's speed change (SOS-42). */
    void pace(long now) {
        if (ai().mode() != AiMode.HOST) {
            return;
        }
        clock.paceCap(pacer.frame(now, clock.step(), clock.lastStep()));
        double speed = pacer.speed(now);
        if (speed > 0) {
            logSpeed(speed, pacer.wantedSpeed(), pacer.slowPc());
        }
    }

    /** A speed change for every PC at its step: the agreed speed, the host player's, the PC that held it down or -1. */
    private void logSpeed(double speed, double wanted, int slowPc) {
        ai().captureOrder(AiEntry.SPEED, -1, AiEntry.ints((int) Math.round(speed * 1000),
                (int) Math.round(wanted * 1000), slowPc));
    }

    /** Every PC, as a speed change applies: the line the top bar shows while a slow PC holds the speed down. */
    void speedNotice(double speed, double wanted, int slowPc, long now) {
        if (slowPc >= 0 && speed < wanted) {
            speedNoticeUntil = 0;
            speedNotice = "SOSDET: speed " + speedText(speed) + ", PC " + slowPc + " can't keep up (host set "
                    + speedText(wanted) + ")";
        } else if (speedNotice != null && speedNoticeUntil == 0) {
            speedNoticeUntil = now + 5 * Pacer.SECOND;
            speedNotice = "SOSDET: every PC keeps up again, speed " + speedText(speed);
        }
    }

    /** The speed line to show now, or null. */
    String speedNoticeNow(long now) {
        String n = speedNotice;
        long until = speedNoticeUntil;
        return n == null || until != 0 && now > until ? null : n;
    }

    /** The top bar's speed line, made at first use (it needs the game's fonts). */
    SpeedLine speedLine() {
        if (speedLine == null) {
            speedLine = new SpeedLine();
        }
        return speedLine;
    }

    private static String speedText(double speed) {
        double r = Math.round(speed * 10) / 10.0;
        return (r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r)) + "x";
    }

    void ai(AiLog log) {
        ai = log;
    }

    PlanStash plans() {
        if (plans == null) {
            plans = new PlanStash();
        }
        return plans;
    }

    private GameInputs inputs;

    /** Makes the city's inputs and applies them in the world part: the game, made at first use. */
    GameInputs inputs() {
        if (inputs == null) {
            inputs = new GameInputs(sentSupplies, eventMarks, () -> reportsFrom < 0);
        }
        return inputs;
    }

    /** What applies a report's inputs in the world part: the game, made at first use; tests put in their own. */
    java.util.function.Consumer<CityReport> inputApplier;

    java.util.function.Consumer<CityReport> inputApplier() {
        if (inputApplier == null) {
            inputApplier = inputs();
        }
        return inputApplier;
    }

    java.util.function.Consumer<Txn> txnApplier() {
        if (txnApplier == null) {
            txnApplier = new GameTxns(inputs(), cityInputs, () -> reportsFrom >= 0);
        }
        return txnApplier;
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
        reportsTaken = 0;
        reportNanos = 0;
        reportMaxBytes = 0;
        reportMisses = 0;
        inputsSent = 0;
        reportsReceived = 0;
        txns.resetStats();
        java.util.Arrays.fill(jobs.nanos, 0);
        pacer.resetCounts();
    }
}

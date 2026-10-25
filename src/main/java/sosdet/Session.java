package sosdet;

import sosdet.ai.AiEntry;
import sosdet.ai.AiLog;
import sosdet.ai.AiMode;
import sosdet.game.GameAi;
import sosdet.game.GameHash;
import sosdet.game.GameInputs;
import sosdet.game.GameReport;
import sosdet.game.GameTxns;
import sosdet.game.StepParts;
import sosdet.game.SpeedLine;
import sosdet.game.cmd.BannerDraft;
import sosdet.game.cmd.PlanStash;
import sosdet.hash.Desync;
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
    /** The human it asks: only that human's PC shows it, and only that human's answer applies (SOS-46). */
    int worldPromptHuman;
    /** Its default answer when its time runs out, the time it has waited, and the armies waiting while it is open (SOS-45). */
    Runnable worldPromptTimeout;
    final PromptTimer promptTimer = new PromptTimer();
    int[] worldPromptArmies = NO_ARMIES;
    static final int[] NO_ARMIES = new int[0];
    /**
     * The simulation's yes/no questions not yet answered ({@code cmd.StepPrompts}): key, then the yes and no actions and
     * the human asked.
     */
    final java.util.Map<Long, Object[]> stepPrompts = new java.util.HashMap<>();
    /**
     * The step the last question opened at, and how many world and city questions opened in it: the next question's
     * key. A city question opens on one PC only, so it never shifts a world question's key (SOS-46).
     */
    long stepPromptStep = -1;
    int stepPromptsInStep;
    int stepCityPromptsInStep;
    /** The kept question this PC's yes/no dialog shows, or -1: answered here, or by another PC's answer. */
    long stepPromptShown = -1;

    /** Host: the game speed last seen, so a change goes into the log once (B16). */
    double seenSpeed;
    /** Host: the pacing by the slowest PC and the speed governor (SOS-42). */
    final Pacer pacer = new Pacer(pc -> hashes.reached(pc));
    /** Every PC's game speed follows the agreed one, and the host's governor runs (SOS-42). */
    boolean followSpeed;
    /** The desync every PC holds for, from the host's hold entry, until a resync loads it (SOS-53); or null. */
    DesyncHold desyncHold;
    /** Host: a hold entry was logged for the desync found now, so a second one isn't (SOS-53). */
    boolean holdLogged;
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
    /** Own-city mode: the battle on the battle map, from Engage until every PC is back in its city (plan 0006). */
    OwnBattle battle;
    /** Own-city mode, set by the mod: every PC holds before a step whose commands engage a battle (SOS-70). */
    boolean battleHold;
    /** The step an Engage applies at while every PC holds before it, and the last one let through; -1 for none. */
    long battleAhead = -1, battleGo = -1;
    /** The engine itself loads a battle save ({@link Engine#battleIn}): the restart hook lets it through. */
    boolean battleLoading;
    /** The mod's actions after each load of a whole game: the new game has none of the old one's (plan 0006). */
    final java.util.List<Runnable> afterLoads = new java.util.ArrayList<>();
    /** Other PCs' report bytes that came in during a battle, by PC, fed after this PC is back in its city. */
    final java.util.List<Object[]> heldReports = new java.util.ArrayList<>();
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
    /** Each PC's human, by PC: the same table on every PC (ADR 0004); slot 0 for a PC the mod hasn't set. */
    final int[] pcHumans = unset(new int[SLOTS]);
    /**
     * Outside the step only: the human this PC reads the game as ({@code Engine.asHuman}, the rig's probes), -1 for its
     * own. Never read in the step (SOS-46).
     */
    int viewHuman = -1;
    /** Game time since the player's own faction update last ran in the city part; 0 after every load. */
    double playerUpdateAcc;
    /** The slots a game has ({@code FACTIONS.MAX()}). */
    static final int SLOTS = 64;
    /** Each human's city reports, by slot, which the world part reads instead of the live city (ADR 0005 §2, ADR 0004). */
    private final Reports[] reports = new Reports[SLOTS];
    /** The game's human slots in slot order, read from the city at first use after a load. */
    private int[] humans;
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
    /** The NPCs' production boosts while a city generates (hook 18), put back after it; null otherwise (SOS-56). */
    double[] keptBoosts;
    /** Whose city the world part reads: -1 this PC's own; otherwise the PC whose reports come in (SOS-38's follower). */
    int reportsFrom = -1;
    /** Own-city mode: this PC's reports going out. */
    final sosdet.report.ReportFeed reportFeed = new sosdet.report.ReportFeed();
    /** Own-city mode: each other PC's reports coming in, by PC. */
    private final java.util.Map<Integer, sosdet.report.ReportFeed> feedsIn = new java.util.HashMap<>();
    /** By human slot: the step the last report from another PC was taken at (-1: none since the load). */
    final long[] receivedTakenAt = none();
    long reportsReceived;
    /** A world resync's load is running: its resets of the world never reach the city (SOS-38). */
    boolean worldLoading;
    /** The world resyncs loaded: a game cache made from the world before one starts again after it (SOS-32). */
    int worldLoads;

    /** The city tail applies the transactions of this PC's human only: every other human's city is its own PC's. */
    final java.util.function.Consumer<Txn> ownTxns = t -> {
        if (t.human == localHuman) {
            txnApplier().accept(t);
        }
    };

    private static long[] none() {
        long[] l = new long[SLOTS];
        java.util.Arrays.fill(l, -1);
        return l;
    }

    /** The reports of the human in {@code slot}. */
    Reports reports(int slot) {
        Reports r = reports[slot];
        if (r == null) {
            r = new Reports();
            reports[slot] = r;
        }
        return r;
    }

    /** The reports read now: in the world part the current human's, elsewhere this PC's human's. */
    Reports reports() {
        return reports(part == StepParts.WORLD_PART ? currentHuman : localHuman);
    }

    /** The game's human slots, in slot order. */
    int[] humans() {
        if (humans == null) {
            humans = city().humans();
        }
        return humans;
    }

    /**
     * The slot {@code FACTIONS.player()} is now: in the world part the current human, elsewhere this PC's human (or the
     * one it reads as outside the step).
     */
    int humanNow() {
        if (battle != null && !battle.awaiting) {
            return battle.human; // the battle is the battle PC's game on every PC, its human's boosts too (plan 0006)
        }
        if (part == StepParts.WORLD_PART) {
            return currentHuman;
        }
        return viewHuman >= 0 ? viewHuman : localHuman;
    }

    /** The ordinal of the human in {@code slot} among the human slots; 0 for slot 0 and while a game loads (SOS-46). */
    int ordinal(int slot) {
        if (slot == 0 || loading) {
            return 0;
        }
        int[] hs = humans();
        for (int i = 0; i < hs.length; i++) {
            if (hs[i] == slot) {
                return i;
            }
        }
        throw new IllegalStateException("SOSDET: slot " + slot + " is no human (ADR 0004)");
    }

    /** True when a human's city reports come from another PC: every human but this PC's, and this PC's on a follower. */
    boolean remote(int slot) {
        return slot != localHuman || reportsFrom >= 0;
    }

    boolean isHuman(int slot) {
        for (int h : humans()) {
            if (h == slot) {
                return true;
            }
        }
        return false;
    }

    /** Another PC's report stream. */
    sosdet.report.ReportFeed feedIn(int pc) {
        return feedsIn.computeIfAbsent(pc, k -> new sosdet.report.ReportFeed());
    }

    /**
     * The reports the world part reads now, as one hash: none 0, one its own hash (as with a single human), several
     * each with its slot, mixed in slot order.
     */
    long reportsHash() {
        CityReport one = null;
        int n = 0;
        for (Reports r : reports) {
            if (r != null && r.current() != null) {
                one = r.current();
                n++;
            }
        }
        if (n <= 1) {
            return one == null ? 0 : one.hash();
        }
        long h = HashLog.start();
        for (int slot = 0; slot < SLOTS; slot++) {
            if (reports[slot] != null && reports[slot].current() != null) {
                h = HashLog.mix(HashLog.mix(h, slot), reports[slot].current().hash());
            }
        }
        return h;
    }

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
        if (!ownCity || battle != null) {
            return stepSeed(step); // in a battle every PC runs the same game (plan 0006)
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

    /** The world stream's seed after a battle's Engage in own-city mode: every PC's own generation drew differently. */
    int afterEngageSeed(long step) {
        long h = HashLog.mix(HashLog.mix(HashLog.mix(HashLog.start(), seed), step), BATTLE_STREAM + 1);
        return (int) (h ^ (h >>> 32));
    }

    /** The PC whose human is {@code slot} (ADR 0004); the host (0) when no PC has it, as with one human. */
    int pcOf(int slot) {
        for (int pc = 0; pc < pcHumans.length; pc++) {
            if (pcHumans[pc] == slot) {
                return pc;
            }
        }
        return 0;
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
        viewHuman = -1; // a read as another human never outlives a load (SOS-46)
        for (Reports r : reports) {
            if (r != null) {
                r.clear(); // every PC reloads, so every PC takes its load report from the loaded city
            }
        }
        humans = null;
        currentHuman = 0;
        reportFeed.clear();
        for (sosdet.report.ReportFeed f : feedsIn.values()) {
            f.clear();
        }
        java.util.Arrays.fill(receivedTakenAt, -1);
        txns.dropQueued();
        cityInputs.clear(); // the loaded world has the city's state as saved
        sentSupplies.clear();
        eventMarks.clear();
        battleConclude = null;
        if (battleLeaving) {
            // every PC left the battle at this step: the step, the hashes and the AI log go on (SOS-32)
            battleLeaving = false;
            resyncDraws = streams.drawsSinceLoad(); // the load's own draws don't count, as in a resync
            if (battle != null) {
                battleLoaded();
            }
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

    /**
     * Own-city mode, a load in a battle (plan 0006): the battle PC's battle, loaded on every PC, or each PC's own city
     * back at the battle's end. Each PC's own city drew its own count, so both start the draws from 0 alike; the world's
     * count goes back to the Engage step's, as the world stood still. The hash covers the battle while it runs.
     */
    private void battleLoaded() {
        resyncDraws = 0;
        streams.worldDraws(battle.worldDraws);
        if (battle.awaiting) {
            battle.awaiting = false;
            hashes.worldOnly(false);
        } else {
            hashes.worldOnly(true);
        }
    }

    /**
     * Whether the world part of this step may run: it waits for each report it reads from another PC, every other
     * human's and, on a follower, the source's (ADR 0005 §4, ADR 0004).
     */
    boolean worldMayRun(long step) {
        if (!ownCity || battle != null) {
            return true; // co-op: every PC takes the same reports itself; in a battle the world stands still
        }
        for (int h : humans()) {
            if (remote(h) && (receivedTakenAt[h] < 0 || step > Reports.lastReadableStep(receivedTakenAt[h]))) {
                return false;
            }
        }
        return true;
    }

    /** A report from another PC, of a human it runs: it applies at its step as this PC's own would have. */
    void reportReceived(CityReport r) {
        if (r.faction < 0 || r.faction >= SLOTS || !isHuman(r.faction) || !remote(r.faction)) {
            throw new IllegalArgumentException("SOSDET: a city report of faction " + r.faction + ", not a human whose city"
                    + " the world reads from another PC");
        }
        if (r.takenAt <= receivedTakenAt[r.faction]) {
            return; // a repeat changes nothing
        }
        reports(r.faction).stamp(r);
        receivedTakenAt[r.faction] = r.takenAt;
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
            reports(c.faction()).stamp(r); // a follower's world reads the source's report instead
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

    /**
     * A world-part read the report has no slot for, read live instead: counted, and refused with several humans,
     * where the live value is another human's on each PC (SOS-46).
     */
    void reportMiss(Object what) {
        reportMisses++;
        if (humans().length > 1) {
            throw new IllegalStateException("SOSDET: the world part read " + what + " live; with several humans every"
                    + " city value it reads needs a report slot (ADR 0004)");
        }
    }

    /**
     * A load builds a new game: the report's lookups, the inputs and the transactions' applier were made from the old
     * one's values, boosts and locks, and would miss every one of the new game's (SOS-46). Made again at first use.
     */
    void dropGameObjects() {
        city = null;
        humans = null;
        inputs = null;
        inputApplier = null;
        txnApplier = null;
    }

    /**
     * Before the run (step 0), with several humans: each human has exactly one PC, and this PC's is its own human
     * (ADR 0004). A PC without a human, or two PCs on one, would act for the wrong human everywhere.
     */
    void checkPcHumans() {
        int[] hs = humans();
        if (hs.length < 2) {
            return;
        }
        for (int h : hs) {
            int pcs = 0;
            for (int pc : pcHumans) {
                if (pc == h) {
                    pcs++;
                }
            }
            if (pcs != 1) {
                throw new IllegalStateException("SOSDET: the human in slot " + h + " has " + pcs + " PCs; give each human"
                        + " one PC with pcHuman before the run");
            }
        }
        if (pcHumans[ai().localPc()] != localHuman) {
            throw new IllegalStateException("SOSDET: this PC (" + ai().localPc() + ") is slot " + pcHumans[ai().localPc()]
                    + "'s, its local human is slot " + localHuman);
        }
    }

    private static int[] unset(int[] a) {
        java.util.Arrays.fill(a, -1);
        return a;
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
        holdForDesync();
        clock.paceCap(pacer.frame(now, clock.step(), clock.lastStep()));
        double speed = pacer.speed(now);
        if (speed > 0) {
            logSpeed(speed, pacer.wantedSpeed(), pacer.slowPc());
        }
    }

    /**
     * Host, every frame: the first desync its hashes show puts a hold entry in the log, so every PC, the host too,
     * stops at that entry's step and the host chooses what follows (SOS-53). The desync and the differing PC travel in
     * the entry, so every PC's window can name them.
     */
    private void holdForDesync() {
        Desync d = hashes.desync();
        if (d == null || holdLogged) {
            return;
        }
        holdLogged = true;
        ai().captureHold(AiEntry.ints(d.pc, (int) (d.step >>> 32), (int) d.step));
    }

    /** Every PC, as the host's hold entry applies at {@code step}: no step past it until a resync ends the hold. */
    void desyncHold(long step, int pc, long desyncStep) {
        desyncHold = new DesyncHold(desyncStep, pc, step);
        clock.holdAt(step);
    }

    /** A world resync at the hold's step ends the hold (SOS-53). */
    void endDesyncHold(long resyncStep) {
        if (desyncHold != null && desyncHold.holdStep == resyncStep) {
            desyncHold = null;
            holdLogged = false;
            clock.holdAt(StepClock.NO_LIMIT);
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
            inputs = new GameInputs(sentSupplies, eventMarks, () -> reportsFrom < 0 && currentHuman == localHuman);
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

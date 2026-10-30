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
import sosdet.game.siege.AssaultTerms;
import sosdet.hash.Desync;
import sosdet.hash.HashLog;
import sosdet.hash.StateSource;
import sosdet.report.CityReport;
import sosdet.report.CitySource;
import sosdet.report.Input;
import sosdet.report.SentSupplies;
import sosdet.report.Reports;
import sosdet.txn.Txn;
import sosdet.txn.TransferLog;
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
    /**
     * A battle between two humans' armies (SOS-83): the other human it asks too, or -1; the next prompt's, set just
     * before it opens; which of the two pressed Fight (bit 0 the asked, bit 1 the other); and the step its battle
     * engages at, once both did or the time ran out, or -1. All set inside the step, alike on every PC.
     */
    int worldPromptOther = -1;
    int worldPromptOtherNext = -1;
    /** The open prompt is a sally against this besieging human, asked of the defender only; -1 for none (SOS-83). */
    int worldPromptSally = -1;
    int worldPromptSallyNext = -1;
    /** The last battle between two humans' outcome, until the mod takes it (SOS-83). */
    BattleOutcome battleOutcome;
    int worldPromptReady;
    long pvpEngageAt = -1;
    /** Its default answer when its time runs out, the time it has waited, and the armies waiting while it is open (SOS-45). */
    Runnable worldPromptTimeout;
    final PromptTimer promptTimer = new PromptTimer();
    /** A battle between two humans' deployment: Start for both when it runs out (SOS-83). */
    final PromptTimer deployTimer = new PromptTimer(PromptTimer.DEPLOY_SECONDS);
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
    final Pacer pacer = new Pacer(pc -> hashes.reached(pc), pc -> hashes.capacityOf(pc));
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
    /** The time this frame's steps took, for this PC's capacity (SOS-94); timing only. */
    long frameStepNanos;
    long pathJobs;
    long pathNanos;
    /** The step profile, while a rig run asks for it (SOS-106); null otherwise. Timing only. */
    sosdet.game.StepProfiler profiler;
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
    /** By slot, read with {@link #humans}: a removed human, retired on every PC from its removal's step (SOS-66). */
    private boolean[] retired;
    /**
     * By slot: a removal in the host's log, not yet applied (SOS-66). This PC waits for no report of that human taken
     * after {@link #removeCutoff}, the last one every PC reads: the host's when it logged the removal (-1: none).
     */
    final boolean[] removing = new boolean[SLOTS];
    final long[] removeCutoff = none();
    /** By slot: a removal that applied, its realm freed at the start of a world part once no battle runs (SOS-66). */
    final boolean[] removeDue = new boolean[SLOTS];
    /** By slot: a load in the session cleared the reports, and none has come in since (SOS-66). */
    private final boolean[] reportsLost = new boolean[SLOTS];
    /** What retires a human in the game: the game, made at first use; tests put in their own. */
    java.util.function.IntConsumer remover;
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
    /** Every money and goods transaction, for the logs only (SOS-81): not saved, not hashed, never read by the step. */
    final TransferLog transfers = new TransferLog();
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
    /**
     * By human slot: the first step a report from another PC not yet in may apply at, from the last one's promise
     * (SOS-90); the world part runs to the step before without waiting (-1: none since the load).
     */
    final long[] receivedNextAt = none();
    /** The step this PC's last report promised its next would apply at, at the earliest (SOS-90). */
    private long reportPromise;
    /** This frame's steps stopped to wait for another PC's report or the host's log (SOS-90). */
    boolean waited;
    long reportsReceived;
    /** A world resync's load is running: its resets of the world never reach the city (SOS-38). */
    boolean worldLoading;
    /** The world resyncs loaded: a game cache made from the world before one starts again after it (SOS-32). */
    int worldLoads;

    /**
     * The city tail applies the transactions of the city this PC runs only: every other human's city is its own PC's.
     * While this PC views another human's city, its own human's wait until its city is back (SOS-82).
     */
    final java.util.function.Consumer<Txn> ownTxns = t -> {
        if (t.human == cityHuman()) {
            txnApplier().accept(t);
        } else if (t.human == localHuman && frozen(localHuman)) {
            this.heldTxns.add(sosdet.game.HeldTxn.of(t)); // the queue clears and reuses t after the step (SOS-97)
        }
    };

    // ------------------------------------------------------------ city views (plan 0013, SOS-82)

    /** The city views in the log, by the viewer's slot: the same on every PC; null where there is none. */
    final View[] views = new View[SLOTS];
    /** This PC as a viewer: the transactions for its own city since the freeze, applied when the city is back. */
    final java.util.ArrayList<sosdet.game.HeldTxn> heldTxns = new java.util.ArrayList<>();
    /** This PC as a viewer: its own city's engine state at the freeze ({@link ViewCity#cityState}), or null. */
    byte[] ownCityState;
    /** The owner's city's engine state, put in by the view load on the owner's and the viewer's PC, then cleared. */
    byte[] viewLoad;
    /** A view load runs: the owner's save loading on the owner's or the viewer's PC, or another PC's own game. */
    boolean viewLoading;
    /** When the running view load started ({@code System.nanoTime}), and how long the last one took, in ms. */
    private long viewLoadStarted;
    double lastViewLoadMs;
    /** Test tooling (rig dumps): run once the next view load is set, before any step ({@code reloadOwnForDebug}). */
    Runnable viewLoadDone;
    /** Test tooling (rig dumps): run once between steps, after step {@link #debugAtStep} ({@code Engine.debugAt}). */
    long debugAtStep = -1;
    Runnable debugAt;
    /** The viewer whose view hashes start once the view load is set. */
    int viewHashFor = -1;
    /** The view hashes: the viewer's copy of the owner's city, or the owner's city for one viewer, by viewer slot. */
    final HashLog[] viewHashes = new HashLog[SLOTS];
    /** The city's draws at the freeze step, by viewer slot, so both PCs count from there alike. */
    final long[] viewDrawsBase = new long[SLOTS];
    /** This PC as a viewer: its limits' clock, from its view load (M2). */
    final ViewLimit viewLimit = new ViewLimit();

    /** True from a view's freeze step: the human in {@code slot} views another city, and every world reads it frozen. */
    boolean frozen(int slot) {
        View v = views[slot];
        return v != null && v.frozen();
    }

    /** This PC's view as the viewer, running: the owner's city is the one this PC runs. Null otherwise. */
    View viewing() {
        View v = views[localHuman];
        return v != null && v.state == View.RUNNING && !v.back ? v : null;
    }

    /** This PC as a viewer: the world saved at the step before the thaw, loaded into its own game once that is back. */
    java.nio.file.Path viewWorld;
    /** The world's move remainders, which no save holds: put back after a view load or the load back. */
    double[] viewMoves;

    /**
     * Between steps, before a view load (the owner's save, on the owner's or the viewer's PC): the city's engine state
     * and the world's move remainders the load puts in, and the viewer whose view hashes start after it.
     */
    void viewLoadStart(byte[] state, double[] moves, int hashesFor) {
        viewLoadStarted = System.nanoTime();
        viewLoad = state;
        viewMoves = moves;
        viewHashFor = hashesFor;
        viewLoading = true;
    }

    /** The view load is set: the world's move remainders go back, and the view's hashes count from here. */
    void viewLoadEnd() {
        sosdet.game.GameWorldLoad.unsaved(viewMoves);
        viewMoves = null;
        if (viewHashFor >= 0) {
            viewHashesFrom(viewHashFor);
        }
        lastViewLoadMs = (System.nanoTime() - viewLoadStarted) / 1e6;
        if (viewing() != null) {
            viewLimit.start(System.nanoTime()); // catching up starts: the save, the download and the load don't count
        }
        if (viewLoadDone != null) {
            Runnable r = viewLoadDone;
            viewLoadDone = null;
            r.run(); // debugging: the rig's restore, before any step (SOS-82)
        }
        viewHashFor = -1;
        viewLoading = false;
    }
    /** This PC's own game is loading back for the thaw. */
    boolean viewBackLoading;

    /**
     * Between steps, before each step: the viewer at the step before its thaw saves the world it runs and loads its
     * own game back; the world goes into it once it is set ({@link #viewBackWorld}).
     */
    void viewBackStart() {
        View v = viewing();
        if (v == null || v.thaw < 0 || viewBackLoading || clock.step() != v.thaw - 1) {
            return; // a thaw put off (a prompt open, a battle) moved at the end of this step already (viewsEnd)
        }
        try {
            viewWorld = java.nio.file.Files.createTempFile("sosdet-viewworld", ".save");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("SOSDET: no file for the world a view leaves", e);
        }
        sosdet.game.GameWorldLoad.save(viewWorld);
        viewMoves = sosdet.game.GameWorldLoad.unsaved();
        viewBackLoading = true;
        sosdet.game.GameView.loadOwn();
    }

    /**
     * The viewer's own game is set again: the world it ran while viewing goes into it, keeping its city, with the
     * world's move remainders; then the transactions its city missed apply, and its player acts again.
     */
    void viewBackWorld() {
        View v = views[localHuman];
        viewBackLoading = false;
        v.back = true; // FACTIONS.player() is this PC's own human again: the world load keeps its city's part
        loadWorld(viewWorld, viewMoves);
        viewWorld = null;
        viewMoves = null;
        part = StepParts.CITY; // the city tail's work, before the thaw step's report is taken
        for (int i = 0; i < heldTxns.size(); i++) {
            Txn t = heldTxns.get(i).resolve(); // the loaded game's objects (SOS-97)
            if (t != null) {
                txnApplier().accept(t);
            }
        }
        part = -1;
        heldTxns.clear();
        ai().watchOnly(false);
        viewLimit.stop();
    }

    /**
     * Between steps, before each step: every PC but a view's owner and viewer saves its own world and loads it back
     * after the view's freeze step, and the owner too before its thaw step, where the viewer loads, as the owner loads
     * its save for the view. A load resets world state no save holds; so it does on every PC alike, and the world
     * stays the same everywhere. What a whole game's load resets beyond the world's ({@code GameWorldLoad.unsaved},
     * rig runs V82N to V82X) is carried or reset alike, so these PCs load only their world (owner, 2026-10-28).
     */
    void viewWorldReloads() {
        for (View v : views) {
            if (v == null || !v.frozen() || v.viewer == localHuman || v.loadSkipped) {
                continue; // a view a battle ended before its load: nobody loaded, so nobody reloads
            }
            boolean atFreeze = v.owner != localHuman && clock.step() == v.freeze;
            boolean atThaw = v.thaw >= 0 && clock.step() == v.thaw - 1;
            long at = atFreeze ? v.freeze : atThaw ? v.thaw : -1;
            if (at < 0 || v.reloaded == at) {
                continue;
            }
            v.reloaded = at;
            long started = System.nanoTime();
            java.nio.file.Path file;
            try {
                file = java.nio.file.Files.createTempFile("sosdet-viewreload", ".save");
            } catch (java.io.IOException e) {
                throw new IllegalStateException("SOSDET: no file for this PC's world reload", e);
            }
            double[] unsaved = sosdet.game.GameWorldLoad.unsaved();
            sosdet.game.GameWorldLoad.save(file);
            loadWorld(file, unsaved);
            lastViewLoadMs = (System.nanoTime() - started) / 1e6;
        }
    }

    /** Loads the world saved in {@code file} into this PC's game, keeping its city, with {@code unsaved} put back. */
    private void loadWorld(java.nio.file.Path file, double[] unsaved) {
        java.nio.file.Path scratch;
        try {
            scratch = java.nio.file.Files.createTempFile("sosdet-city", ".bin");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("SOSDET: no scratch file for the city's part of the player faction", e);
        }
        worldLoading = true;
        try {
            sosdet.game.GameWorldLoad.load(file, scratch);
        } finally {
            worldLoading = false;
        }
        worldLoads++;
        sosdet.game.GameWorldLoad.unsaved(unsaved);
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("SOSDET: cannot delete " + file, e);
        }
    }

    /** The human whose city this PC runs: its own, or while it views another's, that owner's (SOS-82). */
    int cityHuman() {
        View v = viewing();
        return v != null ? v.owner : localHuman;
    }

    /** The PC whose city this PC runs: its own, or the viewed city's owner's PC. */
    int cityPc() {
        View v = viewing();
        return v != null ? v.ownerPc : ai().localPc();
    }

    /** The city's own draws so far: every draw since the load but the world part's. */
    long cityDraws() {
        return streams.drawsSinceLoad() - streams.worldDraws();
    }

    /**
     * Every PC, as a view entry applies at {@code step}: the viewer's human freezes at the next report step, or with
     * {@code owner} -1, the view ends (M2), {@code ownerPc} then holding why ({@link View#CLOSED} to
     * {@link View#DESYNC}; below 0 for closed).
     */
    void viewEntry(long step, int viewer, int owner, int ownerPc) {
        if (owner < 0) {
            int why = ownerPc < 0 || ownerPc > View.LAST_ASKED ? View.CLOSED : ownerPc;
            endView(viewer, step, why, View.sentence(why));
            return;
        }
        views[viewer] = new View(viewer, pcOf(viewer), owner, ownerPc, nextReportStep(step));
    }

    // ------------------------------------------------------------ the assault on a human's capital (SOS-92 step B)

    /** The assault on a human's capital, from Storm until the world took its outcome; null when none runs. */
    Assault assault;
    /** True while the assault's invasion is being built in the defender's city: it starts at the bombardment. */
    boolean assaultStarting;
    /** The last assault over, for the mod's notices and the rig; null before the first. */
    Assault lastAssault;
    /** The assault whose outcome the world just took, until the mod takes it (SOS-92 C). */
    Assault assaultOutcome;
    /** A taken throne whose attacker hasn't chosen what to take yet; saves wait for it (SOS-92 D). */
    Assault taken;
    /** The assault whose terms the world just applied, until the mod takes it (SOS-92 D). */
    Assault assaultTermsOutcome;
    /** NPC invasions of a human's capital, as the world part starts them: step, human, invading faction (-1: rebels). */
    final java.util.ArrayDeque<long[]> invasionNotices = new java.util.ArrayDeque<>();
    /** The notices kept for the mod at most; a mod that never takes them loses the oldest past this. */
    static final int NOTICES_MAX = 64;

    /** Why no assault can start now, the same on every PC; null when one can. */
    String assaultRefusal() {
        if (assault != null) {
            return "An assault is running.";
        }
        if (taken != null) {
            return "An attacker is choosing what to take from a throne.";
        }
        if (battle != null) {
            return "A battle is running.";
        }
        for (View v : views) {
            if (v != null && v.state != View.CANCELLED && v.state != View.ENDED) {
                return "A city view is open: the assault starts once it is closed.";
            }
        }
        return null;
    }

    /**
     * Every PC, in the world part at Storm's step: {@code attacker}'s armies assault {@code defender}'s capital. Every
     * other human views the defender's city from the next report step, where the world holds; the invasion starts at
     * the step after it ({@link #assaultStep}).
     */
    void assaultBegin(long step, int attacker, int defender, Object spec, int[] armies, int men) {
        String why = assaultRefusal();
        if (why != null) {
            throw new IllegalStateException("SOSDET: an assault at step " + step + ": " + why);
        }
        long freeze = nextReportStep(step);
        assault = new Assault(step, attacker, defender, freeze, spec, armies, men);
        for (int h : humans()) {
            if (h != defender && !retired(h)) {
                views[h] = new View(h, pcOf(h), defender, pcOf(defender), freeze);
            }
        }
    }

    /**
     * Every PC, in the city tail of each step: the assault's invasion starts in the defender's city the step after the
     * freeze, on every PC that runs it; it ends as the invasion ends, and every view of it thaws at the next report
     * step. A view that couldn't freeze calls the assault off.
     */
    void assaultStep(long step) {
        Assault a = assault;
        if (a == null || a.endStep >= 0 || step <= a.freeze) {
            return;
        }
        if (!a.started) {
            for (View v : views) {
                if (assaultView(v, a) && v.state == View.CANCELLED) {
                    assaultOver(step, "the view of human " + v.viewer + " couldn't start: " + v.reason);
                    return;
                }
            }
            if (cityHuman() == a.defender) {
                assaultStarting = true;
                try {
                    a.invasionRef = sosdet.game.GameAssault.invade(a.spec);
                } finally {
                    assaultStarting = false;
                }
            }
            a.started = true;
            return;
        }
        if (a.forced >= 0 && a.outcome < 0) { // rig tests only: the invasion ends as the test asked
            if (cityHuman() == a.defender) {
                sosdet.game.GameAssault.endForTest(a.invasionRef);
            }
            a.outcome = a.forced;
        }
        if (a.outcome >= 0) {
            assaultOver(step, null);
        } else if (!sosdet.game.GameAssault.invading()) {
            assaultOver(step, "the invasion stopped"); // the game dropped it: no room to fight in
        }
    }

    private static boolean assaultView(View v, Assault a) {
        return v != null && v.owner == a.defender && v.freeze == a.freeze;
    }

    private void assaultOver(long step, String cancelled) {
        Assault a = assault;
        a.cancelled = cancelled;
        a.endStep = step;
        for (View v : views) {
            if (assaultView(v, a)) {
                endView(v.viewer, step, View.GAME, "The assault is over.");
            }
        }
    }

    /**
     * Every PC: true from the step after Storm until every view of the assault has thawed; the world stands still
     * meanwhile (mod spec §4), so no siege window or battle opens before the views freeze.
     */
    boolean assaultHolds(long step) {
        Assault a = assault;
        if (a == null || step <= a.step) {
            return false;
        }
        if (a.endStep < 0) {
            return true;
        }
        for (View v : views) {
            if (assaultView(v, a) && v.state != View.ENDED && v.state != View.CANCELLED) {
                return true;
            }
        }
        return false;
    }

    /** Every PC, in the world part: once every view of an ended assault has thawed, the outcome reaches the world. */
    void assaultResolve(long step) {
        Assault a = assault;
        if (a == null || a.endStep < 0 || assaultHolds(step)) {
            return;
        }
        assault = null;
        lastAssault = a;
        if (a.cancelled == null) {
            sosdet.game.GameAssault.resolve(a.defender, a.outcome, a.armies);
            assaultOutcome = a;
            if (a.outcome == Assault.TAKEN) {
                a.termsDue = step + TERMS_WINDOWS * windowSteps(clock.agreedSpeed());
                taken = a;
            }
        }
    }

    /** The attacker chooses what to take within two minutes of real time: 240 half-second windows (SOS-92 D). */
    static final int TERMS_WINDOWS = 240;

    /**
     * Every PC, in the world part: the attacker's choice after a taken throne ({@code ASSAULT_TERMS}), or the default
     * once it is due. Returns false when there is no such choice to make, or {@code attacker} isn't the one to make it.
     */
    boolean assaultTerms(int attacker, int kind, int percent) {
        Assault a = taken;
        if (a == null || a.attacker != attacker || AssaultTerms.refusal(kind, percent) != null) {
            return false;
        }
        a.termsCredits = sosdet.game.cmd.Sieges.applyTerms(a.defender, a.attacker, kind, percent);
        a.termsKind = kind;
        a.termsPercent = percent;
        taken = null;
        assaultTermsOutcome = a;
        return true;
    }

    /** Every PC, in the world part: the default terms once the attacker's choice is due. */
    void assaultTermsDue(long step) {
        Assault a = taken;
        if (a != null && step >= a.termsDue) {
            assaultTerms(a.attacker, AssaultTerms.DEFAULT_KIND, AssaultTerms.DEFAULT_PERCENT);
        }
    }

    /**
     * The step a regular report taken at {@code step} applies at: a window later at the agreed speed, never before the
     * last report's promise (SOS-90).
     */
    long reportAppliesAt(long step) {
        return Math.max(step + windowSteps(clock.agreedSpeed()), reportPromise);
    }

    /** How long a regular report and a log entry stay in flight: half a second of real time (SOS-90). */
    static final double WINDOW_SECONDS = 0.5;

    /**
     * The lockstep window at this agreed speed, in steps: {@link #WINDOW_SECONDS} of real time, 30 steps at 1x and at
     * least that, at most the log's longest delay (SOS-90).
     */
    static int windowSteps(double speed) {
        double perSecond = speed / StepClock.stepSecondsAt(speed);
        long steps = (long) Math.ceil(WINDOW_SECONDS * perSecond - 1e-9);
        return (int) Math.max(AiLog.DELAY_STEPS, Math.min(AiLog.MAX_DELAY_STEPS, steps));
    }

    /** The first report step after {@code step}: where a view freezes or thaws its viewer. */
    private static long nextReportStep(long step) {
        return (step / Reports.EVERY + 1) * Reports.EVERY;
    }

    /**
     * Every PC alike at {@code step}: the view of the human in {@code viewer} ends at the next report step, or, not
     * frozen yet, is cancelled; {@code why} and {@code reason} say why ({@link View#why}, {@link View#reason}). Nothing
     * for a view already ending or over. A view ended in its own freeze step loads nothing ({@link #skipLoad}).
     */
    void endView(int viewer, long step, int why, String reason) {
        View v = views[viewer];
        if (v == null || v.thaw >= 0 || v.state == View.CANCELLED || v.state == View.ENDED) {
            return;
        }
        v.why = why;
        v.reason = reason;
        if (v.state == View.SCHEDULED) {
            if (why == View.CLOSED) {
                v.reason = "Closed before it opened.";
            }
            v.state = View.CANCELLED;
            return;
        }
        v.thaw = nextReportStep(step);
        if (step == v.freeze) {
            skipLoad(v);
        }
    }

    /**
     * A view ended in its freeze step, before any PC loaded for it (the loads run after that step): nobody loads. Its
     * viewer stays frozen in every world until the thaw, as its report at the freeze step went untaken, while its PC
     * runs its own city on, and its player can answer what ended the view (a prompt) there.
     */
    private void skipLoad(View v) {
        v.loadSkipped = true;
        v.sent = true; // the owner holds for no save
        v.reloaded = v.freeze; // no other PC reloads its world
        viewHashes[v.viewer] = null;
        if (v.viewer == localHuman) {
            v.state = View.RUNNING;
            v.back = true; // its own city never left
        }
    }

    /**
     * Every PC, own-city mode, inside the step that engages a battle on the battle map (plan 0006): every PC loads that
     * battle and later its own city back, so no view lives through it (mod spec §10: a battle ends the view). The views
     * not frozen yet are cancelled; the others thaw at the first report step after the battle ({@link #viewsEnd}), and
     * their hashes stop here, as the battle's loads start every PC's draws again.
     */
    void battleEndsViews(long step) {
        for (View v : views) {
            if (v == null) {
                continue;
            }
            endView(v.viewer, step, View.GAME, "A battle started.");
            if (v.frozen()) {
                viewHashes[v.viewer] = null;
            }
        }
    }

    /**
     * Every PC, at the end of each step: a view due to thaw at the next step is put off by a report interval while a
     * battle runs or a world prompt or question is open, whose actions are game objects the viewer's load back would
     * lose. Decided here, inside the step, so every PC moves it alike before any of them acts on it between steps.
     */
    void viewsEnd(long step) {
        for (View v : views) {
            if (v != null && v.frozen() && v.thaw == step + 1 && (battle != null || worldPromptOrQuestion() != null)) {
                v.thaw += Reports.EVERY;
            }
        }
    }

    /**
     * Every PC, at the start of each step: the views whose freeze step it is freeze their viewer, or are cancelled
     * alike on every PC when the view can't start now.
     */
    void viewsAt(long step) {
        for (View v : views) {
            if (v != null && v.frozen() && v.thaw == step) {
                thaw(v, step);
            }
            if (v == null || v.state != View.SCHEDULED || v.freeze != step) {
                continue;
            }
            String why = viewRefusal(v);
            if (why != null) {
                v.why = View.GAME;
                v.reason = why;
                v.state = View.CANCELLED;
                continue;
            }
            v.state = v.viewer == localHuman ? View.LOADING : View.RUNNING;
            pacer.away(v.viewerPc, true); // the host's pace waits for no viewer
        }
    }

    /**
     * Every PC at a view's thaw step: the viewer's reports are read again from the one it takes now, and the pace
     * waits for it again. Until that report applies, every world reads the frozen one, as no PC holds an earlier one.
     */
    private void thaw(View v, long step) {
        if (v.viewer == localHuman && !v.back) {
            throw new IllegalStateException("SOSDET: " + v + " thaws at step " + step + ", but this PC's own city isn't back");
        }
        v.state = View.ENDED;
        viewHashes[v.viewer] = null;
        pacer.away(v.viewerPc, false);
        if (v.viewer != localHuman) {
            // the world reads the frozen report through the step before the thaw report applies (ADR 0005 §4)
            receivedTakenAt[v.viewer] = Math.max(receivedTakenAt[v.viewer], step - Reports.EVERY);
            // the thaw report applies at the least delay after it, or later (SOS-90)
            receivedNextAt[v.viewer] = Math.max(receivedNextAt[v.viewer], step + Reports.DELAY);
        }
    }

    /** Why a view can't freeze its viewer now, the same on every PC; null when it can. */
    private String viewRefusal(View v) {
        return viewRefusal(v.viewer, v.owner, v);
    }

    /**
     * Why the human in {@code viewer} can't view {@code owner}'s city now; null when it can. {@code self} is that view
     * when it is in the log already, or null for a view still to ask for ({@code Engine.viewRefusal}).
     */
    String viewRefusal(int viewer, int owner, View self) {
        if (viewer == owner || !isHuman(viewer) || !isHuman(owner) || retired(viewer) || retired(owner)
                || removing[viewer] || removing[owner]) {
            return "That city can't be viewed.";
        }
        if (frozen(owner)) {
            return "Its player is viewing another city.";
        }
        for (View o : views) {
            if (o != null && o != self && o.frozen() && o.owner == viewer) {
                return "Your own city is being viewed.";
            }
        }
        if (battle != null) {
            return "A battle is running.";
        }
        return worldPromptOrQuestion();
    }

    /**
     * Why no view may freeze or thaw now, the same on every PC: a world prompt or a world question is open, whose
     * actions are game objects a viewer's load would lose. Null when none is.
     */
    private String worldPromptOrQuestion() {
        if (worldPrompt != null) {
            return "A battle prompt is open.";
        }
        for (Long key : stepPrompts.keySet()) {
            if (Hooks.stepPromptWorld(key)) {
                return "A question is open on the world map.";
            }
        }
        return null;
    }

    /** Before step {@code step}: true while this PC, the viewer or the owner of a view, holds at its freeze step. */
    boolean viewHolds(long step) {
        if (viewLoading) {
            return true; // a view load runs: the owner's, the viewer's or another PC's own
        }
        for (View v : views) {
            if (v == null || step <= v.freeze) {
                continue;
            }
            if (v.state == View.LOADING && v.viewer == localHuman) {
                return true; // until the owner's city is loaded
            }
            if (v.viewer == localHuman && v.thaw >= 0 && step >= v.thaw && !v.back) {
                return true; // until its own city is loaded back
            }
            if (v.frozen() && v.owner == localHuman && (!v.sent || viewLoading)) {
                return true; // until the mod has taken its save (Engine.viewOut) and this PC has loaded it
            }
        }
        return false;
    }

    /** End of a step: each view this PC is part of hashes the city it runs, at the hash steps after the freeze. */
    void viewHashes(long step) {
        for (View v : views) {
            if (v == null || !v.frozen()) {
                continue;
            }
            HashLog h = viewHashes[v.viewer];
            if (h == null || !h.due(step) || step <= v.freeze) {
                continue;
            }
            long clock = HashLog.mix(HashLog.mix(HashLog.start(), step), cityDraws() - viewDrawsBase[v.viewer]);
            h.take(step, clock, 0, source());
        }
    }

    /** A view's hash log for the city this PC runs, from now: the owner's, or the viewer's copy. */
    void viewHashesFrom(int viewer) {
        HashLog h = new HashLog();
        h.every(hashes.interval());
        viewHashes[viewer] = h;
        viewDrawsBase[viewer] = cityDraws();
    }

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
            int[] hs = city().humans();
            boolean[] r = new boolean[SLOTS];
            for (int h : hs) {
                r[h] = city().retired(h);
            }
            retired = r;
            humans = hs;
        }
        return humans;
    }

    /** True for a human slot whose human was removed (SOS-66). */
    boolean retired(int slot) {
        humans();
        return retired[slot];
    }

    /**
     * Peer, before each step it may run: each removal entry that came in with the host's log. From now this PC reads no
     * report of that human taken after the host's last one (SOS-66). After a load in the session (a battle's end, a
     * resync) no PC holds any report of that human, so none waits for one.
     */
    void takeRemovals() {
        if (ai().mode() != AiMode.PEER) {
            return;
        }
        AiEntry e;
        while ((e = ai().takeRemoval()) != null) {
            int slot = e.intAt(0);
            if (removing[slot] || retired(slot)) {
                continue;
            }
            removing[slot] = true;
            long cutoff = ((long) e.intAt(1) << 32) | (e.intAt(2) & 0xFFFFFFFFL);
            removeCutoff[slot] = reportsLost[slot] ? -1 : cutoff;
            dropReportsAfter(slot, cutoff);
        }
    }

    /** No report of the human in {@code slot} taken after {@code takenAt} is read on any PC (SOS-66). */
    private void dropReportsAfter(int slot, long takenAt) {
        reports(slot).dropTakenAfter(takenAt);
        receivedTakenAt[slot] = Math.min(receivedTakenAt[slot], takenAt);
    }

    /** Every PC, as a removal entry applies: its realm is freed in the next world part with no battle running. */
    void humanRemoved(int slot) {
        if (!isHuman(slot) || slot == 0) {
            throw new IllegalStateException("SOSDET: a removal of slot " + slot + ", no human but the host's");
        }
        if (retired(slot)) {
            return; // retired already, after a load before its step (dueWithoutReports)
        }
        removing[slot] = true;
        removeDue[slot] = true;
    }

    /**
     * Start of a world part (SOS-66): a human being removed whose reports a load in the session cleared (a battle's
     * end, a resync, before the removal's step) retires now. No PC has a report of it then, and a PC runs this step only
     * once it knows of the removal, so every PC that runs it retires that human here alike.
     */
    void dueWithoutReports() {
        for (int slot = 1; slot < SLOTS; slot++) {
            if (removing[slot] && !removeDue[slot] && !retired(slot) && reports(slot).current() == null
                    && !reports(slot).pending()) {
                removeDue[slot] = true;
            }
        }
    }

    /**
     * Start of a world part with no battle running (SOS-66): each removal due retires its human on every PC. A
     * world-battle prompt or question asking that human closes unanswered, so nothing of it applies.
     */
    void retireDue() {
        for (int slot = 1; slot < SLOTS; slot++) {
            if (!removeDue[slot]) {
                continue;
            }
            if (worldPrompt != null && (worldPromptHuman == slot || promptHoldsArmyOf(slot))) {
                Hooks.worldPromptClose(); // its sides would name armies that are disbanded now
            }
            int asked = slot;
            stepPrompts.values().removeIf(p -> (Integer) p[2] == asked);
            remover().accept(slot);
            retired[slot] = true;
            removeDue[slot] = false;
            removing[slot] = false;
            reports(slot).clear();
        }
    }

    private boolean promptHoldsArmyOf(int slot) {
        for (int army : worldPromptArmies) {
            if (armyOwner().applyAsInt(army) == slot) {
                return true;
            }
        }
        return false;
    }

    /** The slot of a world army's faction, by its index (-1: none): the game, made at first use; tests put in their own. */
    java.util.function.IntUnaryOperator armyOwner;

    java.util.function.IntUnaryOperator armyOwner() {
        if (armyOwner == null) {
            armyOwner = sosdet.game.GameHumans::armyOwner;
        }
        return armyOwner;
    }

    java.util.function.IntConsumer remover() {
        if (remover == null) {
            remover = slot -> sosdet.game.GameHumans.get().retire(slot);
        }
        return remover;
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
        return viewHuman >= 0 ? viewHuman : cityHuman(); // a viewed city's player is its owner (SOS-82)
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
        // a viewed city draws on its viewer's PC as on its owner's (SOS-82)
        long h = HashLog.mix(HashLog.mix(HashLog.mix(HashLog.mix(HashLog.start(), seed), step), CITY_STREAM),
                cityPc());
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
        if (viewLoad != null) {
            viewLoaded();
            return;
        }
        if (viewBackLoading) {
            ownCityBack();
            return;
        }
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
        java.util.Arrays.fill(receivedNextAt, -1);
        reportPromise = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (removing[slot]) {
                removeCutoff[slot] = -1; // a human being removed sends no load report: no PC waits for one (SOS-66)
            }
        }
        txns.dropQueued();
        cityInputs.clear(); // the loaded world has the city's state as saved
        sentSupplies.clear();
        eventMarks.clear();
        battleConclude = null;
        if (battleLeaving || resync != null) {
            java.util.Arrays.fill(reportsLost, true); // a load in the session: no PC holds an earlier report (SOS-66)
        }
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
     * A viewer loads the owner's city (SOS-82): only this PC loads, at the freeze step every PC has reached alike, so
     * the session goes on as it was. The reports, the feeds, the log, the hashes and the clock stay; the city's engine
     * state is the owner's from now, and so is the city this PC runs.
     */
    private void viewLoaded() {
        byte[] state = viewLoad;
        viewLoad = null;
        viewHuman = -1;
        humans = null;
        currentHuman = 0;
        battleConclude = null;
        resyncDraws = streams.drawsSinceLoad(); // the load's own draws don't count, as in a resync
        ViewCity.restoreCity(this, state);
        View v = views[localHuman];
        if (v != null && v.state == View.LOADING) {
            v.state = View.RUNNING; // the viewer; the owner runs its own city still
        }
    }

    /** The viewer's own game loads back: as a view load, with its own city's engine state from the freeze step. */
    private void ownCityBack() {
        viewHuman = -1;
        humans = null;
        currentHuman = 0;
        battleConclude = null;
        resyncDraws = streams.drawsSinceLoad();
        ViewCity.restoreCity(this, ownCityState);
        ownCityState = null;
        viewHashes[localHuman] = null;
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
            if (ai().mode() == sosdet.ai.AiMode.HOST) {
                commandBattle();
            }
            if (battle.other >= 0) {
                deployTimer.open();
            }
        } else {
            deployTimer.close();
            hashes.worldOnly(true);
        }
    }

    /**
     * Host, as the battle loads (plan 0006 M2, SOS-83): who commands it, through the log, so every PC applies it at the
     * step the host stamps. Army 0, the battle's human's, goes to that human's PC; army 1 to the other human's PC in a
     * battle between two humans, else to the host's AI general. Divisions come in army order, split evenly.
     */
    private void commandBattle() {
        int divisions = sosdet.game.OwnArmy.divisions();
        int armies = sosdet.game.OwnArmy.armies();
        int army1 = battle.other >= 0 ? pcOf(battle.other) : sosdet.ai.AiEntry.AI;
        int[] byDivision = new int[divisions];
        for (int i = 0; i < divisions; i++) {
            int army = i / (divisions / armies);
            byDivision[i] = army == 0 ? battle.pc : army == 1 ? army1 : sosdet.ai.AiEntry.AI;
        }
        int[] artillery = new int[armies];
        for (int a = 0; a < armies; a++) {
            artillery[a] = a == 0 ? battle.pc : a == 1 ? army1 : sosdet.ai.AiEntry.AI;
        }
        ai().captureCommanders(sosdet.ai.Commanders.payload(byDivision, artillery));
    }

    /**
     * Inside the step, the Start command from the PC applying it (SOS-83): true when the battle may start now. Between
     * two humans it waits for both sides' commanders, each PC pressing for its own army; any other battle starts at once.
     */
    boolean deployReady(int fromPc) {
        if (battle == null || battle.other < 0) {
            return true;
        }
        if (fromPc == battle.pc) {
            battle.deployReady |= 1;
        } else if (fromPc == pcOf(battle.other)) {
            battle.deployReady |= 2;
        }
        if (battle.deployReady != 3) {
            return false;
        }
        deployTimer.close();
        return true;
    }

    /**
     * Inside the step, the Retreat command from the PC applying it (plan 0006 M2, SOS-83): the army it retreats, 0 or 1,
     * or -1 when that PC commands neither side. Army 0 is the battle's human's; army 1 retreats only between two humans,
     * from the other human's PC. With no own-city battle (co-op) it is army 0, as in vanilla.
     */
    int retreatSide(int fromPc) {
        if (battle == null) {
            return 0;
        }
        if (fromPc == battle.pc) {
            return 0;
        }
        if (battle.other >= 0 && fromPc == pcOf(battle.other)) {
            battle.army1Retreat = true;
            return 1;
        }
        return -1;
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
            if (!remote(h) || retired(h) || frozen(h) // a viewer's world reads its last report (SOS-82)
                    || receivedTakenAt[h] >= 0 && step < receivedNextAt[h]) {
                continue;
            }
            // a human being removed is waited for only until its last report every PC reads is in (SOS-66)
            if (!removing[h] || receivedTakenAt[h] < removeCutoff[h]) {
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
        if (frozen(r.faction) && r.takenAt >= views[r.faction].freeze) {
            return; // a viewer sends none from its freeze step; every PC reads its last one before (SOS-82)
        }
        if (retired(r.faction) || removing[r.faction] && r.takenAt > removeCutoff[r.faction]) {
            return; // past its human's last report every PC reads (SOS-66)
        }
        reports(r.faction).stamp(r);
        receivedTakenAt[r.faction] = r.takenAt;
        receivedNextAt[r.faction] = Math.max(receivedNextAt[r.faction], r.nextAt);
        reportsLost[r.faction] = false;
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
        // the next regular report applies a window after its step at the earliest, whatever the speed is then
        reportPromise = Math.max(nextReportStep(step) + windowSteps(clock.agreedSpeed()), appliesAt + 1);
        CityReport r = new CityReport(c.layout(), c.faction(), step, appliesAt, reportPromise, v, cityInputs);
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
            if (retired(h) || removing[h]) {
                continue; // a removed human has no PC (SOS-66)
            }
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
        boolean w = waited;
        waited = false;
        long stepNanos = frameStepNanos;
        frameStepNanos = 0;
        if (ai().mode() != AiMode.HOST) {
            pacer.frame(now, clock.step(), clock.lastStep(), w, stepNanos); // only counts this PC's waits as held (SOS-90)
            hashes.ownCapacity(pacer.capacity()); // goes to the host with this PC's next hash (SOS-94)
            return;
        }
        holdForDesync();
        ai().delay(windowSteps(clock.agreedSpeed())); // orders stay in flight the same real time at every speed
        clock.paceCap(pacer.frame(now, clock.step(), clock.lastStep(), w, stepNanos));
        hashes.ownCapacity(pacer.capacity());
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
        } else if (slowPc == Pacer.LINK && speed < wanted) {
            speedNoticeUntil = 0;
            speedNotice = "SOSDET: speed " + speedText(speed) + ", the connection between the PCs can't keep up (host set "
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
            inputs = new GameInputs(sentSupplies, eventMarks, () -> reportsFrom < 0 && currentHuman == cityHuman());
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

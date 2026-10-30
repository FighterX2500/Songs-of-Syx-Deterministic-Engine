package sosdet;

import java.util.Random;

import game.GAME;
import game.GAME.GameResource;
import game.boosting.BValue;
import game.faction.FACTIONS;
import game.faction.FCredits.CTYPE;
import game.faction.Faction;
import game.faction.player.PCredits;
import game.faction.player.Player;
import init.type.CAUSE_LEAVE;
import init.type.HCLASS_RACE;
import init.trade.TRADABLE;
import init.trade.TRADE_TYPE;
import init.value.Locker;
import init.value.Value;
import settlement.trade.PBuyer;
import settlement.entity.humanoid.Humanoid;
import settlement.stats.Induvidual;
import settlement.trade.PSeller;
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.rnd.RND;
import sosdet.ai.AiApplier;
import sosdet.ai.Commanders;
import sosdet.ai.AiMode;
import sosdet.game.GameOwnCity;
import sosdet.game.GameReport;
import sosdet.game.StepParts;
import sosdet.game.WorldStatics;
import sosdet.game.cmd.BannerDraft;
import sosdet.game.cmd.HumanDeals;
import sosdet.game.cmd.PlanStash;
import sosdet.hash.HashLog;
import sosdet.report.CityReport;
import sosdet.report.Field;
import sosdet.report.Reports;
import sosdet.txn.TransferLog;
import sosdet.txn.Txn;
import sosdet.txn.TxnKind;
import util.data.DOUBLE_O;
import view.main.VIEW;
import world.entity.army.WArmy;

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
    private static final int[] FIRST_HUMAN = {0};

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

    /**
     * Whether an open window stops game time, as in vanilla: its own pause, and VIEW giving the game no time while the
     * window holds the frame. Not in a session (mod spec section 4): a click there, or a prompt's answer, is a command
     * for a later step, which a stopped PC would hold until the window closed, and every other PC would wait for it
     * (SOS-32, SOS-36). Input still stops at the window.
     */
    public static boolean menusPause() {
        return Engine.session() == null;
    }

    /** No autosave during a session; saves at a step come through the API (SOS-30). */
    public static boolean autoSaveAllowed() {
        return Engine.session() == null;
    }

    /**
     * No quick load during a session: it would load a save on this PC alone, in the middle of the lockstep. Loads in a
     * session come through the API (SOS-79).
     */
    public static boolean quickLoadAllowed() {
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
     * Whole steps this frame completes. {@code setSpeed} is this PC's game speed as the player set it (never the
     * throttled one): the host logs a change of it (B16), and paces itself by the slowest PC (SOS-42). The frame's game
     * time is the real time since the last frame at that speed; vanilla's {@code gameSeconds} only after a long frame.
     */
    public static int stepsFor(double gameSeconds, double setSpeed) {
        return stepsFor(gameSeconds, setSpeed, System.nanoTime());
    }

    static int stepsFor(double gameSeconds, double setSpeed, long now) {
        Session s = Engine.session();
        s.speedSeen(setSpeed);
        s.pace(now);
        double speed = frameSpeed(setSpeed); // a local battle's PC keeps the world's pace, whatever the battle's own speed
        if ((s.viewing() != null || s.localHere() != null) && s.ai().mode() == sosdet.ai.AiMode.PEER
                && s.ai().confirmed() - s.clock.step() > VIEW_CATCH_UP_LAG) {
            speed *= VIEW_CATCH_UP; // a viewer behind the host runs faster until it has caught up (SOS-82), a battle PC too
        }
        if (s.viewing() != null && s.ai().mode() == sosdet.ai.AiMode.PEER) {
            // the viewer's limits, on this PC's clock: they only decide whether it asks the host to end the view
            s.viewLimit.update(now, Math.max(0, s.ai().confirmed() - s.clock.step()));
        }
        return s.clock.stepsForFrame(now, gameSeconds, speed);
    }

    /** A viewer more than this many steps behind the host's confirmed step catches up (plan 0013). */
    static final int VIEW_CATCH_UP_LAG = 60;
    /** How much faster a viewer runs while it catches up: a 5-second gap closes in about 2 seconds (mod spec §10). */
    static final double VIEW_CATCH_UP = 3.5;

    /**
     * A speed change from the log, at its step: the step length from the next step on, the speed line (with the host
     * player's speed and the PC that held it down, or -1), and, when every PC follows it, this PC's game speed.
     */
    /** The host's desync hold applies at {@code step} on this PC (SOS-53). */
    public static void desyncHold(long step, int pc, long desyncStep) {
        Engine.session().desyncHold(step, pc, desyncStep);
    }

    public static void agreedSpeed(double speed, double wanted, int slowPc) {
        Session s = Engine.session();
        s.clock.agreedSpeed(speed);
        s.speedNotice(speed, wanted, slowPc, System.nanoTime());
        if (s.followSpeed) {
            GAME.SPEED.speedSet(speed);
            s.seenSpeed = speed; // not the host player's own change
        }
    }

    /** The top bar, every frame: the engine's speed line under it, while there is one (SOS-42). */
    public static void renderSpeedLine(SPRITE_RENDERER r, int cx, int y) {
        Session s = Engine.session();
        if (s == null) {
            return;
        }
        String line = s.speedNoticeNow(System.nanoTime());
        if (line != null) {
            s.speedLine().render(r, line, cx, y);
        }
    }

    /** Whole steps this frame's game time completes. */
    public static int stepsFor(double gameSeconds) {
        return Engine.session().clock.stepsFor(gameSeconds);
    }

    /**
     * Starts a step; false when the session may not run further (the frame's other steps are dropped): at its last
     * step, while a map battle's end loads the game saved before it, as a peer already at the host's horizon, or as a follower whose source's
     * report for the step has not come in (ADR 0005 §4). This PC's room plan is lifted out
     * of the simulation before the frame's first step (ADR 0003). The AI log entries due at this step are applied
     * first, before anything else runs.
     */
    public static boolean stepBegin() {
        Session s = Engine.session();
        if (s.viewHuman >= 0) {
            throw new IllegalStateException("SOSDET: Engine.asHuman(" + s.viewHuman + ") still set at a step (SOS-46)");
        }
        if (s.ownCity && s.clock.step() == 0) {
            s.checkPcHumans();
        }
        if (s.ownCity) {
            s.takeRemovals(); // before this PC may run a step: no later report of a removed human is read (SOS-66)
        }
        if (s.ownCity && s.battle == null && s.reportsFrom < 0 && s.reports(s.localHuman).current() == null
                && !s.reports(s.localHuman).pending()) {
            // the load report goes out before this PC waits for the others' (SOS-46): with several humans every PC waits
            // for every other's first report, which none would send from a world part it may not run
            // taken at the last step run, so a regular report the next step takes is never a repeat of it
            s.takeReport(s.clock.step(), s.clock.step() + 1);
        }
        if (s.debugAt != null && s.clock.step() == s.debugAtStep) {
            Runnable r = s.debugAt;
            s.debugAt = null;
            r.run(); // debugging: the rig's state dump at an exact step (SOS-82)
        }
        s.viewBackStart(); // a viewer before its thaw step loads its own city back (SOS-82)
        s.viewWorldReloads(); // every other PC reloads its world where the owner and the viewer load
        s.localBacks(); // after a local battle's result: its PC loads its city back, every other PC its world (SOS-93)
        boolean battleAwaited = s.battle != null && s.battle.awaiting; // the battle PC's battle isn't loaded yet
        boolean waits = !s.ai().mayRun(s.clock.step() + 1) || !s.worldMayRun(s.clock.step() + 1);
        if (waits) {
            s.waited = true; // waiting for another PC: counted as held (SOS-90)
        }
        if (s.battleLeaving || battleAwaited || waits || battleAhead(s, s.clock.step() + 1)
                || s.viewHolds(s.clock.step() + 1)) {
            s.clock.hold();
            return false;
        }
        if (!s.clock.begin()) {
            return false;
        }
        s.viewsAt(s.clock.step()); // before anything of the step: a view freezes its viewer from here (SOS-82)
        s.localsAt(s.clock.step()); // and a local battle its human (SOS-93)
        s.plans().lift();
        s.streams.enter();
        // Reseeded every step: the stream depends only on the seed and the step, so a resync needs no random state
        s.streams.reseed(s.citySeed(s.clock.step()));
        if (s.streams.trace != null) {
            s.streams.trace.step(s.clock.step());
        }
        s.stepStartNanos = System.nanoTime(); // timing only, never fed to the simulation
        s.ai().stepBegin(s.clock.step());
        if (s.battle != null && !s.battle.awaiting && s.deployTimer.step(s.clock.stepSeconds(), s.clock.agreedSpeed())) {
            sosdet.game.cmd.MapBattles.deployForAll(); // a human between two didn't press Start in time (SOS-83)
        }
        if (s.battle != null && !s.battle.awaiting && s.removeDue[s.battle.human]) {
            // the battle's human is removed: the battle ends with no result, a step at a time, as its commands would (SOS-66)
            sosdet.game.cmd.MapBattles.endForRemoval();
        }
        return true;
    }

    /**
     * With the mod's hold before battles on: true while {@code step}'s commands engage a battle on the battle map and the
     * mod hasn't let the step through ({@link Engine#battleGo}), so every PC holds before it alike (SOS-70).
     */
    private static boolean battleAhead(Session s, long step) {
        if (!s.battleHold || s.battle != null || s.battleGo == step) {
            return false;
        }
        if (s.battleAhead == step) {
            return true;
        }
        if (s.pvpEngageAt == step) { // a battle between two humans engages at a step set the step before (SOS-83)
            s.battleAhead = step;
            return true;
        }
        if (s.worldPromptOther >= 0) { // between two humans one Fight doesn't engage: only pvpEngageAt does
            return false;
        }
        if (s.localBattles && s.worldPromptSally < 0) {
            return false; // a battle against NPCs saves nothing first: the world runs on (spec §11)
        }
        if (!s.ai().commandDue(step, sosdet.game.cmd.WorldBattles::isEngage)) {
            return false;
        }
        s.battleAhead = step;
        return true;
    }

    /** Ends a step; every 30th step (by default) the state hash is taken first, still inside the step (B13). */
    public static void stepEnd() {
        Session s = Engine.session();
        long step = s.clock.step();
        if (s.pathQueue != null) {
            long start = System.nanoTime();
            // the city's paths, queued in the city part: run as the city part, so the report hooks read the live city
            // and FACTIONS.player() is this PC's human, not the world part's current one (SOS-46)
            s.part = StepParts.CITY;
            s.pathJobs += s.paths.run(s.pathQueue);
            s.pathNanos += System.nanoTime() - start;
            s.pathQueue = null;
        }
        s.streams.worldPart(false);
        if (!s.txns.isEmpty()) {
            s.part = StepParts.CITY; // the city tail: the world part's changes to the city apply (ADR 0005 §3)
            s.txns.apply(s.ownTxns); // this PC's human's only (ADR 0004)
        }
        if ((step & 1) == 0 && (s.battle == null || s.battle.awaiting) && s.plans().inGame()) { // unit tests have no game
            s.part = StepParts.CITY;
            sosdet.game.GameProduction.refresh(); // the goods window's rates: the scan vanilla's UI drove (SOS-102)
        }
        if (s.assault != null) {
            s.part = StepParts.CITY; // the city tail: the assault's invasion starts or ends here (SOS-92 B)
            s.assaultStep(step);
        }
        if (s.hashes.due(step)) {
            // an own city's draws are its own: the world part's alone are counted (ADR 0005 §1); in a battle every PC
            // runs the same game, its draws counted from the battle's load (plan 0006)
            boolean battleRuns = s.battle != null && !s.battle.awaiting;
            long draws = s.ownCity && !battleRuns ? s.streams.worldDraws() : s.streams.drawsSinceLoad();
            long clock = HashLog.mix(HashLog.mix(HashLog.mix(HashLog.start(), step), draws), s.ai().applied());
            if (s.ai().commanders().set()) {
                clock = HashLog.mix(clock, s.ai().commanders().hash()); // left out until a table is set (SOS-15)
            }
            if (s.ownCity && s.humans().length > 1) {
                // the human slots and each PC's: the same table on every PC, or each acts for other humans (SOS-46)
                for (int h : s.humans()) {
                    clock = HashLog.mix(clock, h);
                    if (s.retired(h)) {
                        clock = HashLog.mix(clock, -1); // and which are removed; a game with none hashes as before (SOS-66)
                    }
                }
                for (int pc : s.pcHumans) {
                    clock = HashLog.mix(clock, pc);
                }
            }
            s.part = -1; // the same part on every PC, whether or not this one ran city paths or a city tail (SOS-46)
            s.hashes.take(step, clock, s.reportsHash(), s.source());
            s.viewHashes(step);
        }
        s.viewsEnd(step); // a thaw due next step is put off while a battle runs or a world prompt is open (SOS-82)
        long stepNanos = System.nanoTime() - s.stepStartNanos;
        s.times.record(stepNanos);
        if (s.profiler != null)
            s.profiler.stepDone(stepNanos);
        s.frameStepNanos += stepNanos; // this PC's capacity (SOS-94): timing only, never fed to the simulation
        s.stepStartNanos = 0;
        s.part = -1;
        s.streams.leave();
    }

    /**
     * True when the world map must not update in the city view: a session holding the world still. An open world-battle
     * prompt no longer holds it (spec §4, SOS-45): only the battle's armies wait ({@link #armyHeld}).
     */
    /**
     * Patch 10, in the step: the engine's step profile while one is on (SOS-106), for each game part's update; null
     * otherwise, and the part gets vanilla's own. An Object, so that loading Hooks never needs the game's Profiler type
     * (the unit tests run without the game).
     */
    public static Object stepProfiler() {
        Session s = Engine.session();
        return s == null ? null : s.profiler;
    }

    /** Patch 10, in the step: the world part runs on a local battle's PC whatever its battle view (SOS-93). */
    public static boolean localWorldPart(int part) {
        Session s = Engine.session();
        return s != null && part == StepParts.WORLD_PART && s.localHere() != null;
    }

    /** Patch 10, in the step's city part: on a local battle's PC the world's clock runs every step (SOS-93, O3). */
    public static boolean localClock(GameResource r, int part) {
        Session s = Engine.session();
        return s != null && part == StepParts.CITY && s.localHere() != null && StepParts.worldClock(r);
    }

    /** True on a local battle's PC while it runs (SOS-93): the battle's Start keeps the world's tick count. */
    public static boolean localBattleHere() {
        Session s = Engine.session();
        return s != null && s.localHere() != null;
    }

    /**
     * The freeze step's world part, on a local battle's PC: vanilla's Engage generates the battle, on a stream of its
     * own; the world stream goes on as on every other PC, reseeded, its draw count put back (SOS-93). The world's NPC
     * production boosts, which vanilla's generation re-rolls as it clears the city, stay as every other PC has them.
     */
    private static void localGenerate(Session s, long step) {
        LocalBattle b = s.localHere();
        if (b == null || b.freeze != step || b.generate == null) {
            return;
        }
        Runnable generate = b.generate;
        b.generate = null;
        GAME.SPEED.speedSet(1); // as vanilla's battle opens; the world's speed goes on in the log
        s.ownCityState = ViewCity.cityState(s); // put back with the city at the battle's end, as after a view
        long worldDraws = s.streams.worldDraws();
        double[] boosts = sosdet.game.GameWorldLoad.fboostMuls();
        s.streams.reseed(s.battleSeed(step));
        try {
            generate.run();
        } finally {
            sosdet.game.GameWorldLoad.fboostMuls(boosts);
            s.streams.reseed(s.worldSeed(step));
            s.streams.worldDraws(worldDraws);
        }
    }

    /**
     * Patch 15, vanilla's Engage (ResolverPlayer.Manual), in a local battle's Engage on every PC: keeps its after-battle,
     * which takes the result's arrays. True when it is a local battle: vanilla's own generation doesn't run now.
     */
    public static boolean localEngaged(java.util.function.Consumer<Object> after) {
        Session s = Engine.session();
        if (s == null || s.engaging == null) {
            return false;
        }
        s.engaging.after = after;
        return true;
    }

    /** Patch 15, in a local battle's Engage: true on the battle PC, which generates the battle at the freeze step. */
    public static boolean localBattleMine() {
        Session s = Engine.session();
        return s != null && s.engaging != null && s.engaging.human == s.localHuman;
    }

    /** Patch 15, on the battle PC: the generation, run at the freeze step ({@link #localGenerate}). */
    public static void localGenerateLater(Runnable generate) {
        Engine.session().engaging.generate = generate;
    }

    /**
     * Patch 99, the battle's exit on a local battle's PC (Retreat, or Close on the victory or defeat panel): its result
     * goes to every PC as a command, applied at the step the host stamps; nothing loads now. True when it is a local
     * battle; false for vanilla's exit.
     */
    public static boolean localExit(Object result) {
        Session s = Engine.session();
        LocalBattle b = s == null ? null : s.localHere();
        if (b == null) {
            return false;
        }
        if (!b.resultSent) {
            b.resultSent = true;
            orderCapture(sosdet.ai.AiEntry.COMMAND, -1, sosdet.game.cmd.MapBattles.localResultCommand(b.human, result));
        }
        return true;
    }

    /** The result command, inside the step on every PC: the battle's result, applied once (SOS-93). */
    public static boolean localResult(int human, Object result) {
        Session s = Engine.session();
        return s != null && s.localResult(s.clock.step(), human, result);
    }

    /**
     * Patch 10: the frame's speed: the game's, but on a local battle's PC the game's speed is the battle's own, so the
     * frame takes the world's: a paused battle's world runs on, and a fast one's stays at the agreed speed (SOS-93).
     * The host holds that speed too while a peer fights: the peer generating its battle is waited for by no pace.
     */
    public static double frameSpeed(double speed) {
        Session s = Engine.session();
        if (s == null) {
            return speed;
        }
        if (s.localHere() != null) {
            return s.clock.agreedSpeed();
        }
        return s.localCapped() ? Math.min(speed, s.clock.agreedSpeed()) : speed;
    }

    /**
     * Patch 15, the world battles' polls: true when a battle between these armies waits for a local battle: a human of
     * theirs is fighting one, or one of them stands in one (spec §8). {@code b} may be null.
     */
    public static boolean localWaits(WArmy a, WArmy b) {
        Session s = Engine.session();
        return s != null && (localWaits(s, a) || b != null && localWaits(s, b));
    }

    private static boolean localWaits(Session s, WArmy a) {
        if (a.faction() != null && s.localBusy(a.faction().index())) {
            return true;
        }
        for (LocalBattle b : s.locals) {
            if (b != null && contains(b.armies, a.armyIndex())) {
                return true;
            }
        }
        return false;
    }

    /** Patch 15, the polls of regions: true while a prompt about this faction's region waits for its local battle. */
    public static boolean localWaits(game.faction.Faction f) {
        Session s = Engine.session();
        return s != null && f != null && s.localBusy(f.index());
    }

    /** Patch 10: the battle's own pause on its PC: its resources stand still, as in deployment; the world runs on. */
    public static boolean localPaused() {
        Session s = Engine.session();
        return s != null && s.localPaused();
    }

    /** Patch 10: true for a resource of the battle itself, which runs again in a local battle's extra rounds. */
    public static boolean localRound(GameResource r) {
        return StepParts.battleRound(r);
    }

    /** Patch 10: how many more times the battle's resources run in this part of this step. */
    public static int localExtraRounds(int part, boolean deploying) {
        Session s = Engine.session();
        return s == null || deploying || part != StepParts.CITY ? 0 : s.localExtraRounds();
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    public static boolean worldHeld() {
        Session s = Engine.session();
        return s != null && (s.worldHeld || s.assaultHolds(s.clock.step())); // an assault holds it too (SOS-92 B)
    }

    // ------------------------------------------------------------ the assault on a human's capital (SOS-92 step B)

    /** {@code Invasion}'s ends ({@link #assaultOutcome}): the defender held, the invaders took the throne, they withdrew. */
    public static final int ASSAULT_HELD = Assault.HELD;
    public static final int ASSAULT_TAKEN = Assault.TAKEN;
    public static final int ASSAULT_RETREAT = Assault.RETREAT;

    /** True while the assault's invasion is being built: {@code Invasion} starts at the bombardment, with no warning. */
    public static boolean assaultStarting() {
        Session s = Engine.session();
        return s != null && s.assaultStarting;
    }

    /** True while an assault's invasion runs in the defender's city, on every PC that runs it. */
    public static boolean assaultRuns() {
        Session s = Engine.session();
        return s != null && s.assault != null && s.assault.started && s.assault.endStep < 0;
    }

    /** Why no assault can start now (a battle, an open view, another assault); null when one can. */
    public static String assaultRefusal() {
        Session s = Engine.session();
        return s == null ? "No session." : s.assaultRefusal();
    }

    /** True while an assault on the capital of the human in {@code slot} runs or waits for its views. */
    public static boolean assaultOf(int slot) {
        Session s = Engine.session();
        return s != null && s.assault != null && s.assault.defender == slot;
    }

    /** In the world part at Storm's step: the assault begins on every PC ({@link Session#assaultBegin}). */
    public static void assaultBegin(int attacker, int defender, Object spec, int[] armies, int men) {
        Session s = Engine.session();
        s.assaultBegin(s.clock.step(), attacker, defender, spec, armies, men);
    }

    /** {@code ASSAULT_TERMS} in the world part: the attacker's choice after a taken throne (SOS-92 D). */
    public static boolean assaultTerms(int attacker, int kind, int percent) {
        Session s = Engine.session();
        return s != null && s.assaultTerms(attacker, kind, percent);
    }

    /** The rig's {@code ASSAULT_END} in the world part: the running assault's invasion ends with {@code outcome}. */
    public static boolean assaultForce(int outcome) {
        Session s = Engine.session();
        if (s == null || s.assault == null || !s.assault.started || s.assault.outcome >= 0 || outcome < Assault.HELD
                || outcome > Assault.RETREAT) {
            return false;
        }
        s.assault.forced = outcome;
        return true;
    }

    /** {@code Invasion.update}, as the assault's invasion ends in the step: the same on every PC that runs the city. */
    public static void assaultOutcome(int outcome) {
        Session s = Engine.session();
        if (s != null && s.assault != null && s.assault.started && s.assault.outcome < 0) {
            s.assault.outcome = outcome;
        }
    }

    /**
     * True for an army in the open world-battle prompt, either side: it waits in place until the prompt is answered or
     * its time runs out (spec §16, owner 2026-10-25, SOS-45). The prompt opens and closes inside the step on every PC
     * alike, so every PC holds the same armies at the same steps. Per army and world update: no allocation.
     */
    public static boolean armyHeld(WArmy a) {
        Session s = Engine.session();
        if (s == null) {
            return false;
        }
        int index = a.armyIndex();
        for (LocalBattle b : s.locals) {
            if (b != null && contains(b.armies, index)) {
                return true; // a local battle's armies wait until its result applies (SOS-93)
            }
        }
        int[] held = s.worldPromptArmies;
        if (held.length == 0) {
            return false;
        }
        for (int h : held) {
            if (h == index) {
                return true;
            }
        }
        return false;
    }

    /** True with a session: the battle state updates inside the step, not from the battle view's frame. */
    public static boolean battleStateInStep() {
        return Engine.session() != null;
    }

    // ------------------------------------------------------------ the step's two parts (ADR 0005, SOS-38)

    /**
     * The step enters a part: 0 the city part, 1 the world part. Between them, every 30 steps, the city part takes its
     * report for 30 steps later; the world part then reads the report due now (the first one is taken at once), and
     * first applies its inputs. The world part takes a random stream of its own, seeded from the seed and the step, so
     * the city's draws on this PC never shift the world's.
     */
    public static void stepPart(int part) {
        Session s = Engine.session();
        if (part == StepParts.WORLD_PART) {
            long step = s.clock.step();
            if (!s.ownCity && s.humans().length > 1) {
                throw new IllegalStateException("SOSDET: " + s.humans().length + " humans need own-city mode: each"
                        + " human's city runs on its own PC");
            }
            // in a battle the world stands still (spec §4) and no city runs: no reports are taken or read (plan 0006)
            boolean battleRuns = s.battle != null && !s.battle.awaiting;
            if (!battleRuns && s.reports(s.localHuman).current() == null && !s.reports(s.localHuman).pending()) {
                s.takeReport(step, step);
            }
            if (!battleRuns && Reports.takenAt(step) && !s.frozen(s.localHuman)) {
                s.takeReport(step, s.reportAppliesAt(step)); // a viewer takes none: its city is unloaded (SOS-82)
            }
            s.part = part;
            s.streams.reseed(s.worldSeed(step));
            s.streams.worldPart(true);
            localGenerate(s, step); // a local battle's PC generates its battle at the freeze step (SOS-93)
            s.localAfter(step); // the step after a local battle's result: vanilla's after-battle on every PC
            s.assaultResolve(step); // an assault whose views have all thawed: its outcome reaches the world (SOS-92 B)
            s.assaultTermsDue(step); // a taken throne whose attacker didn't choose in time: the default terms (SOS-92 D)
            if (!battleRuns) {
                s.dueWithoutReports();
                s.retireDue(); // first: a removed human's report is read no more (SOS-66)
            }
            int[] humans = battleRuns ? Session.NO_ARMIES : s.humans();
            for (int h : humans) {
                if (s.retired(h) || s.removing[h] && s.reports(h).current() == null && !s.reports(h).pending()) {
                    continue; // removed, or being removed after a load: it sends no report (SOS-66)
                }
                s.currentHuman = h; // a report's inputs act for its own human
                s.reports(h).applyDue(step, s.inputApplier());
                if (s.reports(h).current() == null) {
                    throw new IllegalStateException("SOSDET: no city report of the human in slot " + h + " for step " + step
                            + (h == s.localHuman ? " from PC " + s.reportsFrom : ""));
                }
            }
            s.currentHuman = 0;
            s.ai().worldPart();
            if (s.pvpEngageAt == step && s.worldPrompt != null) { // both humans answered, or time ran out (SOS-83)
                sosdet.game.cmd.WorldBattles.pvpEngage();
            }
            // after the world's commands: an answer given at this step comes first (SOS-45)
            if (s.promptTimer.step(s.clock.stepSeconds(), s.clock.agreedSpeed()) && s.worldPromptTimeout != null) {
                s.currentHuman = s.worldPromptHuman; // as the human asked, whose answer it stands in for (SOS-46)
                s.worldPromptTimeout.run();
                s.currentHuman = 0;
            }
            return;
        }
        s.part = part;
    }

    /** True in own-city mode (ADR 0005 §1): the hash leaves this PC's city out, the player's credits included. */
    public static boolean ownCity() {
        Session s = Engine.session();
        return s != null && s.ownCity;
    }

    /** True when {@code pc} is this PC (the player who gave a command or order). */
    public static boolean localPc(int pc) {
        Session s = Engine.session();
        return s != null && pc == s.ai().localPc();
    }

    /**
     * Whether a city command PC {@code pc} gave applies here: always in co-op; an own city's only on its own PC, and on
     * a PC viewing that city (SOS-82).
     */
    public static boolean cityCommandHere(int pc) {
        Session s = Engine.session();
        // in a battle every PC runs the battle PC's game, so its commands apply everywhere (plan 0006)
        return s == null || !s.ownCity || pc == s.cityPc() || s.battle != null && !s.battle.awaiting;
    }

    /** A city view's start or end in the log, at its step (SOS-82). */
    public static void viewEntry(long step, int viewer, int owner, int ownerPc) {
        Engine.session().viewEntry(step, viewer, owner, ownerPc);
    }

    /** True when the resource updates in this part of the step. */
    public static boolean updatesIn(GameResource r, int part) {
        return StepParts.updatesIn(r, part);
    }

    /** True when the resource's once-per-step work runs at the start of this part. */
    public static boolean afterTickIn(GameResource r, int part) {
        return StepParts.afterTickIn(r, part);
    }

    /** True in a session's city part: a resource that runs in both parts does its city half. */
    public static boolean cityPart() {
        Session s = Engine.session();
        return s != null && s.part == StepParts.CITY;
    }

    /** True for a resource's city half: in the city part, or always without a session. */
    public static boolean cityHalf() {
        Session s = Engine.session();
        return s == null || s.part == StepParts.CITY;
    }

    /** True for a resource's world half: in the world part, or always without a session. */
    public static boolean worldHalf() {
        Session s = Engine.session();
        return s == null || s.part == StepParts.WORLD_PART;
    }

    /** True when the event with this save key runs in the current part (always without a session). */
    public static boolean eventRuns(String key) {
        Session s = Engine.session();
        return s == null || (s.part == StepParts.WORLD_PART) == StepParts.worldEvent(key);
    }

    // ------------------------------------------------------------ the humans (ADR 0004, SOS-46)

    /**
     * {@code FACTIONS.player()}: slot 0's Player without a session; in one, this PC's human, and in the world part the
     * human the world is working on. While a game loads, before its humans are in their slots, it is slot 0's.
     */
    public static Player player(Player first) {
        Session s = Engine.session();
        if (s == null) {
            return first;
        }
        int slot = s.humanNow();
        if (slot == 0) {
            return first;
        }
        Faction f = FACTIONS.getByIndex(slot);
        if (f instanceof Player) {
            return (Player) f;
        }
        if (s.loading) {
            return first;
        }
        throw new IllegalStateException("SOSDET: slot " + slot + " is this PC's or the current human, but no human (ADR 0004)");
    }

    /**
     * Whether a game message sent now is this PC's player's to see (SOS-89): {@code FACTIONS.player()} at the send is
     * this PC's human. The world part runs every human's raids, events and diplomacy on every PC, each with its human
     * current, and a viewer's PC runs the viewed city: their messages are for another PC's player. Without a session,
     * and while a game loads, every message is.
     */
    public static boolean messageHere() {
        Session s = Engine.session();
        return s == null || s.loading || s.humanNow() == s.localHuman;
    }

    /** {@link #newsHumans}: no human to set, the site sends as it is. */
    private static final int[] NEWS_AS_IT_IS = {-1};

    /**
     * World news (SOS-99): a message vanilla sends its player about the world, whoever caused it: an NPC's capital moved
     * after a conquest, a realm that collapsed near the player, a war between neighbours. Every PC runs the world part,
     * and a message shows only on the PC whose human is current at the send ({@link #messageHere}, SOS-89), so such news
     * reached one player only. In the world part the site now sends once for each human, that human current
     * ({@link #newsAs}), so its own conditions (a neighbour of that human's) decide; each PC shows its own human's.
     * Elsewhere, and without a session, the site sends once, as it is. Rare events: the list is made per call.
     */
    public static int[] newsHumans() {
        Session s = Engine.session();
        if (s == null || s.part != StepParts.WORLD_PART) {
            return NEWS_AS_IT_IS;
        }
        int[] all = s.humans();
        int n = 0;
        for (int h : all) {
            if (!retired(h)) {
                n++;
            }
        }
        int[] out = new int[n];
        n = 0;
        for (int h : all) {
            if (!retired(h)) {
                out[n++] = h;
            }
        }
        return out;
    }

    /** {@link #newsHumans}: makes {@code slot} the current human and returns the one before; -1 changes nothing. */
    public static int newsAs(int slot) {
        if (slot < 0) {
            return -1;
        }
        return currentHumanSwap(slot);
    }

    /** True when the world part runs every human's world half: in a session; without one only slot 0's, as in vanilla. */
    public static boolean everyHuman() {
        return Engine.session() != null;
    }

    /**
     * The human of PC {@code pc}: the one its world commands act for (ADR 0004); slot 0 for a PC the mod hasn't set
     * while the game has one human.
     */
    public static int humanOf(int pc) {
        Session s = Engine.session();
        if (s == null) {
            return 0;
        }
        int h = pc < 0 || pc >= s.pcHumans.length ? -1 : s.pcHumans[pc];
        if (h >= 0) {
            return h;
        }
        if (s.humans().length > 1) {
            throw new IllegalStateException("SOSDET: PC " + pc + " has no human; set each PC's with pcHuman (ADR 0004)");
        }
        return 0;
    }

    /**
     * This PC's human: the one whose banner draft and profile this PC's player edits (SOS-78); -1 without a session, or
     * when several humans share this PC and none is set for it.
     */
    public static int localHuman() {
        Session s = Engine.session();
        if (s == null) {
            return -1;
        }
        int pc = s.ai().localPc();
        int h = pc < 0 || pc >= s.pcHumans.length ? -1 : s.pcHumans[pc];
        return h >= 0 ? h : s.humans().length > 1 ? -1 : 0;
    }

    /**
     * The human slots, in slot order: slot 0 alone without a session. Not to be changed. A removed human keeps its
     * slot, so every human keeps its place and slices; each per-human loop skips it ({@link #retired}, SOS-66).
     */
    public static int[] humanSlots() {
        Session s = Engine.session();
        return s == null ? FIRST_HUMAN : s.humans();
    }

    /** True for the slot of a removed human (SOS-66): nothing runs for it. Per human and step: no allocation. */
    public static boolean retired(int slot) {
        Session s = Engine.session();
        if (s == null || slot < 0 || slot >= Session.SLOTS) {
            return false;
        }
        // while a game loads, the session's lists are the old game's: the loaded flags are read (their section loads first)
        return s.loading ? sosdet.game.GameHumans.get().retired(slot) : s.retired(slot);
    }

    /** As the host's removal entry applies, on every PC (SOS-66). */
    public static void humanRemoved(int slot) {
        Engine.session().humanRemoved(slot);
    }

    /**
     * The most humans a game holds (slot 0's Player and the spares): the size of every per-human slice the game keeps
     * (relations, events, raids, distances; SOS-46 M3 to M5). Slice 0 is slot 0's, the game's own fields.
     */
    public static final int HUMANS_MAX = sosdet.game.GameHumans.SPARES + 1;

    /**
     * The slice a per-human value reads now: the ordinal (0 to {@link #HUMANS_MAX}-1, in slot order) of the human
     * {@code FACTIONS.player()} is, so in the world part the current human's and elsewhere this PC's human's (or the
     * one {@link Engine#asHuman} reads as). 0 without a session, and while a game loads (SOS-46).
     */
    public static int humanIndex() {
        Session s = Engine.session();
        return s == null ? 0 : s.ordinal(s.humanNow());
    }

    /** The slice of the human {@code faction} (a Player in a slot): its ordinal among the human slots (SOS-46). */
    public static int humanIndex(Object faction) {
        if (!(faction instanceof Player)) {
            throw new IllegalArgumentException("SOSDET: " + faction + " is no human (ADR 0004)");
        }
        int slot = ((Player) faction).index();
        Session s = Engine.session();
        if (s != null) {
            return s.ordinal(slot);
        }
        int n = 0;
        for (int i = 0; i < slot; i++) {
            n += FACTIONS.getByIndex(i) instanceof Player ? 1 : 0;
        }
        return n;
    }

    /**
     * The slot of the human with the most regions, the first in slot order on a tie: the world's NPC events (wars and
     * expansion between NPCs) measure "the player" by it (SOS-46 M4). Slot 0 without a session and with one human.
     */
    public static int largestHuman() {
        int[] hs = humanSlots();
        if (hs.length == 1) {
            return hs[0];
        }
        int best = hs[0];
        int most = -1;
        for (int slot : hs) {
            int n = FACTIONS.getByIndex(slot).realm().regions();
            if (n > most) {
                most = n;
                best = slot;
            }
        }
        return best;
    }

    /** The world part works on the human in {@code slot} (its own world half), or on the first human again (slot 0). */
    public static void currentHuman(int slot) {
        Session s = Engine.session();
        if (s != null) {
            s.currentHuman = slot;
        }
    }

    /**
     * The world part works on the human in {@code slot} for one call into the game: returns the human it worked on
     * before, to give back with a second call (nesting-safe inside the per-human loops and a command's apply). Without
     * a session nothing changes and it returns 0. Outside the world part the value is not read ({@code FACTIONS.player()}
     * is this PC's human there), and the second call puts it back (SOS-46).
     */
    public static int currentHumanSwap(int slot) {
        Session s = Engine.session();
        if (s == null) {
            return 0;
        }
        int before = s.currentHuman;
        s.currentHuman = slot;
        return before;
    }

    /** As {@link #currentHumanSwap(int)}, for a faction: a human's slot; for one that is no human it stays as it is. */
    public static int currentHumanSwap(Object faction) {
        Session s = Engine.session();
        if (s == null) {
            return 0;
        }
        int before = s.currentHuman;
        if (faction instanceof Player) {
            s.currentHuman = ((Player) faction).index();
        }
        return before;
    }

    /**
     * True for a human whose capital is besieged (mod spec §9, SOS-92): the market brings it nothing and takes nothing
     * from it, from NPCs or other humans. World state, the same on every PC. Per good and trade partner: no allocation.
     */
    public static boolean humanBlockaded(Object faction) {
        return Engine.session() != null && faction instanceof Player
                && sosdet.game.GameHumans.get().sieges().book().besieged(((Player) faction).index());
    }

    /** True when the world part's faction updater runs this faction: no human's own in a session. */
    public static boolean worldUpdatesFaction(Object faction) {
        return Engine.session() == null || StepParts.worldUpdatesFaction(faction);
    }

    /**
     * The city part runs the player's own faction update on a clock of its own: the time to pass when one is due
     * this step ({@code period} of game time since the last), else 0.
     */
    public static double playerUpdateDue(double ds, double period) {
        Session s = Engine.session();
        s.playerUpdateAcc += ds;
        if (s.playerUpdateAcc < period) {
            return 0;
        }
        s.playerUpdateAcc -= period;
        return period;
    }

    // ------------------------------------------------------------ the city report (ADR 0005 §2, SOS-38)

    /**
     * True in a session's world part: a city value it reads comes from the city's report, not the live city. Outside
     * the step (the UI) and in the city part the live city is read.
     */
    public static boolean reportRead() {
        Session s = Engine.session();
        return s != null && s.part == StepParts.WORLD_PART;
    }

    /**
     * {@code WDivStored}'s men, race and kit (B4): read from the city's report in the world part and in a world resync's
     * load, which counts the soldiers away as the world part would (on a follower the live city is another city).
     */
    public static boolean divReport() {
        Session s = Engine.session();
        return s != null && (s.part == StepParts.WORLD_PART || s.worldLoading);
    }

    /** True on a follower: this PC's world reads another PC's city, whose world armies and soldiers they are (SOS-38). */
    public static boolean follower() {
        Session s = Engine.session();
        return s != null && s.reportsFrom >= 0;
    }

    /**
     * The world resyncs loaded so far (0 with no session): a game cache made from the world, with a window or a scale
     * the load leaves standing, starts again when this changes, on every PC at the same step (SOS-32).
     */
    public static int worldLoads() {
        Session s = Engine.session();
        return s == null ? 0 : s.worldLoads;
    }

    public static double reportD(Field f, int i) {
        return Engine.session().reports().value(f, i);
    }

    /** A value of the human in {@code human}, from its report, whichever human the world part works on (ADR 0004). */
    public static double reportD(int human, Field f, int i) {
        return Engine.session().reports(human).value(f, i);
    }

    /** {@code PCredits.getD} in the world part: the treasury of the human it is, from its report (ADR 0004). */
    public static double reportCredits(PCredits c) {
        Session s = Engine.session();
        return s.reports(creditsOwner(s, c)).value(Field.CREDITS, 0);
    }

    public static double reportD(Field f, int i, int j) {
        return Engine.session().reports().value(f, i, j);
    }

    /** As {@link #reportD(Field, int, int)}, from the report of the human in {@code human} (ADR 0004). */
    public static double reportD(int human, Field f, int i, int j) {
        return Engine.session().reports(human).value(f, i, j);
    }

    public static int reportI(Field f, int i) {
        return (int) reportD(f, i);
    }

    public static int reportI(Field f, int i, int j) {
        return (int) reportD(f, i, j);
    }

    /** As {@link #reportI(Field, int)}, from the report of the human in {@code human} (ADR 0004). */
    public static int reportI(int human, Field f, int i) {
        return (int) reportD(human, f, i);
    }

    public static int reportI(int human, Field f, int i, int j) {
        return (int) Engine.session().reports(human).value(f, i, j);
    }

    public static boolean reportB(Field f, int i) {
        return reportD(f, i) != 0;
    }

    /**
     * In the world part: the player's value of a boost value, from the report. One the report has no slot for (a
     * booster added after the report's layout was made) is read live and counted in {@link Stats#reportMisses}.
     */
    public static double reportBoost(BValue v) {
        return boostOf(v, FACTIONS.player().index());
    }

    /** As {@link #reportBoost(BValue)}, on the human {@code f}: from that human's report (ADR 0004). */
    public static double reportBoost(BValue v, Faction f) {
        return boostOf(v, f instanceof Player ? f.index() : FACTIONS.player().index());
    }

    // by slot, so this class's verification never loads the game's faction types (the tests run without the game)
    private static double boostOf(BValue v, int human) {
        Session s = Engine.session();
        int i = s.city().indexOf(Field.BOOST, v);
        if (i >= 0) {
            return s.reports(human).current().get(Field.BOOST, i);
        }
        s.reportMiss(v);
        return v.vGet((Player) FACTIONS.getByIndex(human));
    }

    /**
     * In the world part: a boost value on the city's population class ({@code HCLASS_RACE.clP()}), from the report. As
     * {@link #reportBoost}, one without a slot is read live and counted in {@link Stats#reportMisses}.
     */
    public static double reportBoostClP(BValue v) {
        Session s = Engine.session();
        int i = s.city().indexOf(Field.BOOST, v);
        if (i >= 0) {
            return s.reports().current().get(Field.BOOST_CLP, i);
        }
        s.reportMiss(v);
        return v.vGet(HCLASS_RACE.clP());
    }

    /**
     * Army 1 of a battle between two humans ({@link #battleArmy1Human} not null): a boost value of the other human, from
     * its report at Engage. One the report has no slot for counts as a miss and is 0, the same on every PC (SOS-83).
     */
    public static double army1Boost(BValue v) {
        return army1Report(v, Field.BOOST);
    }

    /** As {@link #army1Boost}, on the other human's city population class ({@link Field#BOOST_CLP}). */
    public static double army1BoostClP(BValue v) {
        return army1Report(v, Field.BOOST_CLP);
    }

    private static double army1Report(BValue v, Field f) {
        Session s = Engine.session();
        int i = s.city().indexOf(Field.BOOST, v);
        if (i >= 0) {
            return s.battle.otherReport.get(f, i);
        }
        s.reportMiss(v);
        return 0;
    }

    /** A faction value's value: a human's from its report in the world part, otherwise the game's own. */
    public static <T> double reportValue(Value<T> v, T t) {
        if (reportRead() && t instanceof Player) {
            Session s = Engine.session();
            int i = s.city().indexOf(Field.VALUE, v);
            if (i >= 0) {
                return s.reports(((Player) t).index()).current().get(Field.VALUE, i);
            }
            s.reportMiss(v);
        }
        return v.d.getD(t);
    }

    /**
     * An unlock of a tech, level or title ({@code Lockers}) on something a human owns: that human's from its report in
     * the world part, otherwise the game's own. It reads the city's tech, level and titles, which on another PC are
     * that PC's (SOS-32). A person's has no slot: in own-city mode or with several humans the world part takes it as
     * unlocked, the same on every PC, since a human's people live in that human's city (SOS-46). Any other without a slot is read live and counted in
     * {@link Stats#reportMisses}.
     */
    public static <T> double reportUnlock(Locker<T> l, DOUBLE_O<T> v, T t) {
        if (reportRead()) {
            int h = GameReport.ownerHuman(t);
            if (h >= 0) {
                Session s = Engine.session();
                int i = s.city().indexOf(Field.UNLOCK, l);
                if (i >= 0) {
                    return s.reports(h).current().get(Field.UNLOCK, i);
                }
                if (GameReport.person(t) && (s.ownCity || s.humans().length > 1)) {
                    return 1; // co-op with one human reads it live, as before: every PC has the same city
                }
                s.reportMiss(l);
            }
        }
        return v.getD(t);
    }

    // ------------------------------------------------------------ transactions (ADR 0005 §3, SOS-38)

    /**
     * In the world part: a new transaction of this kind for the current human, queued for the step's city tail. Null
     * anywhere else: the caller changes the city at once, as in vanilla (the city part, the tail itself, the UI, no
     * session).
     */
    private static Txn toCity(TxnKind kind) {
        Session s = Engine.session();
        if (s == null || s.part != StepParts.WORLD_PART) {
            return null;
        }
        Txn t = s.txns.add(s.clock.step());
        t.kind = kind;
        t.human = s.currentHuman;
        return t;
    }

    /** The human whose treasury {@code c} is; the current human for one no human has. */
    private static int creditsOwner(Session s, PCredits c) {
        for (int h : s.humans()) {
            if (((Player) FACTIONS.getByIndex(h)).credits() == c) {
                return h;
            }
        }
        return s.currentHuman;
    }

    /**
     * {@code PBuyer.addReserve}: true when queued. The world part reads the credits paid and the goods queued at once,
     * through the report's ledger.
     */
    public static boolean buyerReserve(PBuyer b, int amount, TRADE_TYPE type, int price, Faction seller) {
        Txn t = toCity(TxnKind.BUYER_RESERVE);
        if (t == null) {
            return false;
        }
        t.a = b;
        t.b = type;
        t.c = seller;
        t.i = amount;
        t.j = price;
        Session s = Engine.session();
        long step = s.clock.step();
        Reports r = s.reports(t.human);
        r.change(step, Field.CREDITS, 0, -price);
        r.change(step, Field.QUEUED, b.tradable.index(), 0, amount);
        r.change(step, Field.QUEUED, b.tradable.index(), type.index + 1, amount);
        s.transfers.record(step, t.id(), t.human, TransferLog.Kind.BOUGHT, b.tradable.index(), amount, price, type.index,
                seller == null ? -1 : seller.index());
        return true;
    }

    /** {@code PBuyer.addDeliver}: true when queued. The goods stay queued, so the ledger has nothing to add. */
    public static boolean buyerDeliver(PBuyer b, int amount, TRADE_TYPE type) {
        Txn t = toCity(TxnKind.BUYER_DELIVER);
        if (t == null) {
            return false;
        }
        t.a = b;
        t.b = type;
        t.i = amount;
        Session s = Engine.session();
        s.transfers.record(s.clock.step(), t.id(), t.human, TransferLog.Kind.DELIVERED, b.tradable.index(), amount, 0,
                type.index, -1);
        return true;
    }

    /** {@code PSeller.remove}: true when queued. The world part reads the credits paid and the goods promised at once. */
    public static boolean sellerRemove(PSeller p, int amount, TRADE_TYPE type, int price, Faction buyer) {
        Txn t = toCity(TxnKind.SELLER_REMOVE);
        if (t == null) {
            return false;
        }
        t.a = p;
        t.b = type;
        t.c = buyer;
        t.i = amount;
        t.j = price;
        Session s = Engine.session();
        long step = s.clock.step();
        Reports r = s.reports(t.human);
        r.change(step, Field.CREDITS, 0, price);
        r.change(step, Field.PROMISED, p.type.index(), 0, amount);
        r.change(step, Field.PROMISED, p.type.index(), type.index + 1, amount);
        s.transfers.record(step, t.id(), t.human, TransferLog.Kind.SOLD, p.type.index(), amount, price, type.index,
                buyer == null ? -1 : buyer.index());
        return true;
    }

    /**
     * {@code TradeSorter.buy}, a purchase by {@code buyer} from {@code seller} at {@code price}: the buyer's tariff of it
     * when both are humans, which the seller doesn't get and the buyer's treasury does; otherwise 0 (SOS-77).
     */
    public static int tariffCut(Faction buyer, Faction seller, int price) {
        return HumanDeals.tariffCut(buyer, seller, price);
    }

    /** {@code PCredits.inc}: true when queued. The treasury counts whole credits ({@code CredHistory}), as here. */
    public static boolean credits(PCredits c, double amount, CTYPE type) {
        Txn t = toCity(TxnKind.CREDITS);
        if (t == null) {
            return false;
        }
        t.a = c;
        t.b = type;
        t.d = amount;
        Session s = Engine.session();
        t.human = creditsOwner(s, c);
        s.reports(t.human).change(s.clock.step(), Field.CREDITS, 0, (int) amount);
        s.transfers.record(s.clock.step(), t.id(), t.human, TransferLog.Kind.MONEY, -1, amount, 0, type.ordinal(), -1);
        return true;
    }

    /** {@code PCredits.inc} for traded goods: true when queued. */
    public static boolean credits(PCredits c, double amount, CTYPE type, TRADABLE res, int resAm) {
        Txn t = toCity(TxnKind.CREDITS_TRADE);
        if (t == null) {
            return false;
        }
        t.a = c;
        t.b = type;
        t.c = res;
        t.d = amount;
        t.i = resAm;
        Session s = Engine.session();
        t.human = creditsOwner(s, c);
        s.reports(t.human).change(s.clock.step(), Field.CREDITS, 0, (int) amount);
        s.transfers.record(s.clock.step(), t.id(), t.human, TransferLog.Kind.MONEY_FOR_GOODS, res.index(), amount, resAm,
                type.ordinal(), -1);
        return true;
    }

    /**
     * {@code WDivStoredAll.update} (C4): in the world part, the entry point a returning soldier takes is picked in the
     * city tail; this stands in for it and carries the pick's seed as its x.
     */
    public static COORDINATE entryLater(int seed) {
        return sosdet.game.GameTxns.entryLater(seed); // there, so the game types load only in a game
    }

    /**
     * {@code WDivStored.popSoldier} (C4): true when queued. The world part goes on to take the soldier out of their
     * division; the city tail brings a copy of them, as they were now, into the city at an entry point.
     */
    public static boolean soldierReturns(Induvidual t, int div, int entrySeed, int human) {
        Txn x = toCity(TxnKind.SOLDIER_RETURNS);
        if (x == null) {
            return false;
        }
        x.human = human; // the division's human, whose city takes them back (SOS-46)
        Induvidual copy = new Induvidual(t.hType(), t.race());
        copy.copyFrom(t);
        x.a = copy;
        x.i = div;
        x.j = entrySeed;
        return true;
    }

    /** {@code WDivStored.age} (C4): true when queued; the world part has taken the retiree out of their division. */
    public static boolean retiree(Induvidual i, int human) {
        Txn x = toCity(TxnKind.RETIREE);
        if (x == null) {
            return false;
        }
        x.human = human; // the division's human (SOS-46)
        x.a = i;
        return true;
    }

    /** {@code StatsRelations.setSoldier} (C5): the city's relation table; true when queued. */
    public static boolean relSoldier(Induvidual i, int storedId) {
        Txn x = toCity(TxnKind.REL_SOLDIER);
        if (x == null) {
            return false;
        }
        x.a = i;
        x.i = storedId;
        return true;
    }

    /** {@code StatsRelations.setDeath} (C5): true when queued. */
    public static boolean relDeath(Induvidual i, CAUSE_LEAVE cause) {
        Txn x = toCity(TxnKind.REL_DEATH);
        if (x == null) {
            return false;
        }
        x.a = i;
        x.b = cause;
        return true;
    }

    /** As {@link #relSoldier(Induvidual, int)}, for the city of the division's human (C26, SOS-46). */
    public static boolean relSoldier(Induvidual i, int storedId, int human) {
        Txn x = toCity(TxnKind.REL_SOLDIER);
        if (x == null) {
            return false;
        }
        x.human = human;
        x.a = i;
        x.i = storedId;
        return true;
    }

    /** As {@link #relDeath(Induvidual, CAUSE_LEAVE)}, for the city of the division's human (C26, SOS-46). */
    public static boolean relDeath(Induvidual i, CAUSE_LEAVE cause, int human) {
        Txn x = toCity(TxnKind.REL_DEATH);
        if (x == null) {
            return false;
        }
        x.human = human;
        x.a = i;
        x.b = cause;
        return true;
    }

    /** {@code StatsRelations.setHumanoid} (C5): true when queued. */
    public static boolean relHumanoid(Humanoid h) {
        Txn x = toCity(TxnKind.REL_HUMANOID);
        if (x == null) {
            return false;
        }
        x.a = h;
        return true;
    }

    /**
     * {@code WDivStoredAll.attach} (C1): true when queued. The city tail takes the division's supplies from its
     * stockpiles, as much as it has, and its next report gives them to the army (B2): the world asks, the city answers.
     */
    public static boolean armySupplies(WArmy a, int div) {
        Txn x = toCity(TxnKind.ARMY_SUPPLY_DIV);
        if (x == null) {
            return false;
        }
        x.a = a;
        x.i = div;
        return true;
    }

    /** As {@link #armySupplies(WArmy, int)}, for {@code men} recruited of a race (the recruit command). */
    public static boolean armySupplies(WArmy a, Object race, int men) {
        Txn x = toCity(TxnKind.ARMY_SUPPLY_RECRUIT);
        if (x == null) {
            return false;
        }
        x.a = a;
        x.b = race;
        x.i = men;
        return true;
    }

    /**
     * {@code WDivCity.resolve} (C5): a world battle's result for a capitol garrison division of the human in
     * {@code human}, whose city fought (SOS-46); true when queued.
     */
    public static boolean garrisonResolve(Object div, int survivors, double experiencePerMan, int human) {
        Txn x = toCity(TxnKind.GARRISON_RESOLVE);
        if (x == null) {
            return false;
        }
        x.human = human;
        x.a = div;
        x.i = survivors;
        x.d = experiencePerMan;
        return true;
    }

    /** {@code WDivCity.resolve(Induvidual[])} (C5): true when queued, for the capitol's human (SOS-46). */
    public static boolean garrisonResolve(Object div, Induvidual[] survivors, int human) {
        Txn x = toCity(TxnKind.GARRISON_RESOLVE_PEOPLE);
        if (x == null) {
            return false;
        }
        x.human = human;
        x.a = div;
        x.b = survivors;
        return true;
    }

    /**
     * {@code Invador.invade} (C6): in the world part the world gives the invasion its ref, the report's next one plus the
     * world's own invasions since, so every PC knows it at once and the city tail starts the invasion with it.
     */
    public static int invasionRef() {
        return (int) Engine.session().reports().value(Field.INVASION_REF, 0);
    }

    /** C14 (SOS-46): the span of one city's invasion refs in the world's; slot 0's refs stay as they are (old saves). */
    private static final int INVASION_REF_SPAN = 1 << 24;

    /**
     * The world's name for a city's invasion ref (C14, SOS-46): in the world part, of the current human's city; elsewhere
     * (and without a session) the city's own ref, as in vanilla.
     */
    public static int worldInvasionRef(int cityRef) {
        Session s = Engine.session();
        int h = s == null || s.part != StepParts.WORLD_PART ? 0 : s.currentHuman;
        return h * INVASION_REF_SPAN + cityRef;
    }

    /** {@code Invador.invade} (C6): true when queued for the city tail. */
    public static boolean invade(Object spec) {
        Txn x = toCity(TxnKind.INVADE);
        if (x == null) {
            return false;
        }
        x.a = spec;
        Session s = Engine.session();
        s.reports(x.human).change(s.clock.step(), Field.INVASION_REF, 0, 1);
        if (s.invasionNotices.size() < Session.NOTICES_MAX) { // every PC learns it at this step: the mod tells every player (SOS-92 F)
            s.invasionNotices.add(new long[] {s.clock.step(), x.human, ((settlement.battle.invasion.InvasionSpec) spec).fi});
        }
        return true;
    }

    /** {@code Invador.invade} in the city tail: true while it starts the invasion the world part made. */
    public static boolean invasionArrives(Object spec) {
        Session s = Engine.session();
        if (s == null) {
            return false;
        }
        Txn t = s.txns.applying();
        return t != null && t.kind == TxnKind.INVADE && t.a == spec;
    }

    /** {@code Invador.cancel} (C6): true when queued. */
    public static boolean invasionCancel(int ref) {
        Session s = Engine.session();
        if (s != null && s.worldLoading) {
            return true; // a world resync clears the raiders it reloads; the city's invasion goes on
        }
        Txn x = toCity(TxnKind.INVASION_CANCEL);
        if (x == null) {
            return false;
        }
        x.human = ref / INVASION_REF_SPAN; // the city whose invasion it is: the world names it by its human (C14, SOS-46)
        x.i = ref % INVASION_REF_SPAN;
        return true;
    }

    /**
     * {@code Invador}'s diplomacy listener (C6): a war with the player ended in the world part; the city tail runs the
     * listener, which calls off that faction's invasions. True when queued.
     */
    public static boolean warEnded(Object listener, Object faction, Object other, Object old, Object stance) {
        Session s = Engine.session();
        if (s == null || s.part != StepParts.WORLD_PART) {
            return false;
        }
        boolean fh = faction instanceof Player;
        boolean oh = other instanceof Player;
        if (fh == oh && (fh || s.humans().length > 1)) {
            // no human's city holds invasions of it: a war between humans, or (with several humans) between NPCs; with
            // one human a war between NPCs still goes to its city, as in vanilla (C13, SOS-46)
            return true;
        }
        Txn x = toCity(TxnKind.WAR_ENDED);
        if (fh != oh) {
            x.human = ((Faction) (fh ? faction : other)).index(); // the human side's city calls off the other's invasions
        }
        x.a = listener;
        x.b = new Object[] {faction, other, old, stance};
        return true;
    }

    /**
     * {@code Immigration.setHigher} (A2, SOS-46): in the world part, a transaction for the current human's city, whose
     * haven it is; true when queued.
     */
    public static boolean immigrantsHigher(Object race, int am) {
        Txn x = toCity(TxnKind.IMMIGRANTS_HIGHER);
        if (x == null) {
            return false;
        }
        x.a = race;
        x.i = am;
        return true;
    }

    // ------------------------------------------------------------ city inputs to the world (ADR 0005 §2)

    /** In the city part, an input for the next report; outside it, null. */
    private static Session inCityPart() {
        Session s = Engine.session();
        return s != null && s.part == StepParts.CITY ? s : null;
    }

    /**
     * {@code WDivStoredAll.add} (B1): in the city part, the soldier joins their world division when the city's next
     * report applies; true when sent.
     */
    public static boolean soldierJoins(Induvidual i, int div) {
        Session s = inCityPart();
        if (s == null) {
            return false;
        }
        s.cityInputs.add(s.inputs().soldierJoins(i, div));
        return true;
    }

    /** {@code ADSupply.current().set} (B2): in the city part, the change goes to the world in the next report. */
    public static boolean supplyFromCity(int supply, WArmy a, int delta) {
        Session s = inCityPart();
        if (s == null || a == null) {
            return false;
        }
        if (delta != 0) {
            s.cityInputs.add(s.inputs().supply(supply, a, delta));
        }
        return true;
    }

    /** The outcomes {@code Invador.tell} passes to the invasion listeners (B4). */
    public static final int TELL_VICTORY = 0;
    public static final int TELL_DEFEAT = 1;
    public static final int TELL_WEIRDNESS = 2;

    /** {@code Invador.tell} (B4): in the city part, an invasion's outcome reaches the world's listeners in the next report. */
    public static boolean invasionTell(int kind, int lossed, int kills, int ref) {
        Session s = inCityPart();
        if (s == null) {
            return false;
        }
        s.cityInputs.add(s.inputs().invasionTell(kind, lossed, kills, ref));
        return true;
    }

    /** {@code ADStats.report} (B4): in the city part, the battle reaches the army stats in the next report. */
    public static boolean battleStats(Faction f, boolean victory, int losses, int kills) {
        Session s = inCityPart();
        if (s == null) {
            return false;
        }
        s.cityInputs.add(s.inputs().battleStats(f, victory, losses, kills));
        return true;
    }

    /** {@code Invasion.loose} (B4): in the city part, the invader takes the player as vassal in the world part. */
    public static boolean vassal(Faction f) {
        Session s = inCityPart();
        if (s == null) {
            return false;
        }
        s.cityInputs.add(s.inputs().vassal(f));
        return true;
    }

    /**
     * {@code Attack.afterExit} (B4): in the city part, the survivors of a world division that fought the invaders in the
     * city reach it in the next report.
     */
    public static boolean divResolve(Object div, Induvidual[] survivors) {
        Session s = inCityPart();
        if (s == null) {
            return false;
        }
        s.cityInputs.add(s.inputs().divResolve(div, survivors));
        return true;
    }

    /** The targets and kinds of world mark a scripted event sets ({@code EContext}'s selections, B6). */
    public static final int EVENT_REGION = 0;
    public static final int EVENT_FACTION = 1;
    public static final int EVENT_ROYAL = 2;
    public static final int EVENT_FLAG = 0;
    public static final int EVENT_MARK = 1;

    /**
     * {@code EContext}'s selections (B6): outside the world part a scripted event's world mark travels in the city's
     * next report instead of changing the world; {@code world} is the world's value now. True when handled so.
     */
    public static boolean eventMarkSet(int target, int kind, int index, int value, int world) {
        Session s = Engine.session();
        if (s == null || s.part == StepParts.WORLD_PART) {
            return false;
        }
        if (s.eventMarks.set(target, kind, index, value, world)) {
            s.cityInputs.add(s.inputs().eventMark(target, kind, index, value));
        }
        return true;
    }

    /** {@code EContext}'s selections (B6): outside the world part, the world's mark with what the city sent over it. */
    public static int eventMarkGet(int target, int kind, int index, int world) {
        Session s = Engine.session();
        if (s == null || s.part == StepParts.WORLD_PART) {
            return world;
        }
        return s.eventMarks.read(target, kind, index, world);
    }

    /**
     * A scripted event's world action ({@code _BOOST_PERM}, {@code _OPINION}, {@code _REGION_POP}, {@code _INVASION}
     * and their removals, B6): outside the world part it travels in the city's next report, and the world part runs
     * it. True when sent.
     */
    public static boolean eventAction(Object action, Object event, Object context) {
        Session s = Engine.session();
        if (s == null || s.part == StepParts.WORLD_PART) {
            return false;
        }
        s.cityInputs.add(s.inputs().eventAction(action, event, context));
        return true;
    }

    /**
     * {@code SETT.CreateFromWorldMap} starts (hook 18): in a session, a city's generation (a PC's own city at setup, the
     * landing view's Regenerate) keeps the world's state. It re-rolls the NPCs' production boosts on this PC only
     * ({@code Recipes.clear}); they are put back at {@link #cityGenerateEnd}. A battle's generation runs as vanilla
     * (SOS-56).
     */
    public static void cityGenerateBegin(boolean isBattle) {
        Session s = Engine.session();
        if (s == null || isBattle) {
            return;
        }
        s.keptBoosts = GameOwnCity.boosts();
    }

    /** {@code SETT.CreateFromWorldMap} ends (hook 18). */
    public static void cityGenerateEnd() {
        Session s = Engine.session();
        if (s == null || s.keptBoosts == null) {
            return;
        }
        GameOwnCity.restore(s.keptBoosts);
        s.keptBoosts = null;
    }

    /** A cache kept per update ({@code GAME.updateI()}) that must not carry the city part's values into the world part. */
    public static int partKey(int updateI) {
        return reportRead() ? ~updateI : updateI;
    }

    /**
     * True with a session anywhere but the world part's own thread (the city part, the UI between steps, another
     * thread): a world cache whose value comes from the city, such as an army's power, is computed afresh there and
     * neither read nor filled, so the world part only ever sees the value it made from the report (ADR 0005).
     */
    public static boolean worldCacheClosed() {
        Session s = Engine.session();
        return s != null && (s.part != StepParts.WORLD_PART || !s.streams.isSimulationThread());
    }

    /** {@code ADSupply.current().get}: outside the world part, what the city sent the army that the world has not applied. */
    public static int supplyPending(int supply, WArmy a) {
        Session s = Engine.session();
        return s == null || a == null || s.part == StepParts.WORLD_PART ? 0
                : s.sentSupplies.pending(supply, a.armyIndex(), a.iteration());
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
        // a local battle's general runs in the step on its own PC: only that PC runs the battle (SOS-93)
        s.jobs.run(s.clock.step(), orders, centres, trajectories, general, status,
                s.ai().mode() == AiMode.IN_STEP || s.localHere() != null);
    }

    /**
     * True when a battle thread's own thread must not start: with a session its job runs inside the step. The one
     * exception is the AI general on the host, which runs on its own thread as in vanilla.
     */
    public static boolean threadRunsInStep(boolean general) {
        Session s = Engine.session();
        return s != null && !(general && s.ai().mode() == AiMode.HOST && s.localHere() == null);
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
        if (s != null && s.localHere() == null) { // a local battle's general captures nothing (SOS-93)
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
        // a local battle's orders apply on its own PC, the only one running it (SOS-93)
        return s != null && !s.streams.inStep() && !s.loading && s.ai().capturingOrders() && s.localHere() == null;
    }

    /** A player's order on this PC: into the host's log, through it on a peer. */
    public static void orderCapture(int kind, int div, byte[] payload) {
        Engine.session().ai().captureOrder(kind, div, payload);
    }

    /** Who commands each battle division (SOS-15); null without a session, and then nothing is restricted. */
    public static Commanders commanders() {
        Session s = Engine.session();
        return s == null ? null : s.ai().commanders();
    }

    /** The session's game adapter for the AI (host or peer mode), which also encodes the general's orders. */
    public static AiApplier aiApplier() {
        return Engine.session().ai().applier();
    }

    /** The path finder keeps one scratch per share; job i of a step always runs on share i % PATH_SHARES. */
    public static final int PATH_SHARES = PathWorkers.SHARES;

    /** True with a session: a path is never handed to the game's own path thread, which answers at no fixed step. */
    public static boolean pathsNow() {
        return Engine.session() != null;
    }

    /**
     * True in the step on the simulation thread: the job waits in {@code q} for the step's end, where the path threads
     * run it before the hash (SOS-37). Outside the step the caller computes the path at once.
     */
    public static boolean queuePath(PathQueue q) {
        Session s = Engine.session();
        if (s == null || !s.streams.isSimulationThread()) {
            return false;
        }
        if (s.pathQueue != null && s.pathQueue != q) {
            throw new IllegalStateException("SOSDET: a second path queue in step " + s.clock.step());
        }
        s.pathQueue = q;
        return true;
    }

    /** A path computed at once, outside the step. */
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
        s.worldPrompt = null; // a load builds a new view: no prompt is open
        s.worldPromptClose = null;
        s.worldPromptTimeout = null;
        s.worldPromptArmies = Session.NO_ARMIES;
        s.worldPromptOther = -1;
        s.worldPromptOtherNext = -1;
        s.worldPromptSally = -1;
        s.worldPromptSallyNext = -1;
        s.worldPromptReady = 0;
        s.pvpEngageAt = -1;
        s.promptTimer.close();
        s.stepPrompts.clear(); // the load's questions are not asked again
        s.stepPromptShown = -1;
        s.bannerDraft.reset(); // the load's banners are every PC's: no draft or Accept of this PC's is over them (SOS-78)
        if (s.plans().inGame()) { // unit tests have no game to reset
            WorldStatics.reset();
            s.dropGameObjects();
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
        if (s.plans().inGame()) {
            s.dropGameObjects(); // anything made while loading saw a game half built (SOS-46)
            if (s.ownCity && s.clock.step() > 0) {
                s.checkPcHumans(); // a load in the run (a resync, a battle's end): its humans must still match the PCs
            }
        }
        if (s.loadSeed != 0) {
            s.streams.leave();
        }
        if (s.resyncDraws >= 0) {
            s.streams.drawsSinceLoad(s.resyncDraws);
            s.resyncDraws = -1;
            if (s.viewLoading) {
                s.viewLoadEnd(); // the world's move remainders back, the view's hashes from here (SOS-82)
            }
            if (s.viewBackLoading) {
                s.viewBackWorld(); // the viewer's own city is back: the world it ran goes into it (SOS-82)
            }
            if (s.localBackLoading) {
                s.localBackWorld(); // a local battle's PC: its city is back, the world it ran goes in (SOS-93)
                GAME.SPEED.speedSet(s.seenSpeed); // the save's speed isn't the player's: no change to send
            }
            if (s.plans().inGame()) {
                // a map battle's end or a resync: the session goes on at this step, with no "click to continue" (SOS-41)
                VIEW.inters().load.deactivate();
            }
        }
        for (Runnable r : s.afterLoads) {
            r.run(); // the new game is set: the mod puts its per-update actions and windows in it again (plan 0006)
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
        if (s != null && s.streams.isSimulationThread()) {
            // a local battle's world part runs as on every other PC, where no battle runs (SOS-93)
            return battleRunning && !(s.part == StepParts.WORLD_PART && s.localHere() != null);
        }
        return localViewOpen;
    }

    /**
     * A frame's game update took this long (for the timing measurements; kept per session). Its steps are over: this
     * PC's room plan goes back for the UI and the renderer (ADR 0003), before the queued player actions run.
     */
    public static void frameUpdated(long nanos) {
        Session s = Engine.session();
        if (s == null) {
            Engine.vanillaFrameUpdated(nanos);
        }
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

    /** This PC's player's banner draft (SOS-32), for the profile's banner editor. */
    public static BannerDraft bannerDraft() {
        return Engine.session().bannerDraft;
    }

    /**
     * The world step opened the world-battle prompt: {@code decides} is the battle, siege or result it decides, and
     * {@code close} hides it. Kept for the decision command (SOS-35, {@code cmd.WorldBattles}). {@code timeout} is the
     * answer given when nobody answers in time, and {@code armies} wait in place until then (SOS-45).
     */
    public static boolean worldPromptOpened(Object decides, Runnable close, Runnable timeout, int[] armies) {
        Session s = Engine.session();
        if (s == null) {
            return true;
        }
        s.worldPrompt = decides;
        s.worldPromptClose = close;
        s.worldPromptTimeout = timeout;
        s.worldPromptArmies = armies;
        // a battle's result asks the battle's human on every PC; it opens outside the world part (plan 0006)
        s.worldPromptHuman = s.battle != null ? s.battle.human
                : s.part == StepParts.WORLD_PART ? s.currentHuman : s.cityHuman();
        if (s.battle != null && s.battle.other >= 0 && decides instanceof world.battle.spec.WBattleResult) {
            // between two humans the winner chooses (SOS-83): army 0's human on a victory, else the other human
            world.battle.spec.WBattleResult r = (world.battle.spec.WBattleResult) decides;
            boolean army0Won = r.result == world.battle.spec.BATTLE_RESULT.VICTORY;
            s.worldPromptHuman = army0Won ? s.battle.human : s.battle.other;
            s.battleOutcome = new BattleOutcome(s.clock.step(), army0Won ? s.battle.human : s.battle.other,
                    army0Won ? s.battle.other : s.battle.human, r.result == world.battle.spec.BATTLE_RESULT.RETREAT
                            || s.battle.army1Retreat, army0Won ? r.player.losses() : r.enemy.losses(),
                    army0Won ? r.enemy.losses() : r.player.losses());
        }
        s.worldPromptOther = s.worldPromptOtherNext;
        s.worldPromptOtherNext = -1;
        s.worldPromptSally = s.worldPromptSallyNext;
        s.worldPromptSallyNext = -1;
        s.worldPromptReady = 0;
        s.pvpEngageAt = -1;
        // the prompt ends a view (SOS-82): both humans' between two humans (SOS-83)
        s.endView(s.worldPromptHuman, s.clock.step(), View.GAME, "A battle prompt asks for you.");
        if (s.worldPromptOther >= 0) {
            s.endView(s.worldPromptOther, s.clock.step(), View.GAME, "A battle prompt asks for you.");
        }
        s.promptTimer.open();
        return s.worldPromptHuman == s.localHuman || s.worldPromptOther == s.localHuman;
    }

    /**
     * In the world part, just before a battle between two humans' armies opens its prompt ({@code Resolver}): the
     * prompt asks {@code other} too, the defender, besides the current human, the attacker (SOS-83).
     */
    public static void worldPromptAlsoAsks(int other) {
        Session s = Engine.session();
        if (s != null) {
            s.worldPromptOtherNext = other;
        }
    }

    /**
     * In the world part, just before a human's garrison is asked to sally ({@code Resolver.besige}): the besieger is the
     * human {@code besieger}, so the sally is a battle between two humans, built by the defender's PC (SOS-83).
     */
    public static void worldPromptSallyAgainst(int besieger) {
        Session s = Engine.session();
        if (s != null) {
            s.worldPromptSallyNext = besieger;
        }
    }

    /** The besieging human the open sally prompt is against, a battle between two humans; -1 for none (SOS-83). */
    public static int worldPromptSally() {
        Session s = Engine.session();
        return s == null || s.worldPrompt == null ? -1 : s.worldPromptSally;
    }

    /** Inside the Start command: true when the battle may start; between two humans once both pressed it (SOS-83). */
    public static boolean battleDeployReady() {
        Session s = Engine.session();
        return s == null || s.deployReady(s.ai().applyingPc());
    }

    /** The other human the open world-battle prompt asks, between two humans; -1 for none (SOS-83). */
    public static int worldPromptOther() {
        Session s = Engine.session();
        return s == null ? -1 : s.worldPromptOther;
    }

    /** Inside the Retreat command: the army it retreats (0 or 1), or -1 when the PC giving it commands neither (SOS-83). */
    public static int battleRetreatSide() {
        Session s = Engine.session();
        return s == null ? 0 : s.retreatSide(s.ai().applyingPc());
    }

    /** In the battle's result, built inside the step: army 1 retreated, in a battle between two humans (SOS-83). */
    public static boolean battleArmy1Retreats() {
        Session s = Engine.session();
        return s != null && s.battle != null && s.battle.army1Retreat;
    }

    /**
     * In the result panel of a battle between two humans: true when {@code army0Lost}, so the second army's human won and
     * answers it, seeing the result from their own side (SOS-83).
     */
    public static boolean resultForArmy1(boolean army0Lost) {
        return army0Lost && battleBetweenHumans();
    }

    /**
     * The battle map's victory or defeat panel, opened inside the step with army 0's {@code title}: on the PC that
     * commands army 1 in a battle between two humans, the other one, as that player won or lost. Display only (SOS-83).
     */
    public static CharSequence concludeTitle(CharSequence title, boolean army0Won, CharSequence victory, CharSequence defeat) {
        Session s = Engine.session();
        if (s == null || s.battle == null || s.battle.other < 0 || s.ai().localPc() != s.pcOf(s.battle.other)) {
            return title;
        }
        return army0Won ? defeat : victory;
    }

    /** True while a battle between two humans' armies runs, from Engage to the return (SOS-83). */
    public static boolean battleBetweenHumans() {
        Session s = Engine.session();
        return s != null && s.battle != null && s.battle.other >= 0;
    }

    /**
     * In a battle between two humans, loaded: when {@code f} is army 1's faction ({@code FACTIONS.otherFaction()}, an
     * NPC in vanilla), the other human, whose reported values army 1 fights with; otherwise null (SOS-83).
     */
    public static Faction battleArmy1Human(Object f) {
        Session s = Engine.session();
        if (s == null || s.battle == null || s.battle.otherReport == null || s.battle.awaiting || f != FACTIONS.otherFaction()) {
            return null;
        }
        return FACTIONS.getByIndex(s.battle.other);
    }

    /**
     * In the battle's placer: whether army 1 may stand on tile {@code tx, ty} while deploying, in a battle between two
     * humans: inside army 0's deployment area turned about the map's centre (owner, 2026-10-27; SOS-83).
     */
    public static boolean deployMirrorHolds(snake2d.util.datatypes.RECTANGLE bounds, int tx, int ty) {
        return bounds.holdsPoint(2 * settlement.main.SETT.TILE_BOUNDS.cX() - tx, 2 * settlement.main.SETT.TILE_BOUNDS.cY() - ty);
    }

    /** True while the open world-battle prompt is a battle between two humans' armies: Fight only (SOS-83). */
    public static boolean worldPromptBetweenHumans() {
        Session s = Engine.session();
        return s != null && s.worldPrompt != null && s.worldPromptOther >= 0;
    }

    /**
     * Inside the step: the current human pressed Fight in a battle between two humans. When both have, the battle
     * engages at the next step, which the hold before battles sees coming (SOS-70, SOS-83).
     */
    public static void worldPromptFight() {
        Session s = Engine.session();
        s.worldPromptReady |= s.currentHuman == s.worldPromptHuman ? 1 : 2;
        if (s.worldPromptReady == 3) {
            worldPromptEngageNext();
        }
    }

    /** Inside the step: the battle between two humans engages at the next step, answered or not (SOS-83). */
    public static void worldPromptEngageNext() {
        Session s = Engine.session();
        if (s.pvpEngageAt < 0) {
            s.pvpEngageAt = s.clock.step() + 1;
            s.promptTimer.close();
        }
    }

    /**
     * The world-battle prompt's "already open" test: with a session the engine's, the same on every PC, since only the
     * asked human's PC shows the dialog (SOS-46); without one the dialog's own.
     */
    public static boolean worldPromptBlocked(boolean dialogOpen) {
        Session s = Engine.session();
        return s == null ? dialogOpen : s.worldPrompt != null;
    }

    /** True when the open world-battle prompt asks this PC's human (the rig answers only those). */
    public static boolean worldPromptHere() {
        Session s = Engine.session();
        return s == null || s.worldPrompt == null || s.worldPromptHuman == s.localHuman || s.worldPromptOther == s.localHuman;
    }

    /** Inside a command: true when it acts for the human the open world-battle prompt asks (SOS-46). */
    public static boolean worldPromptAnswerer() {
        Session s = Engine.session();
        return s == null || s.currentHuman == s.worldPromptHuman || (s.worldPromptOther >= 0 && s.currentHuman == s.worldPromptOther);
    }

    /** True in a session's step: a yes/no the simulation asks is kept, and answered by command (cmd.StepPrompts). */
    public static boolean stepPromptsKept() {
        return Engine.session() != null && !outsideStep();
    }

    /**
     * The step asks a yes/no: keeps its actions; returns its key (the step and its order in the step), the same on every
     * PC for a world question. A city question (asked in the city part, on its own PC only) counts apart, keys 32 to 63
     * of its step. Returns -1 when this PC doesn't show it: a world question to another PC's human (SOS-46).
     */
    public static long stepPromptOpened(Object yes, Object no) {
        Session s = Engine.session();
        long step = Engine.step();
        if (s.stepPromptStep != step) {
            s.stepPromptStep = step;
            s.stepPromptsInStep = 0;
            s.stepCityPromptsInStep = 0;
        }
        boolean world = s.part == StepParts.WORLD_PART;
        int n = world ? s.stepPromptsInStep++ : 32 + s.stepCityPromptsInStep++;
        if (n >= (world ? 32 : 64)) {
            throw new IllegalStateException("SOSDET: more than 32 " + (world ? "world" : "city") + " questions in step " + step);
        }
        long key = step * 64 + n;
        int human = world ? s.currentHuman : s.cityHuman(); // a viewed city's questions are its owner's (SOS-82)
        if (world) {
            s.endView(human, step, View.GAME, "A question asks for you."); // a world question ends its human's view
        }
        s.stepPrompts.put(key, new Object[] {yes, no, human});
        if (human != s.localHuman) {
            return -1;
        }
        s.stepPromptShown = key; // the game's dialog shows one question: the last one asked
        return key;
    }

    /** True when the question's key is a world question's (asked in the world part, kept on every PC). */
    public static boolean stepPromptWorld(long key) {
        return key % 64 < 32;
    }

    /** The kept question this PC's dialog shows, or -1. */
    public static long stepPromptShown() {
        Session s = Engine.session();
        return s == null ? -1 : s.stepPromptShown;
    }

    /** This PC's dialog no longer shows that question: its player answered it, or another PC's answer applied. */
    public static void stepPromptHidden(long key) {
        Session s = Engine.session();
        if (s != null && s.stepPromptShown == key) {
            s.stepPromptShown = -1;
        }
    }

    /** The oldest kept question to this PC's human, or -1 (the rig answers it). */
    public static long stepPromptOldest() {
        Session s = Engine.session();
        long oldest = -1;
        if (s != null) {
            for (java.util.Map.Entry<Long, Object[]> e : s.stepPrompts.entrySet()) {
                long key = e.getKey();
                if ((Integer) e.getValue()[2] == s.localHuman && (oldest < 0 || key < oldest)) {
                    oldest = key;
                }
            }
        }
        return oldest;
    }

    /**
     * The answer command takes the kept question: the chosen action, or null when it is gone or has none. A world
     * question's answer applies only from the human it asks (inside the world command, the current human); another's
     * is skipped and the question stays (SOS-46).
     */
    public static Object stepPromptTake(long key, boolean yes) {
        Session s = Engine.session();
        Object[] actions = s == null ? null : s.stepPrompts.get(key);
        if (actions == null || stepPromptWorld(key) && (Integer) actions[2] != s.currentHuman) {
            return null;
        }
        s.stepPrompts.remove(key);
        return actions[yes ? 0 : 1];
    }

    // ------------------------------------------------------------ map battles (SOS-32)

    /**
     * A map battle ends inside the step, and the game saved before it loads next (the loader runs between frames): the
     * steps hold until then, and the load continues at this step, on every PC alike. A restart loads the battle as
     * generated the same way (SOS-41).
     */
    public static void battleLeaving() {
        Session s = Engine.session();
        if (s != null) {
            s.battleLeaving = true;
            GAME.BATTLE_THREADS().pause(); // the battle being replaced keeps no job running (the host's general)
        }
    }

    /**
     * After the load that ends a map battle: the result prompt opens, and the survivors and captives are counted, on a
     * stream seeded from the seed and the step, so every PC draws the same.
     */
    public static void battleReturned(Runnable afterExit) {
        Session s = Engine.session();
        if (s == null) {
            afterExit.run();
            return;
        }
        s.streams.enter();
        s.streams.reseed(s.battleSeed(s.clock.step()));
        try {
            afterExit.run();
        } finally {
            s.streams.leave();
        }
        if (s.battle != null && !s.battle.awaiting) {
            s.battle = null; // every PC is back in its own city: the reports go on (plan 0006)
            Engine.feedHeldReports(s);
        }
    }

    /**
     * Own-city mode, the world-battle prompt's Engage, inside the world part on every PC (plan 0006): the battle of
     * {@code human}. Every PC saves its own city and generates a battle, on a stream of its own: only the battle PC's
     * is kept, which every PC then loads ({@link Engine#battleIn}). The world stream goes on alike after it: reseeded,
     * its draw count put back. No step runs past this one until the battle is loaded.
     */
    public static void ownCityEngage(int human, Runnable engage) {
        ownCityEngage(human, -1, engage);
    }

    /** {@link #ownCityEngage(int, Runnable)} for a battle between two humans: {@code other} commands army 1 (SOS-83). */
    public static void ownCityEngage(int human, int other, Runnable engage) {
        Session s = Engine.session();
        long step = s.clock.step();
        if (s.localBattles && other < 0) {
            // against NPCs: on the human's PC only, the world running on (SOS-93). Every PC runs vanilla's Engage, which
            // keeps the two sides for the result; the battle PC's generation waits for the freeze step
            LocalBattle b = s.localEngage(step, human);
            long worldDraws = s.streams.worldDraws();
            s.engaging = b;
            s.streams.reseed(s.battleSeed(step));
            try {
                engage.run();
            } finally {
                s.engaging = null;
                s.streams.reseed(s.afterEngageSeed(step));
                s.streams.worldDraws(worldDraws);
            }
            return;
        }
        OwnBattle b = new OwnBattle(step, human, s.pcOf(human), other, s.streams.worldDraws());
        b.otherReport = other >= 0 ? s.reports(other).current() : null; // a battle load clears the reports (SOS-83)
        s.battle = b;
        s.battleEndsViews(step); // every PC loads the battle: no view lives through it (SOS-82)
        s.streams.reseed(s.battleSeed(step));
        try {
            engage.run();
        } finally {
            s.streams.reseed(s.afterEngageSeed(step));
            s.streams.worldDraws(b.worldDraws);
        }
    }

    /** The human the open world-battle prompt asks (SOS-46); read before a decision closes it. */
    public static int worldPromptHuman() {
        Session s = Engine.session();
        return s == null ? 0 : s.worldPromptHuman;
    }

    /** True while the engine itself loads a battle save ({@link Engine#battleIn}), which no hook turns into a command. */
    public static boolean battleLoadingNow() {
        Session s = Engine.session();
        return s != null && s.battleLoading;
    }

    /** True in own-city mode from a battle's Engage until every PC is back in its city (plan 0006). */
    public static boolean ownCityBattle() {
        Session s = Engine.session();
        return s != null && s.battle != null;
    }

    /** True once a map battle has ended in this step: no other end, retreat or close, applies after it. */
    public static boolean battleOver() {
        Session s = Engine.session();
        return s != null && s.battleLeaving;
    }

    /** The step opened the battle's victory or defeat panel: its close, kept for the conclude command. */
    public static void battleConcludeOpened(Runnable close) {
        Session s = Engine.session();
        if (s != null) {
            s.battleConclude = close;
        }
    }

    /** True while the battle's victory or defeat panel is open and its Close not yet applied (the rig answers it). */
    public static boolean battleConcludeOpen() {
        Session s = Engine.session();
        return s != null && s.battleConclude != null;
    }

    /** The conclude command takes the open panel's close, or null when none is open. */
    public static Runnable battleConcludeTake() {
        Session s = Engine.session();
        Runnable close = s == null ? null : s.battleConclude;
        if (s != null) {
            s.battleConclude = null;
        }
        return close;
    }

    /** What the open world-battle prompt decides, or null. */
    public static Object worldPrompt() {
        Session s = Engine.session();
        return s == null ? null : s.worldPrompt;
    }

    /** A decision command closes the world-battle prompt, inside the step on every PC. */
    public static void worldPromptClose() {
        Session s = Engine.session();
        if (s == null || s.worldPrompt == null) {
            return;
        }
        Runnable close = s.worldPromptClose;
        s.worldPrompt = null;
        s.worldPromptClose = null;
        s.worldPromptTimeout = null;
        s.closedPromptArmies = s.worldPromptArmies; // a local battle's Engage keeps them waiting (SOS-93)
        s.worldPromptArmies = Session.NO_ARMIES;
        s.worldPromptOther = -1;
        s.worldPromptOtherNext = -1;
        s.worldPromptSally = -1;
        s.worldPromptSallyNext = -1;
        s.worldPromptReady = 0;
        s.pvpEngageAt = -1;
        s.promptTimer.close();
        close.run();
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

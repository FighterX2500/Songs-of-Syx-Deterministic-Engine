package sosdet;

import sosdet.ai.AiEntry;
import sosdet.ai.AiLog;
import sosdet.ai.AiMode;
import sosdet.ai.Commanders;
import java.nio.file.Path;
import java.util.List;

import sosdet.game.GameAi;
import sosdet.game.GameHash;
import sosdet.game.GameHumans;
import sosdet.game.GameOwnCity;
import sosdet.game.GameResync;
import sosdet.game.GameWorldLoad;
import sosdet.game.GameWorldSetup;
import sosdet.game.OwnArmy;
import sosdet.game.cmd.HumanDeals;
import sosdet.game.deal.DealBook;
import sosdet.report.CityReport;
import sosdet.report.Field;
import sosdet.hash.Desync;
import sosdet.hash.HashLog;
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
        if (s.humans().length > 1) {
            throw new IllegalStateException("SOSDET: reportsFrom with " + s.humans().length + " humans; each PC runs its own"
                    + " human's city (ADR 0004)");
        }
        s.reportsFrom = pc;
    }

    /**
     * More of PC {@code pc}'s reports, in any pieces: its human's city (ADR 0004), or on a follower the source's. A
     * repeated report changes nothing. Feed them after the load.
     */
    public static void reportsIn(int pc, byte[] bytes) {
        Session s = required();
        if (!s.ownCity) {
            throw new IllegalStateException("SOSDET: reportsIn in co-op; every PC takes the same reports itself");
        }
        if (s.reportsFrom >= 0 && pc != s.reportsFrom) {
            throw new IllegalArgumentException("SOSDET: reports from PC " + pc + ", the world reads PC " + s.reportsFrom
                    + "'s city");
        }
        if (pc < 0 || pc >= s.pcHumans.length) {
            throw new IllegalArgumentException("SOSDET: reports from PC " + pc + ", not 0 to " + (s.pcHumans.length - 1));
        }
        if (pc == s.ai().localPc()) {
            throw new IllegalArgumentException("SOSDET: reports from PC " + pc + ", this PC itself");
        }
        if (s.loading) {
            throw new IllegalStateException("SOSDET: reports from PC " + pc + " while the game loads; feed them after the load");
        }
        if (s.battle != null) {
            // the battle's loads clear the feeds: kept until this PC is back in its own city (plan 0006)
            s.heldReports.add(new Object[] {pc, bytes.clone()});
            return;
        }
        readReports(s, pc, bytes, -1);
    }

    /** Another PC's report bytes into its feed; reports taken before {@code from} are dropped. */
    private static void readReports(Session s, int pc, byte[] bytes, long from) {
        for (byte[] r : s.feedIn(pc).read(bytes)) {
            CityReport c = CityReport.fromBytes(r, s.city().layout());
            if (s.reportsFrom < 0 && c.faction != s.pcHumans[pc]) {
                throw new IllegalArgumentException("SOSDET: PC " + pc + " sent a city report of faction " + c.faction
                        + ", its human is slot " + s.pcHumans[pc]);
            }
            if (c.takenAt >= from) {
                s.reportReceived(c);
            }
        }
    }

    /**
     * Back in its own city after a battle: the reports that came in during it. Those taken before the return belong to
     * the cities before the battle, which the return's load replaced (plan 0006).
     */
    static void feedHeldReports(Session s) {
        java.util.List<Object[]> held = new java.util.ArrayList<>(s.heldReports);
        s.heldReports.clear();
        for (Object[] h : held) {
            readReports(s, (Integer) h[0], (byte[]) h[1], s.clock.step());
        }
    }

    // ------------------------------------------------------------ battles with own cities (plan 0006, SOS-61)

    /**
     * Runs {@code action} after every load of a whole game in this session: a battle's start and end, a resync. Each
     * such load builds a new game, without the per-update actions and windows the mod added to the old one; the action
     * adds them again. Game thread, outside the step.
     */
    public static void afterEachLoad(Runnable action) {
        required().afterLoads.add(action);
    }

    /**
     * Own-city mode, off by default: with it on, every PC holds before a step whose commands engage a battle on the
     * battle map, so the mod can save the campaign there, before the battle (mod spec §11, SOS-70). The mod lets the
     * step run with {@link #battleGo}.
     */
    public static void battleHold(boolean on) {
        Session s = required();
        if (!s.ownCity) {
            throw new IllegalStateException("SOSDET: the hold before a battle is for own-city mode");
        }
        s.battleHold = on;
    }

    /**
     * With {@link #battleHold} on: the step a battle's Engage applies at while every PC holds before it, the same on
     * every PC; this PC's last step run is the one before it. -1 when there is none.
     */
    public static long battleAhead() {
        Session s = required();
        return s.battleAhead > s.clock.step() && s.battleAhead != s.battleGo ? s.battleAhead : -1;
    }

    /** Every PC, between steps while it holds before an Engage: the step runs, and the battle begins as before. */
    public static void battleGo() {
        Session s = required();
        long ahead = battleAhead();
        if (ahead < 0) {
            throw new IllegalStateException("SOSDET: no battle ahead to let through at step " + s.clock.step());
        }
        s.battleGo = ahead;
    }

    /**
     * Own-city mode: the battle on the battle map, from its Engage step until every PC is back in its own city, or
     * null. While {@link OwnBattle#awaiting()}, every PC holds at {@link OwnBattle#step} until it has loaded the battle
     * PC's battle: the mod carries {@link #battleOut} from that PC to every PC's {@link #battleIn}.
     */
    public static OwnBattle battle() {
        return required().battle;
    }

    /** Once, as a battle between two humans' result prompt opens: who won and lost, and each side's losses; else null. */
    public static BattleOutcome battleOutcomeTake() {
        Session s = required();
        BattleOutcome o = s.battleOutcome;
        s.battleOutcome = null;
        return o;
    }

    /**
     * Between steps, on the battle PC (its human's): the battle it generated at Engage, as save bytes for every PC's
     * {@link #battleIn}, once; null on every other PC, before Engage, and after the first call.
     */
    public static byte[] battleOut() {
        Session s = required();
        OwnBattle b = s.battle;
        if (b == null || !b.awaiting || b.sent || b.pc != s.ai().localPc() || s.streams.inStep()) {
            return null;
        }
        b.sent = true;
        return sosdet.game.cmd.MapBattles.battleSave();
    }

    /**
     * Every PC, the battle PC too, between steps at the Engage step: loads the battle PC's battle, as Restart reloads a
     * battle (SOS-41). The session goes on at this step with the battle running on every PC alike.
     */
    public static void battleIn(byte[] bytes) {
        Session s = required();
        OwnBattle b = s.battle;
        if (b == null || !b.awaiting) {
            throw new IllegalStateException("SOSDET: a battle save with no battle awaiting one");
        }
        if (s.streams.inStep()) {
            throw new IllegalStateException("SOSDET: a battle is loaded between steps, not inside one");
        }
        if (s.clock.step() != b.step) {
            throw new IllegalStateException("SOSDET: the battle of step " + b.step + " loaded at step " + s.clock.step());
        }
        s.battleLeaving = true; // the load goes on at this step, as a battle's restart does
        s.battleLoading = true;
        try {
            sosdet.game.cmd.MapBattles.loadBattle(bytes);
        } finally {
            s.battleLoading = false;
        }
    }

    // ------------------------------------------------------------ city views (plan 0013, SOS-82)

    /**
     * Host, own-city mode, between steps: the human in {@code viewer} views {@code owner}'s city (mod spec §10). A view
     * entry in the log applies at its stamped step on every PC; the viewer freezes at the next report step, F. There
     * every PC cancels the view alike when it can't start ({@link View#reason}); otherwise the owner's PC holds after F
     * until the mod takes {@link #viewOut}, and the viewer's PC holds after F until the mod gives it to
     * {@link #viewIn}. No PC waits for the viewer's reports or its pace while it views. The mod asks the owner first.
     */
    public static void viewBegin(int viewer, int owner) {
        Session s = required();
        if (!s.ownCity || s.ai().mode() != AiMode.HOST) {
            throw new IllegalStateException("SOSDET: the host starts a view, in own-city mode (mode " + s.ai().mode() + ")");
        }
        if (s.streams.inStep()) {
            throw new IllegalStateException("SOSDET: a view is started between steps, not inside one");
        }
        if (viewer == owner || !s.isHuman(viewer) || !s.isHuman(owner)) {
            throw new IllegalArgumentException("SOSDET: human " + viewer + " can't view human " + owner + "'s city");
        }
        View v = s.views[viewer];
        if (v != null && v.state != View.CANCELLED && v.state != View.ENDED) {
            throw new IllegalStateException("SOSDET: human " + viewer + " has a view already: " + v);
        }
        s.ai().captureView(AiEntry.ints(viewer, owner, s.pcOf(owner)));
    }

    /** Host, between steps: the viewer closes the view of the human in {@code viewer} ({@link #viewEnd(int, int)}). */
    public static void viewEnd(int viewer) {
        viewEnd(viewer, View.CLOSED);
    }

    /**
     * Host, between steps: the view of the human in {@code viewer} ends, for {@code why}: {@link View#CLOSED}, or a
     * limit the viewer's PC hit ({@link #viewLimit}: {@link View#NOT_CAUGHT_UP}, {@link View#FELL_BEHIND}), or
     * {@link View#DESYNC}. An end entry in the log applies at its stamped step on every PC, with its reason
     * ({@link View#why}, {@link View#reason}), and the viewer thaws at the next report step: its PC loads its own city
     * back just before, and every PC reads its reports again from there. A view not yet frozen is cancelled instead.
     */
    public static void viewEnd(int viewer, int why) {
        Session s = required();
        if (s.ai().mode() != AiMode.HOST) {
            throw new IllegalStateException("SOSDET: the host ends a view (mode " + s.ai().mode() + ")");
        }
        if (s.streams.inStep()) {
            throw new IllegalStateException("SOSDET: a view is ended between steps, not inside one");
        }
        if (why < View.CLOSED || why > View.LAST_ASKED) {
            throw new IllegalArgumentException("SOSDET: a view's end for reason " + why + ", not " + View.CLOSED
                    + " to " + View.LAST_ASKED);
        }
        s.ai().captureView(AiEntry.ints(viewer, -1, why));
    }

    /**
     * The viewer's PC, between steps: the limit it hit ({@link View#NOT_CAUGHT_UP}: not caught up with the owner's city
     * a minute after the view load; {@link View#FELL_BEHIND}: more than 60 steps behind the host for a minute after
     * that), for the mod to send to the host's {@link #viewEnd(int, int)}; {@link View#OPEN} while none is hit, and
     * once the view's end is in the log. Measured on this PC's clock (mod spec §10, plan 0013 M2).
     */
    public static int viewLimit() {
        Session s = required();
        View v = s.viewing();
        return v == null || v.thaw >= 0 ? View.OPEN : s.viewLimit.hit();
    }

    /** The viewer's PC: seconds from its view load to catching up with the owner's city (playtest log), or -1. */
    public static double viewCaughtUpSeconds() {
        return required().viewLimit.caughtUpSeconds();
    }

    /**
     * Test tooling (the rig's {@code --view-limits}): other limits than the spec's minute and 60 steps, so a short run
     * reaches each one. Never called by the mod.
     */
    public static void viewLimits(double catchUpSeconds, double behindSeconds, int behindSteps) {
        required().viewLimit.limits(catchUpSeconds, behindSeconds, behindSteps);
    }

    /** This PC's view as the viewer while it runs the owner's city, or null: its own city is the one it runs. */
    public static View viewing() {
        return required().viewing();
    }

    /**
     * Host, between steps: why a view of {@code owner}'s city by the human in {@code viewer} would be cancelled if it
     * began now, as a sentence for the player, or null when it wouldn't: the mod refuses such a request at once (mod
     * spec §10). A view of that viewer not over yet refuses too.
     */
    public static String viewRefusal(int viewer, int owner) {
        Session s = required();
        if (!s.ownCity) {
            return "Views need own-city mode.";
        }
        if (viewer < 0 || viewer >= Session.SLOTS || owner < 0 || owner >= Session.SLOTS) {
            return "That city can't be viewed.";
        }
        View v = s.views[viewer];
        if (v != null && v.state != View.CANCELLED && v.state != View.ENDED) {
            return "You are viewing a city already.";
        }
        View o = s.views[owner];
        if (o != null && o.state == View.SCHEDULED) {
            return "Its player is about to view another city.";
        }
        return s.viewRefusal(viewer, owner, null);
    }

    /** The view of the human in {@code viewer}, the same on every PC: scheduled, loading, running or cancelled; or null. */
    public static View view(int viewer) {
        return required().views[viewer];
    }

    /**
     * The owner's PC, between steps at a view's freeze step: saves its game and returns it with its city's engine state,
     * for the mod to send to the viewer's {@link #viewIn}; then this PC runs on. Null when no view waits for its save.
     */
    public static byte[] viewOut() {
        Session s = required();
        if (s.streams.inStep()) {
            return null;
        }
        for (View v : s.views) {
            if (v == null || !v.frozen() || v.owner != s.localHuman || v.sent || s.clock.step() != v.freeze) {
                continue;
            }
            byte[] save = sosdet.game.GameView.saveForView();
            byte[] state = ViewCity.cityState(s);
            double[] moves = GameWorldLoad.unsaved();
            v.sent = true;
            // the owner loads its save too: two PCs that load one save run its city alike, a loaded and an unloaded
            // game don't (plan 0013, rig run V82D)
            s.viewLoadStart(state, moves, v.viewer);
            sosdet.game.GameView.loadSaved();
            return ViewCity.toBytes(v.freeze, state, moves, save);
        }
        return null;
    }

    /**
     * The viewer's PC, between steps at its view's freeze step: saves its own game and keeps its city's engine state,
     * then loads the owner's city from {@link #viewOut}'s bytes. The load keeps the session; from then on this PC runs
     * the owner's city, in step with the owner's, and its player only watches.
     *
     * <p>A room plan the viewer was still drawing (the placer's, never built) is gone: no save holds it, and the load
     * resets the placer, so the own city that comes back at the thaw has none (plan 0013 M2).
     */
    public static void viewIn(byte[] bytes) {
        Session s = required();
        View v = s.views[s.localHuman];
        if (v == null || v.state != View.LOADING) {
            throw new IllegalStateException("SOSDET: a view's city with no view loading on this PC (" + v + ")");
        }
        if (s.streams.inStep()) {
            throw new IllegalStateException("SOSDET: a view's city is loaded between steps, not inside one");
        }
        ViewCity c = ViewCity.fromBytes(bytes);
        if (c.step != v.freeze || s.clock.step() != v.freeze) {
            throw new IllegalStateException("SOSDET: the city of step " + c.step + " for the view from step " + v.freeze
                    + ", loaded at step " + s.clock.step());
        }
        sosdet.game.GameView.saveOwn();
        s.ownCityState = ViewCity.cityState(s);
        s.ai().watchOnly(true);
        s.viewLoadStart(c.state, c.moves, s.localHuman);
        sosdet.game.GameView.loadView(c.save);
    }

    /**
     * Test tooling, for the rig's state dumps only (never the mod): between steps, this PC saves its own game and loads
     * it back as a PC outside a view does, keeping the session, and runs {@code loaded} once it is set, before any
     * step; {@link #viewLoading()} is true until then. It found what a load resets (SOS-82, plan 0013 §3).
     */
    public static void reloadOwnForDebug(Runnable loaded) {
        Session s = required();
        if (s.streams.inStep() || s.viewLoading) {
            throw new IllegalStateException("SOSDET: a debug reload is between steps, with no other view load running");
        }
        s.viewLoadDone = loaded;
        s.viewLoadStart(ViewCity.cityState(s), GameWorldLoad.unsaved(), -1);
        sosdet.game.GameView.reloadOwn();
    }

    /**
     * Test tooling, for the rig's state dumps only (never the mod): runs {@code r} once, between steps, right before
     * step {@code step} + 1 begins (SOS-82).
     */
    public static void debugAt(long step, Runnable r) {
        Session s = required();
        s.debugAtStep = step;
        s.debugAt = r;
    }

    /** True while a view load runs on this PC: the owner's save, the viewer's, or this PC's own. */
    public static boolean viewLoading() {
        Session s = Engine.session();
        return s != null && s.viewLoading;
    }

    /**
     * How long this PC's last view load took, in ms, its own save included for a PC outside the view: the pause a
     * view's opening or closing costs it (playtest log).
     */
    public static double lastViewLoadMs() {
        return required().lastViewLoadMs;
    }

    /** The owner's PC: its city's view hashes for the viewer in {@code viewer} since the last call, for that viewer. */
    public static byte[] viewHashOut(int viewer) {
        HashLog h = required().viewHashes[viewer];
        return h == null ? new byte[0] : h.out();
    }

    /**
     * The viewer's PC: more of the owner's view hashes, in any pieces; each is compared as this PC reaches its step.
     * Hashes that come in after the view stopped hashing (its end, a battle) are dropped.
     */
    public static void viewHashIn(byte[] bytes) {
        Session s = required();
        View v = s.views[s.localHuman];
        if (v == null) {
            throw new IllegalStateException("SOSDET: view hashes with no view of this PC's human");
        }
        HashLog h = s.viewHashes[s.localHuman];
        if (h != null) {
            h.in(v.ownerPc, bytes);
        }
    }

    /** The viewer's PC: the first difference between its copy of the viewed city and the owner's city, or null. */
    public static Desync viewDesync() {
        Session s = required();
        HashLog h = s.viewHashes[s.localHuman];
        return h == null ? null : h.desync();
    }

    /** The viewer's PC: the view hashes compared so far with the owner's. */
    public static long viewHashesCompared() {
        Session s = required();
        HashLog h = s.viewHashes[s.localHuman];
        return h == null ? 0 : h.compared();
    }

    /** How many steps this PC is behind the host's last confirmed step (0 on the host): a viewer's catching up. */
    public static long behindHost() {
        Session s = required();
        return s.ai().mode() == AiMode.PEER ? Math.max(0, s.ai().confirmed() - s.clock.step()) : 0;
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
        for (boolean removing : s.removing) {
            if (removing) {
                throw new IllegalStateException("SOSDET: a world resync waits until the human being removed is retired (SOS-66)");
            }
        }
        long step = s.clock.step();
        GameWorldLoad.save(file);
        s.clock.runUntil(step); // the host waits for its own load too
        int[] slots = s.humans();
        byte[][] ledgers = new byte[slots.length][];
        for (int i = 0; i < slots.length; i++) {
            ledgers[i] = s.reports(slots[i]).ledgerOut(); // every PC's world keeps every human's ledger (SOS-52)
        }
        return new WorldResync(step, s.hashes.epoch() + 1, s.streams.worldDraws(), slots, ledgers).toBytes();
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
        if (!java.util.Arrays.equals(r.slots, s.humans())) {
            throw new IllegalStateException("SOSDET: a world resync of human slots " + java.util.Arrays.toString(r.slots)
                    + " loaded in a game of slots " + java.util.Arrays.toString(s.humans()));
        }
        if (s.reports(s.localHuman).current() == null) {
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
        for (int slot : r.slots) {
            s.reports(slot).ledgerIn(r.ledger(slot));
        }
        s.hashes.rewind(r.step, r.hashEpoch);
        s.endDesyncHold(r.step);
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

    // ------------------------------------------------------------ world setup (SOS-55, plan 0005)

    /**
     * Setup: why a human can't start at {@code tx, ty}, as {@code WorldGen.playerX/playerY} (the terrain, or closer than
     * {@link HumanStart#MIN_DISTANCE} tiles to one of the other humans' locations), or null. Every PC's location picker
     * asks it; needs a game with its terrain generated, no session.
     */
    public static String locationProblem(int tx, int ty, int[] otherTx, int[] otherTy) {
        String p = GameWorldSetup.terrainProblem(tx, ty);
        return p != null ? p : HumanStart.tooClose(tx, ty, otherTx, otherTy);
    }

    /**
     * Setup, on the one PC that builds the world (owner, 2026-10-25): the confirmed terrain becomes a world with a
     * capital at each human's location, NPC countries and routes around them as vanilla makes them, and this PC's city
     * at {@code humans[0]}'s capital. {@code humans[0]} is slot 0, this PC's; the others take slots 1 to N-1 in order.
     * Then this PC saves, and every PC, this one too, loads that save before step 0.
     */
    public static void buildWorld(HumanStart[] humans) {
        String p = HumanStart.problem(humans);
        if (p != null) {
            throw new IllegalArgumentException("SOSDET: " + p);
        }
        GameWorldSetup.build(humans);
    }

    /** Setup: this PC's player's banner, as {@link HumanStart} carries it to the PC that builds the world. */
    public static byte[] bannerBytes() {
        return GameWorldSetup.bannerBytes();
    }

    /**
     * The world hash now, as the hash log's world section takes it ({@code GameHash.worldHash}): the world map, every
     * faction's shared state, the raiders and the calendar; in own-city mode the humans' cities are left out. The mod
     * compares it on every PC after the setup's load, at step 0, before any hash is logged (mod SOS-56). Outside the
     * step only; with or without a session.
     */
    /**
     * Setup, on a PC whose human isn't slot 0, after the setup's save loads: makes this PC's city at its human's capital,
     * centre {@code tx, ty} (the location it picked), as vanilla's finish stage makes the player's; the save holds slot
     * 0's city. Own-city mode, at step 0, between steps. The NPCs' production boosts, which the city's generation would
     * re-roll on this PC only, stay as loaded (mod SOS-56).
     */
    public static void ownCityAt(int tx, int ty) {
        Session s = required();
        if (!s.ownCity || s.clock.step() != 0 || s.streams.isSimulationThread()) {
            throw new IllegalStateException("SOSDET: an own city is made in own-city mode, at step 0, between steps (own city "
                    + s.ownCity + ", step " + s.clock.step() + ")");
        }
        GameOwnCity.make(tx, ty);
    }

    public static long worldHash() {
        Session s = session;
        if (s != null && s.streams.isSimulationThread()) {
            throw new IllegalStateException("SOSDET: the world hash is read between steps");
        }
        return GameHash.worldHash();
    }

    /**
     * This PC's human (ADR 0004): the slot of its faction, a human in the loaded game. Outside the world part
     * {@code FACTIONS.player()} is this human, and the city part runs only its city; in the world part it is the human
     * the world is working on. Set it at step 0, on every PC; slot 0 by default.
     */
    public static void localHuman(int slot) {
        Session s = required();
        if (s.clock.step() != 0) {
            throw new IllegalStateException("SOSDET: this PC's human is set before the run (step " + s.clock.step() + ")");
        }
        for (int h : GameHumans.get().slots()) {
            if (h == slot) {
                s.localHuman = slot;
                s.pcHumans[s.ai().localPc()] = slot; // this PC's world commands act for it too
                return;
            }
        }
        throw new IllegalArgumentException("SOSDET: slot " + slot + " is not a human in this game");
    }

    /**
     * PC {@code pc}'s human (ADR 0004): its world commands act for that human, and its city reports are that human's.
     * Set it at step 0, for every PC, the same on every PC; for this PC it also sets {@link #localHuman}.
     */
    public static void pcHuman(int pc, int slot) {
        Session s = required();
        if (s.clock.step() != 0) {
            throw new IllegalStateException("SOSDET: a PC's human is set before the run (step " + s.clock.step() + ")");
        }
        if (pc < 0 || pc >= Session.SLOTS) {
            throw new IllegalArgumentException("SOSDET: PC " + pc + " is out of range");
        }
        if (!s.isHuman(slot)) {
            throw new IllegalArgumentException("SOSDET: slot " + slot + " is not a human in this game");
        }
        s.pcHumans[pc] = slot;
        if (pc == s.ai().localPc()) {
            s.localHuman = slot;
        }
    }

    /**
     * Host, own-city mode, between steps: removes the human in {@code slot}, whose PC is gone (mod spec §11, SOS-66).
     * A removal entry in the log applies at its stamped step on every PC. First a battle that human is in ends with no
     * result; then each of its armies is disbanded and every region it held is freed, as vanilla destroys a faction,
     * and it stays in its slot, retired. Until that step every PC reads the human's last report the host has and waits
     * for no later one, so feed none of that human's reports after the call.
     *
     * <p>Refused for slot 0 and while a battle waits for that human's PC's battle save, which can't come: the host
     * returns to the last save then. {@link #removeRefusal} says beforehand whether the call would be refused.
     */
    public static void removeHuman(int slot) {
        Session s = required();
        if (!s.ownCity || s.ai().mode() != AiMode.HOST) {
            throw new IllegalStateException("SOSDET: the host removes a human, in own-city mode (mode " + s.ai().mode() + ")");
        }
        if (s.streams.inStep()) {
            throw new IllegalStateException("SOSDET: a human is removed between steps, not inside one");
        }
        removable(s, slot);
        String battle = battleRefusal(s, slot);
        if (battle != null) {
            throw new IllegalStateException("SOSDET: " + battle);
        }
        int pc = s.pcOf(slot);
        long cutoff = s.receivedTakenAt[slot];
        s.removing[slot] = true;
        s.removeCutoff[slot] = cutoff;
        s.ai().captureRemove(AiEntry.ints(slot, (int) (cutoff >>> 32), (int) cutoff));
        s.pacer.drop(pc); // its PC is gone: the host's pace waits for it no more
        s.ai().pcGone(pc); // and none of its orders still to come goes into the log
    }

    /**
     * Every PC, own-city mode, at step 0 before the run: a resumed campaign starts without the human in {@code slot},
     * whose player is missing (mod spec §11, SOS-66). It is removed as {@link #removeHuman} does, in the first world
     * part, and no PC waits for its load report.
     */
    public static void removeHumanAtStart(int slot) {
        Session s = required();
        if (!s.ownCity) {
            throw new IllegalStateException("SOSDET: removing a human is for own-city mode");
        }
        if (s.clock.step() != 0) {
            throw new IllegalStateException("SOSDET: a human is removed at the start before the run (step "
                    + s.clock.step() + ")");
        }
        removable(s, slot);
        s.removing[slot] = true;
        s.removeCutoff[slot] = -1;
        s.removeDue[slot] = true;
    }

    /**
     * Host, between steps: why {@link #removeHuman} would refuse the human in {@code slot} now, as a sentence for the
     * player, or null when it would remove it (SOS-65). With {@code atStart}, why {@link #removeHumanAtStart} would.
     */
    public static String removeRefusal(int slot, boolean atStart) {
        Session s = required();
        if (!s.ownCity) {
            return "Removing a player needs own-city mode.";
        }
        if (slot == 0) {
            return "The player in slot 0 can't be removed.";
        }
        if (slot < 0 || slot >= Session.SLOTS || !s.isHuman(slot)) {
            return "Slot " + slot + " is not a player's.";
        }
        if (s.retired(slot) || s.removing[slot]) {
            return "That player is already removed.";
        }
        if (atStart) {
            return s.clock.step() == 0 ? null : "The game has already started.";
        }
        if (s.ai().mode() != AiMode.HOST) {
            return "Only the host removes a player.";
        }
        String battle = battleRefusal(s, slot);
        return battle == null ? null : Character.toUpperCase(battle.charAt(0)) + battle.substring(1) + ".";
    }

    /** Why a battle keeps the human in {@code slot} from being removed now, or null (SOS-66). */
    private static String battleRefusal(Session s, int slot) {
        OwnBattle b = s.battle;
        if (b != null && b.awaiting && b.human == slot) {
            return "the battle of step " + b.step + " waits for the battle save of slot " + slot
                    + "'s PC, which can't come; return to the last save instead";
        }
        if (s.ai().commandPendingFrom(s.pcOf(slot), sosdet.game.cmd.WorldBattles::isEngage)) {
            // that battle would wait for a battle save from the PC that is gone
            return "slot " + slot + "'s PC engaged a battle that is still to begin; return to the last save instead";
        }
        return null;
    }

    private static void removable(Session s, int slot) {
        if (slot == 0) {
            throw new IllegalArgumentException("SOSDET: slot 0 is the host's human, which is never removed");
        }
        if (slot < 0 || slot >= Session.SLOTS || !s.isHuman(slot)) {
            throw new IllegalArgumentException("SOSDET: slot " + slot + " is not a human in this game");
        }
        if (s.retired(slot) || s.removing[slot]) {
            throw new IllegalStateException("SOSDET: the human in slot " + slot + " is already removed");
        }
    }

    /** True for a slot whose human was removed ({@link #removeHuman}): it owns nothing and nothing runs for it. */
    public static boolean retired(int slot) {
        required();
        return Hooks.retired(slot);
    }

    // ------------------------------------------------------------ deals between humans (SOS-72, plan 0009)

    /**
     * Between steps: why this PC's player can't propose {@code terms} to the human in {@code slot} now, as a sentence for
     * the player, or null. Only this PC's own money and goods can be read here; the other side's are checked when the
     * deal applies, and a deal that no longer holds then is refused on every PC.
     */
    public static String dealRefusal(int slot, DealBook.Terms terms) {
        required();
        return HumanDeals.proposeRefusal(slot, terms);
    }

    /** Between steps: this PC's player proposes {@code terms} to the human in {@code slot}. Check {@link #dealRefusal} first. */
    public static void dealPropose(int slot, DealBook.Terms terms) {
        required();
        String why = HumanDeals.proposeRefusal(slot, terms);
        if (why != null) {
            throw new IllegalStateException("SOSDET: a deal refused: " + why);
        }
        HumanDeals.propose(slot, terms);
    }

    /** Between steps: why this PC's player can't accept the proposal {@code id} now, or null. Declining always works. */
    public static String dealAcceptRefusal(int id) {
        required();
        return HumanDeals.acceptRefusal(id);
    }

    /** Between steps: this PC's player answers the proposal {@code id} made to them. */
    public static void dealAnswer(int id, boolean accept) {
        required();
        HumanDeals.answer(id, accept);
    }

    /** Between steps: why this PC's player can't cancel every treaty with the human in {@code slot} now, or null. */
    public static String dealCancelRefusal(int slot) {
        required();
        return HumanDeals.cancelRefusal(slot);
    }

    /**
     * Between steps: this PC's player cancels every treaty with the human in {@code slot} at once, with no proposal and
     * no answer (SOS-76). Check {@link #dealCancelRefusal} first.
     */
    public static void dealCancel(int slot) {
        required();
        String why = HumanDeals.cancelRefusal(slot);
        if (why != null) {
            throw new IllegalStateException("SOSDET: a cancel refused: " + why);
        }
        HumanDeals.cancel(slot);
    }

    /** Between steps: why this PC's player can't declare war on the human in {@code slot} now, or null. */
    public static String warRefusal(int slot) {
        required();
        return HumanDeals.warRefusal(slot);
    }

    /**
     * Between steps: this PC's player declares war on the human in {@code slot} at once, with no answer (SOS-74). Check
     * {@link #warRefusal} first.
     */
    public static void declareWar(int slot) {
        required();
        String why = HumanDeals.warRefusal(slot);
        if (why != null) {
            throw new IllegalStateException("SOSDET: a war refused: " + why);
        }
        HumanDeals.war(slot);
    }

    /** Between steps: why this PC's player can't set a tariff of {@code percent} on the human in {@code slot} now, or null. */
    public static String tariffRefusal(int slot, int percent) {
        required();
        return HumanDeals.tariffRefusal(slot, percent);
    }

    /**
     * Between steps: this PC's player sets their tariff on what the human in {@code slot} sells them, at once, with no
     * answer (SOS-77): 0% to 80% in steps of 5%. Check {@link #tariffRefusal} first.
     */
    public static void setTariff(int slot, int percent) {
        required();
        String why = HumanDeals.tariffRefusal(slot, percent);
        if (why != null) {
            throw new IllegalStateException("SOSDET: a tariff refused: " + why);
        }
        HumanDeals.tariff(slot, percent);
    }

    /**
     * Between steps: why the human in {@code giver} can't give {@code region} (a region index) to the human in
     * {@code receiver} in one deal with the regions {@code chosen}, or null (SOS-73, the region picker). Regions are
     * world state, so both sides can be checked on any PC.
     */
    public static String dealRegionRefusal(int giver, int receiver, int region, int[] chosen) {
        required();
        return HumanDeals.regionRefusal(giver, receiver, region, chosen);
    }

    /** The yearly tribute, in percent of its worth, the human in {@code vassal} pays the human in {@code overlord} (SOS-73). */
    public static int tributePercent(int vassal, int overlord) {
        required();
        return HumanDeals.tributePercent(vassal, overlord);
    }

    /** The tariff the human in {@code setter} puts on what the human in {@code other} sells them, in percent. */
    public static int tariff(int setter, int other) {
        required();
        return HumanDeals.tariffs().rate(setter, other);
    }

    /**
     * What the tariff of the human in {@code buyer} on the human in {@code seller} brought in this year, or last year;
     * with {@code seller} -1, all of {@code buyer}'s tariffs this year.
     */
    public static int tariffRevenue(int buyer, int seller, boolean lastYear) {
        required();
        return HumanDeals.tariffRevenue(buyer, seller, lastYear);
    }

    /**
     * Outside the step: the price per item the human in {@code slot} last reported for the tradable with index
     * {@code tradable}: their lowest export price ({@code sell}), or their highest import price (SOS-77, the Goods tab).
     */
    public static int reportedPrice(int slot, int tradable, boolean sell) {
        Session s = required();
        if (!s.isHuman(slot)) {
            throw new IllegalArgumentException("SOSDET: slot " + slot + " is not a human in this game");
        }
        CityReport r = s.reports(slot).current();
        return r == null ? 0 : (int) r.get(sell ? Field.SELL_PRICE_CAP : Field.BUY_PRICE_CAP, tradable);
    }

    /**
     * Outside the step: the stock of the tradable with index {@code tradable} that the human in {@code slot} last
     * reported, as the world reads it (another player's Goods tab, SOS-76); 0 before that human's first report.
     */
    public static int reportedStock(int slot, int tradable) {
        Session s = required();
        if (!s.isHuman(slot)) {
            throw new IllegalArgumentException("SOSDET: slot " + slot + " is not a human in this game");
        }
        CityReport r = s.reports(slot).current();
        return r == null ? 0 : (int) r.get(Field.HELD, tradable);
    }

    /** Every open proposal between humans, oldest first: a live view, read on the game thread. */
    public static List<DealBook.Proposal> deals() {
        required();
        return HumanDeals.open();
    }

    /** The latest answers to proposals between humans, oldest first: a live view, read on the game thread. */
    public static List<DealBook.Answer> dealAnswers() {
        required();
        return HumanDeals.answers();
    }

    /**
     * Outside the step only (the rig's probes, a save's per-human sections): reads the game as the human in
     * {@code slot}: {@code FACTIONS.player()} and every per-human slice are that human's until the call that gives
     * back the returned slot (-1: this PC's own human). Throws in the step (SOS-46).
     */
    public static int asHuman(int slot) {
        Session s = required();
        if (s.part >= 0 || !Hooks.outsideStep()) {
            throw new IllegalStateException("SOSDET: reading as another human is for between steps (part " + s.part + ")");
        }
        if (slot >= 0 && !s.isHuman(slot)) {
            throw new IllegalArgumentException("SOSDET: slot " + slot + " is not a human in this game");
        }
        int before = s.viewHuman;
        s.viewHuman = slot;
        return before;
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

    /**
     * This PC's player only watches (spec 0.12, watch-only viewing): a click that would give an order or a command
     * changes nothing and sends nothing. Camera, windows and selection stay this PC's own. {@code false} lets the player
     * act again; either takes effect at once, since a dropped order never reaches any log (SOS-15).
     */
    public static void watchOnly(boolean on) {
        required().ai().watchOnly(on);
    }

    /** A battle division or artillery commanded by the host's AI general (SOS-15). */
    public static final int AI = AiEntry.AI;

    /**
     * Host, between steps: who commands each battle division and each army's artillery from now on (SOS-15).
     * {@code byDivision[i]} is the PC (the mod's numbering, the host 0) or {@link #AI} commanding battle division i,
     * every division of both armies in the game's order; {@code artilleryByArmy[a]} commands army a's artillery. An
     * order or a change of the general for a division or artillery its source doesn't command is skipped on every PC,
     * and each PC's player can select only its own. Applied at the step the host stamps, on every PC; kept in the
     * resync state and in the state hash. {@code null, null}: nothing is restricted again, as before the first table.
     */
    public static void commanders(int[] byDivision, int[] artilleryByArmy) {
        Session s = required();
        if (byDivision != null && (byDivision.length != OwnArmy.divisions() || artilleryByArmy == null
                || artilleryByArmy.length != OwnArmy.armies())) {
            throw new IllegalArgumentException("SOSDET: a commanders table for " + byDivision.length + " divisions and "
                    + (artilleryByArmy == null ? "no" : String.valueOf(artilleryByArmy.length)) + " armies; the battle has "
                    + OwnArmy.divisions() + " and " + OwnArmy.armies());
        }
        s.ai().captureCommanders(Commanders.payload(byDivision, artilleryByArmy));
    }

    /** Division {@code div}'s commander now, or {@link Commanders#UNSET} with no table. */
    public static int commanderOf(int div) {
        return required().ai().commanders().of(div);
    }

    /** Orders and changes of the general skipped because their source didn't command their division or artillery. */
    public static long ordersNotCommanded() {
        return required().ai().commanders().skipped();
    }

    /** The orders and commands this PC's player gave while only watching, all dropped. */
    public static long watchedDropped() {
        return required().ai().watchedDropped();
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

    /**
     * The steps a second this PC could run, from its frames that didn't wait for another PC, or -1 before the first
     * measure (SOS-94). Each PC sends it with its hashes; the host's governor lowers the speed only for a PC under it.
     */
    public static int capacity() {
        return required().pacer.capacity();
    }

    /** Another PC's capacity, from its last hash, or -1 before the first (SOS-94). */
    public static int capacity(int pc) {
        return (int) required().hashes.capacityOf(pc);
    }

    /**
     * The desync every PC holds for (SOS-53), or null. The host logs the hold when its hashes first differ; every PC
     * applies it at the same step and stops there, while the mod's window names the PC and the host chooses: a world
     * resync at {@link DesyncHold#holdStep} ({@link #worldResyncSave}, then {@link #worldResyncLoad} on every PC, which
     * ends the hold), or saving and leaving ({@link #end}).
     */
    public static DesyncHold desyncHold() {
        return required().desyncHold;
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
     * Test tooling, own-city mode: as {@link #traceDraws}, with the city's draws too, to compare a viewed city with its
     * viewer's copy in the rig (SOS-82).
     */
    public static void traceAllDraws(int keepSteps) {
        traceDraws(keepSteps);
        required().streams.traceWorldOnly = false;
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

    /**
     * Between steps: adds to {@code out} the money and goods transfers of every human made after the one numbered
     * {@code afterSeq}, oldest first (SOS-81, the playtest log), and returns how many of those were already overwritten.
     * Every PC records the same. {@link #lastTransfer()} gives the number to read after next time.
     */
    public static long transfers(long afterSeq, java.util.List<sosdet.txn.TransferLog.Transfer> out) {
        return required().transfers.read(afterSeq, out);
    }

    /** The number of the latest transfer recorded; 0 before the first. */
    public static long lastTransfer() {
        return required().transfers.last();
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

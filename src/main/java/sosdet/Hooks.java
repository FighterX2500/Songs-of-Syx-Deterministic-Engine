package sosdet;

import java.util.Random;

import game.GAME;
import game.GAME.GameResource;
import game.boosting.BValue;
import game.faction.FACTIONS;
import game.faction.FCredits.CTYPE;
import game.faction.Faction;
import game.faction.player.PCredits;
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
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.rnd.RND;
import sosdet.ai.AiApplier;
import sosdet.ai.AiMode;
import sosdet.game.GameReport;
import sosdet.game.StepParts;
import sosdet.game.WorldStatics;
import sosdet.game.cmd.PlanStash;
import sosdet.hash.HashLog;
import sosdet.report.CityReport;
import sosdet.report.Field;
import sosdet.report.Reports;
import sosdet.txn.Txn;
import sosdet.txn.TxnKind;
import util.data.DOUBLE_O;
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
     * Whether an open window pauses the game, as in vanilla. Not in a session (mod spec section 4): a click there is a
     * command for a later step, which a pause would hold until the window closed (SOS-32).
     */
    public static boolean menusPause() {
        return Engine.session() == null;
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
     * step, while a map battle's end loads the game saved before it, as a peer that is already 30 steps past the host's last confirmed step, or as a follower whose source's
     * report for the step has not come in (ADR 0005 §4). This PC's room plan is lifted out
     * of the simulation before the frame's first step (ADR 0003). The AI log entries due at this step are applied
     * first, before anything else runs.
     */
    public static boolean stepBegin() {
        Session s = Engine.session();
        if (s.battleLeaving || !s.ai().mayRun(s.clock.step() + 1) || !s.worldMayRun(s.clock.step() + 1)) {
            s.clock.hold();
            return false;
        }
        if (!s.clock.begin()) {
            return false;
        }
        s.plans().lift();
        s.streams.enter();
        // Reseeded every step: the stream depends only on the seed and the step, so a resync needs no random state
        s.streams.reseed(s.citySeed(s.clock.step()));
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
        if (s.pathQueue != null) {
            long start = System.nanoTime();
            s.pathJobs += s.paths.run(s.pathQueue);
            s.pathNanos += System.nanoTime() - start;
            s.pathQueue = null;
        }
        s.streams.worldPart(false);
        if (!s.txns.isEmpty()) {
            s.part = StepParts.CITY; // the city tail: the world part's changes to the city apply (ADR 0005 §3)
            s.txns.apply(s.txnApplier());
        }
        if (s.hashes.due(step)) {
            // an own city's draws are its own: the world part's alone are counted (ADR 0005 §1)
            long draws = s.ownCity ? s.streams.worldDraws() : s.streams.drawsSinceLoad();
            long clock = HashLog.mix(HashLog.mix(HashLog.mix(HashLog.start(), step), draws), s.ai().applied());
            CityReport r = s.reports.current();
            s.hashes.take(step, clock, r == null ? 0 : r.hash(), s.source());
        }
        s.times.record(System.nanoTime() - s.stepStartNanos);
        s.stepStartNanos = 0;
        s.part = -1;
        s.streams.leave();
    }

    /**
     * True when the world map must not update in the city view: a session holding the world still, or a world-battle
     * prompt open (owner's decision, plan 0002: the world holds for everyone while a player decides). The prompt opens
     * and closes inside the step on every PC alike, so every PC holds the same steps; the steps go on, so the decision
     * command applies.
     */
    public static boolean worldHeld() {
        Session s = Engine.session();
        return s != null && (s.worldHeld || s.worldPrompt != null);
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
            if (s.reports.current() == null) {
                s.takeReport(step, step);
            }
            if (Reports.takenAt(step)) {
                s.takeReport(step, step + Reports.DELAY);
            }
            s.part = part;
            s.streams.reseed(s.worldSeed(step));
            s.streams.worldPart(true);
            s.reports.applyDue(step, s.inputApplier());
            if (s.reports.current() == null) {
                throw new IllegalStateException("SOSDET: no city report from PC " + s.reportsFrom + " for step " + step);
            }
            s.ai().worldPart();
            return;
        }
        s.part = part;
    }

    /** True in own-city mode (ADR 0005 §1): the hash leaves this PC's city out, the player's credits included. */
    public static boolean ownCity() {
        Session s = Engine.session();
        return s != null && s.ownCity;
    }

    /** Whether a city command PC {@code pc} gave applies here: always in co-op; an own city's only on its own PC. */
    public static boolean cityCommandHere(int pc) {
        Session s = Engine.session();
        return s == null || !s.ownCity || pc == s.ai().localPc();
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

    /** True when the world part's faction updater runs this faction: not the player's own in a session. */
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
        return Engine.session().reports.value(f, i);
    }

    public static double reportD(Field f, int i, int j) {
        return Engine.session().reports.value(f, i, j);
    }

    public static int reportI(Field f, int i) {
        return (int) reportD(f, i);
    }

    public static int reportI(Field f, int i, int j) {
        return (int) reportD(f, i, j);
    }

    public static boolean reportB(Field f, int i) {
        return reportD(f, i) != 0;
    }

    /**
     * In the world part: the player's value of a boost value, from the report. One the report has no slot for (a
     * booster added after the report's layout was made) is read live and counted in {@link Stats#reportMisses}.
     */
    public static double reportBoost(BValue v) {
        Session s = Engine.session();
        int i = s.city().indexOf(Field.BOOST, v);
        if (i >= 0) {
            return s.reports.current().get(Field.BOOST, i);
        }
        s.reportMisses++;
        return v.vGet(FACTIONS.player());
    }

    /**
     * In the world part: a boost value on the city's population class ({@code HCLASS_RACE.clP()}), from the report. As
     * {@link #reportBoost}, one without a slot is read live and counted in {@link Stats#reportMisses}.
     */
    public static double reportBoostClP(BValue v) {
        Session s = Engine.session();
        int i = s.city().indexOf(Field.BOOST, v);
        if (i >= 0) {
            return s.reports.current().get(Field.BOOST_CLP, i);
        }
        s.reportMisses++;
        return v.vGet(HCLASS_RACE.clP());
    }

    /** A faction value's value: the player's from the report in the world part, otherwise the game's own. */
    public static <T> double reportValue(Value<T> v, T t) {
        if (reportRead() && t == (Object) FACTIONS.player()) {
            Session s = Engine.session();
            int i = s.city().indexOf(Field.VALUE, v);
            if (i >= 0) {
                return s.reports.current().get(Field.VALUE, i);
            }
            s.reportMisses++;
        }
        return v.d.getD(t);
    }

    /**
     * An unlock of a tech, level or title ({@code Lockers}) on something the player owns: the player's from the report
     * in the world part, otherwise the game's own. It reads the city's tech, level and titles, which on another PC are
     * that PC's (SOS-32). One without a slot (on a person) is read live and counted in {@link Stats#reportMisses}.
     */
    public static <T> double reportUnlock(Locker<T> l, DOUBLE_O<T> v, T t) {
        if (reportRead() && GameReport.playerOwns(t)) {
            Session s = Engine.session();
            int i = s.city().indexOf(Field.UNLOCK, l);
            if (i >= 0) {
                return s.reports.current().get(Field.UNLOCK, i);
            }
            s.reportMisses++;
        }
        return v.getD(t);
    }

    // ------------------------------------------------------------ transactions (ADR 0005 §3, SOS-38)

    /**
     * In the world part: a new transaction of this kind, queued for the step's city tail. Null anywhere else: the
     * caller changes the city at once, as in vanilla (the city part, the tail itself, the UI, no session).
     */
    private static Txn toCity(TxnKind kind) {
        Session s = Engine.session();
        if (s == null || s.part != StepParts.WORLD_PART) {
            return null;
        }
        Txn t = s.txns.add(s.clock.step());
        t.kind = kind;
        return t;
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
        s.reports.change(step, Field.CREDITS, 0, -price);
        s.reports.change(step, Field.QUEUED, b.tradable.index(), 0, amount);
        s.reports.change(step, Field.QUEUED, b.tradable.index(), type.index + 1, amount);
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
        s.reports.change(step, Field.CREDITS, 0, price);
        s.reports.change(step, Field.PROMISED, p.type.index(), 0, amount);
        s.reports.change(step, Field.PROMISED, p.type.index(), type.index + 1, amount);
        return true;
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
        Engine.session().reports.change(Engine.session().clock.step(), Field.CREDITS, 0, (int) amount);
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
        Engine.session().reports.change(Engine.session().clock.step(), Field.CREDITS, 0, (int) amount);
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
    public static boolean soldierReturns(Induvidual t, int div, int entrySeed) {
        Txn x = toCity(TxnKind.SOLDIER_RETURNS);
        if (x == null) {
            return false;
        }
        Induvidual copy = new Induvidual(t.hType(), t.race());
        copy.copyFrom(t);
        x.a = copy;
        x.i = div;
        x.j = entrySeed;
        return true;
    }

    /** {@code WDivStored.age} (C4): true when queued; the world part has taken the retiree out of their division. */
    public static boolean retiree(Induvidual i) {
        Txn x = toCity(TxnKind.RETIREE);
        if (x == null) {
            return false;
        }
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

    /** {@code WDivCity.resolve} (C5): a world battle's result for a capitol garrison division; true when queued. */
    public static boolean garrisonResolve(Object div, int survivors, double experiencePerMan) {
        Txn x = toCity(TxnKind.GARRISON_RESOLVE);
        if (x == null) {
            return false;
        }
        x.a = div;
        x.i = survivors;
        x.d = experiencePerMan;
        return true;
    }

    /** {@code WDivCity.resolve(Induvidual[])} (C5): true when queued. */
    public static boolean garrisonResolve(Object div, Induvidual[] survivors) {
        Txn x = toCity(TxnKind.GARRISON_RESOLVE_PEOPLE);
        if (x == null) {
            return false;
        }
        x.a = div;
        x.b = survivors;
        return true;
    }

    /**
     * {@code Invador.invade} (C6): in the world part the world gives the invasion its ref, the report's next one plus the
     * world's own invasions since, so every PC knows it at once and the city tail starts the invasion with it.
     */
    public static int invasionRef() {
        return (int) Engine.session().reports.value(Field.INVASION_REF, 0);
    }

    /** {@code Invador.invade} (C6): true when queued for the city tail. */
    public static boolean invade(Object spec) {
        Txn x = toCity(TxnKind.INVADE);
        if (x == null) {
            return false;
        }
        x.a = spec;
        Session s = Engine.session();
        s.reports.change(s.clock.step(), Field.INVASION_REF, 0, 1);
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
        x.i = ref;
        return true;
    }

    /**
     * {@code Invador}'s diplomacy listener (C6): a war with the player ended in the world part; the city tail runs the
     * listener, which calls off that faction's invasions. True when queued.
     */
    public static boolean warEnded(Object listener, Object faction, Object other, Object old, Object stance) {
        Txn x = toCity(TxnKind.WAR_ENDED);
        if (x == null) {
            return false;
        }
        x.a = listener;
        x.b = new Object[] {faction, other, old, stance};
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
        s.stepPrompts.clear(); // the load's questions are not asked again
        s.stepPromptShown = -1;
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

    /**
     * The world step opened the world-battle prompt: {@code decides} is the battle, siege or result it decides, and
     * {@code close} hides it. Kept for the decision command (SOS-35, {@code cmd.WorldBattles}).
     */
    public static void worldPromptOpened(Object decides, Runnable close) {
        Session s = Engine.session();
        if (s != null) {
            s.worldPrompt = decides;
            s.worldPromptClose = close;
        }
    }

    /** True in a session's step: a yes/no the simulation asks is kept, and answered by command (cmd.StepPrompts). */
    public static boolean stepPromptsKept() {
        return Engine.session() != null && !outsideStep();
    }

    /** The step asks a yes/no: keeps its actions; returns its key (the step and its order in the step), the same on every PC. */
    public static long stepPromptOpened(Object yes, Object no) {
        Session s = Engine.session();
        long step = Engine.step();
        if (s.stepPromptStep != step) {
            s.stepPromptStep = step;
            s.stepPromptsInStep = 0;
        }
        long key = step * 64 + s.stepPromptsInStep++;
        s.stepPrompts.put(key, new Object[] {yes, no});
        s.stepPromptShown = key; // the game's dialog shows one question: the last one asked
        return key;
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

    /** The oldest kept question's key, or -1 (the rig answers it). */
    public static long stepPromptOldest() {
        Session s = Engine.session();
        long oldest = -1;
        if (s != null) {
            for (long key : s.stepPrompts.keySet()) {
                if (oldest < 0 || key < oldest) {
                    oldest = key;
                }
            }
        }
        return oldest;
    }

    /** The answer command takes the kept question: the chosen action, or null when it is gone or has none. */
    public static Object stepPromptTake(long key, boolean yes) {
        Session s = Engine.session();
        Object[] actions = s == null ? null : s.stepPrompts.remove(key);
        return actions == null ? null : actions[yes ? 0 : 1];
    }

    // ------------------------------------------------------------ map battles (SOS-32)

    /**
     * A map battle ends inside the step, and the game saved before it loads next (the loader runs between frames): the
     * steps hold until then, and the load continues at this step, on every PC alike.
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

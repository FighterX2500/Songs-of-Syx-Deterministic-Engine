package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosdet.game.cmd.PlanStash;
import sosdet.report.CitySource;
import sosdet.report.Field;
import sosdet.report.ReportLayout;

/**
 * A battle against NPC armies on its human's PC only (plan 0016, SOS-93): every PC keeps the record alike, freezes the
 * human at the next report step, applies the result once, runs vanilla's after-battle the step after, and thaws the
 * human at the next report step.
 */
class LocalBattleTest {

    private static final ReportLayout SMALL = new ReportLayout(1, 1, 1, 1, 1, 1, 1, 1, 1, 1);

    /** An own-city session with humans 0 and 3 on PCs 0 and 1; this PC's human {@code local}. */
    private static Session session(int local) {
        Engine.begin();
        Engine.aiInStep();
        Session s = Engine.session();
        s.plans = PlanStash.off();
        s.city = new CitySource() {
            @Override
            public ReportLayout layout() {
                return SMALL;
            }

            @Override
            public int faction() {
                return local;
            }

            @Override
            public int[] humans() {
                return new int[] {0, 3};
            }

            @Override
            public void fill(double[] values) {
            }

            @Override
            public int indexOf(Field f, Object key) {
                return -1;
            }
        };
        Engine.localPc(local == 0 ? 0 : 1);
        Engine.ownCity();
        Engine.pcHuman(0, 0);
        Engine.pcHuman(1, 3);
        assertEquals(local, s.localHuman);
        return s;
    }

    @AfterEach
    void endSession() {
        Engine.end();
    }

    @Test
    void anEngageFreezesTheHumanAtTheNextReportStepOnEveryPc() {
        for (int local : new int[] {0, 3}) {
            Session s = session(local);
            LocalBattle b = s.localEngage(1234, 3);
            assertEquals(1260, b.freeze, "the first report step after the Engage");
            assertSame(b, Engine.localBattle(3));
            s.localsAt(1259);
            assertFalse(s.frozen(3), "the human's city runs on until the freeze");
            s.localsAt(1260);
            assertTrue(s.frozen(3));
            assertEquals(1260, s.freezeStep(3));
            assertFalse(s.frozen(0), "the other human isn't frozen");
            Engine.end();
        }
    }

    @Test
    void onlyTheBattlePcRunsTheBattle() {
        Session battlePc = session(3);
        LocalBattle b = battlePc.localEngage(1234, 3);
        assertNull(battlePc.localHere(), "nothing runs before the freeze");
        battlePc.localsAt(1260);
        assertSame(b, battlePc.localHere());
        Engine.end();

        Session other = session(0);
        other.localEngage(1234, 3);
        other.localsAt(1260);
        assertNull(other.localHere(), "another PC runs its own city");
    }

    @Test
    void vanillasEngageHandsTheBattleItsPartsOnTheBattlePcOnly() {
        Session s = session(3);
        LocalBattle b = s.localEngage(1234, 3);
        List<Object> results = new ArrayList<>();
        Runnable generate = () -> { };
        s.engaging = b;
        assertTrue(Hooks.localEngaged(results::add));
        assertTrue(Hooks.localBattleMine());
        Hooks.localGenerateLater(generate);
        s.engaging = null;
        assertNotNull(b.after);
        assertSame(generate, b.generate);
        assertFalse(Hooks.localEngaged(results::add), "outside a local battle's Engage vanilla's own runs");
    }

    @Test
    void theResultAppliesOnceAndThawsAtTheNextReportStep() {
        Session s = session(0);
        LocalBattle b = s.localEngage(1234, 3);
        assertFalse(s.localResult(1250, 3, "early"), "no result before the battle runs");
        s.localsAt(1260);
        Object result = new Object[0];
        assertTrue(s.localResult(1400, 3, result));
        assertFalse(s.localResult(1401, 3, "again"), "a repeated result changes nothing");
        assertFalse(s.localResult(1401, 0, "other"), "no battle of that human");
        assertEquals(1400, b.resultStep);
        assertSame(result, b.result);
        assertEquals(1410, b.thaw);
        s.localsAt(1409);
        assertTrue(s.frozen(3));
        s.localsAt(1410);
        assertFalse(s.frozen(3), "thawed: its reports are read again");
        assertNull(Engine.localBattle(3));
    }

    @Test
    void theWorldIsHeldAt1xFromTheFirstEngageUntilTheLastBattleThaws() {
        Session s = session(0);
        s.clock.agreedSpeed(25);
        assertEquals(0, s.localCap(25), "no battle: no cap");
        LocalBattle one = s.localEngage(1234, 3);
        assertEquals(1, s.localCap(25));
        assertEquals(0, s.localCap(25), "once");
        LocalBattle two = s.localEngage(1235, 0);
        s.localsAt(1260);
        s.localResult(1300, 3, "r");
        s.localsAt(one.thaw);
        assertEquals(0, s.localCap(25), "the other battle still runs");
        s.localResult(1400, 0, "r");
        s.localsAt(two.thaw);
        assertEquals(5, s.localCap(5), "the speed the host set meanwhile");
    }

    @Test
    void theBattlePcsFramesKeepTheWorldsPaceWhateverTheBattlesSpeed() {
        Session s = session(3);
        s.clock.agreedSpeed(1);
        s.localEngage(1234, 3);
        assertEquals(5, Hooks.frameSpeed(5), "before the freeze its city runs on");
        s.localsAt(1260);
        assertEquals(1, Hooks.frameSpeed(5), "the battle at speed 5");
        assertEquals(1, Hooks.frameSpeed(0), "the battle paused");
    }

    @Test
    void theBattlePcKeepsItsMessagesForTheLoadBack() {
        Session s = session(3);
        s.localEngage(1234, 3);
        Runnable back = () -> { };
        Hooks.localMessage(back);
        assertTrue(s.localMessages.isEmpty(), "its city runs on: no load loses it");
        s.localsAt(1260);
        Hooks.localMessage(back);
        assertEquals(List.of(back), s.localMessages);
    }

    @Test
    void anotherPcKeepsNoMessagesOfTheBattle() {
        Session s = session(0);
        s.localEngage(1234, 3);
        s.localsAt(1260);
        Hooks.localMessage(() -> { });
        assertTrue(s.localMessages.isEmpty(), "this PC loads no city back");
    }

    private static View watch(Session s, int viewer, int owner, long freeze) {
        View v = new View(viewer, s.pcOf(viewer), owner, s.pcOf(owner), freeze);
        v.state = View.RUNNING;
        v.battle = true;
        s.views[viewer] = v;
        return v;
    }

    /** Mod spec section 8: any other player can join a running NPC battle (SOS-109). */
    @Test
    void aBattleCanBeWatchedOnlyWhileItRuns() {
        Session s = session(0);
        LocalBattle b = s.localEngage(1234, 3);
        assertEquals("That battle is starting or ending.", s.viewRefusal(0, 3, null));
        s.localsAt(b.freeze);
        assertNull(s.viewRefusal(0, 3, null));
        assertEquals("A battle is running.", s.viewRefusal(3, 0, null), "a fighter watches nobody");
        s.localResult(1400, 3, new Object[0]);
        assertEquals("That battle is starting or ending.", s.viewRefusal(0, 3, null));
    }

    @Test
    void aWatcherRunsTheFightersRoundsStepByStepWheneverItReachesThem() {
        Session s = session(0);
        LocalBattle b = s.localEngage(1234, 3);
        s.localsAt(b.freeze);
        long f = b.freeze + 30;
        View v = watch(s, 0, 3, f);
        LocalTape fighter = new LocalTape(Session.SLOTS);
        fighter.watch(0);
        fighter.record(f + 1, 5);
        fighter.record(f + 2, 0);
        assertTrue(s.watchMayRun(f), "the freeze step is the save's");
        assertFalse(s.watchMayRun(f + 1), "no tape yet: the watcher waits, the fighter doesn't");
        s.tape.in(fighter.out(0), v.ownerPc);
        assertTrue(s.watchMayRun(f + 2));
        assertFalse(s.watchMayRun(f + 3));
        s.localOrders(f + 1);
        assertFalse(s.localPaused());
        assertEquals(4, s.localExtraRounds());
        s.localOrders(f + 2);
        assertTrue(s.localPaused());
        assertEquals(0, s.localExtraRounds());
        assertFalse(s.watchMayRun(f + 3));
        s.removing[3] = true;
        assertTrue(s.watchMayRun(f + 3), "a fighter's PC that has gone sends no more tape");
    }

    @Test
    void theResultEndsTheWatchAndItsAfterBattleWaitsForTheWatchersThaw() {
        Session s = session(0);
        LocalBattle b = s.localEngage(1234, 3);
        List<Object> seen = new ArrayList<>();
        s.engaging = b;
        Hooks.localEngaged(seen::add);
        s.engaging = null;
        s.localsAt(b.freeze);
        View v = watch(s, 0, 3, b.freeze + 30);
        s.localResult(1400, 3, new Object[0]);
        assertTrue(v.thaw > 1400);
        assertEquals("The battle is over.", v.reason());
        b.back = true;
        s.localAfter(1401);
        assertTrue(seen.isEmpty(), "the watcher's game is still the battle's");
        v.state = View.ENDED;
        s.localAfter(v.thaw);
        assertEquals(1, seen.size());
    }

    @Test
    void aWatcherLeavesTheFightersTransactionsToTheFighter() {
        Session s = session(0);
        int[] applied = {0};
        s.txnApplier = t -> applied[0]++;
        LocalBattle b = s.localEngage(1234, 3);
        s.localsAt(b.freeze);
        watch(s, 0, 3, b.freeze + 30);
        s.ownTxns.accept(credits(3));
        assertEquals(0, applied[0], "it would change the watcher's battle, not the fighter's");
        assertEquals(0, s.heldTxns.size());
        s.ownTxns.accept(credits(0));
        assertEquals(1, s.heldTxns.size(), "the watcher's own city waits for it");
    }

    /** A PC ahead of this one has thawed already: its first report mustn't be lost (W3, and SOS-95's symptom). */
    @Test
    void aThawReportFromAPcAheadIsKeptWhileThisPcStillSeesItsHumanFrozen() {
        Session s = session(0);
        LocalBattle b = s.localEngage(1234, 3);
        s.localsAt(b.freeze);
        assertTrue(s.frozen(3));
        s.reportReceived(new sosdet.report.CityReport(SMALL, 3, 1500, 1530, 1560, new double[SMALL.size()],
                new ArrayList<>()));
        assertTrue(s.reports(3).pending(), "the thaw report waits for its step");
    }

    /** A watcher needs each order at a step, so the fighter's orders wait for the next step (SOS-109). */
    @Test
    void aFightersOrdersGoOnTheTapeNotInTheLog() {
        Session s = session(3);
        s.localHuman = 3;
        LocalBattle b = s.localEngage(1234, 3);
        s.localsAt(b.freeze);
        Hooks.orderCapture(sosdet.ai.AiEntry.GUARD, 7, new byte[4]);
        assertEquals(1, s.tape.queued().size());
        assertEquals(b.pc, s.tape.queued().get(0).pc);
    }

    /** Mod spec section 8: the fighter's city receives what the world sent it meanwhile (SOS-112). */
    @Test
    void aFrozenFightersTransactionsWaitForItsReturn() {
        Session s = session(3);
        s.localHuman = 3;
        int[] applied = {0};
        s.txnApplier = t -> applied[0]++;
        LocalBattle b = s.localEngage(1234, 3);
        s.ownTxns.accept(credits(3));
        assertEquals(1, applied[0], "before the freeze the city still runs");
        s.localsAt(b.freeze);
        s.ownTxns.accept(credits(3));
        assertEquals(1, applied[0], "the battle game would lose it");
        assertEquals(1, s.heldTxns.size());
        s.ownTxns.accept(credits(1));
        assertEquals(1, applied[0], "another human's city isn't this PC's");
        assertEquals(1, s.heldTxns.size());
    }

    private static sosdet.txn.Txn credits(int human) {
        sosdet.txn.Txn t = new sosdet.txn.Txn();
        t.kind = sosdet.txn.TxnKind.CREDITS;
        t.human = human;
        return t;
    }

    @Test
    void stormWaitsUntilTheLastLocalBattleHasEnded() {
        Session s = session(0);
        assertNull(s.assaultRefusal());
        LocalBattle b = s.localEngage(1234, 3);
        String why = "A player is fighting a battle: the assault starts once it ends.";
        assertEquals(why, s.assaultRefusal(), "from the Engage: that human can't view the assault");
        s.localsAt(1260);
        assertEquals(why, s.assaultRefusal());
        s.localResult(1400, 3, "r");
        assertEquals(why, s.assaultRefusal(), "until its human has thawed");
        s.localsAt(b.thaw);
        assertNull(s.assaultRefusal());
    }

    @Test
    void termsWithoutATakenThroneChangeNothing() {
        Session s = session(0);
        assertFalse(s.assaultTerms(0, sosdet.game.siege.AssaultTerms.SACK, 50, new int[] {1, 0, 0}), "a repeat finds no throne");
        assertNull(s.assaultTermsOutcome);
    }

    @Test
    void aHumanRemovedInItsBattleEndsTheBattle() {
        Session s = session(0);
        s.remover = slot -> { };
        s.localEngage(1234, 3);
        s.localsAt(1260);
        s.localCap(5);
        s.removeDue[3] = true;
        s.retireDue();
        assertNull(Engine.localBattle(3));
        assertFalse(Engine.localBattleRunning(), "campaign saves go on");
        assertFalse(s.frozen(3));
        assertEquals(5, s.localCap(5), "the world's speed comes back");
    }

    @Test
    void theHostsFramesHoldTheCapWhileAPeerFights() {
        Session s = session(0);
        s.clock.agreedSpeed(5);
        LocalBattle b = s.localEngage(1234, 3);
        s.localCap(5);
        assertEquals(5, Hooks.frameSpeed(5), "the 1x isn't agreed yet");
        s.clock.agreedSpeed(1);
        s.localsAt(b.freeze);
        assertEquals(1, Hooks.frameSpeed(5), "no PC's pace holds the host while the peer generates");
        assertEquals(0, Hooks.frameSpeed(0), "the host's pause stays");
        s.localResult(1400, 3, "r");
        s.localsAt(b.thaw);
        s.localCap(5);
        assertEquals(5, Hooks.frameSpeed(5), "the cap is off");
    }

    @Test
    void anotherPcsFramesKeepTheirOwnSpeed() {
        Session s = session(0);
        s.clock.agreedSpeed(1);
        s.localEngage(1234, 3);
        s.localsAt(1260);
        assertEquals(5, Hooks.frameSpeed(5));
    }

    @Test
    void aSlowBattlePcLowersItsRoundsNotTheWorld() {
        assertEquals(4, Session.roundsFor(5, 5, 60, 60), "can't hold the rate");
        assertEquals(1, Session.roundsFor(1, 5, 30, 60), "one round is the least");
        assertEquals(5, Session.roundsFor(5, 5, 300, 60), "holds it");
        assertEquals(3, Session.roundsFor(2, 5, 300, 60), "room for one more");
        assertEquals(2, Session.roundsFor(2, 5, 120, 60), "three rounds wouldn't fit");
        assertEquals(3, Session.roundsFor(3, 3, 900, 60), "no more than the human's speed");
        assertEquals(3, Session.roundsFor(3, 5, -1, 60), "nothing measured yet");
    }

    @Test
    void aHumansPromptsWaitUntilItsBattlesResultHasBeenAsked() {
        Session s = session(0);
        assertFalse(s.localBusy(3), "no battle");
        LocalBattle b = s.localEngage(1234, 3);
        s.engaging = b;
        Hooks.localEngaged(r -> { });
        s.engaging = null;
        assertTrue(s.localBusy(3));
        assertFalse(s.localBusy(0), "the other human is asked as usual");
        assertFalse(s.localBusy(60), "an NPC has no prompts");
        s.localsAt(1260);
        s.localResult(1400, 3, new Object[0]);
        assertTrue(s.localBusy(3), "its own result comes first");
        b.back = true;
        s.localAfter(1401);
        assertFalse(s.localBusy(3));
    }

    @Test
    void theResultWaitsForAnotherHumansOpenPrompt() {
        Session s = session(0);
        LocalBattle b = s.localEngage(1234, 3);
        List<Object> seen = new ArrayList<>();
        s.engaging = b;
        Hooks.localEngaged(seen::add);
        s.engaging = null;
        s.localsAt(1260);
        s.localResult(1400, 3, new Object[0]);
        assertTrue(s.localBackDue(b));
        s.worldPrompt = new Object();
        assertFalse(s.localBackDue(b), "the battle PC's load would drop the prompt, every other PC keep it");
        s.localAfter(1401);
        assertTrue(seen.isEmpty(), "vanilla's panel holds one prompt");
        long thaw = b.thaw;
        s.localsAt(thaw);
        assertSame(b, Engine.localBattle(3), "the after-battle is still to run");
        assertTrue(b.thaw > thaw);
        s.worldPrompt = null;
        assertTrue(s.localBackDue(b));
        s.localAfter(1405);
        assertTrue(seen.isEmpty(), "only once the PCs are back");
        b.back = true;
        assertFalse(s.localBackDue(b), "once");
        s.localAfter(1406);
        assertEquals(1, seen.size());
    }

    @Test
    void aHumanInALocalBattleIsNotViewedAndViewsNobody() {
        Session s = session(0);
        s.localEngage(1234, 3);
        assertEquals("That battle is starting or ending.", s.viewRefusal(0, 3, null));
        assertEquals("A battle is running.", s.viewRefusal(3, 0, null));
    }

    @Test
    void aResultAtAReportStepThawsAtTheNextOne() {
        Session s = session(0);
        LocalBattle b = s.localEngage(1234, 3);
        s.localsAt(1260);
        s.localResult(1379, 3, "r");
        assertEquals(1410, b.thaw, "after the step that runs the after-battle (1380)");
    }

    @Test
    void theAfterBattleRunsOnceTheStepAfterTheResultWithTheResult() {
        Session s = session(0);
        LocalBattle b = s.localEngage(1234, 3);
        List<Object> seen = new ArrayList<>();
        s.engaging = b;
        Hooks.localEngaged(seen::add);
        s.engaging = null;
        s.localsAt(1260);
        Object result = new Object[0];
        s.localResult(1400, 3, result);
        s.localAfter(1400);
        assertTrue(seen.isEmpty(), "not in the result's own step");
        b.back = true;
        s.localAfter(1401);
        assertEquals(List.of(result), seen);
        assertEquals(0, b.armies.length, "the armies move again");
        s.localAfter(1401);
        assertEquals(1, seen.size(), "once");
    }
}

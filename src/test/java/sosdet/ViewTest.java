package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosdet.game.cmd.PlanStash;
import sosdet.hash.HashLog;
import sosdet.report.CitySource;
import sosdet.report.Field;
import sosdet.report.ReportLayout;

/**
 * A city view's limits and ends (plan 0013 M2, SOS-82): every PC ends a view at one step alike, with a reason the mod
 * shows; the viewer's PC measures its own limits only to ask the host for that end.
 */
class ViewTest {

    private static final long S = 1_000_000_000L;
    private static final ReportLayout SMALL = new ReportLayout(1, 1, 1, 1, 1, 1, 1, 1, 1, 1);

    /** An own-city session with humans 0, 3 and 5 on PCs 0, 1 and 2; this PC's human {@code local}. */
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
                return new int[] {0, 3, 5};
            }

            @Override
            public void fill(double[] values) {
            }

            @Override
            public int indexOf(Field f, Object key) {
                return -1;
            }
        };
        Engine.localPc(local == 0 ? 0 : local == 3 ? 1 : 2);
        Engine.ownCity();
        Engine.pcHuman(0, 0);
        Engine.pcHuman(1, 3);
        Engine.pcHuman(2, 5);
        assertEquals(local, s.localHuman);
        return s;
    }

    @AfterEach
    void endSession() {
        Engine.end();
    }

    // ------------------------------------------------------------ the limits' clock

    @Test
    void aViewerThatCatchesUpWithinAMinuteHitsNoLimit() {
        ViewLimit l = new ViewLimit();
        assertEquals(View.OPEN, l.update(0, 5000), "nothing is measured before the view load");
        l.start(10 * S);
        assertEquals(View.OPEN, l.update(40 * S, 900));
        assertEquals(View.OPEN, l.update(69 * S, 60), "60 steps behind is caught up");
        assertEquals(59, l.caughtUpSeconds(), 1e-9);
        assertEquals(View.OPEN, l.update(500 * S, 10));
    }

    @Test
    void aViewerNotCaughtUpAMinuteAfterItsLoadHitsTheCatchUpLimit() {
        ViewLimit l = new ViewLimit();
        l.start(10 * S);
        assertEquals(View.OPEN, l.update(70 * S, 61), "a minute exactly is still within");
        assertEquals(View.NOT_CAUGHT_UP, l.update(70 * S + 1, 61));
        assertEquals(View.NOT_CAUGHT_UP, l.update(71 * S, 0), "kept once hit");
        assertEquals(-1, l.caughtUpSeconds(), 1e-9);
    }

    @Test
    void aViewerBehindForOverAMinuteAfterCatchingUpHitsTheKeepUpLimit() {
        ViewLimit l = new ViewLimit();
        l.start(0);
        l.update(5 * S, 0);
        assertEquals(View.OPEN, l.update(10 * S, 200), "behind from here");
        assertEquals(View.OPEN, l.update(60 * S, 200));
        assertEquals(View.OPEN, l.update(65 * S, 30), "caught up again: the clock starts over");
        assertEquals(View.OPEN, l.update(70 * S, 61));
        assertEquals(View.OPEN, l.update(130 * S, 61));
        assertEquals(View.FELL_BEHIND, l.update(130 * S + 1, 61));
    }

    @Test
    void testToolingSetsShorterLimitsAndANewViewStartsAfresh() {
        ViewLimit l = new ViewLimit();
        l.limits(1, 2, 5);
        l.start(0);
        assertEquals(View.NOT_CAUGHT_UP, l.update(S + 1, 6));
        l.start(10 * S);
        assertEquals(View.OPEN, l.update(10 * S, 5), "a new view: caught up at once");
        l.update(11 * S, 6);
        assertEquals(View.FELL_BEHIND, l.update(13 * S + 1, 6));
        l.stop();
        assertEquals(-1, l.caughtUpSeconds(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> l.limits(0, 1, 1));
    }

    @Test
    void aWatchRunsBehindForAsLongAsItLikesUpToItsOneLimit() {
        ViewLimit l = new ViewLimit();
        l.battle(10_800);
        l.start(0);
        assertEquals(View.OPEN, l.update(600 * S, 10_800), "ten minutes behind, at the limit: a city's would have ended");
        assertEquals(View.FELL_BEHIND, l.update(601 * S, 10_801));
        l.battle(-1);
        l.start(1000 * S);
        assertEquals(View.OPEN, l.update(1000 * S, 601));
        assertEquals(View.NOT_CAUGHT_UP, l.update(1061 * S, 601), "the next view is a city's again");
    }

    // ------------------------------------------------------------ ends, the same on every PC

    /** The view of human 5's city by human 3, from step 10: every PC freezes human 3 at step 30. */
    private static View frozenView(Session s) {
        s.viewEntry(10, 3, 5, 2);
        View v = s.views[3];
        assertEquals(30, v.freeze);
        s.viewsAt(30);
        assertTrue(v.frozen());
        return v;
    }

    @Test
    void aLimitTheHostLogsEndsTheViewAtTheNextReportStepWithItsReason() {
        Session s = session(0); // a PC outside the view: its end applies here as on the viewer's and the owner's
        View v = frozenView(s);
        assertEquals(View.RUNNING, v.state());
        s.viewEntry(47, 3, -1, View.NOT_CAUGHT_UP);
        assertEquals(60, v.thaw());
        assertEquals(View.NOT_CAUGHT_UP, v.why());
        assertEquals("Your PC couldn't catch up with the city.", v.reason());
        s.viewEntry(50, 3, -1, View.CLOSED);
        assertEquals(View.NOT_CAUGHT_UP, v.why(), "the first end stands");
        s.viewsEnd(59);
        assertEquals(60, v.thaw(), "nothing puts it off");
        s.viewsAt(60);
        assertEquals(View.ENDED, v.state());
    }

    @Test
    void theViewersClosingAndALostStepCarryTheirOwnReasons() {
        Session s = session(0);
        View v = frozenView(s);
        s.viewEntry(61, 3, -1, View.CLOSED);
        assertEquals(View.CLOSED, v.why());
        assertNull(v.reason(), "the viewer closed an open view: no reason");
        assertEquals(90, v.thaw());

        s.viewsAt(90);
        s.views[3] = null;
        View w = frozenView(s);
        s.viewEntry(31, 3, -1, View.DESYNC);
        assertEquals("The view lost step with the city.", w.reason());
        s.views[3] = null;
        s.viewEntry(10, 3, 5, 2);
        s.viewEntry(12, 3, -1, -1); // an end entry of M1, without a reason
        assertEquals(View.CANCELLED, s.views[3].state(), "not frozen yet: cancelled");
        assertEquals(View.CLOSED, s.views[3].why());
        assertEquals("Closed before it opened.", s.views[3].reason());
    }

    @Test
    void aBattlePromptForTheViewerEndsItsViewAndPutsTheThawOffWhileOpen() {
        Session s = session(0);
        View v = frozenView(s);
        s.endView(3, 40, View.GAME, "A battle prompt asks for you."); // as Hooks.worldPromptOpened does
        s.worldPrompt = new Object();
        assertEquals(60, v.thaw());
        s.viewsEnd(58);
        assertEquals(60, v.thaw(), "decided at the step before the thaw");
        s.viewsEnd(59);
        assertEquals(90, v.thaw(), "put off while the prompt is open");
        s.worldPrompt = null;
        s.viewsEnd(89);
        assertEquals(90, v.thaw());
        assertEquals(View.GAME, v.why());
        assertEquals("A battle prompt asks for you.", v.reason());
    }

    @Test
    void aBattleCancelsTheViewsToComeAndEndsTheOthersAfterIt() {
        Session s = session(0);
        View v = frozenView(s);
        s.viewEntry(35, 0, 3, 1); // a second view, of human 3's city by human 0, freezing at 60
        View w = s.views[0];
        s.battle = new OwnBattle(40, 5, 2, -1, 0);
        s.battleEndsViews(40);
        assertEquals(View.CANCELLED, w.state());
        assertEquals("A battle started.", w.reason());
        assertEquals(60, v.thaw());
        assertEquals(View.GAME, v.why());
        s.viewsEnd(59);
        s.viewsEnd(89);
        assertEquals(120, v.thaw(), "put off while the battle runs");
        s.battle = null;
        s.viewsEnd(119);
        s.viewsAt(120);
        assertEquals(View.ENDED, v.state());
    }

    @Test
    void aViewEndedInItsFreezeStepLoadsNothingOnTheViewersPc() {
        Session s = session(3); // the viewer's PC
        s.viewEntry(10, 3, 5, 2);
        View v = s.views[3];
        s.viewsAt(30);
        assertEquals(View.LOADING, v.state());
        assertTrue(s.viewHolds(31), "it holds for the owner's city");
        s.endView(3, 30, View.GAME, "A battle prompt asks for you.");
        assertFalse(v.loaded());
        assertEquals(View.RUNNING, v.state());
        assertTrue(v.frozen(), "every world reads its frozen report until the thaw");
        assertFalse(s.viewHolds(31), "nothing to load");
        assertNull(s.viewing(), "its own city runs on");
        assertEquals(3, s.cityHuman());
        assertEquals(View.OPEN, Engine.viewLimit());
        s.viewsAt(60);
        assertEquals(View.ENDED, v.state());
    }

    /** Mod spec §10: the host refuses at once a request the engine would cancel, with the reason. */
    @Test
    void theHostRefusesARequestTheViewWouldBeCancelledFor() {
        Session s = session(0);
        assertNull(Engine.viewRefusal(3, 5));
        frozenView(s);
        assertEquals("You are viewing a city already.", Engine.viewRefusal(3, 0));
        assertEquals("Its player is viewing another city.", Engine.viewRefusal(0, 3));
        assertEquals("Your own city is being viewed.", Engine.viewRefusal(5, 0));
        assertNull(Engine.viewRefusal(0, 5), "a second viewer joins the first (SOS-151)");
        assertEquals("That city can't be viewed.", Engine.viewRefusal(0, 0));
        assertEquals("That city can't be viewed.", Engine.viewRefusal(0, 7), "not a human");
        s.battle = new OwnBattle(40, 0, 0, -1, 0);
        assertEquals("A battle is running.", Engine.viewRefusal(0, 5));
    }

    /** Viewers of one city share one freeze and one save (SOS-92 E), or a later one joins them (SOS-151). */
    @Test
    void viewersOfOneCityShareAFreezeOrJoinLater() {
        Session s = session(5); // the owner's PC
        s.viewEntry(10, 3, 5, 2);
        s.viewEntry(10, 0, 5, 2);
        s.viewsAt(30);
        assertEquals(View.RUNNING, s.views[3].state());
        assertEquals(View.RUNNING, s.views[0].state(), "both freeze at the same step");
        assertEquals(-1, s.views[3].rejoinAt(), "one save for both: nobody loads again");
        assertEquals(-1, s.views[0].rejoinAt());
        s.views[0] = null;
        assertNull(s.viewRefusal(0, 5, null));
    }

    // ------------------------------------------------------------ several viewers (SOS-151)

    /** Mod spec §10: any number of viewers; each join reloads the owner and every viewer already there. */
    @Test
    void aSecondViewerMakesTheFirstLoadTheCityAgainAtItsFreezeOnEveryPc() {
        for (int local : new int[] {0, 3, 5}) {
            Session s = session(local);
            View first = frozenView(s);
            s.viewEntry(40, 0, 5, 2);
            View second = s.views[0];
            s.viewsAt(60);
            assertTrue(second.frozen(), "PC of human " + local + ": the second view opens");
            assertEquals(60, first.rejoinAt(), "PC of human " + local);
            assertEquals(-1, second.rejoinAt(), "PC of human " + local);
            Engine.end();
        }
    }

    /** A spectator who closed an assault's view doesn't come back into it: a reload would lose the commands. */
    @Test
    void nobodyJoinsARunningAssault() {
        Session s = session(0);
        frozenView(s);
        s.assault = new Assault(10, 3, 5, 30, null, new int[0], 0, new int[0], new int[] {3}, new int[] {1});
        assertEquals("An assault is running.", s.viewRefusal(0, 5, null));
        assertNull(s.viewRefusal(3, 5, s.views[3]), "the assault's own view");
    }

    /** The viewer's PC: frozen at 30, its city of human 5 loaded and running; a second viewer, human 0, freezes at 60. */
    private static View joinedAt60(Session s) {
        View v = frozenView(s);
        v.state = View.RUNNING; // as its own load sets
        s.clock.resetTo(60);
        s.viewEntry(40, 0, 5, 2);
        s.viewsAt(60);
        return v;
    }

    private static ViewCity city(long step, Session s, double acc, byte[] battle) {
        double kept = s.playerUpdateAcc;
        s.playerUpdateAcc = acc;
        byte[] state = ViewCity.cityState(s);
        s.playerUpdateAcc = kept;
        return ViewCity.fromBytes(ViewCity.toBytes(step, state, new double[0], battle, new byte[0]));
    }

    @Test
    void aViewerHoldsAtItsRejoinUntilItHasLoadedTheCityAgainKeepingItsOwn() {
        Session s = session(3);
        View v = frozenView(s);
        v.state = View.RUNNING;
        s.ownCityState = ViewCity.cityState(s);
        byte[] own = s.ownCityState;
        s.viewHashesFrom(3);
        HashLog h = s.viewHashes[3];
        s.viewDrawsBase[3] = -5;
        s.clock.resetTo(60);
        s.viewEntry(40, 0, 5, 2);
        s.viewsAt(60);

        assertTrue(s.viewHolds(61), "until it has the owner's save from step 60");
        assertTrue(Engine.viewRejoinDue());
        assertThrows(IllegalStateException.class, () -> s.viewRejoinStart(city(90, s, 7, new byte[0])), "another step's");
        s.viewRejoinStart(city(60, s, 7, new byte[0]));
        assertTrue(s.viewLoading);
        assertTrue(s.viewHolds(61), "while it loads");
        assertFalse(Engine.viewRejoinDue());
        s.afterLoad();
        s.viewLoadSet();

        assertEquals(7, s.playerUpdateAcc, 0, "the owner's city's state, as saved at 60");
        assertSame(own, s.ownCityState, "its own city stays as saved at its freeze");
        assertSame(h, s.viewHashes[3], "the hashes go on");
        assertEquals(s.cityDraws(), s.viewDrawsBase[3], "counted from the load, as on the owner's PC");
        assertEquals(View.RUNNING, v.state());
        assertSame(v, s.viewing());
        assertFalse(s.viewHolds(61));
        assertFalse(Engine.viewRejoinDue());
    }

    @Test
    void aLaggingViewerLoadsEachJoinInOrder() {
        Session s = session(3);
        View v = joinedAt60(s);
        assertThrows(IllegalStateException.class, () -> s.viewRejoinStart(city(120, s, 0, new byte[0])),
                "the later join's save waits for its step");
        s.viewRejoinStart(city(60, s, 0, new byte[0]));
        s.afterLoad();
        s.viewLoadSet();
        s.viewEntry(70, 0, -1, View.CLOSED);
        s.viewsAt(90);
        assertEquals(View.ENDED, s.views[0].state());
        s.viewEntry(95, 0, 5, 2); // human 0 comes back
        s.clock.resetTo(120);
        s.viewsAt(120);
        assertEquals(120, v.rejoinAt());
        assertTrue(s.viewHolds(121));
        assertTrue(Engine.viewRejoinDue());
        s.viewRejoinStart(city(120, s, 0, new byte[0]));
        s.afterLoad();
        s.viewLoadSet();
        assertFalse(s.viewHolds(121));
    }

    @Test
    void aJoinEndedInItsFreezeStepLeavesTheViewsThereAsTheyAre() {
        Session s = session(3);
        View v = joinedAt60(s);
        s.endView(0, 60, View.GAME, "A battle prompt asks for you.");
        assertEquals(-1, v.rejoinAt(), "the owner doesn't reload");
        assertFalse(s.viewHolds(61));
        assertFalse(Engine.viewRejoinDue());
    }

    @Test
    void theRejoiningPcLoadsTheWholeSaveAndNoWorldOfItsOwn() {
        Session s = session(3);
        joinedAt60(s);
        s.viewWorldReloads(); // a world reload here would need the game
        assertEquals(-1, s.views[0].reloaded);
    }

    @Test
    void theOwnerStartsTheNewViewersHashesAndKeepsTheOthers() {
        Session s = session(5);
        View first = frozenView(s);
        first.sent = true;
        s.viewHashesFrom(3);
        HashLog h = s.viewHashes[3];
        s.clock.resetTo(60);
        s.viewEntry(40, 0, 5, 2);
        s.viewsAt(60);
        assertTrue(s.viewHolds(61), "until the mod has taken its save");
        s.views[0].sent = true; // Engine.viewOut, which loads it here too
        s.viewLoadStart(ViewCity.cityState(s), null, 0);
        s.viewLoadSet();
        assertSame(h, s.viewHashes[3]);
        assertNotNull(s.viewHashes[0]);
        assertNotSame(h, s.viewHashes[0]);
        assertFalse(s.viewHolds(61));
    }

    /** Plan 0017: a battle's tape goes on across a rejoin; the watcher runs the steps it has from there. */
    @Test
    void aWatchersTapeGoesOnAcrossARejoin() {
        Session s = session(3);
        LocalBattle b = s.localEngage(1234, 5);
        s.localsAt(b.freeze);
        s.viewEntry(1270, 3, 5, 2);
        View v = s.views[3];
        s.viewsAt(1290);
        assertTrue(v.battle());
        v.state = View.RUNNING;
        LocalTape fighter = new LocalTape(Session.SLOTS);
        fighter.watch(3);
        for (long step = 1291; step <= 1325; step++) {
            fighter.record(step, 2);
        }
        s.tape.in(fighter.out(3));
        s.viewEntry(1300, 0, 5, 2);
        for (long step = 1291; step <= 1320; step++) {
            if (step == 1320) {
                s.clock.resetTo(1320);
                s.viewsAt(1320);
            }
            s.localOrders(step);
        }
        assertEquals(1320, v.rejoinAt(), "another watcher joined");
        assertTrue(s.viewHolds(1321));
        s.viewRejoinStart(city(1320, s, 0, new byte[] {1}));
        s.afterLoad();
        s.viewLoadSet();
        assertTrue(s.watchMayRun(1321));
        s.localOrders(1321);
        assertEquals(1, s.localExtraRounds(), "step 1321 of the same tape");
        assertEquals(1325, s.tape.reached());
    }

    @Test
    void aViewerWhoseViewEndedCanAskAgain() {
        Session s = session(0); // the host
        s.ai(sosdet.ai.AiLog.host(0, e -> { }));
        View v = frozenView(s);
        s.viewEntry(47, 3, -1, View.CLOSED);
        s.viewsAt(60);
        assertEquals(View.ENDED, v.state());
        assertNull(Engine.viewRefusal(3, 5));
        Engine.viewBegin(3, 5); // the host's second view of a playtest crashed here: "has a view already"
    }

    @Test
    void theOwnerHoldsForNoSaveOfAViewEndedInItsFreezeStep() {
        Session s = session(5); // the owner's PC
        View v = frozenView(s);
        assertTrue(s.viewHolds(31), "until the mod takes its save");
        s.viewEntry(30, 3, -1, View.CLOSED);
        assertFalse(s.viewHolds(31));
        assertNull(Engine.viewOut());
        assertSame(v, s.views[3]);
    }
}

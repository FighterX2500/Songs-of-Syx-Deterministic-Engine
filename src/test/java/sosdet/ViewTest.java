package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosdet.game.cmd.PlanStash;
import sosdet.report.CitySource;
import sosdet.report.Field;
import sosdet.report.ReportLayout;

/**
 * A city view's limits and ends (plan 0012 M2, SOS-82): every PC ends a view at one step alike, with a reason the mod
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
        s.battle = new OwnBattle(40, 5, 2, 0);
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

package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosdet.game.cmd.PlanStash;
import sosdet.report.CitySource;
import sosdet.report.Field;
import sosdet.report.ReportLayout;

/**
 * A battle's restart by the players' vote (mod spec §8, SOS-231): its key travels as a long and in the log; every PC
 * decides alike, inside the step, whether it restarts, and which views load the owner's save again.
 */
class BattleRestartTest {

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
        return s;
    }

    @AfterEach
    void endSession() {
        Engine.end();
    }

    // ------------------------------------------------------------ the key

    @Test
    void aKeyGoesThroughALongAndTheLogUnchanged() {
        BattleKey k = new BattleKey(BattleKey.ASSAULT, 5, 123_456_789_012L);
        BattleKey back = BattleKey.of(k.toLong());
        assertEquals(BattleKey.ASSAULT, back.kind);
        assertEquals(5, back.human);
        assertEquals(123_456_789_012L, back.step);
        assertTrue(k.toLong() >= 0, "never NONE");
        int[] ints = k.ints();
        assertTrue(k.same(BattleKey.ofInts(ints[0], ints[1], ints[2], ints[3])));
        assertFalse(k.same(new BattleKey(BattleKey.LOCAL, 5, 123_456_789_012L)));
        assertEquals(new BattleKey(BattleKey.BATTLE, 63, 0).toLong(), BattleKey.pack(BattleKey.BATTLE, 63, 0));
    }

    @Test
    void aKeyRefusesWhatNoBattleHas() {
        assertThrows(IllegalArgumentException.class, () -> new BattleKey(3, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BattleKey(BattleKey.LOCAL, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BattleKey(BattleKey.LOCAL, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> BattleKey.of(BattleKey.NONE));
    }

    // ------------------------------------------------------------ the roster

    @Test
    void aRestartPutsEveryArmyBackOnTheField() {
        BattleRoster r = new BattleRoster(0, 3, new int[] {5});
        r.gone[2] = true;
        r.leaving = 2;
        r.speedVote.vote(0, 2);
        r.speedVote.vote(1, 2);
        r.speedVote.leave(2);
        assertEquals(2, r.speedVote.commanders());
        r.restart();
        assertEquals(-1, r.leaving);
        assertFalse(r.gone[2]);
        assertEquals(3, r.speedVote.commanders(), "the ally votes again");
        assertEquals(2, r.speedVote.count(2), "the last votes stand");
    }

    // ------------------------------------------------------------ a local battle

    /** Human 3's local battle, engaged at 1234 and running from 1260; human 0 watches it from 1290. */
    private static LocalBattle watchedLocal(Session s) {
        LocalBattle b = s.localEngage(1234, 3);
        s.localsAt(1260);
        s.viewEntry(1270, 0, 3, 1);
        s.viewsAt(1290);
        assertTrue(s.views[0].frozen());
        assertTrue(s.views[0].battle());
        return b;
    }

    @Test
    void aLocalBattleRestartsAndItsWatchersLoadTheFightersSaveThere() {
        for (int local : new int[] {0, 3, 5}) {
            Session s = session(local);
            LocalBattle b = watchedLocal(s);
            b.roster.gone[0] = true;
            BattleKey k = new BattleKey(BattleKey.LOCAL, 3, 1234);
            s.clock.resetTo(1500);
            assertNull(s.restartRefusal(k));
            s.battleRestart(1500, k);
            assertEquals(1500, b.restartAt);
            assertEquals(1500, s.views[0].rejoinAt(), "the watcher loads the fighter's game from the restart");
            assertFalse(b.roster.gone[0]);
            Notice n = s.notices.poll();
            assertEquals(Notice.RESTART, n.kind());
            assertEquals(3, n.first());
            assertEquals(BattleKey.LOCAL, n.second());
            assertEquals(1, n.value());
            Engine.end();
        }
    }

    @Test
    void aLocalBattleDoesntRestartWhileStartingEndingOrAViewerComesOrGoes() {
        Session s = session(5);
        LocalBattle b = s.localEngage(1234, 3);
        BattleKey k = new BattleKey(BattleKey.LOCAL, 3, 1234);
        s.clock.resetTo(1250);
        assertEquals("That battle is over.", s.restartRefusal(k), "not running before its freeze");
        s.localsAt(1260);
        s.clock.resetTo(1260);
        assertEquals("That battle is starting.", s.restartRefusal(k), "generated in its freeze step");
        s.clock.resetTo(1270);
        assertEquals("That battle is over.", s.restartRefusal(new BattleKey(BattleKey.LOCAL, 3, 999)), "another battle");
        s.viewEntry(1270, 0, 3, 1);
        assertEquals("A player is joining it.", s.restartRefusal(k));
        s.viewsAt(1290);
        s.clock.resetTo(1300);
        assertNull(s.restartRefusal(k));
        s.worldPrompt = new Object();
        assertEquals("A battle prompt is open.", s.restartRefusal(k), "the fighter's load would drop it there only");
        s.worldPrompt = null;
        s.endView(0, 1300, View.CLOSED, null);
        assertEquals("A player is leaving it.", s.restartRefusal(k));
        s.battleRestart(1300, k);
        assertEquals(-1, b.restartAt, "skipped on every PC alike");
        assertEquals(0, s.notices.poll().value(), "the players hear it couldn't");
        s.viewsAt(1320);
        assertNull(s.restartRefusal(k));
        assertTrue(s.localResult(1330, 3, new Object[0]));
        s.clock.resetTo(1330);
        assertEquals("That battle is over.", s.restartRefusal(k));
    }

    @Test
    void theHostLogsOneRestartAtATime() {
        Session s = session(0);
        watchedLocal(s);
        BattleKey k = new BattleKey(BattleKey.LOCAL, 3, 1234);
        s.clock.resetTo(1400);
        assertNull(s.restartRefusalHost(k));
        s.restartLogged = k;
        assertEquals("A restart is on its way.", s.restartRefusalHost(k));
        s.battleRestart(1430, k);
        assertNull(s.restartLogged, "applied: the next may go in");
        assertThrows(IllegalStateException.class, () -> Engine.restartBattle(k.toLong()), "only the host logs one");
    }

    /**
     * The host logs no restart of a battle against NPCs while its PC is still generating it: a battle it couldn't make
     * (a sally called off) sends a result from there, which the host must have logged before the restart, so that the
     * restart is refused in its step on every PC alike and no PC loads a battle that doesn't exist (SOS-231).
     */
    @Test
    void theHostLogsNoRestartOfABattleItsPcIsStillGenerating() {
        Session s = session(0);
        LocalBattle b = watchedLocal(s);
        BattleKey k = new BattleKey(BattleKey.LOCAL, 3, 1234);
        s.clock.resetTo(1400);
        b.away = true;
        assertEquals("That battle is being set up.", s.restartRefusalHost(k));
        assertNull(s.restartRefusal(k), "the other PCs don't know: only the host logs it");
        b.away = false;
        assertNull(s.restartRefusalHost(k));
        assertTrue(s.localResult(1401, 3, new Object[] {-1}), "the called-off result, logged first");
        s.clock.resetTo(1401);
        s.battleRestart(1401, k);
        assertEquals(-1, b.restartAt, "refused in its step on every PC alike");
        assertEquals(-1, s.views[0].rejoinAt(), "no watcher waits for a battle that doesn't exist");
    }

    @Test
    void theBattleThisPcSeesIsItsOwnLocalBattleOrTheOneItWatches() {
        Session fighter = session(3);
        LocalBattle b = fighter.localEngage(1234, 3);
        assertEquals(BattleKey.NONE, fighter.battleHere(), "nothing runs before the freeze");
        fighter.localsAt(1260);
        assertEquals(new BattleKey(BattleKey.LOCAL, 3, 1234).toLong(), fighter.battleHere());
        Engine.end();

        Session other = session(5);
        watchedLocal(other);
        assertEquals(BattleKey.NONE, other.battleHere(), "a PC outside it sees none");
        assertFalse(b.restartAt >= 0);
    }

    @Test
    void thePlayersPressOfRestartIsTakenOnce() {
        Session s = session(0);
        assertFalse(s.takeRestartAsked());
        Hooks.restartAsked();
        assertTrue(s.takeRestartAsked());
        assertFalse(s.takeRestartAsked());
    }

    // ------------------------------------------------------------ the assault

    /** Human 3 storms human 5's capital at 2000: every other human views it from 2010, the invasion starts at 2011. */
    private static Assault runningAssault(Session s) {
        s.assaultBegin(2000, 3, 5, null, new int[0], 100, new int[0], new int[] {3}, new int[] {100});
        Assault a = s.assault;
        assertNotNull(a);
        s.viewsAt(a.freeze);
        assertTrue(s.views[0].frozen());
        assertTrue(s.views[3].frozen());
        a.started = true;
        a.startedAt = a.freeze + 1;
        return a;
    }

    @Test
    void anAssaultRestartsAndEveryViewerLoadsTheDefendersCityThere() {
        for (int local : new int[] {0, 3, 5}) {
            Session s = session(local);
            Assault a = runningAssault(s);
            BattleKey k = new BattleKey(BattleKey.ASSAULT, 5, 2000);
            s.clock.resetTo(a.startedAt);
            assertEquals("The assault is starting or reloading.", s.restartRefusal(k), "its start save comes after this step");
            a.forced = Assault.RETREAT;
            a.detected = Assault.HELD;
            s.clock.resetTo(2400);
            assertNull(s.restartRefusal(k));
            s.battleRestart(2400, k);
            assertEquals(2400, a.restartAt);
            assertEquals(-1, a.forced, "a retreat ordered before it is undone with the fight");
            assertEquals(-1, a.detected);
            assertEquals(2400, s.views[0].rejoinAt());
            assertEquals(2400, s.views[3].rejoinAt());
            assertEquals(Notice.ASSAULT, s.notices.poll().kind());
            Notice n = s.notices.poll();
            assertEquals(Notice.RESTART, n.kind());
            assertEquals(1, n.value());
            Engine.end();
        }
    }

    @Test
    void theDefenderSeesTheAssaultOnItsCity() {
        Session s = session(5);
        runningAssault(s);
        assertEquals(new BattleKey(BattleKey.ASSAULT, 5, 2000).toLong(), s.battleHere());
    }

    @Test
    void anAssaultDoesntRestartWhileReloadingOrOver() {
        Session s = session(0);
        Assault a = runningAssault(s);
        BattleKey k = new BattleKey(BattleKey.ASSAULT, 5, 2000);
        s.clock.resetTo(2400);
        assertEquals(BattleKey.NONE, s.battleHere(), "the viewer's PC hasn't loaded the city yet");
        s.views[0].state = View.RUNNING;
        assertEquals(k.toLong(), s.battleHere(), "now it runs the defender's city");
        a.reopen = true;
        assertEquals("The assault is starting or reloading.", s.restartRefusal(k));
        a.reopen = false;
        a.endLogged = true;
        assertEquals("The assault is ending.", s.restartRefusalHost(k));
        assertNull(s.restartRefusal(k), "only the host knows its end is logged");
        a.endStep = 2410;
        assertEquals("That assault is over.", s.restartRefusal(k));
        assertEquals("That assault is over.", s.restartRefusal(new BattleKey(BattleKey.ASSAULT, 0, 2000)));
    }

    // ------------------------------------------------------------ a battle on every PC

    @Test
    void aBattleOnEveryPcIsOverForARestartOnceItsEndLoads() {
        Session s = session(0);
        OwnBattle b = new OwnBattle(500, 0, 0, 3, 0);
        s.battle = b;
        BattleKey k = new BattleKey(BattleKey.BATTLE, 0, 500);
        assertEquals("That battle is over.", s.restartRefusal(k), "its save isn't loaded yet");
        b.awaiting = false;
        assertNull(s.restartRefusal(k));
        assertEquals(k.toLong(), s.battleHere());
        assertEquals("That battle is over.", s.restartRefusal(new BattleKey(BattleKey.BATTLE, 0, 501)));
        s.battleLeaving = true;
        assertEquals("That battle is over.", s.restartRefusal(k), "it ended in this step");
        s.battleLeaving = false;
        s.removeDue[3] = true;
        assertEquals("A player in it is leaving the game.", s.restartRefusal(k));
    }
}

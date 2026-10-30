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
        s.localAfter(1401);
        assertEquals(List.of(result), seen);
        assertEquals(0, b.armies.length, "the armies move again");
        s.localAfter(1401);
        assertEquals(1, seen.size(), "once");
    }
}

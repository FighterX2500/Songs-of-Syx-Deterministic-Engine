package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosdet.hash.HashLog;
import sosdet.hash.StateSource;

/** The engine through the hooks the game calls: vanilla without a session, deterministic with one. */
class EngineTest {

    private final Random simulation = new Random(7);

    /** The state hash needs a battle; this one has two divisions and never changes. */
    private static final StateSource NO_BATTLE = new StateSource() {
        @Override
        public int divisions() {
            return 2;
        }

        @Override
        public void hash(long[] sections, long[] divisions) {
        }

        @Override
        public String divisionName(int index) {
            return "division " + index;
        }
    };

    @AfterEach
    void endSession() {
        Engine.end();
    }

    @Test
    void withoutASessionEveryHookIsVanilla() {
        assertFalse(Engine.active());
        assertFalse(Hooks.fixedSteps());
        assertFalse(Hooks.skipPausedFrame());
        assertTrue(Hooks.autoSaveAllowed());
        assertFalse(Hooks.worldHeld());
        assertFalse(Hooks.battleStateInStep());
        assertSame(simulation, Hooks.random(simulation));
        assertFalse(Hooks.battleJobsNow(1.0 / 60));
        assertFalse(Hooks.threadRunsInStep(false));
        assertFalse(Hooks.threadRunsInStep(true));
        assertTrue(Hooks.slaveWorking(true));
        assertFalse(Hooks.slaveWorking(false));
        assertFalse(Hooks.pathsNow());
        assertEquals(3, Hooks.simulationValue(3, 1));
        assertTrue(Hooks.cameraMatters());
        Hooks.cosmeticBegin(); // no-ops, no failure
        Hooks.cosmeticEnd();
        Hooks.loadBegin();
        Hooks.loadEnd();
        assertThrows(IllegalStateException.class, Engine::step);
    }

    @Test
    void aSessionRunsWholeStepsUpToItsLimit() {
        Engine.begin();
        assertTrue(Hooks.fixedSteps());
        Engine.runUntil(2);
        int ran = 0;
        for (int i = Hooks.stepsFor(0.1); i > 0; i--) { // 6 steps of game time
            if (!Hooks.stepBegin()) {
                break;
            }
            assertSame(simulation, Hooks.random(simulation));
            assertTrue(Hooks.battleJobsNow(Engine.STEP_SECONDS));
            Hooks.stepEnd();
            ran++;
        }
        assertEquals(2, ran);
        assertEquals(2, Engine.step());
        assertNotSame(simulation, Hooks.random(simulation));
        Stats stats = Engine.stats();
        assertEquals(2, stats.simulationDraws);
        assertEquals(2, stats.timedSteps);
    }

    @Test
    void battleJobsUseTheSessionStepForTheirBeat() {
        Engine.begin();
        Engine.session().source = NO_BATTLE;
        Engine.runFreely();
        int[] status = {0};
        Runnable none = () -> { };
        for (int i = Hooks.stepsFor(1.0); i > 0 && Hooks.stepBegin(); i--) {
            Hooks.runBattleJobs(none, none, none, none, () -> status[0]++);
            Hooks.stepEnd();
        }
        assertEquals(2, status[0]); // steps 30 and 60
    }

    @Test
    void theStateIsHashedEveryThirtyStepsAndAgainFromALoad() {
        Engine.begin();
        Engine.session().source = NO_BATTLE;
        Engine.runFreely();
        for (int i = Hooks.stepsFor(1.0); i > 0 && Hooks.stepBegin(); i--) {
            Hooks.random(simulation).nextInt();
            Hooks.stepEnd();
        }
        assertEquals(2, Engine.stats().hashes);
        long at30 = Engine.hash(30);
        assertTrue(at30 != Engine.hash(60), "the clock section moves on");
        assertThrows(IllegalArgumentException.class, () -> Engine.hash(45));
        Engine.resetStats();
        Hooks.loadBegin();
        Hooks.loadEnd();
        Engine.runFreely();
        for (int i = Hooks.stepsFor(0.5); i > 0 && Hooks.stepBegin(); i--) {
            Hooks.random(simulation).nextInt();
            Hooks.stepEnd();
        }
        assertEquals(at30, Engine.hash(30), "draws since the load, not since the stats were reset");
        assertThrows(IllegalStateException.class, () -> Engine.hashEvery(15), "only at step 0");
        assertEquals(HashLog.DEFAULT_INTERVAL, 30);
    }

    @Test
    void stepZeroRunsInsideTheSimulationAndOnlyOnce() {
        Engine.begin();
        Random[] drawn = new Random[1];
        Engine.atStepZero(() -> drawn[0] = Hooks.random(simulation));
        assertSame(simulation, drawn[0]);
        assertNotSame(simulation, Hooks.random(simulation));
        Engine.runUntil(1);
        Hooks.stepsFor(Engine.STEP_SECONDS * 1.5);
        assertTrue(Hooks.stepBegin());
        Hooks.stepEnd();
        assertThrows(IllegalStateException.class, () -> Engine.atStepZero(() -> { }));
    }

    @Test
    void aLoadRestartsTheSessionAtStepZero() {
        Engine.begin();
        Engine.runFreely();
        Hooks.stepsFor(0.1);
        Hooks.stepBegin();
        Hooks.stepEnd();
        Hooks.loadBegin();
        Hooks.loadEnd();
        assertEquals(0, Engine.step());
        Hooks.stepsFor(1.0);
        assertFalse(Hooks.stepBegin(), "after a load the session holds until told to run");
    }

    @Test
    void localSettingsAndTheCameraStopMattering() {
        Engine.begin();
        assertEquals(1, Hooks.simulationValue(3, 1));
        assertFalse(Hooks.cameraMatters());
        assertFalse(Hooks.autoSaveAllowed());
        assertTrue(Hooks.threadRunsInStep(false));
        assertTrue(Hooks.threadRunsInStep(true), "in-step mode: the general's job runs in the step too");
        assertFalse(Hooks.slaveWorking(true), "a battle thread is only working while its job runs in the step");
    }

    @Test
    void oneSessionAtATime() {
        Engine.begin();
        assertThrows(IllegalStateException.class, Engine::begin);
    }

    @Test
    void fixedTablesAreTheSameEveryTime() {
        assertEquals(Hooks.tileOrderRandom().nextLong(), Hooks.tileOrderRandom().nextLong());
        assertEquals(0x52414E31L, Hooks.randomTableSeed(1));
        assertEquals(0x52414E32L, Hooks.randomTableSeed(2));
    }
}

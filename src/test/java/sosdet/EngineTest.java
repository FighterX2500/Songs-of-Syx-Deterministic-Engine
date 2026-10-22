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

        @Override
        public String describeDivisions(long step) {
            return "";
        }

        @Override
        public String describeEntities(long step) {
            return "";
        }
    };

    /**
     * A session as these tests need it: the AI general in-step, since the default (on the host's thread) needs a
     * running game for its adapter.
     */
    private static void begin() {
        Engine.begin();
        Engine.aiInStep();
    }

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
        begin();
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
        begin();
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
        begin();
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

    /** Runs steps 1 to 5, drawing once per step plus {@code extra} times at step 2; returns step 5's draw. */
    private static int fifthStepDraw(Random generator, int extra) {
        begin();
        Engine.seed(42);
        Engine.runFreely();
        int last = 0;
        for (int i = Hooks.stepsFor(5 * Engine.STEP_SECONDS + 1e-9); i > 0 && Hooks.stepBegin(); i--) {
            last = Hooks.random(generator).nextInt();
            if (Engine.step() == 2) {
                for (int e = 0; e < extra; e++) {
                    Hooks.random(generator).nextInt();
                }
            }
            Hooks.stepEnd();
        }
        assertEquals(5, Engine.step());
        Engine.end();
        return last;
    }

    @Test
    void theStreamIsReseededEveryStepFromTheSeedAndTheStep() {
        int plain = fifthStepDraw(new Random(1), 0);
        assertEquals(plain, fifthStepDraw(new Random(99), 7), "earlier draws and the generator's history don't matter");
        assertThrows(IllegalStateException.class, () -> {
            begin();
            Engine.runFreely();
            Hooks.stepsFor(0.1);
            Hooks.stepBegin();
            Hooks.stepEnd();
            Engine.seed(1);
        }, "the seed is set at step 0");
    }

    @Test
    void theStepLengthFollowsTheAgreedSpeed() {
        begin();
        Engine.runFreely();
        assertEquals(60, Hooks.stepsFor(1.0, 1), "1x: 1/60 s steps");
        assertEquals(1.0 / 60, Hooks.stepSeconds());
        assertEquals(16, Hooks.stepsFor(1.0, 5), "in-step, one PC: 5x is the agreed speed at once, 1/16 s steps");
        assertEquals(1.0 / 16, Hooks.stepSeconds());
        assertEquals(16, Hooks.stepsFor(1.0, 0), "paused: the length stays");
        Engine.end();
        begin();
        Engine.speed(25);
        assertEquals(1.0 / 16, Hooks.stepSeconds(), "the starting speed, set at step 0");
        assertEquals(25.0, Engine.stats().agreedSpeed);
        Engine.runFreely();
        Hooks.stepsFor(0.1, 25);
        Hooks.stepBegin();
        Hooks.stepEnd();
        assertThrows(IllegalStateException.class, () -> Engine.speed(1), "only at step 0");
    }

    @Test
    void stepZeroRunsInsideTheSimulationAndOnlyOnce() {
        begin();
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
        begin();
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
        begin();
        assertEquals(1, Hooks.simulationValue(3, 1));
        assertFalse(Hooks.cameraMatters());
        assertFalse(Hooks.autoSaveAllowed());
        assertTrue(Hooks.threadRunsInStep(false));
        assertTrue(Hooks.threadRunsInStep(true), "in-step mode: the general's job runs in the step too");
        assertFalse(Hooks.slaveWorking(true), "a battle thread is only working while its job runs in the step");
    }

    @Test
    void oneSessionAtATime() {
        begin();
        assertThrows(IllegalStateException.class, Engine::begin);
    }

    @Test
    void fixedTablesAreTheSameEveryTime() {
        assertEquals(Hooks.tileOrderRandom().nextLong(), Hooks.tileOrderRandom().nextLong());
        assertEquals(0x52414E31L, Hooks.randomTableSeed(1));
        assertEquals(0x52414E32L, Hooks.randomTableSeed(2));
    }
}

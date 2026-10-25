package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosdet.game.StepParts;
import sosdet.game.cmd.PlanStash;
import sosdet.hash.HashLog;
import sosdet.hash.StateSource;
import sosdet.report.CityReport;
import sosdet.report.CitySource;
import sosdet.report.Field;
import sosdet.report.ReportFeed;
import sosdet.report.ReportLayout;
import sosdet.txn.Txn;

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
     * running game for its adapter, and no room plan stash, which needs one too.
     */
    private static void begin() {
        Engine.begin();
        Engine.aiInStep();
        Engine.session().plans = PlanStash.off();
    }

    /** One player faction, a small report layout, values that never change. */
    private static final ReportLayout SMALL = new ReportLayout(1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
    private static final CitySource CITY = new CitySource() {
        @Override
        public ReportLayout layout() {
            return SMALL;
        }

        @Override
        public int faction() {
            return 0;
        }

        @Override
        public int[] humans() {
            return new int[] { 0 };
        }

        @Override
        public void fill(double[] values) {
        }

        @Override
        public int indexOf(Field f, Object key) {
            return -1;
        }
    };

    /** An own-city session as PC {@code pc}: the world reads the reports through the fakes, nothing crosses. */
    private static Session ownCity(int pc) {
        begin();
        Session s = Engine.session();
        s.source = NO_BATTLE;
        s.city = CITY;
        s.inputApplier = r -> { };
        s.txnApplier = t -> { };
        Engine.localPc(pc);
        Engine.ownCity();
        Engine.seed(5);
        return s;
    }

    /** Runs whole steps, the city part drawing {@code cityDraws} times and the world part once; returns steps run. */
    private int steps(int max, int cityDraws) {
        int run = 0;
        while (run < max && Hooks.stepBegin()) {
            Hooks.stepPart(StepParts.CITY);
            for (int d = 0; d < cityDraws; d++) {
                Hooks.random(simulation).nextInt();
            }
            Hooks.stepPart(StepParts.WORLD_PART);
            Hooks.random(simulation).nextInt();
            Hooks.stepEnd();
            run++;
        }
        return run;
    }

    private static byte[] reportRecord(long takenAt, long appliesAt) {
        return reportRecord(0, takenAt, appliesAt);
    }

    private static byte[] reportRecord(int faction, long takenAt, long appliesAt) {
        ReportFeed f = new ReportFeed();
        f.write(new CityReport(SMALL, faction, takenAt, appliesAt, new double[SMALL.size()]).toBytes());
        return f.drain();
    }

    /** CITY with these human slots. */
    private static CitySource humans(int... slots) {
        return new CitySource() {
            @Override
            public ReportLayout layout() {
                return SMALL;
            }

            @Override
            public int faction() {
                return slots[0];
            }

            @Override
            public int[] humans() {
                return slots;
            }

            @Override
            public void fill(double[] values) {
            }

            @Override
            public int indexOf(Field f, Object key) {
                return -1;
            }
        };
    }

    /**
     * ADR 0004: with several humans the world part waits for every other human's report, keeps each human's apart, and
     * the city tail applies only this PC's human's transactions.
     */
    @Test
    void theWorldWaitsForEveryOtherHumansReportAndKeepsEachApart() {
        Session s = ownCity(0);
        s.city = humans(0, 3);
        Engine.pcHuman(0, 0);
        Engine.pcHuman(1, 3);
        Engine.runFreely();
        assertEquals(0, steps(10, 1), "no load report of the human in slot 3 yet");
        assertTrue(Engine.reportsOut().length > 0, "this PC's load report went out before it waits for slot 3's");
        Engine.reportsIn(1, reportRecord(3, 1, 1));
        assertEquals(59, steps(100, 1), "slot 3's report taken at 30 is read from 60");
        Engine.reportsIn(1, reportRecord(3, 30, 60));
        Engine.reportsIn(1, reportRecord(3, 30, 60)); // a repeat changes nothing
        assertEquals(30, steps(100, 1));
        assertEquals(30, s.reports(3).current().takenAt);
        assertEquals(30, s.reports(0).current().takenAt, "this PC's own, taken here");
        assertEquals(2, Engine.stats().reportsReceived);
        assertThrows(IllegalArgumentException.class, () -> Engine.reportsIn(1, reportRecord(5, 60, 90)), "not PC 1's");
        assertThrows(IllegalArgumentException.class, () -> Engine.reportsIn(2, reportRecord(0, 60, 90)), "this PC's");
        assertThrows(IllegalArgumentException.class, () -> Engine.reportsIn(0, reportRecord(3, 60, 90)), "this PC");

        java.util.List<Txn> applied = new java.util.ArrayList<>();
        s.txnApplier = applied::add;
        Txn mine = new Txn();
        Txn theirs = new Txn();
        theirs.human = 3;
        s.ownTxns.accept(mine);
        s.ownTxns.accept(theirs);
        assertEquals(java.util.List.of(mine), applied, "slot 3's city is its own PC's");
    }

    /** ADR 0004 (SOS-46): with several humans the run doesn't start until each human has exactly one PC. */
    @Test
    void severalHumansNeedOnePcEach() {
        Session s = ownCity(0);
        s.city = humans(0, 3);
        Engine.pcHuman(1, 3);
        Engine.runFreely();
        assertThrows(IllegalStateException.class, () -> steps(1, 1), "slot 0 has no PC");
        assertThrows(IllegalStateException.class, () -> Engine.reportsFrom(1), "each PC runs its own human's city");
    }

    /** ADR 0005 §1: an own city's draws are its own; the hash counts the world part's, so two cities compare equal. */
    @Test
    void anOwnCitysDrawsNeverReachTheHash() {
        ownCity(0);
        Engine.runFreely();
        assertEquals(30, steps(30, 0));
        long quiet = Engine.hash(30);
        Engine.end();
        ownCity(1);
        Engine.runFreely();
        assertEquals(30, steps(30, 3));
        assertEquals(quiet, Engine.hash(30));
        assertThrows(IllegalStateException.class, () -> Engine.resyncSave(null), "a whole-game resync would take the city");
    }

    /** SOS-38's follower: the world part waits at each step for the source's report it reads (ADR 0005 §4). */
    @Test
    void aFollowerRunsTheWorldOnTheSourcesReportsAndWaitsForThem() {
        Session s = ownCity(1);
        Engine.reportsFrom(0);
        Engine.runFreely();
        assertEquals(0, steps(10, 1), "no load report from PC 0 yet");
        byte[] load = reportRecord(1, 1);
        Engine.reportsIn(0, Arrays.copyOf(load, 3)); // in any pieces
        Engine.reportsIn(0, Arrays.copyOfRange(load, 3, load.length));
        assertEquals(59, steps(100, 1), "the report taken at 30 is read from 60");
        Engine.reportsIn(0, reportRecord(30, 60));
        Engine.reportsIn(0, reportRecord(30, 60)); // a repeat changes nothing
        assertEquals(30, steps(100, 1));
        assertEquals(89, Engine.step());
        assertEquals(30, s.reports(0).current().takenAt);
        assertEquals(2, Engine.stats().reportsReceived);
        assertTrue(Engine.reportsOut().length > 0, "the follower's own reports still go out");
        assertThrows(IllegalArgumentException.class, () -> Engine.reportsIn(2, load), "the world reads PC 0's city");
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
        assertTrue(Hooks.localDraft(true));
        assertEquals(7, Hooks.cacheKey(7));
        assertTrue(Hooks.developerFlag(true));
        assertEquals(0.75, Hooks.simulationValue(0.75, 0.0));
        assertFalse(Hooks.sharedStateOnly());
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
        // frames a second apart: longer than a frame may be, so each takes the game time given (SOS-42)
        assertEquals(60, Hooks.stepsFor(1.0, 1, Pacer.SECOND), "1x: 1/60 s steps");
        assertEquals(1.0 / 60, Hooks.stepSeconds());
        assertEquals(16, Hooks.stepsFor(1.0, 5, 2 * Pacer.SECOND),
                "in-step, one PC: 5x is the agreed speed at once, 1/16 s steps");
        assertEquals(1.0 / 16, Hooks.stepSeconds());
        assertEquals(16, Hooks.stepsFor(1.0, 0, 3 * Pacer.SECOND), "paused: the length stays");
        assertEquals(30, Hooks.stepsFor(1.0, 1.5, 4 * Pacer.SECOND), "1.5x: 1/30 s steps (SOS-42)");
        assertEquals(1.0 / 30, Hooks.stepSeconds());
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
    void theWorldStepReadsNoLocalDraftDeveloperFlagOrProfileValue() {
        begin();
        assertTrue(Hooks.localDraft(true), "between steps the UI still shows this PC's upgrade draft");
        assertFalse(Hooks.localDraft(false));
        assertFalse(Hooks.developerFlag(true));
        assertEquals(0.0, Hooks.simulationValue(0.75, 0.0));
        assertTrue(Hooks.sharedStateOnly());
        Engine.runFreely();
        Hooks.stepsFor(Engine.STEP_SECONDS + 1e-9);
        assertTrue(Hooks.stepBegin());
        assertFalse(Hooks.localDraft(true), "inside the step the real levels count");
        assertFalse(Hooks.outsideStep());
        Hooks.stepEnd();
        assertTrue(Hooks.outsideStep());
    }

    @Test
    void theStepsQuestionsAreKeptByStepAndOrderAndTakenOnce() {
        begin();
        assertFalse(Hooks.stepPromptsKept(), "between steps a question is the game's own");
        Engine.runFreely();
        Hooks.stepsFor(Engine.STEP_SECONDS + 1e-9);
        assertTrue(Hooks.stepBegin());
        assertTrue(Hooks.stepPromptsKept());
        String yes = "yes";
        String no = "no";
        long asked = Engine.step();
        long first = Hooks.stepPromptOpened(yes, no);
        long second = Hooks.stepPromptOpened(yes, null);
        Hooks.stepEnd();
        assertEquals(asked * 64 + 32, first, "the key is the step that asked and the order in it: a city question's 32 on");
        assertEquals(first + 1, second);
        assertEquals(first, Hooks.stepPromptOldest());
        assertSame(no, Hooks.stepPromptTake(first, false));
        assertEquals(null, Hooks.stepPromptTake(first, true), "a second answer finds it gone");
        assertEquals(null, Hooks.stepPromptTake(second, false), "no action for that answer");
        assertEquals(-1, Hooks.stepPromptOldest());
    }

    /** ADR 0004 (SOS-46): a world question asks the current human; only that human's PC shows it, and only its answer applies. */
    @Test
    void aWorldQuestionIsItsHumansOnly() {
        begin();
        Session s = Engine.session();
        Engine.runFreely();
        Hooks.stepsFor(Engine.STEP_SECONDS + 1e-9);
        assertTrue(Hooks.stepBegin());
        s.part = StepParts.WORLD_PART;
        s.currentHuman = 3;
        long asked = Engine.step();
        assertEquals(-1, Hooks.stepPromptOpened("yes", "no"), "slot 3's question doesn't show on slot 0's PC");
        s.currentHuman = 0;
        long mine = Hooks.stepPromptOpened("yes", "no");
        s.part = -1;
        Hooks.stepEnd();
        assertEquals(asked * 64 + 1, mine, "world questions count apart from city ones, from 0");
        assertTrue(Hooks.stepPromptWorld(mine));
        assertEquals(mine, Hooks.stepPromptOldest(), "the rig answers only this PC's human's");
        s.currentHuman = 0;
        assertEquals(null, Hooks.stepPromptTake(asked * 64, true), "slot 0 can't answer slot 3's question");
        s.currentHuman = 3;
        assertEquals("yes", Hooks.stepPromptTake(asked * 64, true), "slot 3's answer applies");
        s.currentHuman = 0;
    }

    /** SOS-46 M2: the world part works on a human for one call and gets the one before back, nested or not. */
    @Test
    void theCurrentHumanSwapsAndComesBack() {
        assertEquals(0, Hooks.currentHumanSwap(5), "without a session nothing changes");
        assertEquals(7, Hooks.worldInvasionRef(7), "without a session an invasion keeps its city's ref");
        begin();
        Session s = Engine.session();
        s.part = StepParts.WORLD_PART;
        s.currentHuman = 2;
        int outer = Hooks.currentHumanSwap(5);
        assertEquals(2, outer);
        assertEquals(5, s.currentHuman);
        int inner = Hooks.currentHumanSwap(1);
        assertEquals(5, inner);
        assertEquals((1 << 24) + 7, Hooks.worldInvasionRef(7), "the world names an invasion by its city's human");
        Hooks.currentHumanSwap(inner);
        assertEquals(5, s.currentHuman, "the inner call gives the outer human back");
        Hooks.currentHumanSwap(outer);
        assertEquals(2, s.currentHuman, "and the outer call the one before");
        s.part = -1;
        assertEquals(7, Hooks.worldInvasionRef(7), "outside the world part a ref is the city's own");
        s.currentHuman = 0;
    }

    @Test
    void theDialogShowsTheLastQuestionAskedUntilItIsAnswered() {
        begin();
        assertEquals(-1, Hooks.stepPromptShown());
        Engine.runFreely();
        Hooks.stepsFor(Engine.STEP_SECONDS + 1e-9);
        assertTrue(Hooks.stepBegin());
        long first = Hooks.stepPromptOpened("yes", "no");
        long second = Hooks.stepPromptOpened("yes", "no");
        Hooks.stepEnd();
        assertEquals(second, Hooks.stepPromptShown(), "the game's dialog shows the last question asked");
        Hooks.stepPromptHidden(first);
        assertEquals(second, Hooks.stepPromptShown(), "another question's answer leaves it showing");
        Hooks.stepPromptHidden(second);
        assertEquals(-1, Hooks.stepPromptShown());
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

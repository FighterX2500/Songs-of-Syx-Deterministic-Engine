package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StepClockTest {

    /** SOS-90: the lockstep window is half a second of real time, 30 steps at the least and 400 at the most. */
    @Test
    void theWindowIsHalfASecondAtEverySpeed() {
        assertEquals(30, Session.windowSteps(1));
        assertEquals(30, Session.windowSteps(2), "16 steps would be under the least");
        assertEquals(40, Session.windowSteps(5));
        assertEquals(200, Session.windowSteps(25));
        assertEquals(400, Session.windowSteps(50));
        assertEquals(400, Session.windowSteps(100), "the log's longest delay");
    }

    @Test
    void holdsAtStepZeroUntilToldToRun() {
        StepClock clock = new StepClock();
        assertEquals(60, clock.stepsFor(1.0));
        assertFalse(clock.begin());
        assertEquals(0, clock.step());
    }

    @Test
    void neverRunsPastTheLastStep() {
        StepClock clock = new StepClock();
        clock.runUntil(3);
        int ran = 0;
        for (int i = clock.stepsFor(1.0); i > 0 && clock.begin(); i--) {
            ran++;
        }
        assertEquals(3, ran);
        assertEquals(3, clock.step());
        // The game time owed when it held is dropped, so raising the limit doesn't run a burst of old steps.
        clock.runUntil(10);
        assertEquals(0, clock.stepsFor(0));
    }

    /** A desync hold stops the clock at its step whatever the last step allows, until it ends (SOS-53). */
    @Test
    void aDesyncHoldWinsOverTheLastStep() {
        StepClock clock = new StepClock();
        clock.runUntil(StepClock.NO_LIMIT);
        clock.holdAt(5);
        int ran = 0;
        for (int i = clock.stepsFor(1.0); i > 0 && clock.begin(); i--) {
            ran++;
        }
        assertEquals(5, ran);
        assertFalse(clock.begin());
        clock.runUntil(100); // the mod's or the rig's own limit doesn't end the hold
        assertFalse(clock.begin());
        clock.holdAt(StepClock.NO_LIMIT);
        assertTrue(clock.begin());
        assertEquals(6, clock.step());
    }

    @Test
    void aFrameShorterThanAStepCarriesOver() {
        StepClock clock = new StepClock();
        clock.runUntil(StepClock.NO_LIMIT);
        assertEquals(0, clock.stepsFor(0.01));
        assertEquals(1, clock.stepsFor(0.01));
    }

    @Test
    void stepsDependOnGameTimeNotOnHowFramesSplitIt() {
        StepClock fast = new StepClock();
        StepClock slow = new StepClock();
        int a = 0;
        int b = 0;
        for (int i = 0; i < 600; i++) {
            a += fast.stepsFor(0.05); // 30 s in 20 fps frames at speed 1
        }
        for (int i = 0; i < 100; i++) {
            b += slow.stepsFor(0.3); // 30 s in 20 fps frames at speed 6
        }
        assertTrue(Math.abs(a - 1800) <= 1, "fast " + a);
        assertTrue(Math.abs(b - 1800) <= 1, "slow " + b);
    }

    @Test
    void aFrameRunsItsRealTimeAtTheSpeedNotVanillasCappedTime() {
        StepClock clock = new StepClock();
        long frame = StepClock.MAX_FRAME_NANOS / 5; // 20 frames a second: vanilla gives each 1/32 s
        long now = 1;
        assertEquals(1, clock.stepsForFrame(now, 1.0 / 60, 1), "the first frame takes vanilla's game time");
        int steps = 0;
        for (int i = 0; i < 100; i++) { // 5 s of 20 fps frames at 1x
            now += frame;
            steps += clock.stepsForFrame(now, 1.0 / 32, 1);
        }
        assertTrue(Math.abs(steps - 300) <= 1, "5 s at 1x: " + steps);
        now += frame;
        assertEquals(0, clock.stepsForFrame(now, 0, 1), "a frame vanilla holds (a notice) stays held");
        now += StepClock.MAX_FRAME_NANOS + 1;
        assertEquals(0, clock.stepsForFrame(now, 0, 1), "after a long frame (a pause): vanilla's game time, not the gap");
    }

    @Test
    void aLoadRestartsAtStepZeroAndHolds() {
        StepClock clock = new StepClock();
        clock.runUntil(StepClock.NO_LIMIT);
        clock.stepsFor(0.1);
        clock.begin();
        clock.begin();
        clock.reset();
        assertEquals(0, clock.step());
        assertEquals(0, clock.stepsFor(0.01));
        clock.stepsFor(1.0);
        assertFalse(clock.begin());
    }

    @Test
    void lastStepCannotBeInThePast() {
        StepClock clock = new StepClock();
        clock.runUntil(5);
        clock.stepsFor(1.0);
        while (clock.begin()) {
            // run to 5
        }
        assertThrows(IllegalArgumentException.class, () -> clock.runUntil(4));
    }
}

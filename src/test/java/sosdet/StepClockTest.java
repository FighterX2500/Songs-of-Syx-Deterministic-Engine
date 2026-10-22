package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StepClockTest {

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

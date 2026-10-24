package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptTimerTest {

    /** Steps until the time runs out, at that speed; -1 if it never does within a limit. */
    private static int stepsUntilTimeout(PromptTimer t, double speed) {
        double stepSeconds = StepClock.stepSecondsAt(speed);
        for (int i = 1; i < 1_000_000; i++) {
            if (t.step(stepSeconds, speed)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void theLimitIsRealSecondsAtTheAgreedSpeed() {
        PromptTimer t = new PromptTimer();
        t.open();
        // 1x: 1/60 s steps, 30 s are 1,800 steps
        assertEquals(1800, stepsUntilTimeout(t, 1), 1);
        t.open();
        // 5x: 1/16 s steps of game time, each 1/80 s real: 2,400 steps
        assertEquals(2400, stepsUntilTimeout(t, 5), 1);
        t.open();
        // 0.5x: 1/60 s steps, each 1/30 s real: 900 steps
        assertEquals(900, stepsUntilTimeout(t, 0.5), 1);
    }

    @Test
    void itTimesOutOnceAndNotWithoutAPrompt() {
        PromptTimer t = new PromptTimer();
        assertFalse(t.step(1, 1), "no prompt open");
        t.open();
        assertTrue(stepsUntilTimeout(t, 1) > 0);
        assertFalse(t.running());
        assertFalse(t.step(100, 1), "already answered by the timeout");
    }

    @Test
    void anAnswerStopsTheWaitAndANewPromptStartsAgain() {
        PromptTimer t = new PromptTimer();
        t.open();
        assertFalse(t.step(20, 1));
        t.close();
        assertFalse(t.step(20, 1), "answered: no timeout");
        t.open();
        assertFalse(t.step(20, 1), "the next prompt waits its own 30 s");
        assertTrue(t.step(10, 1));
    }
}

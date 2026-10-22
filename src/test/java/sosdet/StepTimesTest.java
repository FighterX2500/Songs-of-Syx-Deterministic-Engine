package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StepTimesTest {

    @Test
    void percentilesCountsAndMax() {
        StepTimes t = new StepTimes();
        for (int i = 0; i < 998; i++) {
            t.record(2_050_000); // 2.05 ms
        }
        t.record(150_000_000); // 150 ms
        t.record(1_500_000_000); // 1.5 s
        assertEquals(1000, t.steps());
        assertEquals(2.1, t.percentileMs(0.5), 1e-9);
        assertEquals(2.1, t.percentileMs(0.99), 1e-9);
        assertEquals(150.1, t.percentileMs(0.999), 1e-9);
        assertEquals(1500.0, t.percentileMs(1.0), 1e-9);
        assertEquals(1500.0, t.maxMs(), 1e-9);
        assertEquals(2, t.over100Ms());
        assertEquals(1, t.over1S());
    }

    @Test
    void resetClearsEverything() {
        StepTimes t = new StepTimes();
        t.record(5_000_000);
        t.reset();
        assertEquals(0, t.steps());
        assertEquals(0, t.percentileMs(0.5), 1e-9);
        assertEquals(0, t.maxMs(), 1e-9);
    }
}

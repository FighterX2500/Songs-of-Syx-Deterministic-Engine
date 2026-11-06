package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CitySweepTest {

    /** SOS-154: one sweep covers every tile exactly once, in order, whatever the map's size. */
    @Test
    void oneSweepCoversEveryTileOnce() {
        for (int area : new int[] {0, 1, 239, 240, 241, 512 * 512, 1024 * 1024}) {
            int next = 0;
            for (long step = 0; step < CitySweep.STEPS; step++) {
                assertEquals(next, CitySweep.from(step, area), "area " + area + " step " + step);
                next = CitySweep.to(step, area);
            }
            assertEquals(area, next, "area " + area);
        }
    }

    /** The slice depends on the step number alone: a later sweep, a load or a resync sweep the same tiles. */
    @Test
    void theSliceFollowsTheStepNumber() {
        int area = 1024 * 1024;
        assertEquals(CitySweep.from(7, area), CitySweep.from(7 + CitySweep.STEPS * 1000L, area));
        assertEquals(CitySweep.to(7, area), CitySweep.to(7 + CitySweep.STEPS * 1000L, area));
        assertEquals(0, CitySweep.from(CitySweep.STEPS, area));
        assertEquals(4370, CitySweep.to(0, area));
    }

    @Test
    void aNegativeAreaIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CitySweep.from(0, -1));
    }
}

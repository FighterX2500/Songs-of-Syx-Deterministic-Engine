package sosdet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HumanStartTest {

    private static HumanStart at(int tx, int ty) {
        return new HumanStart("HUMAN", "Player", null, tx, ty);
    }

    /** Mod spec §5: a location closer than 6 tiles to another player's can't be picked; 6 or more can. */
    @Test
    void locationsCloserThanSixTilesAreRefused() {
        assertNull(HumanStart.tooClose(10, 10, new int[] {16}, new int[] {10}));
        assertNotNull(HumanStart.tooClose(10, 10, new int[] {15}, new int[] {10}));
        assertNotNull(HumanStart.tooClose(10, 10, new int[] {14}, new int[] {14})); // 5.7 tiles
        assertNull(HumanStart.tooClose(10, 10, new int[0], new int[0]));
    }

    @Test
    void aListBreakingTheRuleOrTooLongIsRefused() {
        assertNull(HumanStart.problem(new HumanStart[] {at(10, 10), at(20, 10), at(10, 20)}));
        String p = HumanStart.problem(new HumanStart[] {at(10, 10), at(30, 10), at(28, 12)});
        assertTrue(p.startsWith("human 2 "), p);
        assertNotNull(HumanStart.problem(new HumanStart[0]));
        HumanStart[] many = new HumanStart[HumanStart.MAX_HUMANS + 1];
        for (int i = 0; i < many.length; i++) {
            many[i] = at(10 * i, 0);
        }
        assertNotNull(HumanStart.problem(many));
        assertNull(HumanStart.problem(java.util.Arrays.copyOf(many, HumanStart.MAX_HUMANS)));
    }

    @Test
    void aStartNeedsARaceAndANameAndKeepsItsOwnBanner() {
        assertThrows(IllegalArgumentException.class, () -> new HumanStart("", "A", null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new HumanStart("HUMAN", null, null, 1, 1));
        byte[] banner = {1, 2, 3};
        HumanStart s = new HumanStart("HUMAN", "A", banner, 1, 1);
        banner[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, s.banner());
    }
}

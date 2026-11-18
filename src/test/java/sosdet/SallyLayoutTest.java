package sosdet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sosdet.report.CityReport;
import sosdet.report.Input;
import sosdet.report.InputKind;
import sosdet.report.ReportLayout;

/** SOS-232: a sally against an invasion with allied humans: who is asked, the slots, the owners and the split by power. */
class SallyLayoutTest {

    private static final int DEFENDER = 0;

    /** The 4-tile rule: an ally's army within 4 tiles along the map is asked, one beyond isn't. */
    @Test
    void anAllyWithin4TilesIsAskedAndOneBeyondIsNot() {
        int[] owners = {1, 2};
        double[] distances = {4.0, 4.4};
        assertArrayEquals(new int[] {1}, SallyLayout.asked(DEFENDER, owners, distances, h -> true));
        assertArrayEquals(new int[] {1, 2}, SallyLayout.asked(DEFENDER, owners, new double[] {0, 2.8}, h -> true));
    }

    /** Only humans the rule allows (allied, at war with the invader, free) are asked; the defender never; each once. */
    @Test
    void eachEligibleAllyIsAskedOnceInTheOrderFound() {
        int[] owners = {2, DEFENDER, 3, 2, 1, -1};
        double[] distances = {1, 0, 2, 3, 1, 0};
        assertArrayEquals(new int[] {2, 1}, SallyLayout.asked(DEFENDER, owners, distances, h -> h != 3));
        assertArrayEquals(new int[0], SallyLayout.asked(DEFENDER, owners, distances, h -> false));
        assertThrows(IllegalArgumentException.class, () -> SallyLayout.asked(DEFENDER, owners, new double[2], h -> true));
    }

    /** Slots split as in a battle with allies, one each in turn; what one doesn't use goes to the others. */
    @Test
    void slotsSplitEvenlyAndUnusedOnesGoToTheOthers() {
        assertArrayEquals(new int[] {20, 20}, SallyLayout.slots(5, new int[] {30, 30}, 40));
        assertArrayEquals(new int[] {10, 30}, SallyLayout.slots(5, new int[] {10, 30}, 40));
        assertArrayEquals(new int[] {14, 13, 13}, SallyLayout.slots(5, new int[] {20, 20, 20}, 40));
    }

    /** The whole garrison always fits: vanilla reads every garrison division's survivors after the battle. */
    @Test
    void theGarrisonAlwaysFitsTakingSlotsFromTheAllyWithMost() {
        assertArrayEquals(new int[] {30, 10}, SallyLayout.slots(30, new int[] {35, 40}, 40));
        assertArrayEquals(new int[] {26, 7, 7}, SallyLayout.slots(26, new int[] {30, 20, 20}, 40));
        assertArrayEquals(new int[] {40, 0}, SallyLayout.slots(50, new int[] {50, 10}, 40));
    }

    /** Each battle position's human, the defender's divisions first, then each ally's, -1 past the last. */
    @Test
    void ownersFollowTheLayout() {
        SallyLayout l = layout(new int[] {2, 1, 2}, new int[] {10, 10, 10});
        assertArrayEquals(new int[] {0, 0, 5, 7, 7, -1}, l.owners(6));
        assertEquals(0, l.first(0));
        assertEquals(2, l.first(1));
        assertEquals(3, l.first(2));
        assertArrayEquals(new int[] {5, 7}, l.allyHumans());
        assertTrue(l.allies());
        assertArrayEquals(new int[] {100, 101, 200, 300}, l.allArmies());
    }

    /** The split by power: each part by strength at the start, the remainder to the strongest, the defender on a tie. */
    @Test
    void spoilsSplitByPowerWithTheRemainderToTheStrongest() {
        SallyLayout l = layout(new int[] {5, 5, 5}, new int[] {100, 300, 100});
        int[][] parts = l.shares(new int[] {10, 0, 7}, new boolean[3]);
        assertArrayEquals(new int[] {2, 0, 1}, parts[0]);
        assertArrayEquals(new int[] {6, 0, 5}, parts[1]); // 10 * 300 / 500 = 6; 7: 1, 4, 1 and the remainder 1
        assertArrayEquals(new int[] {2, 0, 1}, parts[2]);
        SallyLayout tie = layout(new int[] {5, 5}, new int[] {200, 200});
        int[][] even = tie.shares(new int[] {7}, new boolean[2]);
        assertEquals(4, even[0][0]); // the defender, first of the strongest
        assertEquals(3, even[1][0]);
    }

    /** An ally whose army left the field shares nothing; every unit of every tradable is given out, once. */
    @Test
    void anAllyWhoLeftGetsNothingAndNothingIsLost() {
        SallyLayout l = layout(new int[] {5, 5, 5}, new int[] {100, 900, 50});
        int[] spoils = {1001, 3, 0, 77};
        int[][] parts = l.shares(spoils, new boolean[] {false, true, false});
        assertArrayEquals(new int[4], parts[1]);
        for (int t = 0; t < spoils.length; t++) {
            assertEquals(spoils[t], parts[0][t] + parts[1][t] + parts[2][t]);
        }
        int[][] none = layout(new int[] {5, 5}, new int[] {0, 0}).shares(new int[] {9}, new boolean[2]);
        assertEquals(9, none[0][0]); // no strength anywhere: the defender keeps it
    }

    /** An ally's share in the defender's report: the bytes read back to the same array, and travel in a report. */
    @Test
    void anAllysShareReadsBackFromItsReportInput() {
        int[] share = new int[50];
        share[0] = 12;
        share[33] = 1;
        share[49] = 70_000;
        byte[] bytes = SallyLayout.encode(share);
        assertEquals(4 + 3 * 8, bytes.length);
        assertArrayEquals(share, SallyLayout.decode(bytes, 50));
        assertArrayEquals(new int[50], SallyLayout.decode(SallyLayout.encode(new int[50]), 50));
        assertThrows(IllegalArgumentException.class, () -> SallyLayout.decode(bytes, 40));

        ReportLayout v71 = new ReportLayout(50, 4, 8, 4, 120, 8, 6, 400, 200, 300);
        Input in = new Input(InputKind.SALLY_SPOILS, 3, bytes);
        CityReport r = new CityReport(v71, 0, 30, 60, new double[v71.size()], List.of(in));
        CityReport back = CityReport.fromBytes(r.toBytes(), v71);
        assertEquals(List.of(in), back.inputs);
        assertEquals(3, back.inputs.get(0).arg);
        assertArrayEquals(share, SallyLayout.decode(back.inputs.get(0).data(), 50));
        assertEquals(InputKind.SALLY_SPOILS, back.inputs.get(0).kind);
    }

    private static SallyLayout layout(int[] divisions, int[] power) {
        int[] commanders = {0, 5, 7};
        int[][] armies = {{100, 101}, {200}, {300}};
        int n = divisions.length;
        int[][] it = new int[n][];
        for (int c = 0; c < n; c++) {
            it[c] = new int[armies[c].length];
        }
        return new SallyLayout(java.util.Arrays.copyOf(commanders, n), java.util.Arrays.copyOf(armies, n), it, divisions,
                power);
    }
}

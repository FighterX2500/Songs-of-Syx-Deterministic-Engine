package sosdet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BattleRulesTest {

    @Test
    void slotsSplitEvenlyBetweenHumans() {
        assertArrayEquals(new int[] {20, 20, 0}, BattleRules.slots(new int[] {30, 30, 0}, 40));
    }

    @Test
    void slotsAHumanDoesNotUseGoToTheOthers() {
        assertArrayEquals(new int[] {35, 5, 0}, BattleRules.slots(new int[] {40, 5, 0}, 40));
    }

    @Test
    void anNpcArmyGetsWhatIsLeft() {
        assertArrayEquals(new int[] {10, 10, 20}, BattleRules.slots(new int[] {10, 10, 30}, 40));
        assertArrayEquals(new int[] {40, 0}, BattleRules.slots(new int[] {40, 30}, 40));
    }

    @Test
    void leavingPaysHalfItsShare() {
        double perMan = BattleRules.leavingLoss(200, 100, 200, 0.4);
        assertEquals(0.2, perMan, 1e-9, "100 + 100 men against 200: the side's loss is 80, the share 40, the loss 20");
        assertEquals(20, BattleRules.divisionLoss(100, perMan, false));
    }

    @Test
    void aSmallArmyPaysLessThanItsAlly() {
        double perMan = BattleRules.leavingLoss(200, 50, 200, 0.4);
        assertEquals(10, BattleRules.divisionLoss(50, perMan, false));
    }

    @Test
    void aDivisionInMeleeLosesMore() {
        assertEquals(95, BattleRules.divisionLoss(100, 0.2, true));
        assertEquals(100, BattleRules.divisionLoss(100, 0.5, true), "never more than it has");
    }

    @Test
    void aLossNeverPassesTheArmy() {
        assertEquals(1, BattleRules.leavingLoss(100000, 100, 200, 0.4), 1e-9);
        assertEquals(0, BattleRules.leavingLoss(100, 0, 200, 0.4), 1e-9);
    }

    @Test
    void sharesGoByMenBrought() {
        assertArrayEquals(new int[] {75, 25}, BattleRules.share(100, new int[] {300, 100}));
    }

    @Test
    void whatDoesNotDivideGoesToTheMostMen() {
        assertArrayEquals(new int[] {1, 2}, BattleRules.share(3, new int[] {100, 150}));
        assertArrayEquals(new int[] {3, 0}, BattleRules.share(3, new int[] {0, 0}));
    }

    @Test
    void sharesAddUpToTheWhole() {
        int[] p = BattleRules.share(1001, new int[] {37, 61, 13});
        assertEquals(1001, p[0] + p[1] + p[2]);
    }

    /** SOS-160: three or four groups of up to 30 divisions' width stay within vanilla's line, each with room. */
    @Test
    void groupsFitTheLine() {
        for (int groups = 1; groups <= 4; groups++) {
            for (int divisions : new int[] {1, 20, 30}) {
                int half = BattleRules.groupHalf(divisions * 13 / 2, groups, 20);
                assertTrue(half > 0, groups + " groups of " + divisions);
                int outer = (groups * 2 * half + 20 * (groups - 1)) / 2; // the side's half-width
                assertTrue(outer <= BattleRules.HALF_LINE, groups + " groups of " + divisions + ": " + outer);
            }
        }
        assertEquals(97, BattleRules.groupHalf(97, 2, 20), "two groups of 15 divisions keep their width");
    }
}

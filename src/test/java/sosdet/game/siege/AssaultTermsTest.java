package sosdet.game.siege;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class AssaultTermsTest {

    private static final int[] NONE = AssaultTerms.NO_PEOPLE;

    @Test
    void theAttackerWithTheMostMenChoosesAndTheFirstWinsATie() {
        assertEquals(1, AssaultTerms.chooser(new int[] {40, 60}));
        assertEquals(0, AssaultTerms.chooser(new int[] {50, 50}));
    }

    @Test
    void spoilsSplitByTheMenBroughtAndTheChooserGetsTheRest() {
        assertArrayEquals(new int[] {33, 67}, AssaultTerms.split(100, new int[] {1, 2}));
        assertArrayEquals(new int[] {100}, AssaultTerms.split(100, new int[] {7}));
        assertArrayEquals(new int[] {5, 0}, AssaultTerms.split(5, new int[] {0, 0}));
    }

    @Test
    void eliminateTakesItsShareOnly() {
        assertNull(AssaultTerms.refusal(AssaultTerms.ELIMINATE, 90, NONE));
        assertNotNull(AssaultTerms.refusal(AssaultTerms.ELIMINATE, 80, NONE));
        assertNotNull(AssaultTerms.refusal(AssaultTerms.ELIMINATE, 90, new int[] {1, 2}));
    }

    @Test
    void eliminateRemovesNinetyPercentAndFreesWhoeverHasNoFate() {
        int[] in = {60, 40};
        int[] out = AssaultTerms.eliminated(new int[] {10, 20, 0, 0, 5, 0}, in);
        assertEquals(10, out[AssaultTerms.MOVED]);
        assertEquals(20, out[AssaultTerms.KILLED]);
        assertEquals(5, out[AssaultTerms.FATES + AssaultTerms.KILLED]);
        int all = 0;
        for (int n : out) {
            all += n;
        }
        assertEquals(90, all);
        assertEquals(30, out[AssaultTerms.FREED], "the rest of the first race, up to its 60");
        assertEquals(25, out[AssaultTerms.FATES + AssaultTerms.FREED]);
    }

    @Test
    void eliminateWithNoChoiceFreesTheShare() {
        int[] out = AssaultTerms.eliminated(NONE, new int[] {7, 3});
        assertEquals(7, out[AssaultTerms.FREED]);
        assertEquals(2, out[AssaultTerms.FATES + AssaultTerms.FREED]);
    }

    private static int[] people(int... counts) {
        return counts;
    }

    /** Mod spec §9: a sack takes at most 80%; a tribute is a percentage. */
    @Test
    void aSackTakesUpToEightyPercentAndATributeUpToAHundred() {
        assertNull(AssaultTerms.refusal(AssaultTerms.SACK, 0, NONE));
        assertNull(AssaultTerms.refusal(AssaultTerms.SACK, 80, NONE));
        assertNotNull(AssaultTerms.refusal(AssaultTerms.SACK, 81, NONE));
        assertNotNull(AssaultTerms.refusal(AssaultTerms.SACK, -1, NONE));
        assertNull(AssaultTerms.refusal(AssaultTerms.VASSALAGE, 100, NONE));
        assertNotNull(AssaultTerms.refusal(AssaultTerms.VASSALAGE, 101, NONE));
        assertNotNull(AssaultTerms.refusal(2, 10, NONE));
        assertNull(AssaultTerms.refusal(AssaultTerms.DEFAULT_KIND, AssaultTerms.DEFAULT_PERCENT, NONE));
    }

    @Test
    void aShareRoundsDownTakesNothingOfADebtAndDoesNotOverflow() {
        assertEquals(40, AssaultTerms.share(81, 50));
        assertEquals(0, AssaultTerms.share(-500, 80));
        assertEquals(0, AssaultTerms.share(1, 80));
        assertEquals(1717986917, AssaultTerms.share(Integer.MAX_VALUE, 80));
    }

    @Test
    void aChoiceOfPeopleHasCountsForEveryFate() {
        assertNull(AssaultTerms.refusal(AssaultTerms.SACK, 50, people(1, 2, 3, 0, 0, 0)));
        assertNotNull(AssaultTerms.refusal(AssaultTerms.SACK, 50, people(-1, 0, 0)));
        assertNotNull(AssaultTerms.refusal(AssaultTerms.SACK, 50, people(1, 0)));
    }

    @Test
    void aVassalKeepsItsPeople() {
        assertNotNull(AssaultTerms.refusal(AssaultTerms.VASSALAGE, 10, people(1, 0, 0)));
    }

    /** Mod spec §9: up to 80% of the population, in all rather than of each race. */
    @Test
    void theLimitIsEightyPercentOfEveryoneInTheCity() {
        assertEquals(80, AssaultTerms.peopleLimit(people(60, 40)));
        assertEquals(0, AssaultTerms.peopleLimit(NONE));
    }

    @Test
    void oneRaceCanBeTakenWholeWithinTheLimit() {
        assertArrayEquals(people(60, 0, 0, 20, 0, 0), AssaultTerms.limited(people(60, 0, 0, 20, 0, 0), people(60, 40)));
    }

    @Test
    void noRaceLosesMoreThanItHas() {
        assertArrayEquals(people(5, 3, 2, 0, 0, 0), AssaultTerms.limited(people(5, 3, 4, 0, 0, 0), people(10, 90)));
    }

    @Test
    void theLimitCutsTheLastFatesOfTheLastRaces() {
        assertArrayEquals(people(50, 10, 0, 20, 0, 0), AssaultTerms.limited(people(50, 10, 5, 30, 0, 0), people(60, 40)));
    }

    @Test
    void noPeopleStayNoPeopleAndTheChoiceItselfDoesNotChange() {
        assertSame(NONE, AssaultTerms.limited(NONE, people(10)));
        int[] chosen = people(50, 0, 0);
        AssaultTerms.limited(chosen, people(10));
        assertArrayEquals(people(50, 0, 0), chosen);
    }

    @Test
    void removedCountsOneFateOfEveryRace() {
        int[] p = people(1, 2, 3, 10, 20, 30);
        assertEquals(11, AssaultTerms.removed(p, AssaultTerms.MOVED));
        assertEquals(22, AssaultTerms.removed(p, AssaultTerms.KILLED));
        assertEquals(33, AssaultTerms.removed(p, AssaultTerms.FREED));
    }

    /** Mod spec §9: freeing raises mercy, moving lowers it, killing lowers it twice as much. */
    @Test
    void mercyFollowsVanillasPanel() {
        assertEquals(0.5, AssaultTerms.mercy(people(0, 0, 50), 99), 1e-9);
        assertEquals(-0.25, AssaultTerms.mercy(people(50, 0, 0), 99), 1e-9);
        assertEquals(-0.5, AssaultTerms.mercy(people(0, 50, 0), 99), 1e-9);
        assertEquals(0, AssaultTerms.mercy(NONE, 99), 1e-9);
    }
}

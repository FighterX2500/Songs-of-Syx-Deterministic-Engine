package sosdet.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AssaultTermsTest {

    /** Mod spec §9: a sack takes at most 80%; a tribute is a percentage. */
    @Test
    void aSackTakesUpToEightyPercentAndATributeUpToAHundred() {
        assertNull(AssaultTerms.refusal(AssaultTerms.SACK, 0));
        assertNull(AssaultTerms.refusal(AssaultTerms.SACK, 80));
        assertNotNull(AssaultTerms.refusal(AssaultTerms.SACK, 81));
        assertNotNull(AssaultTerms.refusal(AssaultTerms.SACK, -1));
        assertNull(AssaultTerms.refusal(AssaultTerms.VASSALAGE, 100));
        assertNotNull(AssaultTerms.refusal(AssaultTerms.VASSALAGE, 101));
        assertNotNull(AssaultTerms.refusal(2, 10));
        assertNull(AssaultTerms.refusal(AssaultTerms.DEFAULT_KIND, AssaultTerms.DEFAULT_PERCENT));
    }

    @Test
    void aShareRoundsDownTakesNothingOfADebtAndDoesNotOverflow() {
        assertEquals(40, AssaultTerms.share(81, 50));
        assertEquals(0, AssaultTerms.share(-500, 80));
        assertEquals(0, AssaultTerms.share(1, 80));
        assertEquals(1717986917, AssaultTerms.share(Integer.MAX_VALUE, 80));
    }
}

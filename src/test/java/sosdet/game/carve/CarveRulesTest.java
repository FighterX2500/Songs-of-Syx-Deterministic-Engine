package sosdet.game.carve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import sosdet.game.carve.CarveRules.Holder;
import sosdet.game.carve.CarveRules.Outcome;

class CarveRulesTest {

    @Test
    void unownedLandBecomesANewNpc() {
        assertEquals(Outcome.NEW_NPC, CarveRules.outcome(Holder.NOBODY, 0, false, true));
    }

    @Test
    void aRegionOfABigRealmBreaksAway() {
        assertEquals(Outcome.BREAK_AWAY, CarveRules.outcome(Holder.NPC, 4, false, true));
        assertEquals(Outcome.BREAK_AWAY, CarveRules.outcome(Holder.NPC, 30, false, true));
    }

    @Test
    void aSmallRealmKeepsItsRegion() {
        assertEquals(Outcome.KEEP, CarveRules.outcome(Holder.NPC, 3, false, true));
        assertEquals(Outcome.KEEP, CarveRules.outcome(Holder.NPC, 1, true, true));
    }

    @Test
    void aRealmsCapitalNeverBreaksAway() {
        assertEquals(Outcome.KEEP, CarveRules.outcome(Holder.NPC, 12, true, true));
    }

    @Test
    void aHumansRegionStaysTheirs() {
        assertEquals(Outcome.KEEP, CarveRules.outcome(Holder.HUMAN, 10, false, true));
    }

    @Test
    void noFreeSlotMeansNoNewNpc() {
        assertEquals(Outcome.KEEP, CarveRules.outcome(Holder.NOBODY, 0, false, false));
        assertEquals(Outcome.KEEP, CarveRules.outcome(Holder.NPC, 8, false, false));
        assertEquals(Outcome.NEW_NPC, CarveRules.wanted(Holder.NOBODY, 0, false), "wanted tells the missing slot apart");
    }
}

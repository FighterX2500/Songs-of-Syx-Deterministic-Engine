package sosdet.game.deal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sosdet.game.deal.StanceBook.KEEP;
import static sosdet.game.deal.StanceBook.NEUTRAL;
import static sosdet.game.deal.StanceBook.PACT;
import static sosdet.game.deal.StanceBook.TRADE;
import static sosdet.game.deal.StanceBook.WAR;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** SOS-140: the stances passed to a human's NPC vassals. */
class StanceBookTest {

    @Test
    void onlyTradeAndColleaguesPass() {
        assertEquals(TRADE, StanceBook.passes(TRADE));
        assertEquals(PACT, StanceBook.passes(PACT));
        assertEquals(NEUTRAL, StanceBook.passes(StanceBook.ABOVE), "an alliance never passes");
        assertEquals(NEUTRAL, StanceBook.passes(WAR));
        assertEquals(NEUTRAL, StanceBook.passes(NEUTRAL));
    }

    @Test
    void aStancePassesOnceAndGoesBackWhenItsSourceEnds() {
        StanceBook b = new StanceBook();
        assertEquals(TRADE, b.decide(2, 40, TRADE, NEUTRAL));
        assertEquals(KEEP, b.decide(2, 40, TRADE, TRADE), "set once");
        assertEquals(NEUTRAL, b.decide(2, 40, NEUTRAL, TRADE), "the source ended: back to neutral");
        assertTrue(b.empty());
    }

    @Test
    void aStanceTheyHadOnTheirOwnStays() {
        StanceBook b = new StanceBook();
        assertEquals(KEEP, b.decide(2, 40, TRADE, TRADE), "they trade already");
        assertEquals(KEEP, b.decide(2, 40, NEUTRAL, TRADE), "and keep it when the source ends");
        assertEquals(PACT, b.decide(2, 41, PACT, TRADE), "raised from their own trade");
        assertEquals(TRADE, b.decide(2, 41, NEUTRAL, PACT), "and back to it, not to neutral");
        assertTrue(b.empty());
    }

    @Test
    void aPassedStanceFollowsItsSourceUpAndDown() {
        StanceBook b = new StanceBook();
        assertEquals(TRADE, b.decide(2, 40, TRADE, NEUTRAL));
        assertEquals(PACT, b.decide(2, 40, PACT, TRADE));
        assertEquals(TRADE, b.decide(2, 40, TRADE, PACT));
        assertEquals(1, b.size());
    }

    @Test
    void aWarBlocksItUntilPeace() {
        StanceBook b = new StanceBook();
        assertEquals(KEEP, b.decide(2, 40, TRADE, WAR));
        assertFalse(b.has(2, 40));
        assertEquals(TRADE, b.decide(2, 40, TRADE, NEUTRAL), "after the peace");
    }

    @Test
    void aStanceSomeoneElseChangedIsTheirsUntilTheSourceEnds() {
        StanceBook b = new StanceBook();
        assertEquals(TRADE, b.decide(2, 40, TRADE, NEUTRAL));
        assertEquals(KEEP, b.decide(2, 40, TRADE, NEUTRAL), "the vassal's court cancelled it: not passed again");
        assertEquals(KEEP, b.decide(2, 40, PACT, NEUTRAL), "nor when the source rises");
        assertEquals(KEEP, b.decide(2, 40, NEUTRAL, NEUTRAL), "the source ended");
        assertTrue(b.empty());
        assertEquals(TRADE, b.decide(2, 40, TRADE, NEUTRAL), "a new source passes afresh");
    }

    @Test
    void aWarDeclaredOnAPassedStanceIsTheirs() {
        StanceBook b = new StanceBook();
        assertEquals(TRADE, b.decide(2, 40, TRADE, NEUTRAL));
        assertEquals(KEEP, b.decide(2, 40, TRADE, WAR));
        assertEquals(KEEP, b.decide(2, 40, TRADE, NEUTRAL), "the peace after it doesn't bring it back");
    }

    @Test
    void pairsStayInKeyOrderAndGoWithTheirHumanOrVassal() {
        StanceBook b = new StanceBook();
        b.decide(5, 40, TRADE, NEUTRAL);
        b.decide(2, 41, TRADE, NEUTRAL);
        b.decide(2, 40, TRADE, NEUTRAL);
        assertEquals(2, b.human(0));
        assertEquals(40, b.vassal(0));
        assertEquals(41, b.vassal(1));
        assertEquals(5, b.human(2));
        b.drop(40);
        assertEquals(1, b.size());
        b.drop(2);
        assertTrue(b.empty());
    }

    @Test
    void manyPairsGrowTheBook() {
        StanceBook b = new StanceBook();
        for (int v = 0; v < 200; v++) {
            assertEquals(PACT, b.decide(3, 200 - v, PACT, NEUTRAL));
        }
        assertEquals(200, b.size());
        assertEquals(1, b.vassal(0));
        assertEquals(200, b.vassal(199));
    }

    @Test
    void aBookRoundTripsAndHashesItsState() throws IOException {
        StanceBook b = new StanceBook();
        b.decide(2, 40, TRADE, NEUTRAL);
        b.decide(2, 41, PACT, TRADE);
        b.decide(7, 40, TRADE, NEUTRAL);
        b.decide(7, 40, TRADE, NEUTRAL); // released
        StanceBook c = StanceBook.fromInts(b.toInts());
        assertArrayEquals(b.toInts(), c.toInts());
        assertEquals(b.hash(1), c.hash(1));
        assertEquals(KEEP, c.decide(7, 40, PACT, NEUTRAL), "released survives the round trip");
        assertEquals(TRADE, c.decide(2, 41, NEUTRAL, PACT), "and the stance before");
        assertNotEquals(b.hash(1), c.hash(1));
    }

    @Test
    void aBadSaveIsRefused() {
        assertThrows(IOException.class, () -> StanceBook.fromInts(new int[] {2, 0}));
        assertThrows(IOException.class, () -> StanceBook.fromInts(new int[] {1, 1, 5}));
        assertThrows(IOException.class, () -> StanceBook.fromInts(new int[] {1, 2, 9, 0, 1, 0, 9, 0, 1, 0}));
        assertThrows(IllegalArgumentException.class, () -> new StanceBook().decide(-1, 4, TRADE, NEUTRAL));
    }
}

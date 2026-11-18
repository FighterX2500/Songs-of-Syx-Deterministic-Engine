package sosdet.game.deal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sosdet.game.deal.StanceBook.ABOVE;
import static sosdet.game.deal.StanceBook.ALLY;
import static sosdet.game.deal.StanceBook.KEEP;
import static sosdet.game.deal.StanceBook.NEUTRAL;
import static sosdet.game.deal.StanceBook.PACT;
import static sosdet.game.deal.StanceBook.TRADE;
import static sosdet.game.deal.StanceBook.WAR;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** SOS-140, SOS-226: the stances passed to a human's NPC vassals. */
class StanceBookTest {

    @Test
    void tradeAndColleaguesPassAndAnAlliancePassesColleagues() {
        assertEquals(TRADE, StanceBook.passes(TRADE));
        assertEquals(PACT, StanceBook.passes(PACT));
        assertEquals(PACT, StanceBook.passes(ALLY), "an alliance passes Colleagues (SOS-226)");
        assertEquals(NEUTRAL, StanceBook.passes(ABOVE), "a vassalage between the humans passes nothing");
        assertEquals(NEUTRAL, StanceBook.passes(WAR));
        assertEquals(NEUTRAL, StanceBook.passes(NEUTRAL));
    }

    @Test
    void aVassalSparesItsOverlordsTradePartnersColleaguesAndAllies() {
        assertTrue(StanceBook.friendOfOverlord(TRADE));
        assertTrue(StanceBook.friendOfOverlord(PACT));
        assertTrue(StanceBook.friendOfOverlord(ALLY));
        assertTrue(StanceBook.friendOfOverlord(ABOVE), "an overlord or vassal");
        assertFalse(StanceBook.friendOfOverlord(NEUTRAL));
        assertFalse(StanceBook.friendOfOverlord(WAR));
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
    void anAllianceKeepsTheColleaguesItsPactPassed() {
        StanceBook b = new StanceBook();
        assertEquals(PACT, b.decide(2, 40, StanceBook.passes(PACT), NEUTRAL));
        assertEquals(KEEP, b.decide(2, 40, StanceBook.passes(ALLY), PACT),
                "the colleagues become allies: still Colleagues, not back to neutral (playtest 2026-11-17)");
        assertEquals(PACT, b.decide(3, 41, StanceBook.passes(ALLY), TRADE), "an alliance passes Colleagues afresh");
        assertEquals(TRADE, b.decide(3, 41, NEUTRAL, PACT), "and it goes back when the alliance ends");
    }

    @Test
    void aPassedStanceIsLockedWhileItIsPassed() {
        StanceBook b = new StanceBook();
        assertEquals(PACT, b.decide(2, 40, PACT, NEUTRAL));
        assertEquals(PACT, b.decide(2, 40, PACT, TRADE), "the vassal's court cancelled it a step down: set back");
        assertEquals(PACT, b.decide(2, 40, PACT, NEUTRAL), "the human cancelled it: set back");
        assertEquals(KEEP, b.decide(2, 40, PACT, PACT));
        assertEquals(TRADE, b.decide(2, 40, TRADE, NEUTRAL), "lowered as its source fell: set to the new one");
        assertEquals(NEUTRAL, b.decide(2, 40, NEUTRAL, TRADE), "the source ended: back");
        assertTrue(b.empty());
    }

    @Test
    void aStanceLoweredAsItsSourceEndsIsLeftAsItIs() {
        StanceBook b = new StanceBook();
        assertEquals(PACT, b.decide(2, 40, PACT, NEUTRAL));
        assertEquals(KEEP, b.decide(2, 40, NEUTRAL, NEUTRAL), "cancelled as the source ended: already back");
        assertTrue(b.empty());
    }

    @Test
    void aHigherStanceOfTheirOwnIsTheirs() {
        StanceBook b = new StanceBook();
        assertEquals(PACT, b.decide(2, 40, PACT, NEUTRAL));
        assertEquals(KEEP, b.decide(2, 40, PACT, ALLY), "the human and the vassal became allies");
        assertFalse(b.has(2, 40));
        assertEquals(KEEP, b.decide(2, 40, NEUTRAL, ALLY), "and stay allies when the source ends");
    }

    @Test
    void aWarOnAPassedStanceBlocksItUntilPeace() {
        StanceBook b = new StanceBook();
        assertEquals(TRADE, b.decide(2, 40, TRADE, NEUTRAL));
        assertEquals(KEEP, b.decide(2, 40, TRADE, WAR));
        assertFalse(b.has(2, 40), "the war is theirs: nothing to set back");
        assertEquals(KEEP, b.decide(2, 40, TRADE, WAR));
        assertEquals(TRADE, b.decide(2, 40, TRADE, NEUTRAL), "after the peace it passes again");
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
        b.decide(7, 40, PACT, NEUTRAL);
        StanceBook c = StanceBook.fromInts(b.toInts());
        assertArrayEquals(b.toInts(), c.toInts());
        assertEquals(2, c.toInts()[0], "version 2");
        assertEquals(b.hash(1), c.hash(1));
        assertEquals(PACT, c.decide(7, 40, PACT, TRADE), "still locked after the round trip");
        assertEquals(TRADE, c.decide(2, 41, NEUTRAL, PACT), "and the stance before");
        assertNotEquals(b.hash(1), c.hash(1));
    }

    @Test
    void aVersion1BookLoadsWithoutItsReleasedPairs() throws IOException {
        // version 1: key, before, passed, released; human 2 with vassal 40 held, with vassal 41 released
        StanceBook c = StanceBook.fromInts(new int[] {1, 2, 2 * 1024 + 40, NEUTRAL, PACT, 0, 2 * 1024 + 41, NEUTRAL,
                TRADE, 1});
        assertEquals(1, c.size());
        assertTrue(c.has(2, 40));
        assertFalse(c.has(2, 41), "released under the old rule: no longer the book's");
        assertEquals(TRADE, c.decide(2, 41, TRADE, NEUTRAL), "so it passes afresh");
        assertEquals(PACT, c.decide(2, 40, PACT, TRADE), "and the held one is locked");
        assertEquals(NEUTRAL, c.decide(2, 40, NEUTRAL, PACT), "with its stance before");
    }

    @Test
    void aBadSaveIsRefused() {
        assertThrows(IOException.class, () -> StanceBook.fromInts(new int[] {3, 0}));
        assertThrows(IOException.class, () -> StanceBook.fromInts(new int[] {2, 1, 5}));
        assertThrows(IOException.class, () -> StanceBook.fromInts(new int[] {2, 1, 5, 0, 1, 0}),
                "a version 1 pair in a version 2 book");
        assertThrows(IOException.class, () -> StanceBook.fromInts(new int[] {2, 2, 9, 0, 1, 9, 0, 1}));
        assertThrows(IOException.class, () -> StanceBook.fromInts(new int[] {1, 2, 9, 0, 1, 0, 9, 0, 1, 0}));
        assertThrows(IllegalArgumentException.class, () -> new StanceBook().decide(-1, 4, TRADE, NEUTRAL));
    }
}

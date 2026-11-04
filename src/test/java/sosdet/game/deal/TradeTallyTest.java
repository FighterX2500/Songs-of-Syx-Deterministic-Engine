package sosdet.game.deal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** SOS-135: the market trade between humans, per seller, buyer and tradable, and the tally as world state. */
class TradeTallyTest {

    private static final int GRAIN = 3, STONE = 7;

    @Test
    void eachSellerBuyerAndTradableCountsOnItsOwn() {
        TradeTally t = new TradeTally();
        t.add(0, 1, GRAIN, 116, 1276, 5);
        t.add(0, 1, GRAIN, 116, 1276, 5);
        t.add(1, 0, STONE, 144, 4032, 5);
        assertEquals(232, t.items(0, 1, GRAIN, TradeTally.THIS_YEAR, 5));
        assertEquals(2552, t.denari(0, 1, GRAIN, TradeTally.THIS_YEAR, 5));
        assertEquals(0, t.items(1, 0, GRAIN, TradeTally.THIS_YEAR, 5), "the other direction is the other side's");
        assertEquals(144, t.items(1, 0, STONE, TradeTally.ALL_TIME, 5));
        assertEquals(0, t.items(0, 2, GRAIN, TradeTally.ALL_TIME, 5), "nothing sold to slot 2");
    }

    @Test
    void aNewYearMovesThisYearToLastYearAndAllTimeKeepsEverything() {
        TradeTally t = new TradeTally();
        t.add(0, 1, GRAIN, 100, 1000, 3);
        assertEquals(100, t.items(0, 1, GRAIN, TradeTally.LAST_YEAR, 4), "read in the next year, before a sale in it");
        assertEquals(0, t.items(0, 1, GRAIN, TradeTally.THIS_YEAR, 4));
        t.add(0, 1, GRAIN, 10, 150, 4);
        assertEquals(10, t.items(0, 1, GRAIN, TradeTally.THIS_YEAR, 4));
        assertEquals(1000, t.denari(0, 1, GRAIN, TradeTally.LAST_YEAR, 4));
        assertEquals(1150, t.denari(0, 1, GRAIN, TradeTally.ALL_TIME, 4));
        t.add(0, 1, GRAIN, 1, 10, 7);
        assertEquals(0, t.items(0, 1, GRAIN, TradeTally.LAST_YEAR, 7), "years with no sale between: last year is empty");
        assertEquals(111, t.items(0, 1, GRAIN, TradeTally.ALL_TIME, 7));
        assertEquals(0, t.items(0, 1, GRAIN, TradeTally.LAST_YEAR, 9), "read two years on");
    }

    @Test
    void nothingSoldIsNoEntryAndNoSaleToOneself() {
        TradeTally t = new TradeTally();
        t.add(0, 1, GRAIN, 0, 0, 1);
        assertTrue(t.empty());
        assertThrows(IllegalArgumentException.class, () -> t.add(2, 2, GRAIN, 5, 5, 1));
    }

    @Test
    void aRemovedHumansTradeGoesBothWays() {
        TradeTally t = new TradeTally();
        t.add(0, 1, GRAIN, 5, 50, 1);
        t.add(1, 0, GRAIN, 5, 50, 1);
        t.add(0, 2, GRAIN, 5, 50, 1);
        t.dropHuman(1);
        assertEquals(0, t.items(0, 1, GRAIN, TradeTally.ALL_TIME, 1));
        assertEquals(0, t.items(1, 0, GRAIN, TradeTally.ALL_TIME, 1));
        assertEquals(5, t.items(0, 2, GRAIN, TradeTally.ALL_TIME, 1));
    }

    @Test
    void theTallyRoundTripsAndHashesWhatItHolds() throws IOException {
        TradeTally t = new TradeTally();
        t.add(0, 1, GRAIN, 100, 1000, 3);
        t.add(0, 1, GRAIN, 10, 150, 4);
        t.add(2, 0, STONE, 3_000_000, Integer.MAX_VALUE, 4);
        TradeTally back = TradeTally.fromLongs(t.toLongs());
        assertArrayEquals(t.toLongs(), back.toLongs());
        assertEquals(t.hash(17), back.hash(17));
        assertEquals(1000, back.denari(0, 1, GRAIN, TradeTally.LAST_YEAR, 4));
        back.add(0, 1, GRAIN, 1, 1, 4);
        assertNotEquals(t.hash(17), back.hash(17));
    }

    @Test
    void allTimeFiguresGoPastAnInt() {
        TradeTally t = new TradeTally();
        for (int i = 0; i < 3; i++) {
            t.add(0, 1, GRAIN, 1, Integer.MAX_VALUE, i);
        }
        assertEquals(3L * Integer.MAX_VALUE, t.denari(0, 1, GRAIN, TradeTally.ALL_TIME, 2));
    }

    @Test
    void aBrokenTallyIsRefused() {
        assertThrows(IOException.class, () -> TradeTally.fromLongs(new long[] { 2, 0, 0 }), "another version");
        assertThrows(IOException.class, () -> TradeTally.fromLongs(new long[] { 1, 0, 1 }), "an entry missing");
        long self = (5L * 1024 + 5) * 1024 + GRAIN;
        assertThrows(IOException.class, () -> TradeTally.fromLongs(new long[] { 1, 0, 1, self, 0, 0, 0, 0, 0, 0 }),
                "a sale to oneself");
    }
}

package sosdet.game.deal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** SOS-77: each human's tariff on another, the cut it takes, and the book as world state. */
class TariffBookTest {

    @Test
    void aTariffNobodySetIsTwentyPercentAndEachDirectionIsItsOwn() {
        TariffBook b = new TariffBook();
        assertEquals(20, b.rate(1, 2));
        b.set(1, 2, 50);
        assertEquals(50, b.rate(1, 2));
        assertEquals(20, b.rate(2, 1), "the other side's tariff is the other side's");
    }

    @Test
    void onlyZeroToEightyInStepsOfFive() {
        assertNull(TariffBook.refusal(0));
        assertNull(TariffBook.refusal(80));
        assertNotNull(TariffBook.refusal(85));
        assertNotNull(TariffBook.refusal(-5));
        assertNotNull(TariffBook.refusal(12));
        TariffBook b = new TariffBook();
        assertThrows(IllegalArgumentException.class, () -> b.set(1, 2, 90));
        assertThrows(IllegalArgumentException.class, () -> b.set(3, 3, 10), "no tariff on oneself");
    }

    @Test
    void theCutIsTheBuyersRateOfThePriceAndCountsAsTheBuyersRevenue() {
        TariffBook b = new TariffBook();
        b.set(1, 2, 25);
        assertEquals(25, b.cut(1, 2, 100, 3), "buyer 1 buys from seller 2: 1's tariff on 2");
        assertEquals(20, b.cut(2, 1, 100, 3), "buyer 2's tariff on 1, nobody set it");
        assertEquals(2, b.cut(1, 2, 9, 3), "rounded down");
        assertEquals(0, b.cut(1, 2, 0, 3));
        assertEquals(27, b.revenue(1, 2, 3, false));
        assertEquals(27, b.revenue(1, 3));
        assertEquals(20, b.revenue(2, 3));
    }

    @Test
    void aNewYearMovesThisYearsRevenueToLastYears() {
        TariffBook b = new TariffBook();
        b.cut(1, 2, 100, 3);
        assertEquals(20, b.revenue(1, 2, 4, true), "read in the next year, before anything is earned in it");
        assertEquals(0, b.revenue(1, 2, 4, false));
        b.cut(1, 2, 50, 4);
        assertEquals(10, b.revenue(1, 2, 4, false));
        assertEquals(20, b.revenue(1, 2, 4, true));
        b.cut(1, 2, 50, 6);
        assertEquals(0, b.revenue(1, 2, 6, true), "a year with nothing in between");
    }

    @Test
    void theBookRoundTripsAndAnEmptyOneStaysEmpty() throws IOException {
        TariffBook b = new TariffBook();
        assertTrue(b.empty());
        b.set(1, 2, 0);
        b.set(4, 1, 80);
        b.cut(4, 1, 1000, 7);
        TariffBook c = TariffBook.fromInts(b.toInts());
        assertArrayEquals(b.toInts(), c.toInts());
        assertEquals(b.hash(5), c.hash(5));
        assertEquals(0, c.rate(1, 2));
        assertEquals(800, c.revenue(4, 7));
        assertNotEquals(new TariffBook().hash(5), b.hash(5));
        assertThrows(IOException.class, () -> TariffBook.fromInts(new int[] {2, 0, 0}), "a later version");
        assertThrows(IOException.class, () -> TariffBook.fromInts(new int[] {1, 0, 1, 5}), "cut short");
    }

    @Test
    void aRemovedHumansTariffsGoBothWays() {
        TariffBook b = new TariffBook();
        b.set(1, 2, 40);
        b.set(2, 1, 40);
        b.set(1, 3, 40);
        b.dropHuman(2);
        assertEquals(20, b.rate(1, 2));
        assertEquals(20, b.rate(2, 1));
        assertEquals(40, b.rate(1, 3));
    }
}

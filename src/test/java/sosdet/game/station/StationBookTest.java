package sosdet.game.station;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import sosdet.hash.HashLog;

class StationBookTest {

    /** An allowed stationing: own, fortified, 2 tiles off, 10 divisions into a city with 100 of 120 slots taken. */
    private static String refusal(boolean agreement, boolean atWar, int dx, int divisions, int host, int stationed) {
        return StationBook.refusal(true, false, true, false, agreement, atWar, true, dx, 1, divisions, host, stationed, 120);
    }

    @Test
    void anAgreementWithinThreeTilesAndFreeSlotsAllowsIt() {
        assertNull(refusal(true, false, 3, 10, 100, 10));
        assertNull(refusal(true, false, -3, 10, 100, 10));
    }

    @Test
    void moreThanThreeTilesAlongEitherAxisIsRefused() {
        assertTrue(refusal(true, false, 4, 10, 0, 0).contains("within 3 tiles"));
        assertTrue(StationBook.refusal(true, false, true, false, true, false, true, 0, -4, 1, 0, 0, 120)
                .contains("within 3 tiles"));
    }

    @Test
    void noAgreementOrAWarIsRefused() {
        assertTrue(refusal(false, false, 1, 1, 0, 0).contains("ally, a colleague"));
        assertTrue(refusal(true, true, 1, 1, 0, 0).contains("ally, a colleague"));
    }

    @Test
    void theSlotLimitCountsTheHostsOwnAndTheStationedDivisions() {
        assertNull(refusal(true, false, 1, 20, 90, 10)); // 120 exactly
        String why = refusal(true, false, 1, 21, 90, 10);
        assertTrue(why.contains("100 of its 120 army slots"), why);
    }

    @Test
    void theOtherRefusalsComeFirstInOrder() {
        assertTrue(StationBook.refusal(false, false, true, false, true, false, true, 0, 0, 1, 0, 0, 120).contains("own army"));
        assertTrue(StationBook.refusal(true, true, true, false, true, false, true, 0, 0, 1, 0, 0, 120).contains("already"));
        assertTrue(StationBook.refusal(true, false, false, false, true, false, true, 0, 0, 1, 0, 0, 120).contains("another player's"));
        assertTrue(StationBook.refusal(true, false, true, true, true, false, true, 0, 0, 1, 0, 0, 120).contains("away"));
        assertTrue(StationBook.refusal(true, false, true, false, true, false, false, 0, 0, 1, 0, 0, 120).contains("still"));
        assertTrue(StationBook.refusal(true, false, true, false, true, false, true, 0, 0, 0, 0, 0, 120).contains("no soldiers"));
    }

    @Test
    void aRecordEndsForTheFirstRuleItBreaks() {
        assertEquals(-1, StationBook.endReason(true, false, true, true, false, true));
        assertEquals(StationBook.ARMY_GONE, StationBook.endReason(false, true, false, false, true, false));
        assertEquals(StationBook.AWAY, StationBook.endReason(true, true, false, false, true, false));
        assertEquals(StationBook.CAPITAL_LOST, StationBook.endReason(true, false, false, false, true, false));
        assertEquals(StationBook.AGREEMENT_ENDED, StationBook.endReason(true, false, true, false, false, true));
        assertEquals(StationBook.AGREEMENT_ENDED, StationBook.endReason(true, false, true, true, true, true),
                "a war ends it, and the army stays where it stands");
        assertEquals(StationBook.MOVED, StationBook.endReason(true, false, true, true, false, false));
    }

    @Test
    void recordsKeepTheirOrderAndEndOneByOne() {
        StationBook b = new StationBook();
        for (int i = 0; i < 20; i++) { // past the first array
            b.station(i, 7, 1 + i % 2, 0, 10 + i, 20, 100 + i);
        }
        assertEquals(20, b.size());
        assertEquals(20, b.count(0));
        b.end(3);
        assertEquals(19, b.size());
        assertEquals(4, b.army(3));
        assertEquals(-1, b.find(3));
        assertEquals(3, b.find(4, 7));
        assertEquals(-1, b.find(4, 8), "another iteration is another army");
        assertEquals(14, b.x(3));
        assertEquals(104, b.since(3));
        assertThrows(IllegalStateException.class, () -> b.station(4, 9, 1, 0, 0, 0, 0), "an army is stationed once");
        assertThrows(IllegalArgumentException.class, () -> b.station(40, 0, 2, 2, 0, 0, 0), "never at its own capital");
    }

    @Test
    void aRemovedOrFrozenHumansRecordsEndBothWays() {
        StationBook b = new StationBook();
        b.station(1, 0, 1, 0, 0, 0, 0);
        b.station(2, 0, 0, 2, 0, 0, 0);
        b.station(3, 0, 2, 1, 0, 0, 0);
        b.dropHuman(1);
        assertEquals(1, b.size());
        assertEquals(2, b.army(0));
    }

    @Test
    void theBookSavesAndLoadsTheSameAndHashesAlike() throws IOException {
        StationBook b = new StationBook();
        b.station(5, 3, 1, 0, 61, 92, 4000);
        b.station(9, 1, 2, 0, 63, 90, 4100);
        StationBook back = StationBook.fromInts(b.toInts());
        assertArrayEquals(b.toInts(), back.toInts());
        assertEquals(b.hash(HashLog.start()), back.hash(HashLog.start()));
        back.end(1);
        assertNotEquals(b.hash(HashLog.start()), back.hash(HashLog.start()));
        assertTrue(new StationBook().empty());
    }

    @Test
    void aBadBookIsRefused() {
        assertThrows(IOException.class, () -> StationBook.fromInts(new int[] {2, 0}), "another version");
        assertThrows(IOException.class, () -> StationBook.fromInts(new int[] {1, 1, 5}), "cut short");
        assertThrows(IOException.class, () -> StationBook.fromInts(new int[] {1, 2, 5, 0, 1, 0, 0, 0, 0, 5, 0, 2, 0, 0, 0, 0}),
                "one army twice");
        assertThrows(IOException.class, () -> StationBook.fromInts(new int[] {1, 1, 5, 0, 1, 1, 0, 0, 0}), "at its own capital");
    }
}

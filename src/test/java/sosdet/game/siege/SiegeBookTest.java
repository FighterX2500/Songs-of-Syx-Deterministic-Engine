package sosdet.game.siege;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** SOS-92: the siege of a human's capital and its waiting period, and the book as world state. */
class SiegeBookTest {

    private static final double SEASON = 4608; // 4 days of 1,152 s

    @Test
    void oneSeasonForEveryStartedThousandPeopleUpToFour() {
        assertEquals(1, SiegeBook.waitSeasons(0));
        assertEquals(1, SiegeBook.waitSeasons(1));
        assertEquals(1, SiegeBook.waitSeasons(1000));
        assertEquals(2, SiegeBook.waitSeasons(1001));
        assertEquals(2, SiegeBook.waitSeasons(2000));
        assertEquals(3, SiegeBook.waitSeasons(2001));
        assertEquals(4, SiegeBook.waitSeasons(3001));
        assertEquals(4, SiegeBook.waitSeasons(10000), "four seasons at most");
        assertThrows(IllegalArgumentException.class, () -> SiegeBook.waitSeasons(-1));
    }

    @Test
    void theWaitCountsFromTheStartWithThePopulationThen() {
        SiegeBook b = new SiegeBook();
        b.begin(1, 1000, 1500);
        assertEquals(2 * SEASON, b.waitLeft(1, 1000, SEASON));
        assertEquals(SEASON, b.waitLeft(1, 1000 + SEASON, SEASON));
        assertEquals(0, b.waitLeft(1, 1000 + 2 * SEASON + 5, SEASON), "passed: nothing left");
        b.begin(1, 9000, 5000);
        assertEquals(1000, b.startSecond(1), "a running siege keeps its start and population");
        assertEquals(1500, b.population(1));
    }

    @Test
    void aSiegeThatEndsStartsOverNextTime() {
        SiegeBook b = new SiegeBook();
        b.begin(2, 100, 800);
        b.end(2);
        assertFalse(b.besieged(2));
        assertEquals(-1, b.startSecond(2));
        assertThrows(IllegalStateException.class, () -> b.waitLeft(2, 200, SEASON));
        b.begin(2, 5000, 2500);
        assertEquals(3 * SEASON, b.waitLeft(2, 5000, SEASON));
    }

    @Test
    void aRemovedHumansSiegeGoes() {
        SiegeBook b = new SiegeBook();
        b.begin(1, 10, 10);
        b.begin(3, 20, 20);
        b.dropHuman(1);
        assertFalse(b.besieged(1));
        assertTrue(b.besieged(3));
    }

    @Test
    void theBookReadsBackAsItWasAndHashesByContent() throws IOException {
        SiegeBook b = new SiegeBook();
        b.begin(3, 777, 4200);
        b.begin(1, 12, 900);
        SiegeBook back = SiegeBook.fromInts(b.toInts());
        assertArrayEquals(b.toInts(), back.toInts());
        assertEquals(b.hash(5), back.hash(5));
        assertEquals(4200, back.population(3));
        back.end(1);
        assertNotEquals(b.hash(5), back.hash(5));
        assertTrue(new SiegeBook().empty());
    }

    @Test
    void aBrokenBookIsRefused() {
        assertThrows(IOException.class, () -> SiegeBook.fromInts(new int[] {2, 0}), "another version");
        assertThrows(IOException.class, () -> SiegeBook.fromInts(new int[] {1, 2, 1, 0, 0}), "too short");
        assertThrows(IOException.class, () -> SiegeBook.fromInts(new int[] {1, 1, 1, 0, -5}), "a negative population");
        assertThrows(IOException.class, () -> SiegeBook.fromInts(new int[] {1, 2, 1, 0, 0, 1, 5, 5}), "one slot twice");
    }
}

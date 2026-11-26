package sosdet.game.titles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TitleTableTest {

    @Test
    void aSlotReadsItsPickedTitlesAndZeroElsewhere() {
        TitleTable t = new TitleTable();
        t.set(2, new int[] {1, 4}, new int[] {3, 7}, 10);
        assertEquals(3, t.races(2, 1));
        assertEquals(7, t.races(2, 4));
        assertEquals(0, t.races(2, 0));
        assertEquals(0, t.races(2, 99)); // a title past the row: a game with more titles than the table knew
        assertEquals(0, t.races(0, 1)); // a slot with nothing set
    }

    @Test
    void setReplacesWhatTheSlotHad() {
        TitleTable t = new TitleTable();
        t.set(0, new int[] {1}, new int[] {5}, 4);
        t.set(0, new int[] {2}, new int[] {6}, 4);
        assertEquals(0, t.races(0, 1));
        assertEquals(6, t.races(0, 2));
    }

    @Test
    void noTitlesStillCountsAsSet() {
        TitleTable t = new TitleTable();
        assertTrue(t.empty());
        t.set(3, new int[0], new int[0], 4);
        assertFalse(t.empty());
        assertEquals(0, t.races(3, 0));
    }

    @Test
    void aRoundTripKeepsEverySlot() {
        TitleTable t = new TitleTable();
        t.set(0, new int[] {0, 3}, new int[] {1, 2}, 5);
        t.set(7, new int[] {4}, new int[] {9}, 5);
        t.set(63, new int[0], new int[0], 5);
        TitleTable back = TitleTable.fromInts(t.toInts());
        assertEquals(t, back);
        assertEquals(9, back.races(7, 4));
        assertEquals(new TitleTable(), TitleTable.fromInts(new TitleTable().toInts()));
    }

    @Test
    void theHashFollowsTheCounts() {
        TitleTable a = new TitleTable();
        TitleTable b = new TitleTable();
        a.set(1, new int[] {2}, new int[] {3}, 5);
        b.set(1, new int[] {2}, new int[] {3}, 5);
        assertEquals(a.hash(7), b.hash(7));
        b.set(1, new int[] {2}, new int[] {4}, 5);
        assertNotEquals(a.hash(7), b.hash(7));
        assertEquals(7, new TitleTable().hash(7));
    }

    @Test
    void badInputIsRefused() {
        TitleTable t = new TitleTable();
        assertThrows(IllegalArgumentException.class, () -> t.set(64, new int[0], new int[0], 4));
        assertThrows(IllegalArgumentException.class, () -> t.set(0, new int[] {1}, new int[0], 4));
        assertThrows(IllegalArgumentException.class, () -> t.set(0, new int[] {4}, new int[] {1}, 4));
        assertThrows(IllegalArgumentException.class, () -> t.set(0, new int[] {1}, new int[] {-1}, 4));
    }

    @Test
    void aMalformedListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TitleTable.fromInts(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> TitleTable.fromInts(new int[] {2, 0}));
        assertThrows(IllegalArgumentException.class, () -> TitleTable.fromInts(new int[] {1, 1, 0, 3, 1}));
        assertThrows(IllegalArgumentException.class, () -> TitleTable.fromInts(new int[] {1, 0, 5}));
        assertThrows(IllegalArgumentException.class, () -> TitleTable.fromInts(new int[] {1, 2, 3, 0, 3, 0}));
    }
}

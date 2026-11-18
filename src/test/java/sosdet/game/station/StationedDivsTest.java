package sosdet.game.station;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class StationedDivsTest {

    @Test
    void aDivisionIsTheHostsOwnUntilPutAndAgainOnceCleared() {
        StationedDivs s = new StationedDivs(120);
        assertFalse(s.stationed(7));
        s.put(7, 2, 31, 4, 0);
        assertTrue(s.stationed(7));
        assertEquals(2, s.owner(7));
        assertEquals(31, s.army(7));
        assertEquals(4, s.iteration(7));
        assertEquals(0, s.place(7));
        assertEquals(1, s.size());
        s.clear(7);
        assertFalse(s.stationed(7));
        assertEquals(-1, s.owner(7));
        assertTrue(s.empty());
    }

    @Test
    void anIndexOutsideArmyZeroIsNeverStationed() {
        StationedDivs s = new StationedDivs(120);
        assertFalse(s.stationed(-1));
        assertFalse(s.stationed(120), "army 1's first division");
        assertThrows(IllegalArgumentException.class, () -> s.put(120, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> s.army(3), "no soldiers there");
    }

    @Test
    void aDivisionHoldsOneAllysSoldiersAtATime() {
        StationedDivs s = new StationedDivs(10);
        s.put(3, 1, 5, 0, 2);
        assertThrows(IllegalStateException.class, () -> s.put(3, 2, 6, 0, 0));
    }

    @Test
    void theRegistrySavesAndLoadsTheSame() throws IOException {
        StationedDivs s = new StationedDivs(120);
        s.put(40, 1, 12, 3, 0);
        s.put(41, 1, 12, 3, 1);
        s.put(90, 2, 7, 0, 5);
        StationedDivs back = StationedDivs.fromInts(s.toInts());
        assertArrayEquals(s.toInts(), back.toInts());
        assertEquals(120, back.slots());
        assertEquals(5, back.place(90));
        assertEquals(new StationedDivs(120).toInts().length, StationedDivs.fromInts(new StationedDivs(120).toInts()).toInts().length);
    }

    @Test
    void aBadRegistryIsRefused() {
        assertThrows(IOException.class, () -> StationedDivs.fromInts(new int[] {2, 120, 0}), "another version");
        assertThrows(IOException.class, () -> StationedDivs.fromInts(new int[] {1, 120, 1, 3}), "cut short");
        assertThrows(IOException.class, () -> StationedDivs.fromInts(new int[] {1, 120, 2, 3, 1, 5, 0, 0, 3, 2, 6, 0, 0}),
                "one division twice");
        assertThrows(IOException.class, () -> StationedDivs.fromInts(new int[] {1, 0, 0}), "an army of no divisions");
    }
}

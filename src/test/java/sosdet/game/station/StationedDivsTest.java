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
        assertEquals(0, s.key(7));
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
        assertEquals(5, back.key(90));
        assertEquals(new StationedDivs(120).toInts().length, StationedDivs.fromInts(new StationedDivs(120).toInts()).toInts().length);
    }

    @Test
    void aBadRegistryIsRefused() {
        assertThrows(IOException.class, () -> StationedDivs.fromInts(new int[] {3, 120, 0}), "another version");
        assertThrows(IOException.class, () -> StationedDivs.fromInts(new int[] {1, 120, 1, 3}), "cut short");
        assertThrows(IOException.class, () -> StationedDivs.fromInts(new int[] {1, 120, 2, 3, 1, 5, 0, 0, 3, 2, 6, 0, 0}),
                "one division twice");
        assertThrows(IOException.class, () -> StationedDivs.fromInts(new int[] {1, 0, 0}), "an army of no divisions");
    }

    /** Version 1 kept the division's place in its army: it loads as key 0, which no world division has. */
    @Test
    void aVersionOneRegistryLoadsWithNoKeys() throws IOException {
        StationedDivs s = StationedDivs.fromInts(new int[] {1, 120, 1, 40, 1, 12, 3, 2});
        assertTrue(s.stationed(40));
        assertEquals(0, s.key(40));
        assertEquals(12, s.army(40));
    }

    /** A fight's dead come off the world division as it stands then: men who joined it meanwhile stay. */
    @Test
    void theDeadComeOffTheWorldDivision() {
        assertEquals(30, StationedDivs.survivors(50, 20));
        assertEquals(35, StationedDivs.survivors(55, 20), "five joined during the fight");
        assertEquals(50, StationedDivs.survivors(50, 0), "nobody died");
        assertEquals(0, StationedDivs.survivors(10, 20), "never fewer than none");
        assertEquals(50, StationedDivs.survivors(50, -3), "a negative count kills nobody");
    }

    @Test
    void aFightsResolveSurvivesItsBytes() {
        StationedDivs.Resolve r = new StationedDivs.Resolve(7, 3, 2, 14, 0.375);
        assertEquals(r, StationedDivs.Resolve.of(r.toBytes()));
        assertThrows(IllegalArgumentException.class, () -> StationedDivs.Resolve.of(new byte[20]), "cut short");
    }
}

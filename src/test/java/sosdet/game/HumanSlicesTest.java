package sosdet.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** SOS-46 M3, M4: a human named by its slot in an engine section reads into the slice of its place among the slots. */
class HumanSlicesTest {

    @Test
    void aSlotReadsIntoItsPlaceAmongTheHumanSlots() throws IOException {
        int[] slots = {0, 3, 7};
        assertEquals(1, HumanSlices.ordinal(slots, 3));
        assertEquals(2, HumanSlices.ordinal(slots, 7));
    }

    @Test
    void slotZeroAndANonHumanAreABrokenSave() {
        int[] slots = {0, 3, 7};
        assertThrows(IOException.class, () -> HumanSlices.ordinal(slots, 0), "slot 0's values are in the game's sections");
        assertThrows(IOException.class, () -> HumanSlices.ordinal(slots, 5), "slot 5 is no human");
        assertThrows(IOException.class, () -> HumanSlices.ordinal(new int[] {0}, 3), "a one-human game has no other slice");
    }

    @Test
    void aSaveOfFewerHumansKeepsTheOrderOfTheSlots() throws IOException {
        int[] slots = {0, 2, 3, 9};
        assertEquals(3, HumanSlices.ordinal(slots, 9), "the slice is the place now, not the slot");
    }
}

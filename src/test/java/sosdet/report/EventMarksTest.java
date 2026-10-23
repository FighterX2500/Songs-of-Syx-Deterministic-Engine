package sosdet.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EventMarksTest {

    @Test
    void theCityReadsWhatItSentUntilTheWorldAppliesIt() {
        EventMarks m = new EventMarks();
        assertTrue(m.set(0, 1, 40, 7, 0));
        assertEquals(7, m.read(0, 1, 40, 0));
        assertEquals(0, m.read(1, 1, 40, 0)); // another target kind
        m.applied(0, 1, 40);
        assertEquals(3, m.read(0, 1, 40, 3)); // applied: the world's own value again
    }

    @Test
    void aMarkThatChangesNothingDoesNotTravel() {
        EventMarks m = new EventMarks();
        assertFalse(m.set(2, 0, 5, 0, 0));
        assertTrue(m.set(2, 0, 5, 1, 0));
        assertFalse(m.set(2, 0, 5, 1, 0)); // already sent
        assertTrue(m.set(2, 0, 5, 0, 0)); // the world still has 0, but 1 is on its way
        m.applied(2, 0, 5);
        assertEquals(0, m.read(2, 0, 5, 1)); // one still in flight
        m.applied(2, 0, 5);
        assertEquals(0, m.read(2, 0, 5, 0));
    }
}

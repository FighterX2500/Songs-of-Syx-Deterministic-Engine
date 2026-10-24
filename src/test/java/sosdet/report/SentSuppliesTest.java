package sosdet.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SentSuppliesTest {

    /** B2: the city reads what it sent until the report carrying it applies, and never twice. */
    @Test
    void theCityReadsWhatItSentUntilTheWorldAppliesIt() {
        SentSupplies s = new SentSupplies();
        assertEquals(0, s.pending(3, 5, 1));
        s.sent(3, 5, 1, 4, 10, 8);
        s.sent(3, 5, 1, 2, 10, 8);
        s.sent(2, 5, 1, 7, 10, 8);
        assertEquals(6, s.pending(3, 5, 1));
        assertEquals(7, s.pending(2, 5, 1));
        assertEquals(0, s.pending(3, 4, 1));
        s.applied(3, 5, 1, 4);
        assertEquals(2, s.pending(3, 5, 1));
        s.applied(3, 5, 1, 2);
        s.applied(3, 5, 1, 2); // a report applied again changes nothing
        assertEquals(0, s.pending(3, 5, 1));
        assertEquals(7, s.pending(2, 5, 1));
    }

    @Test
    void aNewArmyInTheSlotReadsNothing() {
        SentSupplies s = new SentSupplies();
        s.sent(0, 2, 1, 5, 4, 4);
        assertEquals(0, s.pending(0, 2, 2));
        s.sent(0, 2, 2, 3, 4, 4); // the old army's goods are lost with it
        assertEquals(3, s.pending(0, 2, 2));
        s.applied(0, 2, 1, 5);
        assertEquals(3, s.pending(0, 2, 2));
        s.clear();
        assertEquals(0, s.pending(0, 2, 2));
    }
}

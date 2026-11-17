package sosdet.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** The playing humans' last reports in a world save (SOSDET_FROZEN format 2), for a freeze at the start. */
class LastReportsTest {

    private static final ReportLayout SMALL = new ReportLayout(1, 1, 1, 1, 1, 1, 1, 1, 1, 1);

    private static byte[] report(int slot, long takenAt) {
        double[] v = new double[SMALL.size()];
        v[SMALL.slot(Field.CREDITS, 0)] = 1234.5;
        return new CityReport(SMALL, slot, takenAt, takenAt + 30, v).toBytes();
    }

    private static byte[] ledger(long step, long credits) {
        Reports r = new Reports();
        r.stamp(new CityReport(SMALL, 0, 0, 0, new double[SMALL.size()]));
        r.applyDue(0);
        r.change(step, Field.CREDITS, 0, credits);
        return r.ledgerOut();
    }

    @Test
    void roundTripKeepsEachSlotsReportAndLedger() {
        LastReports out = new LastReports(64);
        out.put(3, report(3, 900), ledger(905, 7));
        out.put(9, report(9, 870), ledger(0, 0));
        LastReports in = new LastReports(64);
        in.put(5, report(5, 1), ledger(1, 1)); // replaced by the load
        in.fromBytes(out.toBytes());
        assertArrayEquals(report(3, 900), in.get(3)[0]);
        assertArrayEquals(ledger(905, 7), in.get(3)[1]);
        assertArrayEquals(report(9, 870), in.get(9)[0]);
        assertArrayEquals(ledger(0, 0), in.get(9)[1]);
        assertNull(in.get(5), "what was there before the load goes");
        assertNull(in.get(0));
        assertArrayEquals(out.toBytes(), in.toBytes(), "the same bytes again: every PC that loads a save writes it alike");
        CityReport r = CityReport.fromBytes(in.get(3)[0], SMALL);
        Reports read = new Reports();
        read.restore(r, in.get(3)[1]);
        assertEquals(1234.5 + 7, read.value(Field.CREDITS, 0), "the world reads the report with its open change");
    }

    @Test
    void noneRoundTrips() {
        LastReports in = new LastReports(64);
        in.fromBytes(new LastReports(64).toBytes());
        assertNull(in.get(3));
        assertArrayEquals(new byte[4], in.toBytes());
    }

    @Test
    void aRemovedSlotIsNotWritten() {
        LastReports out = new LastReports(64);
        out.put(3, report(3, 900), ledger(905, 7));
        out.remove(3);
        assertArrayEquals(new byte[4], out.toBytes());
    }

    @Test
    void badBytesAreRefusedAndChangeNothing() {
        LastReports out = new LastReports(64);
        out.put(3, report(3, 900), ledger(905, 7));
        byte[] good = out.toBytes();
        LastReports in = new LastReports(64);
        in.put(4, report(4, 1), ledger(1, 1));
        assertThrows(IllegalArgumentException.class, () -> in.fromBytes(Arrays.copyOf(good, good.length - 1)), "cut short");
        assertThrows(IllegalArgumentException.class, () -> in.fromBytes(Arrays.copyOf(good, good.length + 1)), "more after");
        byte[] badSlot = good.clone();
        badSlot[7] = 64; // the slot, after the count
        assertThrows(IllegalArgumentException.class, () -> in.fromBytes(badSlot), "a slot out of range");
        LastReports two = new LastReports(64);
        two.put(3, report(3, 900), ledger(905, 7));
        two.put(4, report(4, 900), ledger(905, 7));
        byte[] twice = two.toBytes();
        int second = 4 + 4 + 4 + report(3, 900).length + 4 + ledger(905, 7).length;
        twice[second + 3] = 3; // the second entry's slot: 3 again
        assertThrows(IllegalArgumentException.class, () -> in.fromBytes(twice), "a slot twice");
        assertArrayEquals(report(4, 1), in.get(4)[0], "a refused load leaves what was there");
        assertNull(in.get(3));
    }
}

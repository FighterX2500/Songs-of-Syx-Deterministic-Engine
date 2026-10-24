package sosdet.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class CityReportTest {

    /**
     * V71's sizes: 50 tradables, 4 trade types, 8 races, 4 classes, 120 divisions, 8 equipment types, 6 trainings,
     * and boost and faction values in the hundreds.
     */
    private static final ReportLayout V71 = new ReportLayout(50, 4, 8, 4, 120, 8, 6, 400, 200, 300);

    private static double[] values(ReportLayout layout) {
        double[] v = new double[layout.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = i % 7 == 0 ? 0 : i * 3 - 500; // mostly whole numbers, as a city's counts are
        }
        v[layout.slot(Field.CREDITS, 0)] = 123_456.789;
        v[layout.slot(Field.MERCY, 0)] = -0.0;
        v[layout.slot(Field.WAR_STANDING, 3)] = Double.NaN;
        v[layout.slot(Field.HELD, 7)] = 1L << 53;
        v[layout.slot(Field.HELD, 8)] = -1e300;
        return v;
    }

    @Test
    void aReportReadsBackBitForBit() {
        CityReport r = new CityReport(V71, 0, 3_000, 3_030, values(V71));
        byte[] bytes = r.toBytes();
        CityReport back = CityReport.fromBytes(bytes, V71);
        assertEquals(r, back);
        assertEquals(3_000, back.takenAt);
        assertEquals(3_030, back.appliesAt);
        assertEquals(123_456.789, back.get(Field.CREDITS, 0));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(back.get(Field.MERCY, 0)));
        assertTrue(Double.isNaN(back.get(Field.WAR_STANDING, 3)));
        assertEquals(1L << 53, (long) back.get(Field.HELD, 7));
        // ADR 0005: "a few KB". Fractions take 9 bytes each; the worst case, 120 garrison divisions with fractional
        // training and equipment and 600 fractional boost and faction values, is about 20 KB
        assertTrue(bytes.length < 21_000, bytes.length + " bytes");
    }

    /** B1, B2: what the city did to the world travels in the report, in order, and is part of its hash. */
    @Test
    void inputsReadBackInOrderAndChangeTheHash() {
        List<Input> inputs = new ArrayList<>();
        inputs.add(new Input(InputKind.SOLDIER_JOINS, 17, new byte[] {1, 2, 3, -1}));
        inputs.add(new Input(InputKind.SUPPLY, -2, new byte[0]));
        inputs.add(new Input(InputKind.SOLDIER_JOINS, 0, new byte[300]));
        CityReport r = new CityReport(V71, 0, 30, 60, values(V71), inputs);
        CityReport back = CityReport.fromBytes(r.toBytes(), V71);
        assertEquals(r, back);
        assertEquals(inputs, back.inputs);
        CityReport none = new CityReport(V71, 0, 30, 60, values(V71));
        assertTrue(none.inputs.isEmpty());
        assertTrue(none.hash() != r.hash());
        assertTrue(!none.equals(r));
        inputs.clear(); // the report keeps its own list
        assertEquals(3, r.inputs.size());
    }

    @Test
    void twoListFieldsHaveTheirOwnSlots() {
        assertEquals(V71.slot(Field.INCOMING, 0, 0) + 5, V71.slot(Field.INCOMING, 1, 0));
        assertEquals(V71.slot(Field.PHYSICAL, 0, 0) + 9, V71.slot(Field.PHYSICAL, 1, 0));
        assertEquals(V71.slot(Field.DIV_EQUIP, 0, 7) + 1, V71.slot(Field.DIV_EQUIP, 1, 0));
        assertEquals(V71.slot(Field.GARRISON, 0), V71.slot(Field.DIV_EQUIP, 119, 7) + 1);
        assertEquals(V71.slot(Field.GAR_TRAINING, 0, 5) + 1, V71.slot(Field.GAR_TRAINING, 1, 0));
        assertEquals(V71.slot(Field.BOOST, 0), V71.slot(Field.INVASION_REF, 0) + 1);
        assertEquals(V71.slot(Field.INVASION_REF, 0), V71.slot(Field.INVADING_POWER, 0) + 1);
        assertEquals(V71.slot(Field.VALUE, 199) + 1, V71.slot(Field.UNLOCK, 0));
        assertEquals(V71.size() - 1, V71.slot(Field.UNLOCK, 299));
        assertThrows(IllegalArgumentException.class, () -> V71.slot(Field.HELD, 50));
        assertThrows(IllegalArgumentException.class, () -> V71.slot(Field.PHYSICAL, 3));
        assertThrows(IllegalArgumentException.class, () -> V71.slot(Field.INCOMING, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> V71.slot(Field.CREDITS, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> V71.slot(Field.EQUIP_IN_DIVS, 8));
        assertThrows(IllegalArgumentException.class, () -> new ReportLayout(50, 4, 8, 4, 120, 8, 6, 400, 200));
    }

    @Test
    void anotherVersionOtherSizesOrACutReportAreRefused() {
        byte[] bytes = new CityReport(V71, 0, 0, 30, values(V71)).toBytes();
        byte[] wrongVersion = bytes.clone();
        wrongVersion[7] = 9;
        assertThrows(IllegalArgumentException.class, () -> CityReport.fromBytes(wrongVersion, V71));
        assertThrows(IllegalArgumentException.class,
                () -> CityReport.fromBytes(bytes, new ReportLayout(51, 4, 8, 4, 120, 8, 6, 400, 200, 300)));
        assertThrows(IllegalArgumentException.class,
                () -> CityReport.fromBytes(Arrays.copyOf(bytes, bytes.length - 1), V71));
        assertThrows(IllegalArgumentException.class,
                () -> CityReport.fromBytes(Arrays.copyOf(bytes, bytes.length + 1), V71));
        assertThrows(IllegalArgumentException.class, () -> new CityReport(V71, 0, 0, 30, new double[3]));
    }

    @Test
    void theWorldReadsEachReportFromItsStepUntilTheNext() {
        ReportLayout small = new ReportLayout(1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        double[] v = new double[small.size()];
        Reports reports = new Reports();
        assertTrue(Reports.takenAt(0) && Reports.takenAt(30) && !Reports.takenAt(31));

        CityReport load = new CityReport(small, 0, 1, 1, v);
        reports.stamp(load);
        reports.applyDue(1);
        assertSame(load, reports.current());

        CityReport at30 = new CityReport(small, 0, 30, 60, v);
        CityReport at60 = new CityReport(small, 0, 60, 90, v);
        reports.stamp(at30);
        reports.applyDue(59);
        assertSame(load, reports.current());
        reports.stamp(at60);
        reports.applyDue(60);
        assertSame(at30, reports.current());
        assertTrue(reports.pending());
        reports.applyDue(95); // a late world part applies everything due, the latest last
        assertSame(at60, reports.current());

        assertThrows(IllegalStateException.class, () -> reports.stamp(new CityReport(small, 0, 60, 90, v)));
        reports.clear();
        assertNull(reports.current());
    }

    /** A follower runs the world part only as far as the reports it has (ADR 0005 §4): to the step before the next. */
    @Test
    void theNextReportIsDueThirtyStepsAfterItIsTaken() {
        assertEquals(59, Reports.lastReadableStep(1)); // the load report; the next is taken at 30, read from 60
        assertEquals(89, Reports.lastReadableStep(30));
        assertEquals(89, Reports.lastReadableStep(59));
        assertEquals(119, Reports.lastReadableStep(60));
        assertThrows(IllegalArgumentException.class, () -> Reports.lastReadableStep(-1));
    }

    /** A world resync hands every PC the host's open changes (SOS-38). */
    @Test
    void theLedgerTravelsInAWorldResync() {
        ReportLayout small = new ReportLayout(2, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        double[] v = new double[small.size()];
        v[small.slot(Field.CREDITS, 0)] = 1_000;
        Reports host = new Reports();
        host.stamp(new CityReport(small, 0, 30, 60, v));
        host.applyDue(60);
        host.change(61, Field.CREDITS, 0, -200);
        host.change(62, Field.PROMISED, 1, 0, 7);

        Reports peer = new Reports();
        peer.stamp(new CityReport(small, 0, 30, 60, v));
        peer.applyDue(60);
        peer.change(61, Field.CREDITS, 0, -999); // the peer's world went another way
        peer.ledgerIn(host.ledgerOut());
        assertEquals(800, peer.value(Field.CREDITS, 0));
        assertEquals(7, peer.value(Field.PROMISED, 1, 0));
        assertEquals(2, peer.open());
        assertThrows(IllegalArgumentException.class, () -> peer.ledgerIn(new byte[] {0, 0, 0, 1}));
    }

    /**
     * The world's own changes add to the report it reads until a report taken after them applies, so 500 credits
     * spent at step 40 are never spent again from the step-30 report (ADR 0005 §3).
     */
    @Test
    void theLedgerAddsTheWorldsChangesUntilAReportShowsThem() {
        ReportLayout small = new ReportLayout(2, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        double[] v = new double[small.size()];
        v[small.slot(Field.CREDITS, 0)] = 1_000;
        Reports reports = new Reports();
        reports.stamp(new CityReport(small, 0, 30, 60, v));
        reports.applyDue(60);

        reports.change(40, Field.CREDITS, 0, -500); // made before the step-60 report was taken
        reports.change(61, Field.CREDITS, 0, -200);
        reports.change(61, Field.PROMISED, 1, 0, 7);
        assertEquals(300, reports.value(Field.CREDITS, 0));
        assertEquals(7, reports.value(Field.PROMISED, 1, 0));
        assertEquals(0, reports.value(Field.PROMISED, 0, 0));
        assertEquals(3, reports.open());

        double[] after = v.clone();
        after[small.slot(Field.CREDITS, 0)] = 500; // the city paid the 500 before step 60
        reports.stamp(new CityReport(small, 0, 60, 90, after));
        reports.applyDue(90);
        assertEquals(300, reports.value(Field.CREDITS, 0));
        assertEquals(2, reports.open());

        double[] later = v.clone();
        later[small.slot(Field.CREDITS, 0)] = 300;
        later[small.slot(Field.PROMISED, 1, 0)] = 7;
        reports.stamp(new CityReport(small, 0, 90, 120, later));
        reports.applyDue(120);
        assertEquals(300, reports.value(Field.CREDITS, 0));
        assertEquals(7, reports.value(Field.PROMISED, 1, 0));
        assertEquals(0, reports.open());

        reports.change(121, Field.CREDITS, 0, 5);
        reports.clear();
        assertEquals(0, reports.open());
    }
}

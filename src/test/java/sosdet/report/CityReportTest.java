package sosdet.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class CityReportTest {

    /**
     * V71's sizes: 50 tradables, 4 trade types, 8 races, 4 classes, 120 divisions, 8 equipment types, 6 trainings,
     * and boost and faction values in the hundreds.
     */
    private static final ReportLayout V71 = new ReportLayout(50, 4, 8, 4, 120, 8, 6, 400, 200);

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

    @Test
    void twoListFieldsHaveTheirOwnSlots() {
        assertEquals(V71.slot(Field.INCOMING, 0, 0) + 5, V71.slot(Field.INCOMING, 1, 0));
        assertEquals(V71.slot(Field.PHYSICAL, 0, 0) + 9, V71.slot(Field.PHYSICAL, 1, 0));
        assertEquals(V71.slot(Field.DIV_EQUIP, 0, 7) + 1, V71.slot(Field.DIV_EQUIP, 1, 0));
        assertEquals(V71.slot(Field.GARRISON, 0), V71.slot(Field.DIV_EQUIP, 119, 7) + 1);
        assertEquals(V71.slot(Field.GAR_TRAINING, 0, 5) + 1, V71.slot(Field.GAR_TRAINING, 1, 0));
        assertEquals(V71.slot(Field.BOOST, 0), V71.slot(Field.INVADING_POWER, 0) + 1);
        assertEquals(V71.size() - 1, V71.slot(Field.VALUE, 199));
        assertThrows(IllegalArgumentException.class, () -> V71.slot(Field.HELD, 50));
        assertThrows(IllegalArgumentException.class, () -> V71.slot(Field.PHYSICAL, 3));
        assertThrows(IllegalArgumentException.class, () -> V71.slot(Field.INCOMING, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> V71.slot(Field.CREDITS, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> V71.slot(Field.EQUIP_IN_DIVS, 8));
        assertThrows(IllegalArgumentException.class, () -> new ReportLayout(50, 4, 8, 4, 120, 8, 6, 400));
    }

    @Test
    void anotherVersionOtherSizesOrACutReportAreRefused() {
        byte[] bytes = new CityReport(V71, 0, 0, 30, values(V71)).toBytes();
        byte[] wrongVersion = bytes.clone();
        wrongVersion[7] = 9;
        assertThrows(IllegalArgumentException.class, () -> CityReport.fromBytes(wrongVersion, V71));
        assertThrows(IllegalArgumentException.class,
                () -> CityReport.fromBytes(bytes, new ReportLayout(51, 4, 8, 4, 120, 8, 6, 400, 200)));
        assertThrows(IllegalArgumentException.class,
                () -> CityReport.fromBytes(Arrays.copyOf(bytes, bytes.length - 1), V71));
        assertThrows(IllegalArgumentException.class,
                () -> CityReport.fromBytes(Arrays.copyOf(bytes, bytes.length + 1), V71));
        assertThrows(IllegalArgumentException.class, () -> new CityReport(V71, 0, 0, 30, new double[3]));
    }

    @Test
    void theWorldReadsEachReportFromItsStepUntilTheNext() {
        ReportLayout small = new ReportLayout(1, 1, 1, 1, 1, 1, 1, 1, 1);
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
}

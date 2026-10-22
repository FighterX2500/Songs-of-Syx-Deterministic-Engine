package sosdet.hash;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class HashLogTest {

    /** A battle of four divisions whose state is a few numbers. */
    static final class FakeState implements StateSource {
        final long[] soldiers = new long[4];
        long projectiles;

        @Override
        public int divisions() {
            return soldiers.length;
        }

        @Override
        public void hash(long[] sections, long[] divisions) {
            for (int d = 0; d < soldiers.length; d++) {
                divisions[d] = HashLog.mix(divisions[d], soldiers[d]);
            }
            sections[HashLog.DIVISIONS] = 7;
            sections[HashLog.PROJECTILES] = projectiles;
            sections[HashLog.ENTITIES] = 9;
        }

        @Override
        public String divisionName(int index) {
            return "division " + index;
        }
    }

    /** Runs a PC from step {@code from} to {@code to}, hashing where due. */
    private static void run(HashLog log, FakeState state, long from, long to) {
        for (long s = from; s <= to; s++) {
            if (log.due(s)) {
                log.take(s, s, state);
            }
        }
    }

    @Test
    void aHashEveryThirtyStepsAndTheLastSixtyFourKept() {
        HashLog log = new HashLog();
        FakeState state = new FakeState();
        assertFalse(log.due(0));
        assertTrue(log.due(30));
        assertFalse(log.due(31));
        run(log, state, 1, 30 * 70);
        assertEquals(70, log.taken());
        assertEquals(log.total(30 * 70), log.total(30 * 70));
        log.total(30 * 7); // the oldest kept
        assertThrows(IllegalArgumentException.class, () -> log.total(30 * 6), "dropped from the ring");
        assertThrows(IllegalArgumentException.class, () -> log.total(31), "never hashed");
        assertThrows(IllegalArgumentException.class, () -> log.total(30 * 71), "not reached");
    }

    @Test
    void theSameStateGivesTheSameHashesAndNoDesync() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState sa = new FakeState();
        FakeState sb = new FakeState();
        run(a, sa, 1, 300);
        run(b, sb, 1, 300);
        a.in(2, b.out());
        b.in(1, a.out());
        assertEquals(10, a.compared());
        assertEquals(10, b.compared());
        assertNull(a.desync());
        assertEquals(a.total(300), b.total(300));
    }

    @Test
    void aChangeIsFoundAtTheFirstHashAfterItWithItsSectionAndDivision() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState sa = new FakeState();
        FakeState sb = new FakeState();
        run(a, sa, 1, 120);
        run(b, sb, 1, 45);
        sb.soldiers[2] = 1; // forced change at step 45
        run(b, sb, 46, 120);
        a.in(2, b.out());
        Desync d = a.desync();
        assertNotNull(d);
        assertEquals(60, d.step);
        assertEquals(2, d.pc);
        assertEquals(120, d.noticedAt);
        assertEquals(List.of("soldiers"), d.sections());
        assertArrayEquals(new int[] {2}, d.divisions());
    }

    @Test
    void theFirstDesyncIsKept() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState sa = new FakeState();
        FakeState sb = new FakeState();
        sb.projectiles = 5;
        run(a, sa, 1, 90);
        run(b, sb, 1, 90);
        a.in(2, Arrays.copyOf(b.out(), HashLog.HEADER_BYTES + 3 * recordBytes(4)));
        assertEquals(30, a.desync().step);
        assertEquals(List.of("projectiles"), a.desync().sections());
    }

    @Test
    void aHashFromAPcAheadWaitsUntilThisPcGetsThere() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState sa = new FakeState();
        FakeState sb = new FakeState();
        run(a, sa, 1, 30);
        run(b, sb, 1, 90);
        a.in(2, b.out());
        assertEquals(1, a.compared());
        sa.projectiles = 3; // differs from step 60 on
        run(a, sa, 31, 90);
        assertEquals(3, a.compared());
        assertEquals(60, a.desync().step);
        assertEquals(60, a.desync().noticedAt, "noticed as soon as this PC hashed step 60");
    }

    @Test
    void hashesArriveInAnyPiecesEvenBeforeThisPcsFirstHash() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState s = new FakeState();
        run(b, s, 1, 150);
        byte[] bytes = b.out();
        for (int i = 0; i < bytes.length; i++) {
            a.in(2, Arrays.copyOfRange(bytes, i, i + 1));
        }
        assertEquals(0, a.compared(), "nothing to compare with yet");
        run(a, s, 1, 150);
        assertEquals(5, a.compared());
        assertNull(a.desync());
    }

    @Test
    void aHashOlderThanTheRingCountsAsMissed() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState s = new FakeState();
        run(b, s, 1, 30 * 2);
        run(a, s, 1, 30 * 70);
        a.in(2, b.out());
        assertEquals(0, a.compared());
        assertEquals(2, a.missed());
    }

    @Test
    void anotherIntervalOrFormatIsRefused() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        b.every(15);
        FakeState s = new FakeState();
        run(b, s, 1, 30);
        assertThrows(IllegalStateException.class, () -> a.in(2, b.out()));
        byte[] junk = new byte[HashLog.HEADER_BYTES];
        assertThrows(IllegalStateException.class, () -> new HashLog().in(3, junk));
    }

    @Test
    void theIntervalIsSetBeforeTheFirstHash() {
        HashLog a = new HashLog();
        a.every(10);
        run(a, new FakeState(), 1, 10);
        assertThrows(IllegalStateException.class, () -> a.every(30));
        assertThrows(IllegalArgumentException.class, () -> new HashLog().every(0));
    }

    @Test
    void theReportNamesTheStepSectionsDivisionsAndWhatWasApplied() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState sa = new FakeState();
        FakeState sb = new FakeState();
        sb.soldiers[1] = 4;
        sb.soldiers[3] = 4;
        run(a, sa, 1, 30);
        run(b, sb, 1, 30);
        a.in(2, b.out());
        String report = a.desync().report(sa, List.of("step 12, #0, guard, division 1 = 0"), List.of());
        assertTrue(report.contains("desync at step 30 against PC 2"), report);
        assertTrue(report.contains("soldiers") && report.contains("DIFFERS"), report);
        assertTrue(report.contains("1: division 1") && report.contains("3: division 3"), report);
        assertTrue(report.contains("step 12, #0, guard, division 1 = 0"), report);
        assertTrue(report.contains("Player orders applied last:\n  none"), report);
    }

    private static int recordBytes(int divisions) {
        return 8 + 8 + 8 * HashLog.SECTIONS + 8 * divisions;
    }
}

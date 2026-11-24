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

    /** A battle of four divisions and a world of 14 parts, whose state is a few numbers. */
    static final class FakeState implements StateSource {
        final long[] soldiers = new long[4];
        long projectiles;
        final long[] world = new long[HashLog.WORLD_PARTS];

        @Override
        public int divisions() {
            return soldiers.length;
        }

        @Override
        public void hash(long[] sections, long[] divisions, long[] worldParts) {
            for (int d = 0; d < soldiers.length; d++) {
                divisions[d] = HashLog.mix(divisions[d], soldiers[d]);
            }
            sections[HashLog.DIVISIONS] = 7;
            sections[HashLog.PROJECTILES] = projectiles;
            sections[HashLog.ENTITIES] = 9;
            long h = HashLog.start(); // a running hash, as the game's world section is
            for (int i = 0; i < world.length; i++) {
                h = HashLog.mix(h, world[i]);
                worldParts[i] = h;
            }
            sections[HashLog.WORLD] = h;
        }

        @Override
        public String divisionName(int index) {
            return "division " + index;
        }

        @Override
        public String describeDivisions(long step) {
            return "";
        }

        @Override
        public String describeEntities(long step) {
            return "";
        }

        @Override
        public String describeWorld(long step, boolean[] differs) {
            StringBuilder b = new StringBuilder("  parts told:");
            for (int i = 0; i < differs.length; i++) {
                if (differs[i]) {
                    b.append(' ').append(i);
                }
            }
            return b.append(System.lineSeparator()).toString();
        }
    }

    /** Runs a PC from step {@code from} to {@code to}, hashing where due. */
    private static void run(HashLog log, FakeState state, long from, long to) {
        for (long s = from; s <= to; s++) {
            if (log.due(s)) {
                log.take(s, s, new long[HashLog.CLOCK_PARTS], 0, state);
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
    void aPeersStepIsItsLastHashThatArrived() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState sa = new FakeState();
        FakeState sb = new FakeState();
        assertEquals(-1, a.reached(2), "nothing from PC 2 yet");
        run(b, sb, 1, 100);
        a.in(2, b.out());
        assertEquals(-1, a.reached(2), "read once a took its own first hash");
        run(a, sa, 1, 30);
        a.in(2, new byte[0]);
        assertEquals(90, a.reached(2), "hashed at 30, 60 and 90; a is at 30, so the others wait to be compared");
        assertEquals(1, a.compared());
        run(a, sa, 31, 200);
        run(b, sb, 101, 150);
        a.in(2, b.out());
        assertEquals(150, a.reached(2));
        assertEquals(-1, a.reached(3), "another PC");
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

    /** SOS-250: the record carries the world's parts, and the desync names the part where the world first differs. */
    @Test
    void aWorldPartChangedOnOnePcIsNamedFromTheRecordsParts() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState sa = new FakeState();
        FakeState sb = new FakeState();
        sa.world[9] = 4;
        sb.world[9] = 4;
        run(a, sa, 1, 60);
        run(b, sb, 1, 45);
        sb.world[9] = 5; // the NPC stocks differ from step 45 on
        run(b, sb, 46, 90);
        a.in(2, b.out());
        Desync d = a.desync();
        assertNotNull(d);
        assertEquals(60, d.step);
        assertEquals(List.of("world"), d.sections());
        assertEquals(HashLog.WORLD_PART_NAMES.subList(9, HashLog.WORLD_PARTS), d.worldParts(),
                "the first part that differs, then every later one: each runs on from the one before");
        String report = d.report(sa, List.of(), List.of());
        assertTrue(report.contains("DIFFERS, from part NPC stocks on (5 of 14 parts)"), report);
        assertTrue(report.contains("  parts told: 9 10 11 12 13"), report);
    }

    @Test
    void aDesyncOutsideTheWorldNamesNoWorldPart() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState sa = new FakeState();
        FakeState sb = new FakeState();
        sa.world[3] = 8;
        sb.world[3] = 8;
        sb.projectiles = 2;
        run(a, sa, 1, 30);
        run(b, sb, 1, 30);
        a.in(2, b.out());
        assertEquals(List.of("projectiles"), a.desync().sections());
        assertEquals(List.of(), a.desync().worldParts(), "the parts came through the record unchanged");
        assertFalse(a.desync().report(sa, List.of(), List.of()).contains("parts told"));
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

    @Test
    void aResyncForgetsTheDesyncAndComparesOnlyNewHashes() {
        HashLog host = new HashLog();
        HashLog peer = new HashLog();
        FakeState hs = new FakeState();
        FakeState ps = new FakeState();
        run(host, hs, 1, 90);
        ps.soldiers[0] = 9; // the peer drifted
        run(peer, ps, 1, 120); // and ran ahead
        byte[] stale = peer.out();
        host.in(2, stale);
        assertNotNull(host.desync());

        host.rewind(90, 1); // host saves the resync at step 90
        assertNull(host.desync());
        host.in(2, Arrays.copyOfRange(stale, HashLog.HEADER_BYTES, stale.length)); // from before the resync: ignored
        assertNull(host.desync());

        ps.soldiers[0] = 0; // the peer loads the host's save
        peer.rewind(90, 1);
        assertThrows(IllegalArgumentException.class, () -> peer.total(120), "dropped: hashed again after the load");
        run(peer, ps, 91, 150);
        run(host, hs, 91, 150);
        long before = host.compared();
        host.in(2, peer.out());
        assertEquals(before + 2, host.compared(), "steps 120 and 150 of the new epoch");
        assertNull(host.desync());
        assertThrows(IllegalArgumentException.class, () -> host.rewind(150, 1), "epochs only go up");
    }

    @Test
    void aHashFromAPcThatLoadedTheResyncFirstWaitsForThisOne() {
        HashLog a = new HashLog();
        HashLog b = new HashLog();
        FakeState s = new FakeState();
        run(a, s, 1, 60);
        run(b, s, 1, 60);
        b.rewind(60, 1);
        run(b, s, 61, 120);
        a.in(2, b.out());
        long before = a.compared();
        run(a, s, 61, 120);
        assertEquals(before, a.compared(), "b is a resync ahead");
        a.rewind(60, 1);
        run(a, s, 61, 120); // hashes 90 and 120 again in the new epoch... by a fresh run after the load
        assertEquals(before + 2, a.compared());
        assertNull(a.desync());
    }

    private static int recordBytes(int divisions) {
        return 8 + 4 + 4 + 8 + 8 * HashLog.SECTIONS + 8 * HashLog.WORLD_PARTS + 8 * divisions;
    }
}

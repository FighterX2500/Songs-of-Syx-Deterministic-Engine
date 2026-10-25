package sosdet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class WorldResyncTest {

    @Test
    void theWorldStateReadsBackAsItWasWritten() {
        byte[] ledger = {0, 0, 0, 0};
        WorldResync r = WorldResync.fromBytes(new WorldResync(3000, 2, 41_000, new int[] {0}, new byte[][] {ledger}).toBytes());
        assertEquals(3000, r.step);
        assertEquals(2, r.hashEpoch);
        assertEquals(41_000, r.worldDraws);
        assertArrayEquals(new int[] {0}, r.slots);
        assertArrayEquals(ledger, r.ledger(0));
    }

    /** Several humans: each slot's own ledger comes back, and only for the slots it was written with (SOS-52). */
    @Test
    void everyHumanKeepsItsOwnLedger() {
        byte[] a = {0, 0, 0, 0};
        byte[] b = {0, 0, 0, 1, 9, 9, 9, 9};
        byte[] c = {};
        WorldResync r = WorldResync.fromBytes(new WorldResync(60, 1, 7, new int[] {0, 3, 12}, new byte[][] {a, b, c}).toBytes());
        assertArrayEquals(new int[] {0, 3, 12}, r.slots);
        assertArrayEquals(a, r.ledger(0));
        assertArrayEquals(b, r.ledger(3));
        assertArrayEquals(c, r.ledger(12));
        assertThrows(IllegalArgumentException.class, () -> r.ledger(4));
    }

    @Test
    void anotherFormatOrACutStateIsRefused() {
        byte[] bytes = new WorldResync(30, 1, 5, new int[] {0}, new byte[][] {new byte[4]}).toBytes();
        assertThrows(IllegalArgumentException.class, () -> WorldResync.fromBytes(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> WorldResync.fromBytes(Arrays.copyOf(bytes, bytes.length + 1)));
        bytes[7] = 1; // the format version: 1 carried one ledger
        assertThrows(IllegalArgumentException.class, () -> WorldResync.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> Resync.fromBytes(new WorldResync(30, 1, 5, new int[] {0},
                new byte[][] {new byte[4]}).toBytes()));
    }

    @Test
    void slotsAndLedgersMustPair() {
        assertThrows(IllegalArgumentException.class, () -> new WorldResync(30, 1, 5, new int[] {0, 1}, new byte[][] {new byte[4]}));
        assertThrows(IllegalArgumentException.class, () -> new WorldResync(30, 1, 5, new int[0], new byte[0][]));
    }
}

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
        WorldResync r = WorldResync.fromBytes(new WorldResync(3000, 2, 41_000, ledger).toBytes());
        assertEquals(3000, r.step);
        assertEquals(2, r.hashEpoch);
        assertEquals(41_000, r.worldDraws);
        assertArrayEquals(ledger, r.ledger);
    }

    @Test
    void anotherFormatOrACutStateIsRefused() {
        byte[] bytes = new WorldResync(30, 1, 5, new byte[4]).toBytes();
        assertThrows(IllegalArgumentException.class, () -> WorldResync.fromBytes(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> WorldResync.fromBytes(Arrays.copyOf(bytes, bytes.length + 1)));
        bytes[7] = 9; // the format version
        assertThrows(IllegalArgumentException.class, () -> WorldResync.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> Resync.fromBytes(new WorldResync(30, 1, 5, new byte[4]).toBytes()));
    }
}

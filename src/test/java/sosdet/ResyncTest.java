package sosdet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ResyncTest {

    @Test
    void theStateRoundTrips() {
        Resync r = new Resync(12_000, 777, 5.0, 30, 2, 123_456_789L, new byte[] {1, 2, 3}, new byte[] {9, 8});
        Resync back = Resync.fromBytes(r.toBytes());
        assertEquals(12_000, back.step);
        assertEquals(777, back.seed);
        assertEquals(5.0, back.speed);
        assertEquals(30, back.hashInterval);
        assertEquals(2, back.hashEpoch);
        assertEquals(123_456_789L, back.draws);
        assertArrayEquals(new byte[] {1, 2, 3}, back.ai);
        assertArrayEquals(new byte[] {9, 8}, back.game);
    }

    @Test
    void anotherFormatOrACutStateIsRefused() {
        byte[] bytes = new Resync(1, 2, 1.0, 30, 1, 0, new byte[0], new byte[4]).toBytes();
        byte[] wrongVersion = bytes.clone();
        wrongVersion[7] = 9;
        assertThrows(IllegalArgumentException.class, () -> Resync.fromBytes(wrongVersion));
        assertThrows(IllegalArgumentException.class, () -> Resync.fromBytes(Arrays.copyOf(bytes, bytes.length - 1)));
        byte[] longer = Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(IllegalArgumentException.class, () -> Resync.fromBytes(longer));
    }
}

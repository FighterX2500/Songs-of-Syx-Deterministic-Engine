package sosdet.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReportFeedTest {

    @Test
    void reportsComeOutWholeFromAnySplitOfTheStream() {
        ReportFeed out = new ReportFeed();
        byte[] a = {1, 2, 3};
        byte[] b = new byte[5000];
        b[4999] = 9;
        out.write(a);
        out.write(b);
        byte[] stream = out.drain();
        assertEquals(0, out.drain().length);

        ReportFeed in = new ReportFeed();
        List<byte[]> got = new ArrayList<>();
        for (int at = 0; at < stream.length; at += 7) {
            got.addAll(in.read(Arrays.copyOfRange(stream, at, Math.min(stream.length, at + 7))));
        }
        assertEquals(2, got.size());
        assertArrayEquals(a, got.get(0));
        assertArrayEquals(b, got.get(1));
    }

    @Test
    void aBrokenLengthIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ReportFeed().read(new byte[] {-1, 0, 0, 0}));
    }
}

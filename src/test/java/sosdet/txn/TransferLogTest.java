package sosdet.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sosdet.txn.TransferLog.Kind;
import sosdet.txn.TransferLog.Transfer;

/** SOS-81: the transfers the logs read. */
class TransferLogTest {

    @Test
    void entriesComeBackInOrderAfterTheLastOneRead() {
        TransferLog l = new TransferLog();
        List<Transfer> out = new ArrayList<>();
        assertEquals(0, l.read(0, out));
        assertEquals(List.of(), out);
        l.record(10, 100, 0, Kind.SOLD, 3, 40, 31, 2, 5);
        l.record(10, 101, 2, Kind.MONEY, -1, 1240.5, 0, 7, -1);
        l.record(11, 102, 0, Kind.DELIVERED, 3, 40, 0, 2, -1);
        assertEquals(0, l.read(0, out));
        assertEquals(3, out.size());
        assertEquals(new Transfer(1, 10, 100, 0, Kind.SOLD, 3, 40, 31, 2, 5), out.get(0));
        assertEquals(new Transfer(2, 10, 101, 2, Kind.MONEY, -1, 1240.5, 0, 7, -1), out.get(1));
        out.clear();
        assertEquals(0, l.read(2, out));
        assertEquals(List.of(new Transfer(3, 11, 102, 0, Kind.DELIVERED, 3, 40, 0, 2, -1)), out);
        assertEquals(3, l.last());
    }

    @Test
    void aReaderThatFellBehindLearnsHowManyItMissed() {
        TransferLog l = new TransferLog();
        int n = TransferLog.CAPACITY + 10;
        for (int i = 1; i <= n; i++) {
            l.record(i, i, 0, Kind.MONEY, -1, i, 0, 0, -1);
        }
        List<Transfer> out = new ArrayList<>();
        assertEquals(10, l.read(0, out), "the first 10 were overwritten");
        assertEquals(TransferLog.CAPACITY, out.size());
        assertEquals(11, out.get(0).seq());
        assertEquals(n, out.get(out.size() - 1).seq());
        out.clear();
        assertEquals(0, l.read(n - 1, out));
        assertEquals(1, out.size());
    }
}

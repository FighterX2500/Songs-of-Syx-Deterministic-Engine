package sosdet.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TxnQueueTest {

    @Test
    void appliesInOrderOnceAndEmpties() {
        TxnQueue q = new TxnQueue();
        q.add(5).i = 1;
        q.add(5).i = 2;
        q.add(6).i = 3;
        List<Integer> seen = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        q.apply(t -> {
            seen.add(t.i);
            ids.add(t.id());
        });
        assertEquals(List.of(1, 2, 3), seen);
        assertEquals(List.of(5L << TxnQueue.SEQ_BITS, (5L << TxnQueue.SEQ_BITS) + 1, 6L << TxnQueue.SEQ_BITS), ids);
        assertTrue(q.isEmpty());
        assertEquals(3, q.applied());
        assertEquals(3, q.maxInStep());
        q.apply(t -> seen.add(-1));
        assertEquals(3, seen.size());
    }

    @Test
    void pooledTransactionsComeBackCleared() {
        TxnQueue q = new TxnQueue();
        Txn t = q.add(1);
        t.kind = TxnKind.CREDITS;
        t.a = "city";
        t.d = 2.5;
        q.apply(x -> { });
        Txn again = q.add(2);
        assertEquals(null, again.kind);
        assertEquals(null, again.a);
        assertEquals(0, again.d);
    }

    /** A world reloaded behind the city makes the same transactions again: they change nothing. */
    @Test
    void aRepeatedTransactionChangesNothing() {
        TxnQueue q = new TxnQueue();
        int[] city = {0};
        q.add(10).i = 7;
        q.add(11).i = 5;
        q.apply(t -> city[0] += t.i);
        assertEquals(12, city[0]);

        q.dropQueued(); // a world-only resync to step 10: the city goes on
        q.add(11).i = 5;
        q.add(12).i = 1;
        q.apply(t -> city[0] += t.i);
        assertEquals(13, city[0]);
        assertEquals(1, q.skipped());
    }

    @Test
    void aLoadedCityHoldsEveryTransactionUpToItsStep() {
        TxnQueue q = new TxnQueue();
        int[] city = {0};
        q.add(40).i = 1;
        q.apply(t -> city[0] += t.i);
        q.appliedThrough(20); // a full resync to step 20: city and world both reloaded
        q.add(21).i = 2;
        q.apply(t -> city[0] += t.i);
        assertEquals(3, city[0]);
        q.appliedThrough(-1); // a plain load starts again at step 0
        q.add(0).i = 4;
        q.apply(t -> city[0] += t.i);
        assertEquals(7, city[0]);
    }
}

package sosdet.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sosdet.ArmyOrder;
import sosdet.ArmyOrder.Kind;

/** SOS-249: the world armies' orders, as the logs read them. */
class ArmyOrdersTest {

    @Test
    void ordersComeBackInOrderAfterTheLastOneRead() {
        ArmyOrders o = new ArmyOrders();
        List<ArmyOrder> out = new ArrayList<>();
        assertEquals(0, o.read(0, out));
        assertEquals(List.of(), out);
        o.record(40, 7, 12, Kind.CREATE, 100, 80, -1);
        o.record(40, 7, 12, Kind.BESIEGE, 120, 90, 33);
        o.record(41, 3, -1, Kind.RAID, 50, 60, 9);
        assertEquals(0, o.read(0, out));
        assertEquals(List.of(new ArmyOrder(1, 40, 7, 12, Kind.CREATE, 100, 80, -1),
                new ArmyOrder(2, 40, 7, 12, Kind.BESIEGE, 120, 90, 33),
                new ArmyOrder(3, 41, 3, -1, Kind.RAID, 50, 60, 9)), out);
        out.clear();
        assertEquals(0, o.read(2, out));
        assertEquals(List.of(new ArmyOrder(3, 41, 3, -1, Kind.RAID, 50, 60, 9)), out);
        assertEquals(3, o.last());
        assertEquals("BESIEGE", Kind.BESIEGE.name());
    }

    @Test
    void theStopAnOrderBeginsWithIsPartOfItButAStopOnItsOwnIsRecorded() {
        ArmyOrders o = new ArmyOrders();
        o.record(40, 7, 12, Kind.STOP, 90, 70, -1); // setDestination stops the army first,
        o.record(40, 7, 12, Kind.MOVE, 100, 80, -1); // then records the move once its path is found: one entry
        o.record(40, 9, 12, Kind.TELEPORT, 5, 6, -1); // a teleport records first,
        o.record(40, 9, 12, Kind.STOP, 5, 6, -1); // then stops: part of the teleport
        o.record(40, 8, 12, Kind.STOP, 20, 30, -1); // another army
        o.record(41, 7, 12, Kind.STOP, 91, 70, -1); // the next step: an order of its own
        o.record(41, 7, 12, Kind.CREATE, 91, 70, -1); // a new army in a freed slot is never merged
        List<ArmyOrder> out = new ArrayList<>();
        o.read(0, out);
        assertEquals(List.of(new ArmyOrder(1, 40, 7, 12, Kind.MOVE, 100, 80, -1),
                new ArmyOrder(2, 40, 9, 12, Kind.TELEPORT, 5, 6, -1), new ArmyOrder(3, 40, 8, 12, Kind.STOP, 20, 30, -1),
                new ArmyOrder(4, 41, 7, 12, Kind.STOP, 91, 70, -1), new ArmyOrder(5, 41, 7, 12, Kind.CREATE, 91, 70, -1)),
                out);
    }

    /** SOS-249 review: a resync drops the orders past its step, which the world gives again under new numbers. */
    @Test
    void aResyncDropsTheLaterStepsAndNumbersGoOn() {
        ArmyOrders o = new ArmyOrders();
        o.record(10, 1, 3, Kind.MOVE, 1, 1, -1);
        o.record(11, 1, 3, Kind.BESIEGE, 2, 2, 40);
        o.record(12, 2, 3, Kind.STOP, 3, 3, -1);
        o.record(13, 2, 3, Kind.RAID, 4, 4, 41);
        List<ArmyOrder> read = new ArrayList<>();
        o.read(0, read); // a reader that read past the resync step
        o.cut(11);
        assertEquals(4, o.last(), "numbers never go back");
        o.record(12, 2, 3, Kind.MOVE, 5, 5, -1); // the stop at 12 was dropped: nothing to merge with
        List<ArmyOrder> out = new ArrayList<>();
        assertEquals(0, o.read(0, out));
        assertEquals(List.of(1L, 2L, 5L), out.stream().map(ArmyOrder::seq).toList());
        assertEquals(Kind.MOVE, out.get(2).kind());
        out.clear();
        o.read(read.get(read.size() - 1).seq(), out);
        assertEquals(List.of(new ArmyOrder(5, 12, 2, 3, Kind.MOVE, 5, 5, -1)), out, "the reader gets only the new one");
        o.cut(11);
        o.cut(11); // again: nothing past 11 but the new one, already dropped
        out.clear();
        o.read(0, out);
        assertEquals(2, out.size());
    }

    @Test
    void aReaderThatFellBehindLearnsHowManyItMissed() {
        ArmyOrders o = new ArmyOrders();
        int n = ArmyOrders.CAPACITY + 25;
        for (int i = 1; i <= n; i++) {
            o.record(i, i % 100, 1, Kind.MOVE, i, i, -1);
        }
        List<ArmyOrder> out = new ArrayList<>();
        assertEquals(25, o.read(0, out), "the first 25 were overwritten");
        assertEquals(ArmyOrders.CAPACITY, out.size());
        assertEquals(26, out.get(0).seq());
        assertEquals(n, out.get(out.size() - 1).seq());
        assertEquals(n, out.get(out.size() - 1).x());
        out.clear();
        assertEquals(0, o.read(n - 1, out));
        assertEquals(1, out.size());
    }
}

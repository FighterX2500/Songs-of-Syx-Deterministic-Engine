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
        o.record(40, 7, 12, Kind.MOVE, 100, 80, -1);
        o.record(40, 7, 12, Kind.STOP, 90, 70, -1); // setDestination stops the army first: part of the move
        o.record(40, 8, 12, Kind.STOP, 20, 30, -1); // another army
        o.record(41, 7, 12, Kind.STOP, 91, 70, -1); // the next step: an order of its own
        o.record(41, 7, 12, Kind.STOP, 91, 70, -1); // a stop after a stop: recorded (the hook skips an army that stands)
        List<ArmyOrder> out = new ArrayList<>();
        o.read(0, out);
        assertEquals(4, out.size());
        assertEquals(Kind.MOVE, out.get(0).kind());
        assertEquals(8, out.get(1).army());
        assertEquals(41, out.get(2).step());
        assertEquals(Kind.STOP, out.get(3).kind());
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

package sosdet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sosdet.ai.AiEntry;

class LocalTapeTest {

    private static final int PC = 2;

    @Test
    void aWatcherGetsEachStepOnceInOrderWithItsRoundsAndOrders() {
        LocalTape fighter = new LocalTape(4);
        LocalTape watcher = new LocalTape(4);
        fighter.watch(1);
        fighter.add(AiEntry.PATH, 7, PC, new byte[] {1, 2, 3});
        fighter.record(100, 5);
        fighter.clearQueued();
        fighter.record(101, 0); // paused
        watcher.in(fighter.out(1), PC);
        assertEquals(0, fighter.out(1).length, "the tape is taken once");
        fighter.record(102, 25);
        watcher.in(fighter.out(1), PC);

        assertTrue(watcher.has(102));
        assertFalse(watcher.has(103));
        LocalTape.Step s = watcher.take(100);
        assertEquals(5, s.rounds);
        assertEquals(1, s.orders.length);
        assertEquals(AiEntry.PATH, s.orders[0].kind);
        assertEquals(7, s.orders[0].div);
        assertEquals(PC, s.orders[0].pc);
        assertArrayEquals(new byte[] {1, 2, 3}, s.orders[0].payload());
        assertEquals(0, watcher.take(101).rounds);
        assertEquals(25, watcher.take(102).rounds);
    }

    @Test
    void aStepMissingFromTheTapeIsAnError() {
        LocalTape fighter = new LocalTape(4);
        LocalTape watcher = new LocalTape(4);
        fighter.watch(1);
        fighter.record(100, 1);
        fighter.out(1); // lost
        fighter.record(101, 1);
        watcher.in(fighter.out(1), PC);
        fighter.record(103, 1);
        assertThrows(IllegalStateException.class, () -> watcher.in(fighter.out(1), PC));
        assertThrows(IllegalStateException.class, () -> watcher.take(100));
    }

    @Test
    void aRepeatedCommandBetweenStepsCountsOnce() {
        LocalTape fighter = new LocalTape(4);
        fighter.add(AiEntry.COMMAND, -1, PC, new byte[] {9});
        fighter.add(AiEntry.COMMAND, -1, PC, new byte[] {9});
        fighter.add(AiEntry.COMMAND, -1, PC, new byte[] {8});
        fighter.add(AiEntry.GUARD, 1, PC, new byte[] {0, 0, 0, 1});
        fighter.add(AiEntry.GUARD, 1, PC, new byte[] {0, 0, 0, 1});
        assertEquals(4, fighter.queued().size());
    }

    @Test
    void onlyTheWatchingSlotGetsTheTape() {
        LocalTape fighter = new LocalTape(4);
        fighter.record(99, 1); // nobody watches
        fighter.watch(3);
        fighter.record(100, 1);
        assertEquals(0, fighter.out(1).length);
        assertTrue(fighter.out(3).length > 0);
        fighter.unwatch(3);
        fighter.record(101, 1);
        assertEquals(0, fighter.out(3).length);
    }

    @Test
    void anotherVersionIsRefused() {
        LocalTape fighter = new LocalTape(4);
        fighter.watch(1);
        fighter.record(100, 1);
        byte[] bytes = fighter.out(1);
        bytes[7] = 9;
        assertThrows(IllegalStateException.class, () -> new LocalTape(4).in(bytes, PC));
    }
}

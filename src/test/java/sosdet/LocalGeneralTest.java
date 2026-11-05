package sosdet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sosdet.ai.AiEntry;

/**
 * A local battle's general on its own thread (SOS-150): its changes wait for the next step, then go on the battle's
 * tape as the AI's, so the fighter and its watchers apply the same bytes at the same step.
 */
class LocalGeneralTest {

    @Test
    void changesGoOnTheTapeAsTheAisOldestFirstAndOnce() {
        LocalGeneral g = new LocalGeneral();
        LocalTape tape = new LocalTape(4);
        byte[] payload = {1, 2};
        g.capture(AiEntry.PATH, 5, payload);
        payload[0] = 9; // the general's writer reuses its buffer
        g.capture(AiEntry.TASK, 6, new byte[] {3});
        g.drainInto(tape);
        assertEquals(2, tape.queued().size());
        AiEntry first = tape.queued().get(0);
        assertEquals(AiEntry.PATH, first.kind);
        assertEquals(5, first.div);
        assertEquals(AiEntry.AI, first.pc);
        assertArrayEquals(new byte[] {1, 2}, first.payload());
        assertEquals(0, g.pending());
        tape.clearQueued();
        g.drainInto(tape);
        assertTrue(tape.queued().isEmpty(), "each change goes once");
    }

    @Test
    void aLaterChangeToTheSameSlotReplacesTheEarlierInItsPlaceButArtilleryIsAllKept() {
        LocalGeneral g = new LocalGeneral();
        LocalTape tape = new LocalTape(4);
        g.capture(AiEntry.PATH, 5, new byte[] {1});
        g.capture(AiEntry.TASK, 5, new byte[] {2});
        g.capture(AiEntry.PATH, 5, new byte[] {3});
        g.capture(AiEntry.ARTILLERY, -1, new byte[] {4});
        g.capture(AiEntry.ARTILLERY, -1, new byte[] {5});
        g.drainInto(tape);
        assertEquals(4, tape.queued().size());
        assertArrayEquals(new byte[] {3}, tape.queued().get(0).payload(), "in the first one's place");
        assertEquals(AiEntry.TASK, tape.queued().get(1).kind);
        assertArrayEquals(new byte[] {4}, tape.queued().get(2).payload(), "two pieces of artillery, both kept");
        assertArrayEquals(new byte[] {5}, tape.queued().get(3).payload());
        assertEquals(1, g.coalesced());
    }

    @Test
    void theThreadIsKnownUntilTheBattleEnds() {
        LocalGeneral g = new LocalGeneral();
        assertFalse(g.is(Thread.currentThread()));
        g.threadIs(Thread.currentThread());
        assertTrue(g.is(Thread.currentThread()));
        g.capture(AiEntry.PATH, 1, new byte[0]);
        g.end();
        assertFalse(g.is(Thread.currentThread()));
        assertEquals(0, g.pending(), "what it left is dropped");
    }
}

package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class StreamsTest {

    private final Random simulation = new Random(1);
    private final Streams streams = new Streams();

    @Test
    void outsideTheStepEveryDrawIsCosmetic() {
        assertNotSame(simulation, streams.pick(simulation));
        assertEquals(0, streams.simulationDraws());
    }

    @Test
    void insideTheStepTheSimulationThreadDrawsFromTheSimulation() {
        streams.enter();
        assertSame(simulation, streams.pick(simulation));
        assertEquals(1, streams.simulationDraws());
        streams.leave();
        assertNotSame(simulation, streams.pick(simulation));
    }

    @Test
    void anotherThreadDuringTheStepDrawsCosmeticAndIsCounted() throws InterruptedException {
        streams.enter();
        AtomicReference<Random> got = new AtomicReference<>();
        Thread render = new Thread(() -> got.set(streams.pick(simulation)));
        render.start();
        render.join();
        assertNotSame(simulation, got.get());
        assertEquals(1, streams.foreignDraws());
        assertEquals(0, streams.simulationDraws());
    }

    @Test
    void visualCodeInsideTheStepDrawsCosmetic() {
        streams.enter();
        streams.cosmeticBegin();
        assertNotSame(simulation, streams.pick(simulation));
        streams.cosmeticEnd();
        assertSame(simulation, streams.pick(simulation));
        assertThrows(IllegalStateException.class, streams::cosmeticEnd);
    }

    /**
     * SOS-223: a message's window is built only on the PC whose player the message is for (SOS-89). A random text
     * picked while it was built was a world draw there alone, so the clock split by one draw (248018 against 248017
     * in the 2026-11-17 playtest, after an NPC's help against raiders). Built inside cosmetic, every PC counts alike
     * and the step's next draw matches.
     */
    @Test
    void aMessageWindowBuiltOnOnePcOnlyDrawsNothingFromTheWorld() {
        Random simA = new Random(7);
        Random simB = new Random(7);
        Streams a = new Streams();
        Streams b = new Streams();
        a.enter();
        a.worldPart(true);
        b.enter();
        b.worldPart(true);
        a.pick(simA).nextFloat(); // the fight's own draw, on both
        b.pick(simB).nextFloat();
        a.pick(simA).nextInt(3); // the window's text, on A only, as before the fix
        assertEquals(b.worldDraws() + 1, a.worldDraws());

        Random simC = new Random(7);
        Random simD = new Random(7);
        Streams c = new Streams();
        Streams d = new Streams();
        c.enter();
        c.worldPart(true);
        d.enter();
        d.worldPart(true);
        c.pick(simC).nextFloat();
        d.pick(simD).nextFloat();
        c.cosmeticBegin(); // Message.send around Messages.add (patch 16)
        c.pick(simC).nextInt(3);
        c.cosmeticEnd();
        assertEquals(d.worldDraws(), c.worldDraws());
        assertEquals(d.pick(simD).nextInt(), c.pick(simC).nextInt());
    }

    @Test
    void aJobHandedToAnotherThreadDrawsFromTheSimulationUntilGivenBack() throws InterruptedException {
        streams.enter();
        assertTrue(streams.isSimulationThread());
        AtomicReference<Random> during = new AtomicReference<>();
        Thread gl = new Thread(() -> {
            Thread previous = streams.lend();
            during.set(streams.pick(simulation));
            streams.giveBack(previous);
        });
        gl.start();
        gl.join();
        assertSame(simulation, during.get());
        assertTrue(streams.isSimulationThread());
        streams.leave();
        assertFalse(streams.isSimulationThread());
    }
}

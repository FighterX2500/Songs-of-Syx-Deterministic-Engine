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

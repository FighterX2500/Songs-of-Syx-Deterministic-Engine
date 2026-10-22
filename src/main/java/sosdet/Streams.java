package sosdet;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chooses the random stream for each draw the game makes through {@code RND} (hook B10).
 *
 * <p>The simulation stream is the game's own seeded generator, and only the simulation thread draws
 * from it, only inside the step. Everything else (rendering, UI, sound, saving, other threads, and
 * visual code inside the step) draws from a cosmetic stream that never touches the simulation's.
 */
final class Streams {

    // Volatile: other threads read them to decide that a draw is not theirs to take from the simulation.
    private volatile Thread simulationThread;
    private volatile boolean inStep;
    /** Visual-only code inside the step (tree sway, light flicker); simulation thread only. */
    private int cosmeticDepth;

    private long simulationDraws;
    /** Draws by other threads during a step. Diagnostic only; counted without synchronisation. */
    private long foreignDraws;

    Random pick(Random simulation) {
        if (!inStep || cosmeticDepth > 0) {
            return ThreadLocalRandom.current();
        }
        if (Thread.currentThread() != simulationThread) {
            foreignDraws++;
            return ThreadLocalRandom.current();
        }
        simulationDraws++;
        return simulation;
    }

    /** The calling thread runs simulation code until {@link #leave()}. */
    void enter() {
        simulationThread = Thread.currentThread();
        inStep = true;
    }

    void leave() {
        inStep = false;
    }

    boolean inStep() {
        return inStep;
    }

    boolean isSimulationThread() {
        return inStep && Thread.currentThread() == simulationThread;
    }

    /** Lends the simulation to the calling thread, for a job the waiting simulation thread handed over. */
    Thread lend() {
        Thread previous = simulationThread;
        simulationThread = Thread.currentThread();
        return previous;
    }

    void giveBack(Thread previous) {
        simulationThread = previous;
    }

    void cosmeticBegin() {
        cosmeticDepth++;
    }

    void cosmeticEnd() {
        if (cosmeticDepth == 0) {
            throw new IllegalStateException("SOSDET: cosmeticEnd without cosmeticBegin");
        }
        cosmeticDepth--;
    }

    long simulationDraws() {
        return simulationDraws;
    }

    long foreignDraws() {
        return foreignDraws;
    }

    void resetCounts() {
        simulationDraws = 0;
        foreignDraws = 0;
    }
}

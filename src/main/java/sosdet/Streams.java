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
    /** Simulation draws since the last load; part of the state hash, so never reset by {@link #resetCounts()}. */
    private long drawsSinceLoad;
    /** The world part's draws since the last load: the own-city mode's hash counts only these (ADR 0005, SOS-38). */
    private long worldDraws;
    private boolean worldPart;
    /** Draws by other threads during a step. Diagnostic only; counted without synchronisation. */
    private long foreignDraws;
    /** The seed the simulation's generator takes before its next draw (set at every step's start). */
    private long nextSeed;
    private boolean reseed;
    /** Debugging: where every simulation draw comes from; null when off. */
    DrawTrace trace;

    Random pick(Random simulation) {
        if (!inStep || cosmeticDepth > 0) {
            return ThreadLocalRandom.current();
        }
        if (Thread.currentThread() != simulationThread) {
            foreignDraws++;
            return ThreadLocalRandom.current();
        }
        if (reseed) {
            simulation.setSeed(nextSeed); // as RND.setSeed(int) does
            reseed = false;
        }
        simulationDraws++;
        drawsSinceLoad++;
        if (worldPart) {
            worldDraws++;
        }
        if (trace != null) {
            trace.draw();
        }
        return simulation;
    }

    /** The simulation's generator starts from this seed at its next draw, so a step's draws depend only on its seed. */
    void reseed(int seed) {
        nextSeed = seed;
        reseed = true;
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

    long drawsSinceLoad() {
        return drawsSinceLoad;
    }

    void loaded() {
        drawsSinceLoad = 0;
        worldDraws = 0;
    }

    /** The step's world part begins (true) or ends (false); the city tail's draws are the city's. */
    void worldPart(boolean in) {
        worldPart = in;
    }

    long worldDraws() {
        return worldDraws;
    }

    /** A world resync: the count the host had at the resync step. */
    void worldDraws(long draws) {
        worldDraws = draws;
    }

    /** A resync: the count the host had at the resync step. */
    void drawsSinceLoad(long draws) {
        drawsSinceLoad = draws;
    }

    long foreignDraws() {
        return foreignDraws;
    }

    void resetCounts() {
        simulationDraws = 0;
        foreignDraws = 0;
    }
}

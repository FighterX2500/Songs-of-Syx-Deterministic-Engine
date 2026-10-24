package sosdet;

/**
 * The open world-battle prompt's time limit (SOS-45, spec §4 and §16): when nobody answers, every PC gives the default
 * answer at the same step. The wait is counted inside the step, in the real seconds each step takes at the agreed
 * speed, so every PC counts alike; a paused game runs no steps and doesn't count.
 */
final class PromptTimer {

    /** How long a prompt waits for an answer, in real seconds at the agreed speed. */
    static final double LIMIT_SECONDS = 30;

    /** Seconds waited for the open prompt; negative while none is open. */
    private double waited = -1;

    /** A prompt opened: its wait starts. */
    void open() {
        waited = 0;
    }

    /** The prompt closed, answered or not. */
    void close() {
        waited = -1;
    }

    boolean running() {
        return waited >= 0;
    }

    /**
     * A step ran, {@code stepSeconds} of game time at the agreed {@code speed}: true once, at the step the open
     * prompt's time runs out (it then counts as closed).
     */
    boolean step(double stepSeconds, double speed) {
        if (waited < 0) {
            return false;
        }
        waited += speed > 0 ? stepSeconds / speed : stepSeconds;
        if (waited < LIMIT_SECONDS) {
            return false;
        }
        waited = -1;
        return true;
    }
}

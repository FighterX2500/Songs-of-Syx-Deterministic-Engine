package sosdet;

/**
 * Fixed simulation steps from game time (requirements §1, hook B7).
 *
 * <p>The step length follows the speed every PC agreed on: 1/60 s at 1x or slower, 1/16 s (vanilla's longest
 * sub-step) above (owner, 2026-10-22). A change takes effect at a step every PC applies it at, never from one PC's own
 * game speed. Frames only decide how many steps run. A session holds at step 0 until told how far it may run, and
 * never runs past that step (requirements §3).
 */
final class StepClock {

    /** Length of one step at an agreed speed of 1x or slower, in game seconds. */
    static final double STEP_SECONDS = 1.0 / 60.0;
    /** Length of one step above 1x: vanilla's longest sub-step. */
    static final double LONG_STEP_SECONDS = 1.0 / 16.0;

    /** No last step: run as long as game time advances. */
    static final long NO_LIMIT = -1;

    private double stepSeconds = STEP_SECONDS;
    private double agreedSpeed = 1;
    private double accumulator;
    private long step;
    private long lastStep;

    StepClock() {
        lastStep = 0;
    }

    /** Adds a frame's game time and returns how many whole steps it completes; the rest carries over. */
    int stepsFor(double gameSeconds) {
        if (gameSeconds > 0) {
            accumulator += gameSeconds;
        }
        int steps = (int) (accumulator / stepSeconds);
        accumulator -= steps * stepSeconds;
        return steps;
    }

    /** The speed every PC agreed on sets the step length from the next step on; 0 (paused) keeps it. */
    void agreedSpeed(double speed) {
        if (speed < 0) {
            throw new IllegalArgumentException("SOSDET: speed " + speed);
        }
        if (speed == 0) {
            return;
        }
        agreedSpeed = speed;
        stepSeconds = speed <= 1 ? STEP_SECONDS : LONG_STEP_SECONDS;
    }

    double agreedSpeed() {
        return agreedSpeed;
    }

    double stepSeconds() {
        return stepSeconds;
    }

    /** Starts the next step, or returns false at the last step allowed (and drops the game time owed). */
    boolean begin() {
        if (lastStep != NO_LIMIT && step >= lastStep) {
            accumulator = 0;
            return false;
        }
        step++;
        return true;
    }

    /** The next step may not run yet (a peer waiting for the host): drop the game time owed, as at the last step. */
    void hold() {
        accumulator = 0;
    }

    long step() {
        return step;
    }

    void runUntil(long last) {
        if (last != NO_LIMIT && last < step) {
            throw new IllegalArgumentException("SOSDET: last step " + last + " is before the current step " + step);
        }
        lastStep = last;
    }

    /** After a resync load: at that step, holding there. */
    void resetTo(long at) {
        accumulator = 0;
        step = at;
        lastStep = at;
    }

    /** After a load: back to step 0, holding there. */
    void reset() {
        accumulator = 0;
        step = 0;
        lastStep = 0;
    }
}

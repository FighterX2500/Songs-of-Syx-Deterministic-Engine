package sosdet;

/**
 * Fixed simulation steps of 1/60 s from game time (requirements §1, hook B7).
 *
 * <p>Frames only decide how many steps run; every step has the same length. A session holds at step 0
 * until told how far it may run, and never runs past that step (requirements §3).
 */
final class StepClock {

    /** Length of one step in game seconds. */
    static final double STEP_SECONDS = 1.0 / 60.0;

    /** No last step: run as long as game time advances. */
    static final long NO_LIMIT = -1;

    /** This session's step length (experiment: 1/16 s, vanilla's longest sub-step). */
    private double stepSeconds = STEP_SECONDS;
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

    double stepSeconds() {
        return stepSeconds;
    }

    void stepSeconds(double seconds) {
        stepSeconds = seconds;
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

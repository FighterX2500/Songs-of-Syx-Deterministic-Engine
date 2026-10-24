package sosdet;

/**
 * Fixed simulation steps from game time (requirements §1, hook B7).
 *
 * <p>The step length follows the speed every PC agreed on: 1/60 s at 1x or slower, 1/30 s below 2x, 1/16 s (vanilla's
 * longest sub-step) from 2x (owner, 2026-10-22; the middle band SOS-42). A change takes effect at a step every PC applies it at, never from one PC's own
 * game speed. Frames only decide how many steps run: a frame's game time is the real time since the last frame times
 * the speed, not vanilla's, which gives a frame at most 1/32 s and slows a PC under 40 frames a second (SOS-42). A
 * session holds at step 0 until told how far it may run, and
 * never runs past that step (requirements §3).
 */
final class StepClock {

    /** Length of one step at an agreed speed of 1x or slower, in game seconds. */
    static final double STEP_SECONDS = 1.0 / 60.0;
    /** Length of one step above 1x and below 2x. */
    static final double MID_STEP_SECONDS = 1.0 / 30.0;
    /** Length of one step from 2x: vanilla's longest sub-step. */
    static final double LONG_STEP_SECONDS = 1.0 / 16.0;

    /** A frame longer than this (a pause, a load, a hitch) takes vanilla's game time instead of the real time. */
    static final long MAX_FRAME_NANOS = 250_000_000L;

    /** No last step: run as long as game time advances. */
    static final long NO_LIMIT = -1;

    private double stepSeconds = STEP_SECONDS;
    private double agreedSpeed = 1;
    private double accumulator;
    /** The last frame's time (System.nanoTime), or 0 before the first. */
    private long lastFrame;
    private long step;
    private long lastStep;
    /** The host's pacing: the last step the slowest PC lets it run, on top of the last step (SOS-42). */
    private long paceCap = NO_LIMIT;

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

    /**
     * A frame at {@code now}: its game time is the real time since the last frame times {@code speed}, or vanilla's
     * {@code gameSeconds} on the first frame, after a long one or at speed 0. A frame vanilla gives no time (one a
     * notice or message holds) stays held. Returns the whole steps it completes.
     */
    int stepsForFrame(long now, double gameSeconds, double speed) {
        long wall = lastFrame == 0 ? MAX_FRAME_NANOS + 1 : now - lastFrame;
        lastFrame = now;
        boolean real = wall <= MAX_FRAME_NANOS && speed > 0 && gameSeconds > 0;
        return stepsFor(real ? wall / 1e9 * speed : gameSeconds);
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
        stepSeconds = stepSecondsAt(speed);
    }

    /** The step length at an agreed speed above 0. */
    static double stepSecondsAt(double speed) {
        return speed <= 1 ? STEP_SECONDS : speed < 2 ? MID_STEP_SECONDS : LONG_STEP_SECONDS;
    }

    double agreedSpeed() {
        return agreedSpeed;
    }

    double stepSeconds() {
        return stepSeconds;
    }

    /** Starts the next step, or returns false at the last step allowed (and drops the game time owed). */
    boolean begin() {
        if (lastStep != NO_LIMIT && step >= lastStep || paceCap != NO_LIMIT && step >= paceCap) {
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

    long lastStep() {
        return lastStep;
    }

    void paceCap(long cap) {
        paceCap = cap;
    }

    /** After a resync load: at that step, holding there. */
    void resetTo(long at) {
        accumulator = 0;
        lastFrame = 0;
        step = at;
        lastStep = at;
    }

    /** After a load: back to step 0, holding there. */
    void reset() {
        accumulator = 0;
        lastFrame = 0;
        step = 0;
        lastStep = 0;
    }
}

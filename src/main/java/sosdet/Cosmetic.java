package sosdet;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Random draws for code that is only seen or heard: sound picks and pitch, music, ambience (hook B10).
 * Never the simulation stream, whether or not a session runs and whatever thread calls.
 */
public final class Cosmetic {

    private Cosmetic() {
    }

    /** 0 (inclusive) to max (exclusive), like {@code RND.rInt(max)}. */
    public static int rInt(int max) {
        return ThreadLocalRandom.current().nextInt(max);
    }

    /** 0 to d, like {@code RND.rFloat(d)}. */
    public static float rFloat(double d) {
        return (float) (ThreadLocalRandom.current().nextFloat() * d);
    }

    /** 1 - d to 1 + d, like {@code RND.rFloat1(d)}. */
    public static float rFloat1(double d) {
        return (float) ((1.0 - d) + ThreadLocalRandom.current().nextFloat() * d * 2.0);
    }
}

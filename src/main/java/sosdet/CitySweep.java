package sosdet;

/**
 * The city part's tile sweep (SOS-154). Vanilla changes some city state while drawing it: a drawn house counts its
 * furniture and lays its floor, a fence or wall gets the floor that matches its neighbours, a severed head bleeds. In a
 * session drawing never writes ({@code 67-read-only-ui}), so the step does that work instead, on a slice of the map
 * each step: the whole map every {@link #STEPS} steps, about 4 game seconds at 1x, vanilla's rate for a drawn tile.
 * The slice follows from the step number alone, so every PC sweeps the same tiles at the same step, after a load or a
 * resync too.
 */
final class CitySweep {

    /** Steps for one sweep of the whole map. */
    static final int STEPS = 240;

    private CitySweep() {
    }

    /** The first tile of the slice swept at this step. */
    static int from(long step, int area) {
        return (int) Math.min(area, Math.floorMod(step, STEPS) * (long) slice(area));
    }

    /** One past the last tile of the slice swept at this step. */
    static int to(long step, int area) {
        return (int) Math.min(area, from(step, area) + (long) slice(area));
    }

    private static int slice(int area) {
        if (area < 0) {
            throw new IllegalArgumentException("map area " + area);
        }
        return (area + STEPS - 1) / STEPS;
    }
}

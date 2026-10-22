package sosdet.hash;

/** The game state the hash log reads (the game adapter, or a fake in tests). */
public interface StateSource {

    /** How many divisions the battle has (fixed for the session). */
    int divisions();

    /**
     * Fills {@code sections} at {@link HashLog#DIVISIONS}, {@link HashLog#PROJECTILES} and {@link HashLog#ENTITIES},
     * and one hash of the soldiers per division in {@code divisions}. Called on the simulation thread at the end of a
     * step; it must not allocate, draw random numbers or change anything.
     */
    void hash(long[] sections, long[] divisions);

    /** A division's name for a desync report (not called in the step). */
    String divisionName(int index);

    /**
     * Every division's state as the divisions section hashed it at {@code step}, one line each, if that hash is
     * still kept. Two PCs' lines for the same step differ at the division and field that went differently.
     */
    String describeDivisions(long step);
}

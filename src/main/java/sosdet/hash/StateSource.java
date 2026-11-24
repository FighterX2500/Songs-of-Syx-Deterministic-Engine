package sosdet.hash;

/** The game state the hash log reads (the game adapter, or a fake in tests). */
public interface StateSource {

    /** How many divisions the battle has (fixed for the session). */
    int divisions();

    /**
     * Fills {@code sections} at {@link HashLog#DIVISIONS}, {@link HashLog#PROJECTILES}, {@link HashLog#ENTITIES} and
     * {@link HashLog#WORLD}, one hash of the soldiers per division in {@code divisions}, and the world section's running
     * hash after each of its parts in {@code worldParts} ({@link HashLog#WORLD_PART_NAMES}, SOS-250). Called on the
     * simulation thread at the end of a step; it must not allocate, draw random numbers or change anything.
     */
    void hash(long[] sections, long[] divisions, long[] worldParts);

    /** A division's name for a desync report (not called in the step). */
    String divisionName(int index);

    /**
     * Every division's state as the divisions section hashed it at {@code step}, one line each, if that hash is
     * still kept. Two PCs' lines for the same step differ at the division and field that went differently.
     */
    String describeDivisions(long step);

    /**
     * The entities outside divisions as the entities section hashed them at {@code step} (index, class, id, place and
     * the section's running hash after each), if kept. The first line two PCs disagree on names the entity.
     */
    String describeEntities(long step);

    /**
     * The world as the world section hashed it at {@code step}: the running hash after each part, and each army's
     * row, if kept. The first part and army two PCs disagree on name what went differently (SOS-46 playtests). When the
     * first part {@code differs} marks ({@link HashLog#WORLD_PART_NAMES} order) has items, a line per item:
     * {@code <part> <index> <hash>}, in a fixed order, so two PCs' reports compare line by line (SOS-250).
     */
    String describeWorld(long step, boolean[] differs);
}

package sosdet.ai;

/** Applies AI log entries to the game; called on the simulation thread, inside the step. */
public interface AiApplier {

    void apply(AiEntry entry);

    /** Players' commands skipped because their target was gone at their step (ADR 0002). */
    default long commandsSkipped() {
        return 0;
    }

    /** The step's world part begins: the world commands held back from its start apply now (ADR 0005). */
    default void worldPart() {
    }

    /**
     * Inside the step: whether an order from {@code pc} for division {@code div}, which the commanders table doesn't let
     * through, is a stationed player's for its own soldiers in the city this PC runs (SOS-234). Never changes the table.
     */
    default boolean stationedOrder(int pc, int div) {
        return false;
    }
}

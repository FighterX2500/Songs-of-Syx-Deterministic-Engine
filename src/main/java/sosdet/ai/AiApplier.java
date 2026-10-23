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
}

package sosdet.ai;

/** Applies AI log entries to the game; called on the simulation thread, inside the step. */
public interface AiApplier {

    void apply(AiEntry entry);
}

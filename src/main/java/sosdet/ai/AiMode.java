package sosdet.ai;

/** Where a session's AI general runs (ADR 0003, amended in SOS-23). */
public enum AiMode {
    /** Inside every step on the simulation thread: deterministic with no log, but its planning can freeze a step. */
    IN_STEP,
    /** On its own thread, as in vanilla; every change it makes is captured into the AI log, never applied directly. */
    HOST,
    /** Switched off; the host's AI log is applied instead. */
    PEER
}

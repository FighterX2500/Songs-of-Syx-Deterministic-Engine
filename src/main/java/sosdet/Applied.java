package sosdet;

/**
 * An AI log entry as this PC applied or skipped it at its step (SOS-249): every kind, the AI general's and the players'.
 * Every PC records the same entries; only {@code skipped} may differ between PCs, and {@code seq} after a resync. Plain
 * data for the logs: nothing reads it back into the step.
 *
 * @param seq     its number in this PC's applied ring, from 1 ({@link Engine#lastApplied}); never given twice, so a PC
 *                that had run past a resync's step has a gap where the resync dropped the entries it applies again
 * @param step    the step it applied at
 * @param logSeq  its sequence number in the host's log
 * @param kind    its kind, named by {@link Engine#aiKindName}
 * @param pc      who gave it: -1 the AI general, else the player's PC as the mod numbers it
 * @param div     the division it is for, -1 for none
 * @param command a command's id, named by {@link Engine#commandName}; -1 for every other kind
 * @param skipped true when this PC skipped it: a source that doesn't command the division, or a command whose target
 *                was gone or whose human was removed. Not the same on every PC: a stationed player's order for its own
 *                soldiers is skipped only on the PCs that don't run its city (SOS-234), and in own-city mode a city's
 *                command runs, and so can be skipped, only on its own PC
 */
public record Applied(long seq, long step, int logSeq, int kind, int pc, int div, int command, boolean skipped) {
}

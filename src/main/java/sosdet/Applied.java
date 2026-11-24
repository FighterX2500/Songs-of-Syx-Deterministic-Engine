package sosdet;

/**
 * An AI log entry as this PC applied or skipped it at its step (SOS-249): every kind, the AI general's and the players'.
 * Every PC records the same entries. Plain data for the logs: nothing reads it back into the step.
 *
 * @param seq     its number in the applied ring, from 1 ({@link Engine#lastApplied})
 * @param step    the step it applied at
 * @param logSeq  its sequence number in the host's log
 * @param kind    its kind, named by {@link Engine#aiKindName}
 * @param pc      who gave it: -1 the AI general, else the player's PC as the mod numbers it
 * @param div     the division it is for, -1 for none
 * @param command a command's id, named by {@link Engine#commandName}; -1 for every other kind
 * @param skipped true when it was skipped: a source that doesn't command the division (a stationed player's order for
 *                its own soldiers only on the PCs that don't run its city, SOS-234), or a world command whose target
 *                was gone or whose human was removed. A city's command skipped where the city runs is not marked: in
 *                own-city mode only its own PC runs it
 */
public record Applied(long seq, long step, int logSeq, int kind, int pc, int div, int command, boolean skipped) {
}

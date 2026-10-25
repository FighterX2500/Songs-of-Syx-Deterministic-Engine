package sosdet;

/**
 * A world desync the host found, and the step every PC stops at for it (SOS-53): what the mod's window shows while the
 * host chooses between a resync ({@link Engine#worldResyncSave}, then {@link Engine#worldResyncLoad} on every PC) and
 * saving and leaving. Immutable.
 */
public final class DesyncHold {

    /** The first step whose hashes differ. */
    public final long desyncStep;
    /** The PC whose hashes differ from the host's, as the mod numbers PCs (the host is 0). */
    public final int pc;
    /** The step every PC stops at, the host included: a resync for this desync loads at this step. */
    public final long holdStep;

    DesyncHold(long desyncStep, int pc, long holdStep) {
        this.desyncStep = desyncStep;
        this.pc = pc;
        this.holdStep = holdStep;
    }

    @Override
    public String toString() {
        return "desync at step " + desyncStep + " with PC " + pc + ", every PC holds at step " + holdStep;
    }
}

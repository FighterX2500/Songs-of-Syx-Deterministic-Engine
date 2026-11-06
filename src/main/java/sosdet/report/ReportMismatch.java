package sosdet.report;

/**
 * A city report built on other game data than this PC's (SOS-159): one of its sizes, or its number of fields, isn't
 * this game's. The two PCs run other game files or mods, so their cities can't be read across; the mod ends the session
 * with {@link #what} and both sizes. Immutable.
 */
public final class ReportMismatch extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The size that differs, a {@link Count} name, or "fields". */
    public final String what;
    /** Its size in the report, as the sending PC's game has it. */
    public final int theirs;
    /** Its size in this PC's game. */
    public final int ours;
    /** The PC that sent the report, as the mod numbers PCs; -1 while only the report's bytes are known. */
    public final int pc;

    ReportMismatch(String what, int theirs, int ours, int pc, String message) {
        super(message);
        this.what = what;
        this.theirs = theirs;
        this.ours = ours;
        this.pc = pc;
    }

    /** The same mismatch, sent by PC {@code pc}. */
    public ReportMismatch from(int pc) {
        return new ReportMismatch(what, theirs, ours, pc, "SOSDET: PC " + pc + ": " + getMessage());
    }
}

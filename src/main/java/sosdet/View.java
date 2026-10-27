package sosdet;

/**
 * A human viewing another human's city (mod spec §10, plan 0012, SOS-82): from the view entry's step until the viewer's
 * own city is back. Every PC keeps each view alike: from {@link #freeze} the viewer's human is frozen in every world,
 * and only the viewer's and the owner's PCs do more. What the mod reads; the engine's own state.
 */
public final class View {

    /** The view is in the log: every PC freezes the viewer at {@link #freeze}. */
    public static final int SCHEDULED = 0;
    /** From {@link #freeze}: the viewer's PC holds there until it has loaded the owner's city. */
    public static final int LOADING = 1;
    /** The viewer's PC runs the owner's city. */
    public static final int RUNNING = 2;
    /** Cancelled at {@link #freeze} on every PC alike; {@link #reason} says why. */
    public static final int CANCELLED = 3;
    /** Over: from {@link #thaw} every PC reads the viewer's reports again, and the viewer runs its own city. */
    public static final int ENDED = 4;

    /** The viewer's human and PC. */
    public final int viewer;
    public final int viewerPc;
    /** The owner's human and PC. */
    public final int owner;
    public final int ownerPc;
    /** The step every PC freezes the viewer at: the first multiple of the report interval after the entry's step. */
    public final long freeze;
    /** The owner's PC has handed out its save ({@link Engine#viewOut}). */
    boolean sent;
    /** The step every PC thaws the viewer at, the same on every PC; -1 until the view's end is in the log. */
    volatile long thaw = -1;
    /** The viewer's PC: its own city is loaded back, so it runs its own city until {@link #thaw}. */
    volatile boolean back;
    /** The last freeze or thaw step this PC, neither the owner nor the viewer, reloaded its own world at. */
    long reloaded = -1;
    volatile int state = SCHEDULED;
    volatile String reason;

    View(int viewer, int viewerPc, int owner, int ownerPc, long freeze) {
        this.viewer = viewer;
        this.viewerPc = viewerPc;
        this.owner = owner;
        this.ownerPc = ownerPc;
        this.freeze = freeze;
    }

    public int state() {
        return state;
    }

    /** Why the view was cancelled, as a sentence for the player; null otherwise. */
    public String reason() {
        return reason;
    }

    /** The step the viewer thaws at, the same on every PC; -1 while no end is in the log. */
    public long thaw() {
        return thaw;
    }

    /** True from the freeze step while the view runs or loads: the viewer's human is frozen. */
    public boolean frozen() {
        return state == LOADING || state == RUNNING;
    }

    @Override
    public String toString() {
        String[] names = {"scheduled", "loading", "running", "cancelled", "ended"};
        return "view of human " + owner + " (PC " + ownerPc + ") by human " + viewer + " (PC " + viewerPc + ") from step "
                + freeze + (thaw >= 0 ? " to " + thaw : "") + ", " + names[state] + (reason != null ? ": " + reason : "");
    }
}

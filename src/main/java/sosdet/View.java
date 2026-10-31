package sosdet;

/**
 * A human viewing another human's city (mod spec §10, plan 0013, SOS-82): from the view entry's step until the viewer's
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

    /** Why a view ends ({@link #why}): not ended yet. */
    public static final int OPEN = -1;
    /** The viewer closed it ({@link Engine#viewEnd(int)}). */
    public static final int CLOSED = 0;
    /** The viewer's PC hadn't caught up with the owner's city a minute after its load (mod spec §10). */
    public static final int NOT_CAUGHT_UP = 1;
    /** The viewer's PC fell behind the owner's city for over a minute after catching up (mod spec §10). */
    public static final int FELL_BEHIND = 2;
    /** The viewer's copy of the city lost step with the owner's ({@link Engine#viewDesync}). */
    public static final int DESYNC = 3;
    /** The game ended it on every PC alike: a battle, a prompt or question for the viewer, or it couldn't start. */
    public static final int GAME = 4;
    /** The reasons the viewer's PC asks the host to end a view with ({@link Engine#viewEnd(int, int)}). */
    static final int LAST_ASKED = DESYNC;
    /** Not an end: an attacker orders the retreat from an assault, as a view's end entry reaches every PC (mod spec §9). */
    public static final int RETREAT = 5;

    /** The viewer's human and PC. */
    public final int viewer;
    public final int viewerPc;
    /** The owner's human and PC. */
    public final int owner;
    public final int ownerPc;
    /** The step every PC freezes the viewer at: the first multiple of the report interval after the entry's step. */
    public final long freeze;
    /** The owner runs a local battle at the freeze step: the view is a watch of that battle (plan 0017, SOS-109). */
    boolean battle;
    /** The owner's PC has handed out its save ({@link Engine#viewOut}). */
    boolean sent;
    /** The step every PC thaws the viewer at, the same on every PC; -1 until the view's end is in the log. */
    volatile long thaw = -1;
    /** The viewer's PC: its own city is loaded back, so it runs its own city until {@link #thaw}. */
    volatile boolean back;
    /** The last freeze or thaw step this PC, neither the owner nor the viewer, reloaded its own world at. */
    long reloaded = -1;
    /**
     * A battle began at the freeze step, before any PC loaded for the view: nobody loads for it, the viewer's PC runs
     * its own city on, and the view thaws after the battle as any other (SOS-82).
     */
    boolean loadSkipped;
    volatile int state = SCHEDULED;
    volatile int why = OPEN;
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

    /**
     * Why the view was cancelled or ends, as a sentence for the player: the game's reason, or a limit's. Null while it
     * is open and when the viewer closed it.
     */
    public String reason() {
        return reason;
    }

    /** Why the view ends or was cancelled, the same on every PC: {@link #CLOSED} to {@link #GAME}, or {@link #OPEN}. */
    public int why() {
        return why;
    }

    /** False when a battle at the freeze step ended the view before any PC loaded for it: the viewer saw nothing. */
    public boolean loaded() {
        return !loadSkipped;
    }

    /** The sentence for a view the viewer's PC asked to end: null for {@link #CLOSED}. */
    static String sentence(int why) {
        switch (why) {
        case NOT_CAUGHT_UP:
            return "Your PC couldn't catch up with the city.";
        case FELL_BEHIND:
            return "Your PC fell behind the city for too long.";
        case DESYNC:
            return "The view lost step with the city.";
        default:
            return null;
        }
    }

    /** {@link #sentence} for a watch of a local battle. */
    static String battleSentence(int why) {
        switch (why) {
        case NOT_CAUGHT_UP:
        case FELL_BEHIND:
            return "Your PC fell too far behind the battle.";
        case DESYNC:
            return "The view lost step with the battle.";
        default:
            return null;
        }
    }

    /** True for a view of a running local battle (SOS-109). */
    public boolean battle() {
        return battle;
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

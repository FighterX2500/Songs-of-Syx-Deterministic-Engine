package sosdet;

/**
 * A human's battle against an NPC army on its own PC only (plan 0016, SOS-93): from the Engage step until the human
 * thaws. Every PC keeps the record alike: the battle's armies wait in place from {@link #engage}, and from
 * {@link #freeze} the human is frozen in every world, as a viewer is; only the battle PC generates and runs the battle.
 * What the mod reads; the engine's own state.
 */
public final class LocalBattle {

    /** Engaged: the armies wait; the human's city runs on until {@link #freeze}. */
    public static final int ENGAGED = 0;
    /** From {@link #freeze}: the battle PC runs the battle, every world reads the human frozen. */
    public static final int RUNNING = 1;

    /** The step the world-battle prompt's Engage applied at. */
    public final long engage;
    /** The human whose army fights, and that human's PC. */
    public final int human;
    public final int pc;
    /** The step every PC freezes the human at, and the battle PC generates the battle at: the next report step. */
    public final long freeze;
    /** Who fights it: the human, and allies who joined (SOS-119). */
    final BattleRoster roster;
    /** The speed the commanders' vote settled on, and that one of them paused it (SOS-119). */
    double votedSpeed = 1;
    boolean paused;
    /** This PC's game speed as last seen, as a press is a vote; -1 before the first frame. */
    double seenSpeed = -1;
    /** The battle's armies, which wait in place until the battle's result applies. */
    int[] armies;
    /** The battle PC: vanilla's Engage, run at {@link #freeze}; null once run. */
    Runnable generate;
    /** Every PC: vanilla's after-battle, kept at Engage with the two sides it copied; it takes the result's arrays. */
    java.util.function.Consumer<Object> after;
    /** The host: the pace waits for no battle PC from the freeze until it has caught up after generating. */
    boolean away;
    /** The battle PC: the battle's result went out as a command. */
    boolean resultSent;
    /** The step the result command applied at, the same on every PC; -1 until then. */
    long resultStep = -1;
    /** The result, decoded from the command: the arrays vanilla's after-battle takes. */
    Object result;
    /** Between {@link #resultStep} and the next step: every PC loaded its world (the battle PC its city too). */
    boolean back;
    /** The step the human thaws at: the first report step after the result applies; -1 until then. */
    long thaw = -1;
    volatile int state = ENGAGED;

    LocalBattle(long engage, int human, int pc, long freeze, int[] armies, Runnable generate) {
        this(engage, human, pc, freeze, armies, generate, new int[0]);
    }

    LocalBattle(long engage, int human, int pc, long freeze, int[] armies, Runnable generate, int[] allies) {
        this.engage = engage;
        this.human = human;
        this.pc = pc;
        this.freeze = freeze;
        this.armies = armies;
        this.generate = generate;
        this.roster = new BattleRoster(human, -1, allies);
    }

    public int state() {
        return state;
    }

    /** True from the freeze step: the human is frozen in every world. */
    public boolean frozen() {
        return state == RUNNING;
    }

    @Override
    public String toString() {
        return "local battle of human " + human + " (PC " + pc + ") engaged at step " + engage + ", from step " + freeze
                + (state == RUNNING ? ", running" : ", engaged");
    }
}

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
    /** The battle's armies, which wait in place until the battle's result applies. */
    final int[] armies;
    /** The battle PC: vanilla's Engage, run at {@link #freeze}; null once run. */
    Runnable generate;
    volatile int state = ENGAGED;

    LocalBattle(long engage, int human, int pc, long freeze, int[] armies, Runnable generate) {
        this.engage = engage;
        this.human = human;
        this.pc = pc;
        this.freeze = freeze;
        this.armies = armies;
        this.generate = generate;
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

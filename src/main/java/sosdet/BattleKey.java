package sosdet;

/**
 * One running battle, the same on every PC, for a restart by vote (mod spec §8, SOS-231): which kind, whose and from
 * which step. The mod carries it as a long ({@link #toLong}, {@link #of}). Plain data.
 */
public final class BattleKey {

    /** A battle on the battle map that every PC runs: between humans, or with own cities against NPCs ({@link OwnBattle}). */
    public static final int BATTLE = 0;
    /** A human's battle against NPCs on its own PC, its allies' and watchers' ({@link LocalBattle}). */
    public static final int LOCAL = 1;
    /** The assault on a human's capital ({@link Assault}). */
    public static final int ASSAULT = 2;

    /** No battle: what {@link #toLong} never returns. */
    public static final long NONE = -1;

    private static final int HUMAN_BITS = 8;
    private static final int KIND_BITS = 2;
    /** The largest step a key holds: far past any session's. */
    static final long MAX_STEP = (1L << (63 - HUMAN_BITS - KIND_BITS)) - 1;

    /** {@link #BATTLE}, {@link #LOCAL} or {@link #ASSAULT}. */
    public final int kind;
    /**
     * The battle's human: the one the world-battle prompt asked ({@link #BATTLE}), the fighter ({@link #LOCAL}), or the
     * defender ({@link #ASSAULT}).
     */
    public final int human;
    /** Its start: the Engage step ({@link #BATTLE}, {@link #LOCAL}) or Storm's ({@link #ASSAULT}). */
    public final long step;

    public BattleKey(int kind, int human, long step) {
        if (kind < BATTLE || kind > ASSAULT) {
            throw new IllegalArgumentException("SOSDET: a battle of kind " + kind);
        }
        if (human < 0 || human >= 1 << HUMAN_BITS) {
            throw new IllegalArgumentException("SOSDET: a battle of human " + human);
        }
        if (step < 0 || step > MAX_STEP) {
            throw new IllegalArgumentException("SOSDET: a battle from step " + step);
        }
        this.kind = kind;
        this.human = human;
        this.step = step;
    }

    /** The key as one non-negative long. */
    public long toLong() {
        return pack(kind, human, step);
    }

    /** {@link #toLong} without a key object, for a caller every frame. */
    static long pack(int kind, int human, long step) {
        return step << (HUMAN_BITS + KIND_BITS) | (long) kind << HUMAN_BITS | human;
    }

    /** The key {@link #toLong} gave; throws for a negative value, which is no battle. */
    public static BattleKey of(long key) {
        if (key < 0) {
            throw new IllegalArgumentException("SOSDET: no battle in key " + key);
        }
        int human = (int) (key & ((1 << HUMAN_BITS) - 1));
        int kind = (int) (key >>> HUMAN_BITS & ((1 << KIND_BITS) - 1));
        return new BattleKey(kind, human, key >>> (HUMAN_BITS + KIND_BITS));
    }

    /** The log entry's ints: kind, human, then the step as two ints (high, low). */
    int[] ints() {
        return new int[] {kind, human, (int) (step >>> 32), (int) step};
    }

    static BattleKey ofInts(int kind, int human, int high, int low) {
        return new BattleKey(kind, human, (long) high << 32 | (low & 0xFFFFFFFFL));
    }

    boolean same(BattleKey o) {
        return o != null && o.kind == kind && o.human == human && o.step == step;
    }

    @Override
    public String toString() {
        String[] kinds = {"battle", "local battle", "assault"};
        return kinds[kind] + " of human " + human + " from step " + step;
    }
}

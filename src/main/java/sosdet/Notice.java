package sosdet;

/**
 * An event in the step that the mod tells its players (SOS-110), the same on every PC in the same order: which
 * factions, never where their armies are or how many. Plain data: nothing reads it back into the step.
 *
 * @param step   the step it happened at
 * @param kind   {@link #WAR} and the others
 * @param first  a faction's index, see the kind
 * @param second the other faction's index, -1 when the kind has none
 * @param value  the tariff's percent, the speed voted for; else 0
 */
public record Notice(long step, int kind, int first, int second, int value) {

    /** {@code first} declared war on {@code second}. */
    public static final int WAR = 0;
    /** A deal ended the war between {@code first} and {@code second}. */
    public static final int PEACE = 1;
    /** {@code first} became {@code second}'s vassal. */
    public static final int VASSAL = 2;
    /** The siege of {@code first}'s capital began. */
    public static final int SIEGE = 3;
    /** {@code first} started an assault on {@code second}'s capital. */
    public static final int ASSAULT = 4;
    /** A battle between the players {@code first} and {@code second} began. */
    public static final int BATTLE = 5;
    /** {@code first} set a tariff of {@code value} percent on {@code second}. */
    public static final int TARIFF = 6;
    /** {@code first} voted for a battle speed of {@code value}. */
    public static final int SPEED_VOTE = 7;
    /** The ally {@code first} joined the battle of {@code second}. */
    public static final int ALLY_JOINED = 8;
    /** The ally {@code first} stayed out of the battle of {@code second}. */
    public static final int ALLY_OUT = 9;
    /** {@code first} retreated its army, its ally fighting on. */
    public static final int ARMY_LEFT = 10;
}

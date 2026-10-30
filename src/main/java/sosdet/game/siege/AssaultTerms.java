package sosdet.game.siege;

/**
 * What the attacker takes after reaching a human's throne (mod spec §9, SOS-92 step D): a share of the defender's money
 * and goods (Sack), or the defender as its vassal with a yearly tribute (Vassalage). Eliminate and the people's fates
 * are a later step. Plain Java, no game classes.
 */
public final class AssaultTerms {

    public static final int SACK = 0;
    public static final int VASSALAGE = 1;
    /** The most of the defender's money and of each good a Sack takes, in percent (mod spec §9). */
    public static final int MAX_SACK = 80;
    /** What applies when the attacker hasn't chosen in time: half the money and goods. */
    public static final int DEFAULT_KIND = SACK;
    public static final int DEFAULT_PERCENT = 50;

    private AssaultTerms() {
    }

    /** Why {@code kind} at {@code percent} can't be chosen, as a sentence; null when it can. */
    public static String refusal(int kind, int percent) {
        if (kind == SACK) {
            return percent < 0 || percent > MAX_SACK ? "A sack takes from 0% to " + MAX_SACK + "%." : null;
        }
        if (kind == VASSALAGE) {
            return percent < 0 || percent > 100 ? "A tribute is from 0% to 100%." : null;
        }
        return "No such outcome: " + kind + ".";
    }

    /** {@code percent} of {@code amount}, rounded down; nothing of a debt. */
    public static int share(int amount, int percent) {
        if (amount <= 0) {
            return 0;
        }
        return (int) ((long) amount * percent / 100);
    }

    /** {@code kind} as a word, for logs. */
    public static String name(int kind) {
        return kind == SACK ? "sack" : kind == VASSALAGE ? "vassalage" : "outcome " + kind;
    }
}

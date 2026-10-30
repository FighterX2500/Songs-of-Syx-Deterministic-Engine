package sosdet.game.siege;

/**
 * What the attacker takes after reaching a human's throne (mod spec §9, SOS-92 step D): a share of the defender's money
 * and goods and some of its people (Sack), or the defender as its vassal with a yearly tribute (Vassalage). Eliminate
 * is a later step. Plain Java, no game classes.
 */
public final class AssaultTerms {

    public static final int SACK = 0;
    public static final int VASSALAGE = 1;
    /** The most of the defender's money and of each good a Sack takes, in percent (mod spec §9). */
    public static final int MAX_SACK = 80;
    /** What applies when the attacker hasn't chosen in time: half the money and goods. */
    public static final int DEFAULT_KIND = SACK;
    public static final int DEFAULT_PERCENT = 50;
    /** The fates of removed people. A choice of people is flat: {@code people[race * FATES + fate]}, a count. */
    public static final int MOVED = 0;
    public static final int KILLED = 1;
    public static final int FREED = 2;
    public static final int FATES = 3;
    /** No one is removed: vassalage, and the default, which never decides a people's fate for the attacker. */
    public static final int[] NO_PEOPLE = new int[0];

    private AssaultTerms() {
    }

    /**
     * Why {@code kind} at {@code percent} with {@code people} can't be chosen, as a sentence; null when it can. The
     * population isn't checked here: it moves between the choice and its step, so {@link #limited} caps it there.
     */
    public static String refusal(int kind, int percent, int[] people) {
        if (kind == SACK) {
            if (percent < 0 || percent > MAX_SACK) {
                return "A sack takes from 0% to " + MAX_SACK + "%.";
            }
            for (int n : people) {
                if (n < 0) {
                    return "A count of people can't be negative: " + n + ".";
                }
            }
            return people.length % FATES != 0 ? "A choice of people has " + FATES + " fates per race." : null;
        }
        if (kind == VASSALAGE) {
            if (people.length != 0) {
                return "A vassal keeps its people.";
            }
            return percent < 0 || percent > 100 ? "A tribute is from 0% to 100%." : null;
        }
        return "No such outcome: " + kind + ".";
    }

    /** The most people a sack of a city with {@code inCity} people by race removes in all. */
    public static int peopleLimit(int[] inCity) {
        int all = 0;
        for (int n : inCity) {
            all += n;
        }
        return share(all, MAX_SACK);
    }

    /**
     * {@code people} as far as the city allows: no race beyond those of it in the city, and no more in all than
     * {@link #peopleLimit}. Moved go first, then killed, then freed. A new array, or {@link #NO_PEOPLE} for none.
     */
    public static int[] limited(int[] people, int[] inCity) {
        if (people.length == 0) {
            return NO_PEOPLE;
        }
        int[] out = new int[people.length];
        int room = peopleLimit(inCity);
        for (int r = 0; r < inCity.length; r++) {
            int left = Math.min(inCity[r], room);
            for (int f = 0; f < FATES; f++) {
                int take = Math.min(people[r * FATES + f], left);
                out[r * FATES + f] = take;
                left -= take;
                room -= take;
            }
        }
        return out;
    }

    /** How many of {@code people} have {@code fate}, of every race. */
    public static int removed(int[] people, int fate) {
        int n = 0;
        for (int i = fate; i < people.length; i += FATES) {
            n += people[i];
        }
        return n;
    }

    /**
     * What {@code people}'s fates add to the attacker's mercy, as vanilla's panel after a field battle: freed +n, moved
     * -n/2, killed -n, divided by one more than the attacker's population.
     */
    public static double mercy(int[] people, int attackerPopulation) {
        double m = removed(people, FREED) - removed(people, MOVED) / 2 - removed(people, KILLED);
        return m / (1 + attackerPopulation);
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

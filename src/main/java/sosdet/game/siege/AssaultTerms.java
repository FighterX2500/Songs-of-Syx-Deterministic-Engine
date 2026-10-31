package sosdet.game.siege;

/**
 * What the attacker takes after reaching a human's throne (mod spec §9, SOS-92 step D): a share of the defender's money
 * and goods and some of its people (Sack), the defender as its vassal with a yearly tribute (Vassalage), or nearly all
 * of it, and the defender's end as a player (Eliminate). Plain Java, no game classes.
 */
public final class AssaultTerms {

    public static final int SACK = 0;
    public static final int VASSALAGE = 1;
    public static final int ELIMINATE = 2;
    /** The share of the defender's money, goods and people that Eliminate takes, in percent (mod spec §9). */
    public static final int ELIMINATE_SHARE = 90;
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
        if (kind == ELIMINATE) {
            if (percent != ELIMINATE_SHARE) {
                return "Eliminate takes " + ELIMINATE_SHARE + "%.";
            }
            for (int n : people) {
                if (n < 0) {
                    return "A count of people can't be negative: " + n + ".";
                }
            }
            return people.length % FATES != 0 ? "A choice of people has " + FATES + " fates per race." : null;
        }
        return "No such outcome: " + kind + ".";
    }

    /** The index of the most men in {@code men}: the attacker who chooses; a tie goes to the earlier (mod spec §9). */
    public static int chooser(int[] men) {
        int best = 0;
        for (int i = 1; i < men.length; i++) {
            if (men[i] > men[best]) {
                best = i;
            }
        }
        return best;
    }

    /** {@code amount} by the men each attacker brought, rounded down, with what is left to the chooser (mod spec §9). */
    public static int[] split(int amount, int[] men) {
        int[] out = new int[men.length];
        long all = 0;
        for (int m : men) {
            all += m;
        }
        if (all == 0) {
            out[0] = amount;
            return out;
        }
        int left = amount;
        for (int i = 0; i < men.length; i++) {
            out[i] = (int) (amount * (long) men[i] / all);
            left -= out[i];
        }
        out[chooser(men)] += left;
        return out;
    }

    /** The most people a sack of a city with {@code inCity} people by race removes in all. */
    public static int peopleLimit(int[] inCity) {
        return share(total(inCity), MAX_SACK);
    }

    private static int total(int[] counts) {
        int all = 0;
        for (int n : counts) {
            all += n;
        }
        return all;
    }

    /**
     * The people Eliminate removes: {@code people} as far as {@link #ELIMINATE_SHARE} of the city allows, and everyone the
     * attacker gave no fate up to that share is freed, so an unchosen fate never hurts anyone. A new array.
     */
    public static int[] eliminated(int[] people, int[] inCity) {
        int[] out = new int[inCity.length * FATES];
        int room = share(total(inCity), ELIMINATE_SHARE);
        for (int r = 0; r < inCity.length; r++) {
            int left = inCity[r];
            for (int f = 0; f < FATES; f++) {
                int take = Math.min(r * FATES + f < people.length ? people[r * FATES + f] : 0, Math.min(left, room));
                out[r * FATES + f] = take;
                left -= take;
                room -= take;
            }
        }
        for (int r = 0; r < inCity.length && room > 0; r++) {
            int left = inCity[r] - out[r * FATES + MOVED] - out[r * FATES + KILLED] - out[r * FATES + FREED];
            int take = Math.min(left, room);
            out[r * FATES + FREED] += take;
            room -= take;
        }
        return out;
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
        return kind == SACK ? "sack" : kind == VASSALAGE ? "vassalage" : kind == ELIMINATE ? "elimination" : "outcome " + kind;
    }
}

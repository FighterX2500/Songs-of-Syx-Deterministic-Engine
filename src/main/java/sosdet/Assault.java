package sosdet;

import sosdet.game.siege.AssaultTerms;

/**
 * The assault on a human's capital (mod spec §9, SOS-92 step B): from the besieger's Storm until the world takes its
 * outcome. Every other human views the defender's city ({@link View}), so every PC runs that city, and the world is held
 * from the views' freeze to their thaw. The invasion starts in the defender's city at the step after the freeze, on
 * every PC alike, from a spec built in the world part at Storm's step. The same on every PC.
 */
public final class Assault {

    /** The defender held the city: the invaders were beaten. */
    public static final int HELD = 0;
    /** The invaders reached the throne. */
    public static final int TAKEN = 1;
    /** Five days of fighting without an end: the invaders withdrew. */
    public static final int RETREAT = 2;

    /** Storm's step. */
    public final long step;
    public final int attacker;
    public final int defender;
    /** Every human's view freezes here; the invasion starts at the next step. A resync of the assault moves it on. */
    public volatile long freeze;
    /** The invasion, built at Storm's step from the besieging armies ({@code InvasionSpec}). */
    final Object spec;
    /** The besieging armies' indices: the world's armies the invasion came from. */
    final int[] armies;
    /** The men in those armies at Storm's step. */
    public final int men;
    /** The human who brought each division of the invasion, in its order (allied attackers, SOS-92 E). */
    final int[] divOwners;
    /** Every attacking human, the one who stormed first, and the men each brought. */
    final int[] attackers;
    final int[] attackerMen;
    /** The attacker with the most men in the assault, who chooses the outcome; ties go to the one who stormed. */
    public final int chooser;
    /** The invasion is running in the defender's city. */
    volatile boolean started;
    /** {@link #HELD}, {@link #TAKEN} or {@link #RETREAT}, once the host's log ended it; -1 while it runs. */
    volatile int outcome = -1;
    /** The outcome this PC's city reached, on a PC that runs it; the host's goes in the log. -1 until then. */
    volatile int detected = -1;
    /** The host logged the end. */
    volatile boolean endLogged;
    /** A view lost step with the city: every view ends, and all open again from the city's new save (mod spec §9). */
    volatile boolean reopen;
    /** Those views opened: the players take their soldiers back as the new freeze passes. */
    volatile boolean recommand;
    /** The invaders alive as the invasion ended; -1 until then (SOS-92 C). */
    volatile int survivors = -1;
    /** The step the invasion ended at; -1 while it runs. */
    volatile long endStep = -1;
    /** Why it ended without an outcome (a view that couldn't start); null otherwise. */
    volatile String cancelled;
    /** The invasion's reference in the defender's city, once it runs there. */
    volatile int invasionRef = -1;
    /** The attackers' retreat, or in a rig test the outcome asked for: the host logs it next; -1 when none. */
    volatile int forced = -1;
    /** After {@link #TAKEN}: the step the attacker's choice is due by; then the default applies (SOS-92 D). */
    volatile long termsDue = -1;
    /** What the attacker took ({@code AssaultTerms} kind and percent, and the money); -1 until then. */
    volatile int termsKind = -1;
    volatile int termsPercent = -1;
    volatile int termsCredits;
    /** Whether the human in {@code slot} attacks here, alone or allied. */
    public boolean attacks(int slot) {
        for (int h : attackers) {
            if (h == slot) {
                return true;
            }
        }
        return false;
    }

    /** The people the attacker removed ({@code AssaultTerms}'s flat counts). */
    volatile int[] termsPeople = AssaultTerms.NO_PEOPLE;

    Assault(long step, int attacker, int defender, long freeze, Object spec, int[] armies, int men, int[] divOwners,
            int[] attackers, int[] attackerMen) {
        this.step = step;
        this.attacker = attacker;
        this.defender = defender;
        this.freeze = freeze;
        this.spec = spec;
        this.divOwners = divOwners;
        this.attackers = attackers;
        this.attackerMen = attackerMen;
        this.chooser = attackers[AssaultTerms.chooser(attackerMen)];
        this.armies = armies.clone();
        this.men = men;
    }

    public boolean started() {
        return started;
    }

    public int outcome() {
        return outcome;
    }

    /** The invaders alive as the invasion ended; -1 before that. */
    public int survivors() {
        return survivors;
    }

    public long endStep() {
        return endStep;
    }

    public String cancelled() {
        return cancelled;
    }

    public int termsKind() {
        return termsKind;
    }

    public int termsPercent() {
        return termsPercent;
    }

    public int termsCredits() {
        return termsCredits;
    }

    /** How many people the attacker removed with {@code fate} ({@code AssaultTerms}). */
    public int termsPeople(int fate) {
        return AssaultTerms.removed(termsPeople, fate);
    }

    /** {@link #HELD}, {@link #TAKEN} or {@link #RETREAT} as a word, for logs. */
    public static String outcomeName(int outcome) {
        switch (outcome) {
        case HELD:
            return "held";
        case TAKEN:
            return "taken";
        case RETREAT:
            return "retreat";
        default:
            return "running";
        }
    }

    @Override
    public String toString() {
        return "assault of human " + defender + "'s capital by human " + attacker + " (Storm at step " + step + ", freeze "
                + freeze + ", " + (cancelled != null ? "cancelled: " + cancelled : outcomeName(outcome))
                + (endStep >= 0 ? " at step " + endStep : "") + ")";
    }
}

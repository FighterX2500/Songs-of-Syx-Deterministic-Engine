package sosdet;

import settlement.stats.Induvidual;
import sosdet.report.CityReport;

/**
 * Who fights a battle with allies (mod spec §8, ADR 0009, SOS-118): the commanders, their armies and which divisions are
 * whose, the same on every PC. A battle on every PC and a local battle each hold one.
 */
final class BattleRoster {

    /** Every human who commands in the battle: the lead humans first (one, or one per army), then the allies who joined. */
    final int[] commanders;
    /** How many of {@link #commanders} lead an army and aren't allies. */
    final int leads;
    /** Each commander's army (0 or 1), set at Engage. */
    final int[] sides;
    /** Each commander's report at Engage, the same on every PC; an ally fights with its own. */
    final CityReport[] reports;
    /** The commander whose Retreat command leaves now, its ally fighting on; -1 for none. */
    int leaving = -1;
    /** Each commander whose army left the field, its ally fighting on. */
    final boolean[] gone;
    /** Each division's human by {@code Div.index()}, -1 for an NPC's; null until Engage. */
    int[] owners;
    /** The survivors of the divisions that left the field, by {@code Div.index()}. */
    Induvidual[][] left;
    /** The commanders' speed vote: commander numbers as in {@link #commanders}. */
    final BattleSpeedVote speedVote;

    BattleRoster(int human, int other, int[] allies) {
        leads = other >= 0 ? 2 : 1;
        commanders = new int[leads + allies.length];
        commanders[0] = human;
        sides = new int[commanders.length];
        if (other >= 0) {
            commanders[1] = other;
            sides[1] = 1;
        }
        System.arraycopy(allies, 0, commanders, leads, allies.length);
        reports = new CityReport[commanders.length];
        gone = new boolean[commanders.length];
        speedVote = new BattleSpeedVote(commanders.length);
    }

    /** Commander number of {@code h}, or -1. */
    int commander(int h) {
        for (int c = 0; c < commanders.length; c++) {
            if (commanders[c] == h) {
                return c;
            }
        }
        return -1;
    }

    /** True when {@code h} joined as an ally: the battle's leads have their own routes. */
    boolean ally(int h) {
        return commander(h) >= leads;
    }

    /** True when the battle has an ally in it. */
    boolean allies() {
        return commanders.length > leads;
    }

    /** The army {@code h}'s divisions stand in; a commander with none stays on the side the battle gave them. */
    int sideIn(int[] byDivision, int h, int perArmy) {
        for (int i = 0; i < byDivision.length; i++) {
            if (byDivision[i] == h) {
                return i < perArmy ? 0 : 1;
            }
        }
        return sides[commander(h)];
    }

    /** Every commander of army {@code side}. */
    int[] onSide(int side) {
        int[] r = new int[commanders.length];
        int n = 0;
        for (int c = 0; c < commanders.length; c++) {
            if (sides[c] == side) {
                r[n++] = commanders[c];
            }
        }
        return java.util.Arrays.copyOf(r, n);
    }

    /** The commanders whose armies left the field. */
    int[] goneHumans() {
        int[] r = new int[commanders.length];
        int n = 0;
        for (int c = 0; c < commanders.length; c++) {
            if (gone[c]) {
                r[n++] = commanders[c];
            }
        }
        return java.util.Arrays.copyOf(r, n);
    }

    /** The commanders of army {@code side} still on the field. */
    int[] stillIn(int side) {
        int[] r = new int[commanders.length];
        int n = 0;
        for (int c = 0; c < commanders.length; c++) {
            if (sides[c] == side && !gone[c]) {
                r[n++] = commanders[c];
            }
        }
        return java.util.Arrays.copyOf(r, n);
    }

    /** True while an ally of commander {@code c} is still on the field. */
    boolean allyStays(int c) {
        for (int o = 0; o < commanders.length; o++) {
            if (o != c && sides[o] == sides[c] && !gone[o]) {
                return true;
            }
        }
        return false;
    }
}

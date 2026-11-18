package sosdet;

import java.nio.ByteBuffer;
import java.util.function.IntPredicate;

/**
 * A sally against an invasion of a human's capital with allied humans (mod spec §9, SOS-232): who is asked to join,
 * how the side's division slots split, which division is whose, and how the spoils and captives split by power. Plain
 * arithmetic, worked out alike on every PC; the world reads that feed it are {@code game.GameSally}'s.
 */
final class SallyLayout {

    /** How far an ally's army may stand from the capital along the map: vanilla's reach for reinforcements (4 tiles). */
    static final double REACH_TILES = 4;

    /** The sally's question to the allies near the capital, held as the open world prompt while they answer. */
    static final class Ask {
        /** The defender, the invasion's ref in the defender's city, and the invader's faction (-1: raiders, rebels). */
        final int human;
        final int ref;
        final int invader;

        Ask(int human, int ref, int invader) {
            this.human = human;
            this.ref = ref;
            this.invader = invader;
        }
    }

    /** The defender first, then each ally who joined and still has an army near. */
    final int[] commanders;
    /** Each commander's world armies in the sally, by army index, in the order their divisions go in; and their iterations. */
    final int[][] armies;
    final int[][] iterations;
    /** How many divisions each commander puts into the battle; the defender's count includes the garrison. */
    final int[] divisions;
    /** Each commander's strength at the battle's start, as the game measures it: the split's weights. */
    final int[] power;

    SallyLayout(int[] commanders, int[][] armies, int[][] iterations, int[] divisions, int[] power) {
        if (armies.length != commanders.length || iterations.length != commanders.length
                || divisions.length != commanders.length || power.length != commanders.length) {
            throw new IllegalArgumentException("SOSDET: a sally layout of " + commanders.length + " commanders with "
                    + armies.length + " army lists, " + divisions.length + " counts, " + power.length + " powers");
        }
        this.commanders = commanders;
        this.armies = armies;
        this.iterations = iterations;
        this.divisions = divisions;
        this.power = power;
    }

    /** True when an ally fights beside the defender. */
    boolean allies() {
        return commanders.length > 1;
    }

    /** The allies, without the defender. */
    int[] allyHumans() {
        return java.util.Arrays.copyOfRange(commanders, 1, commanders.length);
    }

    /** Every army in the sally, the defender's first: they wait in place until its result applies. */
    int[] allArmies() {
        int n = 0;
        for (int[] a : armies) {
            n += a.length;
        }
        int[] all = new int[n];
        int k = 0;
        for (int[] a : armies) {
            System.arraycopy(a, 0, all, k, a.length);
            k += a.length;
        }
        return all;
    }

    /** The first battle position of commander {@code c}'s divisions. */
    int first(int c) {
        int p = 0;
        for (int i = 0; i < c; i++) {
            p += divisions[i];
        }
        return p;
    }

    /** Each position's human on the defender's side, for {@code BattleAllies.owners}; {@code slots} long, -1 past the last. */
    int[] owners(int slots) {
        int[] owners = new int[slots];
        java.util.Arrays.fill(owners, -1);
        int p = 0;
        for (int c = 0; c < commanders.length; c++) {
            for (int i = 0; i < divisions[c] && p < slots; i++) {
                owners[p++] = commanders[c];
            }
        }
        return owners;
    }

    /**
     * Each commander's part of every tradable in {@code spoils} (vanilla's array: goods and captives by tradable), by
     * power at the battle's start. A commander whose army left the field gets nothing. What doesn't divide evenly goes
     * to the one who brought the most power, the defender on a tie (mod spec §9).
     */
    int[][] shares(int[] spoils, boolean[] gone) {
        int[] weight = new int[commanders.length];
        for (int c = 0; c < weight.length; c++) {
            weight[c] = gone[c] ? 0 : Math.max(power[c], 0);
        }
        int[][] parts = new int[commanders.length][spoils.length];
        for (int t = 0; t < spoils.length; t++) {
            int[] part = BattleRules.share(spoils[t], weight);
            for (int c = 0; c < parts.length; c++) {
                parts[c][t] = part[c];
            }
        }
        return parts;
    }

    // ------------------------------------------------------------------ rules, as plain arithmetic

    /**
     * The humans asked to join: the owners of the armies found near the capital ({@code owners}, each army's flood
     * {@code distances} along the map), within {@link #REACH_TILES}, not the defender, and {@code eligible} (allied
     * with the defender, at war with the invader, free to fight). Each once, in the order their first army was found.
     */
    static int[] asked(int defender, int[] owners, double[] distances, IntPredicate eligible) {
        if (owners.length != distances.length) {
            throw new IllegalArgumentException("SOSDET: " + owners.length + " army owners and " + distances.length + " distances");
        }
        int[] asked = new int[owners.length];
        int n = 0;
        for (int i = 0; i < owners.length; i++) {
            int h = owners[i];
            if (h < 0 || h == defender || distances[i] > REACH_TILES || contains(asked, n, h) || !eligible.test(h)) {
                continue;
            }
            asked[n++] = h;
        }
        return java.util.Arrays.copyOf(asked, n);
    }

    /**
     * The side's slots: {@code wanted[0]} the defender's divisions (its garrison first, {@code garrison} of them), then
     * each ally's. Split as in a battle with allies ({@link BattleRules#slots}), but the garrison always fits: vanilla
     * puts the whole garrison in and its after-battle reads every garrison division's survivors. Slots for it come from
     * the ally with the most, the last one on a tie.
     */
    static int[] slots(int garrison, int[] wanted, int slots) {
        int[] withNpc = java.util.Arrays.copyOf(wanted, wanted.length + 1);
        int[] got = java.util.Arrays.copyOf(BattleRules.slots(withNpc, slots), wanted.length);
        int need = Math.min(Math.min(garrison, wanted[0]), slots) - got[0];
        while (need > 0) {
            int most = -1;
            for (int c = 1; c < got.length; c++) {
                if (got[c] > 0 && (most < 0 || got[c] >= got[most])) {
                    most = c;
                }
            }
            if (most < 0) {
                break;
            }
            got[most]--;
            got[0]++;
            need--;
        }
        return got;
    }

    /** The first {@code n} entries of {@code a} (all of it when shorter). */
    static int[] head(int[] a, int n) {
        return java.util.Arrays.copyOf(a, Math.min(n, a.length));
    }

    static double[] head(double[] a, int n) {
        return java.util.Arrays.copyOf(a, Math.min(n, a.length));
    }

    // ------------------------------------------------------------------ an ally's share in the defender's report

    /** An ally's share as a report input's bytes: each tradable with something in it, its index and amount. */
    static byte[] encode(int[] share) {
        int n = 0;
        for (int v : share) {
            if (v > 0) {
                n++;
            }
        }
        ByteBuffer b = ByteBuffer.allocate(4 + 8 * n).putInt(n);
        for (int t = 0; t < share.length; t++) {
            if (share[t] > 0) {
                b.putInt(t).putInt(share[t]);
            }
        }
        return b.array();
    }

    /** The share back, as an array of {@code tradables}; an index past it is refused. */
    static int[] decode(byte[] data, int tradables) {
        ByteBuffer b = ByteBuffer.wrap(data);
        int n = b.getInt();
        int[] share = new int[tradables];
        for (int k = 0; k < n; k++) {
            int t = b.getInt();
            int amount = b.getInt();
            if (t < 0 || t >= tradables || amount <= 0) {
                throw new IllegalArgumentException("SOSDET: a sally share of " + amount + " of tradable " + t + " of " + tradables);
            }
            share[t] = amount;
        }
        return share;
    }

    private static boolean contains(int[] a, int n, int v) {
        for (int i = 0; i < n; i++) {
            if (a[i] == v) {
                return true;
            }
        }
        return false;
    }
}

package sosdet.game.station;

import java.io.IOException;
import java.util.Arrays;

import sosdet.hash.HashLog;

/**
 * The armies stationed at another human's capital (mod spec §9, SOS-233, ADR 0011): for each, the army (its index and
 * iteration), its human, the host whose capital it stands at, the tile it was stationed on, the step, and whether its
 * human gave the host command of its soldiers in the city (owner, 2026-11-19), and its men as last sent to the host's
 * city, so a change, after a battle above all, reaches the city at once (owner, 2026-11-19). Also the world's refresh period, which
 * sends the stationed armies' soldiers to their host's city again. A stationed army stays a world army; this book only
 * says it is stationed. Saved with the world ({@code GameStations}) and in the world hash. Plain Java, no game classes: the end rules and the refusals read the game in {@code GameStations}.
 *
 * <p>Kept in the order the records were made, which is the same on every PC (world commands apply in log order), in
 * arrays, so the world part's end check allocates nothing.
 */
public final class StationBook {

    /** The farthest tile, along either axis, from the host's capital centre an army may be stationed at (mod spec §9). */
    public static final int REACH_TILES = 3;

    private static final int VERSION = 3;
    private static final int SLOTS = 1024;
    private static final int FIELDS = 9;
    /** Version 1 (batch 13's first builds): no grant, no period. */
    private static final int FIELDS_1 = 7;
    /** Version 2: no men sent. */
    private static final int FIELDS_2 = 8;

    private int[] v = new int[FIELDS * 8];
    private int size;
    /** The world's refresh period last seen ({@code GameStations.endDue}); -1 before the first. */
    private int period = -1;

    /** The number of stationed armies. */
    public int size() {
        return size;
    }

    public int army(int i) {
        return get(i, 0);
    }

    public int iteration(int i) {
        return get(i, 1);
    }

    /** The human whose army it is. */
    public int owner(int i) {
        return get(i, 2);
    }

    /** The human at whose capital it is stationed. */
    public int host(int i) {
        return get(i, 3);
    }

    /** The tile it was stationed on: it ends once the army stands anywhere else. */
    public int x(int i) {
        return get(i, 4);
    }

    public int y(int i) {
        return get(i, 5);
    }

    /** The step it was stationed at. */
    public int since(int i) {
        return get(i, 6);
    }

    /** Whether its human gave the host command of its soldiers in the host's city (owner, 2026-11-19). */
    public boolean granted(int i) {
        return get(i, 7) != 0;
    }

    /** Its human gives the host command of its soldiers in the city, or takes it back. */
    public void grantSet(int i, boolean granted) {
        get(i, 7);
        v[i * FIELDS + 7] = granted ? 1 : 0;
    }

    /** Its army's men as last sent to the host's city; -1 before the first send. */
    public int men(int i) {
        return get(i, 8);
    }

    /** Its army's soldiers went to the host's city with {@code men} men. */
    public void menSet(int i, int men) {
        get(i, 8);
        v[i * FIELDS + 8] = men;
    }

    /** The world's refresh period last seen; -1 before the first. */
    public int period() {
        return period;
    }

    public void periodSet(int period) {
        this.period = period;
    }

    private int get(int i, int field) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("SOSDET: stationed army " + i + " of " + size);
        }
        return v[i * FIELDS + field];
    }

    /** The record of the army at {@code army} with {@code iteration}, or -1. No allocation. */
    public int find(int army, int iteration) {
        for (int i = 0; i < size; i++) {
            if (v[i * FIELDS] == army && v[i * FIELDS + 1] == iteration) {
                return i;
            }
        }
        return -1;
    }

    /** The record of the army at {@code army}, whatever its iteration, or -1. No allocation. */
    public int find(int army) {
        for (int i = 0; i < size; i++) {
            if (v[i * FIELDS] == army) {
                return i;
            }
        }
        return -1;
    }

    /** True when the army at {@code army} with {@code iteration} is stationed at {@code host}'s capital. No allocation. */
    public boolean stationedAt(int army, int iteration, int host) {
        int i = find(army, iteration);
        return i >= 0 && host(i) == host;
    }

    /** Records an army stationed at {@code host}'s capital; fails for one already stationed or a slot out of range. */
    public void station(int army, int iteration, int owner, int host, int x, int y, int step) {
        check(owner);
        check(host);
        if (owner == host || army < 0) {
            throw new IllegalArgumentException("SOSDET: army " + army + " of slot " + owner + " stationed at slot " + host);
        }
        if (find(army) >= 0) {
            throw new IllegalStateException("SOSDET: army " + army + " is stationed already");
        }
        if ((size + 1) * FIELDS > v.length) {
            v = Arrays.copyOf(v, v.length * 2);
        }
        int at = size * FIELDS;
        v[at] = army;
        v[at + 1] = iteration;
        v[at + 2] = owner;
        v[at + 3] = host;
        v[at + 4] = x;
        v[at + 5] = y;
        v[at + 6] = step;
        v[at + 7] = 0;
        v[at + 8] = -1;
        size++;
    }

    /** Ends record {@code i}; the records after it move up one, in order. */
    public void end(int i) {
        get(i, 0);
        System.arraycopy(v, (i + 1) * FIELDS, v, i * FIELDS, (size - i - 1) * FIELDS);
        size--;
    }

    /** Every record whose army belongs to {@code slot} or stands at its capital ends: a removed or frozen human. */
    public void dropHuman(int slot) {
        for (int i = size - 1; i >= 0; i--) {
            if (owner(i) == slot || host(i) == slot) {
                end(i);
            }
        }
    }

    /** The records at {@code host}'s capital. */
    public int count(int host) {
        int n = 0;
        for (int i = 0; i < size; i++) {
            n += host(i) == host ? 1 : 0;
        }
        return n;
    }

    public boolean empty() {
        return size == 0;
    }

    private static void check(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            throw new IllegalArgumentException("SOSDET: a stationed army of slot " + slot);
        }
    }

    // ---------------------------------------------------------------- the rules (mod spec §9, owner 2026-11-18)

    /**
     * Why the army can't be stationed at the host's capital, as a sentence, or null when it can. The caller reads the
     * game; this holds the rules. {@code agreement}: the two humans are allies, colleagues (a pact), or one is the
     * other's overlord; {@code atWar}: they are at war. {@code dx}, {@code dy}: the army's tile from the capital's
     * centre. {@code divisions}: the army's; {@code hostDivisions}: the host's own, in its capital and its armies;
     * {@code stationedDivisions}: the armies stationed there already; {@code slots}: the city's army slots.
     */
    public static String refusal(boolean ownArmy, boolean stationed, boolean hostPlayer, boolean dormant,
            boolean agreement, boolean atWar, boolean fortified, int dx, int dy, int divisions, int hostDivisions,
            int stationedDivisions, int slots) {
        if (!ownArmy) {
            return "Only your own army can be stationed.";
        }
        if (stationed) {
            return "This army is stationed already.";
        }
        if (!hostPlayer) {
            return "Armies are stationed only at another player's capital.";
        }
        if (dormant) {
            return "That player is away or out of the game.";
        }
        if (atWar || !agreement) {
            return "Only an ally, a colleague, an overlord or a vassal may station an army there.";
        }
        if (Math.max(Math.abs(dx), Math.abs(dy)) > REACH_TILES) {
            return "The army must stand within " + REACH_TILES + " tiles of their capital.";
        }
        if (!fortified) {
            return "The army must stand still first.";
        }
        if (divisions <= 0) {
            return "The army has no soldiers.";
        }
        if (hostDivisions + stationedDivisions + divisions > slots) {
            return "Their city has no room: " + (hostDivisions + stationedDivisions) + " of its " + slots
                    + " army slots are taken, and this army needs " + divisions + ".";
        }
        return null;
    }

    /** Why a record ended, in {@code Notice.STATION_ENDED}'s value. */
    public static final int SENT_AWAY = 0;
    public static final int LEFT = 1;
    public static final int MOVED = 2;
    public static final int ARMY_GONE = 3;
    public static final int AGREEMENT_ENDED = 4;
    public static final int AWAY = 5;
    public static final int CAPITAL_LOST = 6;

    /**
     * The end rules (mod spec §9): why a record ends now, or -1 while it holds. It ends when the army is gone or no
     * longer its human's; either human is removed or frozen; the capital isn't the host's; the two humans lost their
     * agreement or are at war; the army stands anywhere else. No allocation.
     */
    public static int endReason(boolean armyThere, boolean dormant, boolean capitalHeld, boolean agreement,
            boolean atWar, boolean onTile) {
        if (!armyThere) {
            return ARMY_GONE;
        }
        if (dormant) {
            return AWAY;
        }
        if (!capitalHeld) {
            return CAPITAL_LOST;
        }
        if (!agreement || atWar) {
            return AGREEMENT_ENDED;
        }
        return onTile ? -1 : MOVED;
    }

    /**
     * Whether another human's army near a capital may join its Attack against an invasion (mod spec §9): an ally's, or
     * any army stationed at that capital, whatever the agreement (owner, 2026-11-18). The war with the invader is the
     * caller's check.
     */
    public static boolean joinsAttack(boolean allied, boolean stationedHere) {
        return allied || stationedHere;
    }

    // ---------------------------------------------------------------- save and hash

    /** The book as ints: its version, its size and the period first. */
    public int[] toInts() {
        int[] out = new int[3 + FIELDS * size];
        out[0] = VERSION;
        out[1] = size;
        out[2] = period;
        System.arraycopy(v, 0, out, 3, FIELDS * size);
        return out;
    }

    /** A book from {@link #toInts}, this version's, version 2's (no men sent) or version 1's (no grant, no period). */
    public static StationBook fromInts(int[] in) throws IOException {
        if (in.length < 2 || in[0] < 1 || in[0] > VERSION) {
            throw new IOException("SOSDET: a station book of version " + (in.length == 0 ? "none" : in[0])
                    + ", this engine reads 1 to " + VERSION);
        }
        boolean old = in[0] == 1;
        int fields = old ? FIELDS_1 : in[0] == 2 ? FIELDS_2 : FIELDS;
        int head = old ? 2 : 3;
        int n = in[1];
        if (n < 0 || in.length != head + fields * n) {
            throw new IOException("SOSDET: a station book of " + in.length + " ints for " + n + " armies");
        }
        StationBook b = new StationBook();
        b.period = old ? -1 : in[2];
        for (int i = 0; i < n; i++) {
            int at = head + fields * i;
            try {
                b.station(in[at], in[at + 1], in[at + 2], in[at + 3], in[at + 4], in[at + 5], in[at + 6]);
                if (!old && in[at + 7] != 0) {
                    b.grantSet(i, true);
                }
                if (fields == FIELDS) {
                    b.menSet(i, in[at + 8]); // an older book's -1 sends its soldiers again at the first world step
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IOException("SOSDET: a station book entry "
                        + Arrays.toString(Arrays.copyOfRange(in, at, at + fields)), e);
            }
        }
        return b;
    }

    /** Mixes the whole book into {@code h}. No allocation. */
    public long hash(long h) {
        h = HashLog.mix(h, VERSION);
        h = HashLog.mix(h, size);
        h = HashLog.mix(h, period);
        for (int i = 0; i < FIELDS * size; i++) {
            h = HashLog.mix(h, v[i]);
        }
        return h;
    }
}

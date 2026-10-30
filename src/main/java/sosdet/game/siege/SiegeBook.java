package sosdet.game.siege;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import sosdet.hash.HashLog;

/**
 * The sieges of the humans' capitals (mod spec §9, SOS-92): for each besieged human, the game second its siege began
 * and its city's population then, which sets the waiting period before an assault (owner, 2026-10-29: the population
 * at the siege's start). Saved with the world ({@code GameSieges}) and in the world hash. Plain Java, no game classes.
 */
public final class SiegeBook {

    /** People per season of waiting: one season for every started 1,000 (mod spec §9). */
    public static final int PEOPLE_PER_SEASON = 1000;
    public static final int MAX_SEASONS = 4;

    private static final int VERSION = 1;
    private static final int SLOTS = 1024;

    /** By the besieged human's slot, in slot order, so the save and the hash are the same on every PC. */
    private final TreeMap<Integer, Siege> sieges = new TreeMap<>();
    /** {@link #besieged} without boxing: the market asks it per good and partner. */
    private final boolean[] besieged = new boolean[SLOTS];

    private static final class Siege {
        final int startSecond;
        final int population;

        Siege(int startSecond, int population) {
            this.startSecond = startSecond;
            this.population = population;
        }
    }

    /** The seasons a siege of a city of {@code population} waits before an assault: 1 to {@link #MAX_SEASONS}. */
    public static int waitSeasons(int population) {
        if (population < 0) {
            throw new IllegalArgumentException("SOSDET: a population of " + population);
        }
        int started = (population + PEOPLE_PER_SEASON - 1) / PEOPLE_PER_SEASON;
        return Math.max(1, Math.min(MAX_SEASONS, started));
    }

    /** The siege of {@code slot}'s capital began at {@code second}, with {@code population} people; a running one stays. */
    public void begin(int slot, int second, int population) {
        check(slot);
        if (population < 0) {
            throw new IllegalArgumentException("SOSDET: a siege of a city of " + population + " people");
        }
        if (sieges.putIfAbsent(slot, new Siege(second, population)) == null) {
            besieged[slot] = true;
        }
    }

    /** Test tooling (the rig): the siege of {@code slot}'s capital began {@code seconds} earlier; nothing when none runs. */
    public void backdate(int slot, int seconds) {
        check(slot);
        Siege s = sieges.get(slot);
        if (s != null) {
            sieges.put(slot, new Siege(s.startSecond - seconds, s.population));
        }
    }

    /** The siege of {@code slot}'s capital ended: a later one waits from its own start. */
    public void end(int slot) {
        check(slot);
        sieges.remove(slot);
        besieged[slot] = false;
    }

    /** True while {@code slot}'s capital is besieged. No allocation. */
    public boolean besieged(int slot) {
        return slot >= 0 && slot < SLOTS && besieged[slot];
    }

    /** The game second the siege of {@code slot}'s capital began; -1 when it isn't besieged. */
    public int startSecond(int slot) {
        Siege s = sieges.get(slot);
        return s == null ? -1 : s.startSecond;
    }

    /** The population of {@code slot}'s city when its siege began; -1 when it isn't besieged. */
    public int population(int slot) {
        Siege s = sieges.get(slot);
        return s == null ? -1 : s.population;
    }

    /**
     * The game seconds left of the wait before {@code slot}'s capital may be assaulted, at {@code now}, with seasons of
     * {@code secondsPerSeason}: 0 once it has passed. Fails when the capital isn't besieged.
     */
    public double waitLeft(int slot, double now, double secondsPerSeason) {
        Siege s = sieges.get(slot);
        if (s == null) {
            throw new IllegalStateException("SOSDET: slot " + slot + "'s capital isn't besieged");
        }
        double end = s.startSecond + waitSeasons(s.population) * secondsPerSeason;
        return Math.max(0, end - now);
    }

    /** A removed human's siege is gone: its capital is no one's to assault. */
    public void dropHuman(int slot) {
        end(slot);
    }

    /** True when no capital is besieged: such a book saves and hashes as none. */
    public boolean empty() {
        return sieges.isEmpty();
    }

    private static void check(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            throw new IllegalArgumentException("SOSDET: a siege of slot " + slot);
        }
    }

    // ---------------------------------------------------------------- save and hash

    /** The book as ints, its version first. */
    public int[] toInts() {
        int[] v = new int[2 + 3 * sieges.size()];
        int n = 0;
        v[n++] = VERSION;
        v[n++] = sieges.size();
        for (Map.Entry<Integer, Siege> e : sieges.entrySet()) {
            v[n++] = e.getKey();
            v[n++] = e.getValue().startSecond;
            v[n++] = e.getValue().population;
        }
        return v;
    }

    /** A book from {@link #toInts}. */
    public static SiegeBook fromInts(int[] v) throws IOException {
        if (v.length < 2 || v[0] != VERSION) {
            throw new IOException("SOSDET: a siege book of version " + (v.length == 0 ? "none" : v[0]) + ", this engine reads "
                    + VERSION);
        }
        int n = v[1];
        if (n < 0 || v.length != 2 + 3 * n) {
            throw new IOException("SOSDET: a siege book of " + v.length + " ints for " + n + " sieges");
        }
        SiegeBook b = new SiegeBook();
        for (int i = 0; i < n; i++) {
            int at = 2 + 3 * i;
            if (v[at] < 0 || v[at] >= SLOTS || v[at + 2] < 0 || b.sieges.containsKey(v[at])) {
                throw new IOException("SOSDET: a siege book entry " + Arrays.toString(Arrays.copyOfRange(v, at, at + 3)));
            }
            b.sieges.put(v[at], new Siege(v[at + 1], v[at + 2]));
            b.besieged[v[at]] = true;
        }
        return b;
    }

    /** Mixes the whole book into {@code h}. */
    public long hash(long h) {
        for (int i : toInts()) {
            h = HashLog.mix(h, i);
        }
        return h;
    }
}

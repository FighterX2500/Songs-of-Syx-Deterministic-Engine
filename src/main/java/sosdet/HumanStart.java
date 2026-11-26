package sosdet;

import java.util.Locale;

/**
 * One human at world setup (SOS-55, mod spec §5): the race, faction name and banner picked in the lobby's setup, and the
 * world location, as vanilla's location stage stores the player's ({@code WorldGen.playerX/playerY}); the titles it holds,
 * each with the number of races its player's profile earned it with (mod spec §5 Titles). Immutable.
 */
public final class HumanStart {

    /** A world holds at most this many humans (the engine's spare Players plus slot 0). */
    public static final int MAX_HUMANS = 16;
    /** No human's location may be closer than this to another's, in tiles (mod spec §5). */
    public static final double MIN_DISTANCE = 6;
    /** Two humans at most this far apart, in tiles, are guaranteed a connection they can trade along (mod spec §5). */
    public static final double MAX_ROUTE_DISTANCE = 15;

    /** The race's key, as {@code Race.key}. */
    public final String race;
    public final String name;
    /** The ruler's name (SOS-98), or null to keep the game's. */
    public final String ruler;
    public final int tx;
    public final int ty;
    private final byte[] banner;
    private final int[] titles;
    private final int[] titleRaces;

    /** As {@link #HumanStart(String, String, String, byte[], int, int)}, keeping the game's ruler name. */
    public HumanStart(String race, String name, byte[] banner, int tx, int ty) {
        this(race, name, null, banner, tx, ty);
    }

    /**
     * {@code ruler}: the ruler's name the player typed in the setup (SOS-98), or null or empty to keep the game's.
     * {@code banner}: the banner in the game's own save layout ({@link Engine#bannerBytes}), or null to keep it.
     */
    public HumanStart(String race, String name, String ruler, byte[] banner, int tx, int ty) {
        this(race, name, ruler, banner, tx, ty, NONE, NONE);
    }

    /**
     * As {@link #HumanStart(String, String, String, byte[], int, int)}, holding {@code titles} (title indices, as
     * {@code PTitle.index()}), each earned with {@code titleRaces[i]} races ({@link Engine#titleRaces}); no titles: empty.
     */
    public HumanStart(String race, String name, String ruler, byte[] banner, int tx, int ty, int[] titles,
            int[] titleRaces) {
        if (titles.length != titleRaces.length) {
            throw new IllegalArgumentException("SOSDET: " + titles.length + " titles and " + titleRaces.length
                    + " race counts");
        }
        if (race == null || race.isEmpty() || name == null || name.isEmpty()) {
            throw new IllegalArgumentException("SOSDET: a human start needs a race and a name (race " + race + ", name "
                    + name + ")");
        }
        this.race = race;
        this.name = name;
        this.ruler = ruler == null || ruler.isEmpty() ? null : ruler;
        this.banner = banner == null ? null : banner.clone();
        this.tx = tx;
        this.ty = ty;
        this.titles = titles.clone();
        this.titleRaces = titleRaces.clone();
    }

    private static final int[] NONE = new int[0];

    /** The banner bytes (a copy), or null. */
    public byte[] banner() {
        return banner == null ? null : banner.clone();
    }

    /** The titles held, as title indices (a copy); empty for none. */
    public int[] titles() {
        return titles.clone();
    }

    /** For each of {@link #titles}, the races its player's profile earned it with (a copy). */
    public int[] titleRaces() {
        return titleRaces.clone();
    }

    /** Why a location at {@code tx, ty} is too close to one of the others' locations, or null. */
    static String tooClose(int tx, int ty, int[] otherTx, int[] otherTy) {
        if (otherTx.length != otherTy.length) {
            throw new IllegalArgumentException("SOSDET: " + otherTx.length + " x and " + otherTy.length + " y");
        }
        for (int i = 0; i < otherTx.length; i++) {
            double d = Math.hypot(tx - otherTx[i], ty - otherTy[i]);
            if (d < MIN_DISTANCE) {
                return String.format(Locale.ROOT, "%.1f tiles from another player's location; at least %.0f", d,
                        MIN_DISTANCE);
            }
        }
        return null;
    }

    /** Whether two locations are in range of the guaranteed connection: 6 to 15 tiles, as {@link #tooClose} measures. */
    public static boolean inRouteRange(int tx, int ty, int otherTx, int otherTy) {
        double d = Math.hypot(tx - otherTx, ty - otherTy);
        return d >= MIN_DISTANCE && d <= MAX_ROUTE_DISTANCE;
    }

    /** Why these humans can't start a world, or null: their number and the distances between their locations. */
    static String problem(HumanStart[] humans) {
        if (humans.length == 0 || humans.length > MAX_HUMANS) {
            return humans.length + " humans; a world holds 1 to " + MAX_HUMANS;
        }
        for (int i = 1; i < humans.length; i++) {
            int[] x = new int[i];
            int[] y = new int[i];
            for (int j = 0; j < i; j++) {
                x[j] = humans[j].tx;
                y[j] = humans[j].ty;
            }
            String p = tooClose(humans[i].tx, humans[i].ty, x, y);
            if (p != null) {
                return "human " + i + " at " + humans[i].tx + "," + humans[i].ty + ": " + p;
            }
        }
        return null;
    }
}

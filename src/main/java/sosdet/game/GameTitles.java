package sosdet.game;

import java.io.IOException;
import java.lang.reflect.Field;

import game.faction.FACTIONS;
import game.faction.player.PTitles;
import game.faction.player.Player;
import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.SAVABLE;
import sosdet.HumanStart;
import sosdet.game.titles.TitleTable;

/**
 * Each human's titles (mod spec §5 Titles, owner 2026-11-26), saved with the world ({@link GameWorldLoad#KEYS}). Which
 * titles a human holds is vanilla's own state, each Player's {@code PTitles}, saved with FACTIONS; this section keeps how
 * strong each is: the race count its picking player's profile earned it with, which vanilla reads from this PC's profile
 * ({@code Hooks.titleRaces}). A save without it (any one before) reads 0 for every human, vanilla's base, as before.
 */
public final class GameTitles extends Savable {

    static final String KEY = "SOSDET_TITLES";
    /** After the table: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53445449; // "SDTI"
    /** {@code PTitle.raceValue}: the races this PC's profile earned a title with. */
    private static final Field RACE_VALUE = field(PTitles.PTitle.class, "raceValue");

    private TitleTable table = new TitleTable();

    GameTitles() {
        super(KEY);
    }

    /** This game's titles. */
    public static GameTitles get() {
        return GameHumans.get().titles();
    }

    /** The race count of title {@code title} for the human in {@code slot}. */
    public int races(int slot, int title) {
        return table.races(slot, title);
    }

    /** Per title index, the races this PC's profile earned it with: what this PC's player brings to a world. */
    public static int[] localRaces() {
        PTitles titles = FACTIONS.player().titles;
        int[] v = new int[titles.all().size()];
        for (int i = 0; i < v.length; i++) {
            try {
                v[i] = (int) RACE_VALUE.getDouble(titles.all().get(i));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("SOSDET: cannot read PTitle.raceValue" + VERSION, e);
            }
        }
        return v;
    }

    /** The titles the human in {@code slot} holds, as title indices. */
    public static int[] held(int slot) {
        PTitles titles = player(slot).titles;
        int n = 0;
        for (int i = 0; i < titles.all().size(); i++) {
            n += titles.all().get(i).selected() ? 1 : 0;
        }
        int[] v = new int[n];
        int k = 0;
        for (int i = 0; i < titles.all().size(); i++) {
            if (titles.all().get(i).selected()) {
                v[k++] = i;
            }
        }
        return v;
    }

    /** For each of {@link #held}, its race count in this world. */
    public static int[] heldRaces(int slot) {
        int[] held = held(slot);
        int[] v = new int[held.length];
        for (int i = 0; i < held.length; i++) {
            v[i] = get().table.races(slot, held[i]);
        }
        return v;
    }

    private static Player player(int slot) {
        if (slot < 0 || slot >= FACTIONS.MAX() || !(FACTIONS.getByIndex(slot) instanceof Player p)) {
            throw new IllegalArgumentException("SOSDET: slot " + slot + " holds no human");
        }
        return p;
    }

    private static final String VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no field " + owner.getName() + "." + name + " in this game build" + VERSION, e);
        }
    }

    @Override
    protected void save(FilePutter file) {
        int[] v = table.toInts();
        file.i(v.length);
        for (int i : v) {
            file.i(i);
        }
        file.i(END);
    }

    @Override
    protected void load(FileGetter file) throws IOException {
        int n = file.i();
        if (n < 0 || n > file.remainingInts()) {
            throw new IOException("SOSDET: a titles section of " + n + " ints");
        }
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.i();
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a titles section without its end marker");
        }
        table = TitleTable.fromInts(v);
    }

    /**
     * {@code p} holds the titles of {@code h} and no other, each as strong as {@code h} says; on the PC that builds or
     * adds the human, before the world is saved for the others. A start with no titles leaves {@code p} with none.
     */
    static void apply(Player p, HumanStart h) {
        int count = p.titles.all().size();
        int[] titles = h.titles();
        boolean[] held = new boolean[count];
        for (int t : titles) {
            if (t < 0 || t >= count) {
                throw new IllegalArgumentException("SOSDET: title " + t + " of " + count + " for " + p.name);
            }
            held[t] = true;
        }
        get().table.set(p.index(), titles, h.titleRaces(), count);
        GameCodec codec = new GameCodec();
        FilePutter out = codec.hashBegin();
        out.i(count);
        for (boolean s : held) {
            out.bool(false); // not new
            out.bool(s);
        }
        byte[] bytes = codec.written();
        SAVABLE saver = GameWorldLoad.titlesSaver(p);
        try {
            saver.load(codec.reader(java.util.Arrays.copyOf(bytes, bytes.length + 8)));
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: " + p.name + "'s titles could not be set", e);
        }
    }
}

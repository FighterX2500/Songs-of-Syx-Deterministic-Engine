package sosdet.game;

import java.io.IOException;

import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.game.siege.SiegeBook;

/**
 * The sieges of the humans' capitals (SOS-92): when each began and its city's population then, saved with the world.
 * The section is in the "all" list, after {@code GameTariffs}, and a world load carries it with vanilla's sieges
 * ({@link GameWorldLoad#KEYS}). A save without it (any one before SOS-92) gives an empty book. The book carries its
 * own version.
 */
public final class GameSieges extends Savable {

    static final String KEY = "SOSDET_SIEGES";
    /** After the book: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53445347; // "SDSG"

    private SiegeBook book = new SiegeBook();

    GameSieges() {
        super(KEY);
    }

    /** This game's book. */
    public SiegeBook book() {
        return book;
    }

    @Override
    protected void save(FilePutter file) {
        int[] v = book.toInts();
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
            throw new IOException("SOSDET: a sieges section of " + n + " ints");
        }
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.i();
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a sieges section without its end marker");
        }
        book = SiegeBook.fromInts(v);
    }

    /** Mixes the book into {@code h}; a game with no human capital besieged hashes as before SOS-92. */
    static long hash(long h) {
        SiegeBook b = GameHumans.get().sieges().book();
        return b.empty() ? h : b.hash(h);
    }
}

package sosdet.game;

import java.io.IOException;

import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.game.deal.StanceBook;

/**
 * The stances passed to the humans' NPC vassals (SOS-140), saved with the world. The section is in the "all" list,
 * after {@code GameTraded}; a save without it (any one before SOS-140) gives an empty book: nothing passed yet. The
 * book carries its own version.
 */
public final class GamePassed extends Savable {

    static final String KEY = "SOSDET_PASSED";
    /** After the book: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53445053; // "SDPS"

    private StanceBook book = new StanceBook();

    GamePassed() {
        super(KEY);
    }

    /** This game's book. */
    public StanceBook book() {
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
            throw new IOException("SOSDET: a passed stances section of " + n + " ints");
        }
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.i();
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a passed stances section without its end marker");
        }
        book = StanceBook.fromInts(v);
    }

    /** Mixes the book into {@code h}; a game with no stance passed hashes as before SOS-140. Allocates nothing. */
    static long hash(long h) {
        StanceBook b = GameHumans.get().passed().book();
        return b.empty() ? h : b.hash(h);
    }
}

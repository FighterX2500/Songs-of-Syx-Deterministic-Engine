package sosdet.game;

import java.io.IOException;

import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.game.carve.CarveBook;

/**
 * The carved city-states' undo log (SOS-186, SOSGSGMP docs/plan/hot-join.md §3.5), saved with the world. The section is
 * in the "all" list, after {@code GamePassed}; a save without it (any one before SOS-186) gives an empty book: nothing
 * carved. The book carries its own version.
 */
public final class GameCarves extends Savable {

    static final String KEY = "SOSDET_CARVES";
    /** After the book: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53444356; // "SDCV"

    private CarveBook book = new CarveBook();

    GameCarves() {
        super(KEY);
    }

    /** This game's book. */
    public CarveBook book() {
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
            throw new IOException("SOSDET: a carve section of " + n + " ints");
        }
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.i();
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a carve section without its end marker");
        }
        book = CarveBook.fromInts(v);
    }

    /** Mixes the book into {@code h}; a game with nothing carved hashes as before SOS-186. */
    static long hash(long h) {
        CarveBook b = GameHumans.get().carves().book();
        return b.empty() ? h : b.hash(h);
    }
}

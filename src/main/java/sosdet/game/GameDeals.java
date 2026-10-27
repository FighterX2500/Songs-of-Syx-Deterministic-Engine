package sosdet.game;

import java.io.IOException;

import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.game.deal.DealBook;

/**
 * The deals between humans (SOS-72, plan 0009): the book of open proposals and latest answers, saved with the world.
 * The section is in the "all" list, after {@code GameEvents}; a save without it (any one before SOS-72) gives an empty
 * book. The book carries its own version.
 */
public final class GameDeals extends Savable {

    static final String KEY = "SOSDET_DEALS";
    /** After the book: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53444444; // "SDDD"

    private DealBook book = new DealBook();

    GameDeals() {
        super(KEY);
    }

    /** This game's book. */
    public DealBook book() {
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
            throw new IOException("SOSDET: a deals section of " + n + " ints");
        }
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.i();
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a deals section without its end marker");
        }
        book = DealBook.fromInts(v);
    }

    /** Mixes the book into {@code h}; a game with no deals hashes as before SOS-72. */
    static long hash(long h) {
        DealBook b = GameHumans.get().deals().book();
        if (b.open().isEmpty() && b.answers().isEmpty() && b.tributes().isEmpty()) {
            return h;
        }
        return b.hash(h);
    }
}

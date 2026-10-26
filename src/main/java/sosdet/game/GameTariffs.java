package sosdet.game;

import java.io.IOException;

import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.game.deal.TariffBook;

/**
 * The tariffs between humans (SOS-77): each human's tariff on another and what it brought in, saved with the world. The
 * section is in the "all" list, after {@code GameDeals}; a save without it (any one before SOS-77) gives an empty
 * book, every tariff the default. The book carries its own version.
 */
public final class GameTariffs extends Savable {

    static final String KEY = "SOSDET_TARIFFS";
    /** After the book: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53445454; // "SDTT"

    private TariffBook book = new TariffBook();

    GameTariffs() {
        super(KEY);
    }

    /** This game's book. */
    public TariffBook book() {
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
            throw new IOException("SOSDET: a tariffs section of " + n + " ints");
        }
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.i();
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a tariffs section without its end marker");
        }
        book = TariffBook.fromInts(v);
    }

    /** Mixes the book into {@code h}; a game with no tariff set or earned hashes as before SOS-77. */
    static long hash(long h) {
        TariffBook b = GameHumans.get().tariffs().book();
        return b.empty() ? h : b.hash(h);
    }
}

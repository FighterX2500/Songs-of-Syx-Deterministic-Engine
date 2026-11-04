package sosdet.game;

import java.io.IOException;

import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.game.deal.TradeTally;

/**
 * The market trade between humans (SOS-135), saved with the world. The section is in the "all" list, after
 * {@code GameSieges}; a save without it (any one before SOS-135) gives an empty tally. The tally carries its own
 * version.
 */
public final class GameTraded extends Savable {

    static final String KEY = "SOSDET_TRADED";
    /** After the tally: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53445452; // "SDTR"

    private TradeTally tally = new TradeTally();

    GameTraded() {
        super(KEY);
    }

    /** This game's tally. */
    public TradeTally tally() {
        return tally;
    }

    @Override
    protected void save(FilePutter file) {
        long[] v = tally.toLongs();
        file.i(v.length);
        for (long l : v) {
            file.l(l);
        }
        file.i(END);
    }

    @Override
    protected void load(FileGetter file) throws IOException {
        int n = file.i();
        if (n < 0 || 2L * n > file.remainingInts()) {
            throw new IOException("SOSDET: a trade tally section of " + n + " longs");
        }
        long[] v = new long[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.l();
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a trade tally section without its end marker");
        }
        tally = TradeTally.fromLongs(v);
    }

    /** Mixes the tally into {@code h}; a game with no sale between humans hashes as before SOS-135. */
    static long hash(long h) {
        TradeTally t = GameHumans.get().traded().tally();
        return t.empty() ? h : t.hash(h);
    }
}

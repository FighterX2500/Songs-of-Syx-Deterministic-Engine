package sosdet.game;

import java.io.IOException;

import game.save.Savable;
import init.constant.Config;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.game.station.StationedDivs;

/**
 * The divisions of this city's army 0 that hold a stationed ally's soldiers during a fight here (SOS-234, ADR 0011).
 * City state, so not in the world save ({@link GameWorldLoad#KEYS}): a world load or a resync leaves it as it is,
 * and a view's copy of the city loads it with the city. A save without it (any one before SOS-234) has none. The
 * registry carries its own version.
 */
public final class GameStationedDivs extends Savable {

    static final String KEY = "SOSDET_STATIONED_DIVS";
    /** After the registry: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53445344; // "SDSD"

    private StationedDivs divs = new StationedDivs(Config.battle().DIVISIONS_PER_ARMY);

    GameStationedDivs() {
        super(KEY);
    }

    /** This city's registry. */
    public StationedDivs divs() {
        return divs;
    }

    @Override
    protected void save(FilePutter file) {
        int[] v = divs.toInts();
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
            throw new IOException("SOSDET: a stationed divisions section of " + n + " ints");
        }
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.i();
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a stationed divisions section without its end marker");
        }
        divs = StationedDivs.fromInts(v);
    }
}

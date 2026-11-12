package sosdet.game;

import java.io.IOException;
import java.util.ArrayList;

import game.faction.FACTIONS;
import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.txn.Txn;

/**
 * The frozen humans (SOS-193, mod spec §11 Freezing a player), saved with the world. A frozen human keeps its slot and
 * its capital; no PC runs its city, and every PC reads it as its last report showed it, with the world's changes to it
 * since, until its player rejoins. The transactions for its city wait here meanwhile, so views, resyncs and resumes keep
 * them. A save without the section (any one before SOS-193) has no frozen human.
 */
public final class GameFrozen extends Savable {

    static final String KEY = "SOSDET_FROZEN";
    private static final int VERSION = 1;
    /** After the section: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x5344465A; // "SDFZ"

    private final boolean[] frozen = new boolean[FACTIONS.MAX()];
    /** Each frozen slot's last report and the world's open changes to it, as the last save or load had them. */
    private final byte[][] report = new byte[FACTIONS.MAX()][];
    private final byte[][] ledger = new byte[FACTIONS.MAX()][];
    /** The transactions waiting for the frozen humans' cities, in the order the world made them, every PC alike. */
    private final ArrayList<HeldTxn> held = new ArrayList<>();
    /** Transactions of a kind no save holds, dropped for a frozen city (none is expected: see {@link HeldTxn#storable}). */
    private int dropped;

    GameFrozen() {
        super(KEY);
    }

    public boolean frozen(int slot) {
        return frozen[slot];
    }

    /** In the world part, on every PC: the human in {@code slot} is frozen from now. */
    void freeze(int slot) {
        if (frozen[slot]) {
            throw new IllegalStateException("SOSDET: the human in slot " + slot + " is frozen already");
        }
        frozen[slot] = true;
    }

    /**
     * At a rejoin, on every PC: the human in {@code slot} is frozen no more. Returns the transactions that waited for its
     * city, which only the returning PC applies; every other PC drops them as it drops any other PC's city's.
     */
    public ArrayList<HeldTxn> thaw(int slot) {
        if (!frozen[slot]) {
            throw new IllegalStateException("SOSDET: the human in slot " + slot + " is not frozen");
        }
        frozen[slot] = false;
        report[slot] = null;
        ledger[slot] = null;
        ArrayList<HeldTxn> mine = new ArrayList<>();
        held.removeIf(h -> h.human() == slot && mine.add(h));
        return mine;
    }

    /** In the city tail, on every PC: a transaction for a frozen human's city waits for its return. */
    public void hold(Txn t) {
        if (!HeldTxn.storable(t.kind)) {
            dropped++;
            return;
        }
        held.add(HeldTxn.of(t));
    }

    /** The transactions waiting for the city of the human in {@code slot}. */
    public int heldFor(int slot) {
        int n = 0;
        for (HeldTxn h : held) {
            if (h.human() == slot) {
                n++;
            }
        }
        return n;
    }

    public int dropped() {
        return dropped;
    }

    /** The frozen human's last report and the world's open changes to it, as the last load had them; null for none. */
    public byte[][] report(int slot) {
        return frozen[slot] && report[slot] != null ? new byte[][] {report[slot], ledger[slot]} : null;
    }

    @Override
    protected void save(FilePutter file) {
        file.i(VERSION);
        int n = 0;
        for (boolean f : frozen) {
            n += f ? 1 : 0;
        }
        file.i(n);
        for (int slot = 0; slot < frozen.length; slot++) {
            if (!frozen[slot]) {
                continue;
            }
            byte[][] live = sosdet.Hooks.frozenReportOut(slot); // the session's, with the world's changes up to now
            if (live != null) {
                report[slot] = live[0];
                ledger[slot] = live[1];
            }
            if (report[slot] == null) {
                throw new IllegalStateException("SOSDET: the frozen human in slot " + slot + " has no report to save");
            }
            file.i(slot);
            bytes(file, report[slot]);
            bytes(file, ledger[slot]);
        }
        file.i(held.size());
        for (HeldTxn h : held) {
            h.save(file);
        }
        file.i(dropped);
        file.i(END);
    }

    @Override
    protected void load(FileGetter file) throws IOException {
        java.util.Arrays.fill(frozen, false);
        java.util.Arrays.fill(report, null);
        java.util.Arrays.fill(ledger, null);
        held.clear();
        int version = file.i();
        if (version < 1 || version > VERSION) {
            throw new IOException("SOSDET: a frozen humans section of version " + version + ", this engine reads 1 to "
                    + VERSION);
        }
        int n = file.i();
        if (n < 0 || n > frozen.length) {
            throw new IOException("SOSDET: " + n + " frozen humans");
        }
        for (int i = 0; i < n; i++) {
            int slot = file.i();
            if (slot <= 0 || slot >= frozen.length || frozen[slot]) {
                throw new IOException("SOSDET: frozen human slot " + slot);
            }
            frozen[slot] = true;
            report[slot] = bytes(file);
            ledger[slot] = bytes(file);
        }
        int h = file.i();
        if (h < 0 || h > file.remainingInts()) {
            throw new IOException("SOSDET: " + h + " held transactions for frozen humans");
        }
        for (int i = 0; i < h; i++) {
            held.add(HeldTxn.load(file));
        }
        dropped = file.i();
        if (file.i() != END) {
            throw new IOException("SOSDET: a frozen humans section without its end marker");
        }
    }

    @Override
    protected void loadFail() {
        java.util.Arrays.fill(frozen, false); // a save from before SOS-193: nobody is frozen
        java.util.Arrays.fill(report, null);
        java.util.Arrays.fill(ledger, null);
        held.clear();
        dropped = 0;
    }

    private static void bytes(FilePutter file, byte[] b) {
        file.i(b.length);
        file.bs(b);
    }

    private static byte[] bytes(FileGetter file) throws IOException {
        int n = file.i();
        if (n < 0 || n > 4L * file.remainingInts()) {
            throw new IOException("SOSDET: a frozen human's report of " + n + " bytes");
        }
        byte[] b = new byte[n];
        file.bs(b);
        return b;
    }

    /** Mixes the frozen humans and their waiting transactions' count into {@code h}; none hashes as before SOS-193. */
    static long hash(long h) {
        GameFrozen f = GameHumans.get().frozen();
        for (int slot = 0; slot < f.frozen.length; slot++) {
            if (f.frozen[slot]) {
                h = h * 31 + slot + 1;
            }
        }
        return f.held.isEmpty() ? h : h * 31 + f.held.size();
    }
}

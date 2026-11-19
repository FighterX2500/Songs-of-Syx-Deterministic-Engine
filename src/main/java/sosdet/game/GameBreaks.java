package sosdet.game;

import java.io.IOException;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.DipStance;
import game.faction.npc.FactionNPC;
import game.faction.player.Player;
import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.game.deal.BreakBook;

/**
 * Who made the last stance change between each human and each NPC (SOS-227), saved with the world. Every stance change
 * reaches the book through the game's own diplomacy listener; {@code OpsStance.setNewStance}, the way a human changes
 * a stance with an NPC, then marks its change as the human's. Every game has the book, single player too: the rule
 * changes vanilla's betrayal (owner, 2026-11-18). The section is in the "all" list, after {@code GameFrozen}; a save
 * without it (any one before SOS-227) gives an empty book: no NPC broke a stance yet. The book carries its own version.
 */
public final class GameBreaks extends Savable {

    static final String KEY = "SOSDET_BREAKS";
    /** After the book: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53444252; // "SDBR"

    private BreakBook book = new BreakBook(FACTIONS.MAX());

    GameBreaks() {
        super(KEY);
        new DIP.DipActivityListener() {
            @Override
            public void change(Faction faction, Faction other, DipStance old, DipStance nn) {
                if (faction instanceof Player && other instanceof FactionNPC) {
                    book.changed(faction.index(), other.index(), lowers(old, nn));
                } else if (faction instanceof FactionNPC && other instanceof Player) {
                    book.changed(other.index(), faction.index(), lowers(old, nn));
                }
            }
        };
        new Faction.FactionActivityListener() {
            @Override
            public void remove(FactionNPC f) {
                book.forget(f.index());
            }

            @Override
            public void add(FactionNPC f) {
                book.forget(f.index());
            }
        };
    }

    /** This game's book. */
    public BreakBook book() {
        return book;
    }

    private static boolean lowers(DipStance old, DipStance nn) {
        return BreakBook.lowers(old == DIP.WAR(), old.loyalty, nn == DIP.WAR(), nn.loyalty);
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
            throw new IOException("SOSDET: a stance breaks section of " + n + " ints");
        }
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.i();
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a stance breaks section without its end marker");
        }
        book = BreakBook.fromInts(FACTIONS.MAX(), v);
    }

    /** Mixes the book into {@code h}; a game where no NPC broke a stance hashes as before SOS-227. Allocates nothing. */
    static long hash(long h) {
        BreakBook b = GameHumans.get().breaks().book();
        return b.empty() ? h : b.hash(h);
    }
}

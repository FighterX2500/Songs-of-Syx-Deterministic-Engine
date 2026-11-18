package sosdet.game;

import java.io.IOException;

import game.faction.FACTIONS;
import game.faction.FResources.RTYPE;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.DipStance;
import game.faction.player.Player;
import game.save.Savable;
import init.constant.Config;
import settlement.main.SETT;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.Hooks;
import sosdet.Notice;
import sosdet.game.cmd.Args;
import sosdet.game.station.StationBook;
import world.army.AD;
import world.army.ADSupply;
import world.entity.army.WArmy;
import world.entity.army.WArmyState;
import world.map.regions.Region;
import world.region.RD;

/**
 * The armies stationed at another human's capital (mod spec §9, SOS-233, ADR 0011), saved with the world. The section
 * is in the "all" list, after {@code GameFrozen}, and a world load carries it ({@link GameWorldLoad#KEYS}). A save
 * without it (any one before SOS-233) has no stationed army. The book carries its own version.
 */
public final class GameStations extends Savable {

    static final String KEY = "SOSDET_STATIONS";
    /** After the book: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53445354; // "SDST"

    private StationBook book = new StationBook();

    GameStations() {
        super(KEY);
    }

    /** This game's book. */
    public StationBook book() {
        return book;
    }

    /** This game's stationed armies. */
    public static GameStations get() {
        return GameHumans.get().stations();
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
            throw new IOException("SOSDET: a stations section of " + n + " ints");
        }
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.i();
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a stations section without its end marker");
        }
        book = StationBook.fromInts(v);
    }

    /** Mixes the book into {@code h}; a game with no stationed army hashes as before SOS-233. */
    static long hash(long h) {
        StationBook b = get().book;
        return b.empty() ? h : b.hash(h);
    }

    // ---------------------------------------------------------------- the rules, read from the world

    /**
     * Why the current human (inside a world command, its issuer; between steps, this PC's player) can't station
     * {@code a} at the capital of the human in {@code host}, or null.
     */
    public String refusal(WArmy a, int host) {
        Faction me = FACTIONS.player();
        Faction h = host >= 0 && host < FACTIONS.MAX() ? FACTIONS.getByIndex(host) : null;
        boolean hostPlayer = h instanceof Player && h != me && h.isActive() && h.capitolRegion() != null
                && !Hooks.retired(host);
        boolean own = a != null && a.added() && a.faction() == me;
        Region cap = hostPlayer ? h.capitolRegion() : null;
        int divisions = own ? a.divs().size() : 0;
        return StationBook.refusal(own, own && book.find(a.armyIndex()) >= 0, hostPlayer,
                hostPlayer && (Hooks.dormant(host) || Hooks.dormant(me.index())), hostPlayer && agreement(me, h),
                hostPlayer && DIP.WAR().is(me, h), own && fortified(a), cap == null || !own ? 99 : a.ctx() - cap.cx(),
                cap == null || !own ? 99 : a.cty() - cap.cy(), divisions, hostPlayer ? hostDivisions(h) : 0,
                hostPlayer ? stationedDivisions(host) : 0, Config.battle().DIVISIONS_PER_ARMY);
    }

    /** Why the current human can't send away or call back {@code a}, or null: the host and the army's human may. */
    public String endRefusal(WArmy a) {
        int i = a == null ? -1 : book.find(a.armyIndex(), a.iteration());
        if (i < 0) {
            return "This army isn't stationed.";
        }
        int me = FACTIONS.player().index();
        return me == book.host(i) || me == book.owner(i) ? null : "Only the host or the army's player can end it.";
    }

    /** Why the current human can't supply {@code a} from its own city now, or null: the host may, at its capital. */
    public String supplyRefusal(WArmy a) {
        int i = a == null ? -1 : book.find(a.armyIndex(), a.iteration());
        if (i < 0) {
            return "This army isn't stationed.";
        }
        if (FACTIONS.player().index() != book.host(i)) {
            return "Only the player whose capital it stands at can supply it.";
        }
        return null;
    }

    /** The two humans have an agreement that allows stationing: allies, colleagues (a pact), overlord and vassal. */
    static boolean agreement(Faction a, Faction b) {
        DipStance s = DIP.get(a, b);
        return s == DIP.ALLY() || s == DIP.PACT() || s == DIP.VASSAL() || s == DIP.OVERLORD();
    }

    private static boolean fortified(WArmy a) {
        return a.state() == WArmyState.fortified || a.state() == WArmyState.fortifying;
    }

    /** The host's own divisions: its capital's garrison and its armies'. */
    static int hostDivisions(Faction h) {
        int n = RD.MILITARY().divisions(h.capitolRegion()).size();
        for (int i = 0; i < h.armies().all().size(); i++) {
            n += h.armies().all().get(i).divs().size();
        }
        return n;
    }

    /** The divisions of the armies stationed at {@code host}'s capital. */
    int stationedDivisions(int host) {
        int n = 0;
        for (int i = 0; i < book.size(); i++) {
            WArmy a = book.host(i) == host ? Args.army(book.army(i), book.iteration(i)) : null;
            n += a == null ? 0 : a.divs().size();
        }
        return n;
    }

    // ---------------------------------------------------------------- the world part, every PC

    /** A world command: the current human stations {@code a} at {@code host}'s capital; false when refused. */
    public boolean station(WArmy a, int host, long step) {
        if (refusal(a, host) != null) {
            return false;
        }
        book.station(a.armyIndex(), a.iteration(), FACTIONS.player().index(), host, a.ctx(), a.cty(), (int) step);
        Hooks.notice(Notice.STATIONED, FACTIONS.player().index(), host, 0);
        return true;
    }

    /** A world command: the host sends {@code a} away, or its human calls it back; false when refused. */
    public boolean end(WArmy a) {
        if (endRefusal(a) != null) {
            return false;
        }
        int i = book.find(a.armyIndex(), a.iteration());
        end(i, FACTIONS.player().index() == book.host(i) ? StationBook.SENT_AWAY : StationBook.LEFT);
        return true;
    }

    /**
     * A world command: the host supplies {@code a} from its own city. Its city tail fills the army's supplies from its
     * stockpiles, as much as they have ({@code TxnKind.STATION_SUPPLY}); its next report gives them to the army.
     */
    public boolean supply(WArmy a) {
        if (supplyRefusal(a) != null || !Hooks.stationSupply(a)) {
            return false;
        }
        int i = book.find(a.armyIndex(), a.iteration());
        Hooks.notice(Notice.STATION_SUPPLIED, book.host(i), book.owner(i), 0);
        return true;
    }

    /** The world part's end rules, every step outside a battle, on every PC alike. No allocation while nothing ends. */
    public void endDue() {
        for (int i = book.size() - 1; i >= 0; i--) {
            int why = endReason(i);
            if (why >= 0) {
                end(i, why);
            }
        }
    }

    private int endReason(int i) {
        WArmy a = Args.army(book.army(i), book.iteration(i));
        Faction owner = FACTIONS.getByIndex(book.owner(i));
        Faction host = FACTIONS.getByIndex(book.host(i));
        Region cap = host.capitolRegion();
        return StationBook.endReason(a != null && a.faction() == owner,
                Hooks.dormant(book.owner(i)) || Hooks.dormant(book.host(i)), cap != null && cap.faction() == host,
                agreement(owner, host), DIP.WAR().is(owner, host), a != null && a.ctx() == book.x(i) && a.cty() == book.y(i));
    }

    private void end(int i, int why) {
        Hooks.notice(Notice.STATION_ENDED, book.owner(i), book.host(i), why);
        book.end(i);
    }

    // ---------------------------------------------------------------- a fight in the host's city (SOS-234)

    /**
     * The stationed divisions that enter a fight in the host's city: each one's player, its world army (index and
     * iteration), its place in that army, and its people as the fight's soldiers. Built in the world part, the same on
     * every PC; carried to the host's city by a transaction or with the assault.
     */
    public static final class Fighters {
        public final int[] owners;
        public final int[] armies;
        public final int[] iterations;
        public final int[] places;
        public final java.util.List<game.battle.util.DivGeneration> divs;

        Fighters(int[] owners, int[] armies, int[] iterations, int[] places, java.util.List<game.battle.util.DivGeneration> divs) {
            this.owners = owners;
            this.armies = armies;
            this.iterations = iterations;
            this.places = places;
            this.divs = divs;
        }

        public int size() {
            return divs.size();
        }
    }

    /**
     * In the world part, on every PC: the divisions of the armies stationed at {@code host}'s capital whose players are
     * at war with {@code enemy} (a faction's index; -1 for raiders and rebels, at war with everyone), up to the city's
     * free army slots as the world reads them; null when none (mod spec §9, ADR 0011). A removed or frozen player's stay
     * out; so does the army of a player at peace with the enemy ("someone else's war").
     */
    public Fighters fighters(int host, int enemy) {
        Faction h = FACTIONS.getByIndex(host);
        if (book.count(host) == 0 || h.capitolRegion() == null) {
            return null;
        }
        int free = Config.battle().DIVISIONS_PER_ARMY - hostDivisions(h);
        java.util.List<game.battle.util.DivGeneration> divs = new java.util.ArrayList<>();
        int[] owners = new int[Math.max(free, 0)];
        int[] armies = new int[owners.length];
        int[] iterations = new int[owners.length];
        int[] places = new int[owners.length];
        for (int i = 0; i < book.size() && divs.size() < owners.length; i++) {
            int owner = book.owner(i);
            WArmy a = entryFights(i, host, enemy) ? Args.army(book.army(i), book.iteration(i)) : null;
            if (a == null) {
                continue;
            }
            for (int di = 0; di < a.divs().size() && divs.size() < owners.length; di++) {
                if (a.divs().get(di).men() <= 0) {
                    continue;
                }
                int n = divs.size();
                owners[n] = owner;
                armies[n] = a.armyIndex();
                iterations[n] = a.iteration();
                places[n] = di;
                divs.add(a.divs().get(di).generate());
            }
        }
        int n = divs.size();
        return n == 0 ? null : new Fighters(java.util.Arrays.copyOf(owners, n), java.util.Arrays.copyOf(armies, n),
                java.util.Arrays.copyOf(iterations, n), java.util.Arrays.copyOf(places, n), divs);
    }

    /**
     * Between steps or in the world part: whether an army of {@code owner}'s stationed at {@code host}'s capital fights
     * for it against {@code enemy} (a faction's index; below 0 for raiders and rebels), by {@link #fighters}' rule.
     */
    public boolean fights(int owner, int host, int enemy) {
        for (int i = 0; i < book.size(); i++) {
            if (book.owner(i) == owner && entryFights(i, host, enemy)
                    && Args.army(book.army(i), book.iteration(i)) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean entryFights(int i, int host, int enemy) {
        int owner = book.owner(i);
        return book.host(i) == host && !Hooks.dormant(owner)
                && (enemy < 0 || DIP.WAR().is(FACTIONS.getByIndex(owner), FACTIONS.getByIndex(enemy)));
    }

    /**
     * The men a stationed division has now, as the world holds them: 0 when its army no longer stands at {@code host}'s
     * capital or the division is gone. Read in the host's city tail, the same on every PC that runs it.
     */
    public static int men(int army, int iteration, int place, int host) {
        WArmy a = Args.army(army, iteration);
        if (a == null || !get().book.stationedAt(army, iteration, host) || place < 0 || place >= a.divs().size()) {
            return 0;
        }
        return a.divs().get(place).men();
    }

    /**
     * {@code InputKind.STATION_RESOLVE} in the world part: a stationed division's world division loses the men who died
     * in a fight in the host's city and takes its survivors' experience, as a local battle's does. Men who joined it since
     * the fight began stay. An army or a division gone since has nothing to lose.
     */
    public static void resolve(int army, int iteration, int place, int dead, double experience) {
        WArmy a = Args.army(army, iteration);
        if (a != null && place >= 0 && place < a.divs().size()) {
            a.divs().get(place).resolve(sosdet.game.station.StationedDivs.survivors(a.divs().get(place).men(), dead), experience);
        }
    }

    // ---------------------------------------------------------------- the host's city tail

    /**
     * {@code TxnKind.STATION_SUPPLY} in the host's city tail: each of the army's supplies filled from the stockpiles, as
     * much as they can give, as vanilla's recruit takes a new division's. The supply counter sends what it gets to the
     * world in the city's next report (B2).
     */
    public static void supplyFromCity(WArmy a) {
        if (a == null || !a.added()) {
            return; // the army is gone since the world part asked
        }
        for (int k = 0; k < AD.supplies().all.size(); k++) {
            ADSupply s = AD.supplies().all.get(k);
            int am = (int) Math.min(s.needed(a), SETT.ROOMS().STOCKPILE.tally().amountReservable.get(s.res));
            if (am > 0) {
                s.res.remove(am, RTYPE.ARMY_SUPPLY);
                s.current().inc(a, am);
            }
        }
    }
}

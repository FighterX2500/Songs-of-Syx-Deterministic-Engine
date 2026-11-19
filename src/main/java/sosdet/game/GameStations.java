package sosdet.game;

import java.io.IOException;

import game.faction.FACTIONS;
import game.faction.FResources.RTYPE;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.DipStance;
import game.faction.player.Player;
import game.save.Savable;
import game.time.TIME;
import init.constant.Config;
import settlement.main.SETT;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.Hooks;
import sosdet.Notice;
import sosdet.game.cmd.Args;
import sosdet.game.station.StationBook;
import world.army.AD;
import world.army.ADDiv;
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
    /**
     * The game seconds between two refreshes of the stationed soldiers in their host's city from their world armies:
     * men, equipment and training (owner, 2026-11-19).
     */
    public static final int REFRESH_SECONDS = 120;
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
        if (a != null && book.find(a.armyIndex()) >= 0 && a.faction() == FACTIONS.player()) {
            return enterRefusal(a, host);
        }
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

    /**
     * Station on an army stationed already (owner, 2026-11-19): why its divisions in the camp can't go into the host's
     * city now, or null: they go in when it is stationed at that host and has a division with men in the camp.
     */
    private String enterRefusal(WArmy a, int host) {
        int i = book.find(a.armyIndex(), a.iteration());
        if (i < 0 || book.host(i) != host) {
            return "This army is stationed elsewhere.";
        }
        if (Hooks.dormant(host)) {
            return "That player is away or out of the game.";
        }
        for (int di = 0; di < a.divs().size(); di++) {
            ADDiv d = a.divs().get(di);
            if (d.men() > 0 && !book.inCity(i, key(d))) {
                return null;
            }
        }
        return "This army is stationed already: every division is in their city.";
    }

    /** A world division's key in the book ({@link StationBook#divKey}): its type and index. */
    public static int key(ADDiv d) {
        int type = d instanceof world.army.WDivRegional ? 0 : d instanceof world.army.WDivMercenary ? 2 : 1;
        return StationBook.divKey(type, d.index);
    }

    /** The record of the stationed army {@code a}, or -1. No allocation. */
    private int record(WArmy a) {
        return a == null ? -1 : book.find(a.armyIndex(), a.iteration());
    }

    /**
     * Whether {@code a} is stationed (owner, 2026-11-19): it can't be attacked directly on the world map, though it helps
     * in battles nearby as any army does. World state, the same on every PC. No allocation.
     */
    public boolean stationed(WArmy a) {
        return record(a) >= 0;
    }

    /** Whether world division {@code d} is in the host's city of its stationed army (owner, 2026-11-19). No allocation. */
    public boolean inCity(ADDiv d) {
        WArmy a = d == null ? null : d.army();
        int i = record(a);
        return i >= 0 && book.inCity(i, key(d));
    }

    /**
     * Why {@code a} can't move now, or null (owner, 2026-11-19): a stationed army moves only once every division is out
     * of the host's city, in the camp; the move then ends the stationing.
     */
    public String moveRefusal(WArmy a) {
        int i = record(a);
        if (i < 0 || book.cityCount(i) == 0) {
            return null;
        }
        return "Stationed in " + FACTIONS.getByIndex(book.host(i)).name
                + "'s city. Bring its divisions out to the camp first: Leave for the camp, near an entry point of the city.";
    }

    /** Why world division {@code d} can't be recalled or moved to another army now, or null (owner, 2026-11-19). */
    public String recallRefusal(ADDiv d) {
        if (!inCity(d)) {
            return null;
        }
        return "Stationed in " + FACTIONS.getByIndex(book.host(record(d.army()))).name
                + "'s city. Bring it out to the camp first: Leave for the camp, near an entry point of the city.";
    }

    /**
     * Whether the current human commands the stationed army {@code a}'s divisions in the host's city: its player, and the
     * host while given command (owner, 2026-11-19).
     */
    public boolean commandsCamp(WArmy a) {
        int i = record(a);
        int me = FACTIONS.player().index();
        return i >= 0 && (me == book.owner(i) || me == book.host(i) && book.granted(i));
    }

    /**
     * Why the current human can't send the division of {@code key} of {@code a} from the host's city to the camp, or
     * null: its player may, and the host while given command; it must be in the city.
     */
    public String campRefusal(WArmy a, int key) {
        int i = record(a);
        if (i < 0) {
            return "This army isn't stationed.";
        }
        if (!commandsCamp(a)) {
            return "Only the army's player, or the host given command, can send it to the camp.";
        }
        if (Hooks.dormant(book.host(i))) {
            return "That player is away or out of the game.";
        }
        return book.cityCount(i) != 0 && book.inCity(i, key) ? null : "That division isn't in the city.";
    }

    /** Why the current human can't send away or call back {@code a}, or null: the host and the army's human may. */
    public String endRefusal(WArmy a) {
        int i = a == null ? -1 : book.find(a.armyIndex(), a.iteration());
        if (i < 0) {
            return "This army isn't stationed.";
        }
        int me = FACTIONS.player().index();
        if (me == book.owner(i) && me != book.host(i) && book.cityCount(i) != 0) {
            return moveRefusal(a); // its player brings the divisions out by an entry point first (owner, 2026-11-19)
        }
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

    /** Why the current human can't give the host command of {@code a}'s soldiers in its city, or take it back, or null. */
    public String grantRefusal(WArmy a) {
        int i = a == null ? -1 : book.find(a.armyIndex(), a.iteration());
        if (i < 0) {
            return "This army isn't stationed.";
        }
        if (FACTIONS.player().index() != book.owner(i)) {
            return "Only the army's player can give its command.";
        }
        return null;
    }

    /** Whether the host commands the soldiers of the stationed army at {@code army} with {@code iteration}. */
    public boolean granted(int army, int iteration) {
        int i = book.find(army, iteration);
        return i >= 0 && book.granted(i);
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
        int i = book.find(a.armyIndex(), a.iteration());
        if (i >= 0) {
            book.citySet(i, keys(a)); // Station again: the camp's divisions go into the city (owner, 2026-11-19)
            send(i, a);
            return true;
        }
        book.station(a.armyIndex(), a.iteration(), FACTIONS.player().index(), host, a.ctx(), a.cty(), (int) step);
        book.citySet(book.size() - 1, keys(a));
        Hooks.notice(Notice.STATIONED, FACTIONS.player().index(), host, 0);
        send(book.size() - 1, a); // its soldiers come into the host's city now (owner, 2026-11-19)
        return true;
    }

    /** The keys of {@code a}'s divisions with men. */
    private static int[] keys(WArmy a) {
        int[] k = new int[a.divs().size()];
        int n = 0;
        for (int di = 0; di < a.divs().size(); di++) {
            if (a.divs().get(di).men() > 0) {
                k[n++] = key(a.divs().get(di));
            }
        }
        return java.util.Arrays.copyOf(k, n);
    }

    /**
     * A world command (owner, 2026-11-19): the division of {@code key} of {@code a} leaves the host's city for the camp;
     * its soldiers there go ({@code TxnKind.STATION_CAMP}). The player's button allows it only near an entry point.
     */
    public boolean toCamp(WArmy a, int key) {
        if (campRefusal(a, key) != null) {
            return false;
        }
        int i = record(a);
        book.toCamp(i, key);
        book.menSet(i, cityMen(i, a));
        Hooks.stationCamp(book.host(i), a.armyIndex(), a.iteration(), key);
        return true;
    }

    /**
     * A world command: the army's human gives the host command of {@code a}'s soldiers in its city, or takes it back
     * (owner, 2026-11-19); false when refused or unchanged.
     */
    public boolean grant(WArmy a, boolean granted) {
        if (grantRefusal(a) != null) {
            return false;
        }
        int i = book.find(a.armyIndex(), a.iteration());
        if (book.granted(i) == granted) {
            return false;
        }
        book.grantSet(i, granted);
        Hooks.notice(Notice.STATION_GRANT, book.owner(i), book.host(i), granted ? 1 : 0);
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

    /**
     * The world part's end rules, every step outside a battle, on every PC alike. Then a stationed army whose men
     * changed since its soldiers last went to the host's city, after a battle above all, sends them again at once (owner,
     * 2026-11-19); and once every {@link #REFRESH_SECONDS} of game time every stationed army does, for its equipment and
     * training. No allocation while nothing ends or changes.
     */
    public void endDue() {
        for (int i = book.size() - 1; i >= 0; i--) {
            int why = endReason(i);
            if (why >= 0) {
                end(i, why);
            }
        }
        int period = (int) (TIME.currentSecond() / REFRESH_SECONDS);
        boolean refresh = period != book.period() && book.period() >= 0;
        book.periodSet(period);
        for (int i = 0; i < book.size(); i++) {
            WArmy a = Args.army(book.army(i), book.iteration(i));
            if (a == null) {
                continue;
            }
            if (book.city(i) == null) {
                book.citySet(i, keys(a)); // a book before version 4: every division was in the city
            }
            if (refresh || cityMen(i, a) != book.men(i)) {
                send(i, a);
            }
        }
    }

    /** The men of record {@code i}'s divisions in the host's city. No allocation. */
    private int cityMen(int i, WArmy a) {
        int men = 0;
        for (int di = 0; di < a.divs().size(); di++) {
            ADDiv d = a.divs().get(di);
            if (book.inCity(i, key(d))) {
                men += d.men();
            }
        }
        return men;
    }

    /** Record {@code i}'s army {@code a}: its soldiers in the city go there again as the world holds them now. */
    private void send(int i, WArmy a) {
        book.menSet(i, cityMen(i, a));
        Hooks.stationDivs(book.host(i), divs(a, i));
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
        Hooks.stationLeave(book.host(i), book.army(i), book.iteration(i)); // its soldiers leave the host's city
        book.end(i);
    }

    // ---------------------------------------------------------------- the soldiers in the host's city (SOS-234)

    /**
     * A stationed army's divisions for the host's city: each one's player, its world army (index and iteration), its
     * place in that army, and its people as the city's soldiers. Built in the world part, the same on every PC; carried
     * to the host's city by a transaction ({@code TxnKind.STATION_DIVS}).
     */
    public static final class Fighters {
        public final int[] owners;
        public final int[] armies;
        public final int[] iterations;
        /** Each division's key ({@link StationBook#divKey}). */
        public final int[] keys;
        public final java.util.List<game.battle.util.DivGeneration> divs;
        /** The army's tile on the world map: its soldiers come in by the city's entry point nearest it. */
        public final int wx;
        public final int wy;

        Fighters(int[] owners, int[] armies, int[] iterations, int[] keys, java.util.List<game.battle.util.DivGeneration> divs,
                int wx, int wy) {
            this.owners = owners;
            this.armies = armies;
            this.iterations = iterations;
            this.keys = keys;
            this.divs = divs;
            this.wx = wx;
            this.wy = wy;
        }

        public int size() {
            return divs.size();
        }
    }

    /**
     * In the world part, on every PC: a stationed army's divisions with men in the host's city, as the world holds them
     * now (its men, their equipment and training, which its own supplies keep up), for that city (owner, 2026-11-19).
     */
    private Fighters divs(WArmy a, int i) {
        java.util.List<game.battle.util.DivGeneration> divs = new java.util.ArrayList<>();
        int[] keys = new int[a.divs().size()];
        for (int di = 0; di < a.divs().size(); di++) {
            ADDiv d = a.divs().get(di);
            if (d.men() > 0 && book.inCity(i, key(d))) {
                keys[divs.size()] = key(d);
                divs.add(d.generate());
            }
        }
        int n = divs.size();
        int[] owners = new int[n];
        int[] armies = new int[n];
        int[] iterations = new int[n];
        java.util.Arrays.fill(owners, a.faction().index());
        java.util.Arrays.fill(armies, a.armyIndex());
        java.util.Arrays.fill(iterations, a.iteration());
        return new Fighters(owners, armies, iterations, java.util.Arrays.copyOf(keys, n), divs, a.ctx(), a.cty());
    }

    /**
     * Between steps or in the world part: whether an army of {@code owner}'s stationed at {@code host}'s capital fights
     * for it against {@code enemy} (a faction's index; below 0 for raiders and rebels): its player isn't frozen or
     * removed and is at war with the enemy. The mod asks such a player whether they watch the invaded city.
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
     * {@code InputKind.STATION_RESOLVE} in the world part: a stationed division's world division loses the men who died
     * in the host's city since the last report and takes its soldiers' experience, as a local battle's does. Men who
     * joined it since stay. An army or a division gone since has nothing to lose.
     */
    public static void resolve(int army, int iteration, int key, int dead, double experience) {
        WArmy a = Args.army(army, iteration);
        for (int di = 0; a != null && di < a.divs().size(); di++) {
            ADDiv d = a.divs().get(di);
            if (key(d) == key) {
                d.resolve(sosdet.game.station.StationedDivs.survivors(d.men(), dead), experience);
                return;
            }
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

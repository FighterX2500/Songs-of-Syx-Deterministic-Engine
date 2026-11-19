package sosdet.game;

import java.io.IOException;

import game.GAME;
import game.battle.div.Div;
import game.battle.util.DivGeneration;
import game.save.Savable;
import init.constant.C;
import init.constant.Config;
import init.type.CAUSE_LEAVES;
import init.type.HTYPES;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.room.main.throne.THRONE;
import settlement.stats.STATS;
import settlement.stats.colls.StatsBattle.StatTraining;
import settlement.stats.equip.EquipBattle;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.sets.ArrayList;
import sosdet.game.station.StationedDivs;
import world.army.AD;

/**
 * A stationed ally's soldiers in this city (SOS-234, ADR 0011; owner 2026-11-19): the divisions of army 0 that hold
 * them for as long as their army is stationed here ({@link StationedDivs}). They come in when the stationing starts,
 * take their men, equipment and training from their world army at every refresh, and leave when it ends; each time,
 * the dead since the last report and the soldiers' experience go back to the world army. City state, so not in the
 * world save ({@link GameWorldLoad#KEYS}): a world load or a resync leaves it as it is, and a view's copy of the city
 * loads it with the city. Its section is in the "before" list, so it loads ahead of the city's people (their
 * population count reads it). A save without it (any one before SOS-234) has none.
 *
 * <p>Everything here runs in the city tail of the city that holds it, on every PC that runs that city (its owner's,
 * and its viewers' alike), from the same state at the same step.
 */
public final class GameStationedDivs extends Savable {

    static final String KEY = "SOSDET_STATIONED_DIVS";
    private static final int VERSION = 2;
    /** After the section: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53445344; // "SDSD"
    /** Steps between clearing the stationed soldiers' needs: they don't eat, drink or shop in the host's city. */
    private static final int NEEDS_EVERY = 60;

    private StationedDivs divs = new StationedDivs(Config.battle().DIVISIONS_PER_ARMY);
    /**
     * By division of army 0: the men the world counts in it, the dead reported so far taken off. The soldiers alive
     * fewer than this died since the last report.
     */
    private int[] spawned = new int[Config.battle().DIVISIONS_PER_ARMY];
    private int steps;

    GameStationedDivs() {
        super(KEY);
    }

    /** This city's stationed soldiers. */
    public static GameStationedDivs get() {
        return GameHumans.get().stationedDivs();
    }

    /** The divisions of army 0 that hold them. */
    public StationedDivs divs() {
        return divs;
    }

    /** No division holds stationed soldiers: the city tail does nothing. */
    public boolean idle() {
        return divs.empty();
    }

    // ---------------------------------------------------------------- save

    @Override
    protected void save(FilePutter file) {
        file.i(VERSION);
        int[] v = divs.toInts();
        file.i(v.length);
        for (int i : v) {
            file.i(i);
        }
        file.i(spawned.length);
        for (int i : spawned) {
            file.i(i);
        }
        file.i(END);
    }

    @Override
    protected void load(FileGetter file) throws IOException {
        int version = file.i();
        if (version != VERSION && version != 1) {
            throw new IOException("SOSDET: a stationed divisions section of version " + version + ", this engine reads "
                    + VERSION + " and 1");
        }
        int n = file.i();
        if (n < 0 || n > file.remainingInts()) {
            throw new IOException("SOSDET: a stationed divisions section of " + n + " ints");
        }
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = file.i();
        }
        divs = StationedDivs.fromInts(v);
        if (version == 1) {
            file.i(); // the fight's invasion ref: version 1 only kept them during an invasion
        }
        int slots = file.i();
        if (slots != divs.slots()) {
            throw new IOException("SOSDET: " + slots + " stationed division counts for " + divs.slots() + " divisions");
        }
        spawned = new int[slots];
        for (int i = 0; i < slots; i++) {
            spawned[i] = file.i();
        }
        if (version == 1) {
            int waiting = file.i(); // the divisions waiting for the invaders: the next refresh brings them in now
            if (waiting < 0 || waiting > divs.slots()) {
                throw new IOException("SOSDET: " + waiting + " stationed divisions waiting");
            }
            for (int k = 0; k < waiting * 4; k++) {
                file.i();
            }
            for (int k = 0; k < waiting; k++) {
                new DivGeneration(file);
            }
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a stationed divisions section without its end marker");
        }
    }

    // ---------------------------------------------------------------- the city tail

    /**
     * {@code TxnKind.STATION_DIVS}: one stationed army's divisions in the city as the world holds them now. A division
     * not in the city yet comes in, mustered, at the city's entry point nearest the army (owner, 2026-11-19); one
     * already here reports its dead and its soldiers' experience, then takes the world's men (new soldiers come in at
     * that entry point, or soldiers leave), equipment and training. A division of that army the world no longer sends
     * leaves. One with no free division left stays out until a refresh finds one.
     */
    public void arrive(GameStations.Fighters f) {
        if (f == null || f.size() == 0) {
            return; // an army with no men in the city sends nothing: its soldiers here stay until it has men or leaves
        }
        for (int d = 0; d < divs.slots(); d++) {
            if (divs.stationed(d) && divs.army(d) == f.armies[0] && divs.iteration(d) == f.iterations[0]
                    && !sent(f, divs.key(d))) {
                leaveDiv(d);
            }
        }
        COORDINATE entry = entry(f.wx, f.wy);
        for (int k = 0; k < f.size(); k++) {
            int d = find(f.armies[k], f.iterations[k], f.keys[k]);
            if (d >= 0) {
                refresh(d, f.divs.get(k), entry);
                continue;
            }
            Div div = freeDiv();
            if (div == null) {
                break;
            }
            DivGeneration g = f.divs.get(k);
            divs.put(div.index(), f.owners[k], f.armies[k], f.iterations[k], f.keys[k]);
            place(g, div, entry);
            spawned[div.index()] = div.menNrOf();
        }
    }

    /**
     * {@code TxnKind.STATION_CAMP} (owner, 2026-11-19): the division of {@code key} of that army leaves the city for the
     * camp: its dead reported, its soldiers gone, the division the host's again.
     */
    public void toCamp(int army, int iteration, int key) {
        int d = find(army, iteration, key);
        if (d >= 0) {
            leaveDiv(d);
        }
    }

    /** The farthest tile, along either axis, from an entry point a stationed division may leave the city by. */
    public static final int CAMP_TILES = 3;

    /**
     * Between steps, for the division popup's Leave for the camp (owner, 2026-11-19): whether a soldier of army 0's
     * division {@code d} stands within {@link #CAMP_TILES} of one of the city's active entry points. No allocation.
     */
    public boolean nearEntry(int d) {
        if (!divs.stationed(d)) {
            return false;
        }
        snake2d.util.sets.LIST<settlement.entry.EntryPoints.EntryPoint> points = SETT.ENTRY().points.active();
        if (points.size() == 0) {
            return false;
        }
        for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
            if (!(e instanceof Humanoid) || e.isRemoved() || ((Humanoid) e).division() == null
                    || ((Humanoid) e).division().index() != d) {
                continue;
            }
            Humanoid h = (Humanoid) e;
            int tx = h.physics.tileC().x();
            int ty = h.physics.tileC().y();
            for (int p = 0; p < points.size(); p++) {
                COORDINATE c = points.get(p).coo();
                if (Math.abs(c.x() - tx) <= CAMP_TILES && Math.abs(c.y() - ty) <= CAMP_TILES) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The city's reachable entry point nearest the world tile {@code wx}, {@code wy} (owner, 2026-11-19), the same on
     * every PC that runs the city; the throne when the city has none.
     */
    private static COORDINATE entry(int wx, int wy) {
        if (SETT.ENTRY().points.reachable().size() > 0) {
            return SETT.ENTRY().points.reachable(wx, wy).coo();
        }
        return THRONE.coo();
    }

    /** {@code TxnKind.STATION_LEAVE}: the stationing of that army ended; its soldiers leave the city. */
    public void leave(int army, int iteration) {
        for (int d = 0; d < divs.slots(); d++) {
            if (divs.stationed(d) && divs.army(d) == army && divs.iteration(d) == iteration) {
                leaveDiv(d);
            }
        }
    }

    /** Every step, in the city tail of {@code host}'s city, while {@link #idle} isn't true. */
    public void cityStep(int host) {
        for (int d = 0; d < divs.slots(); d++) {
            if (divs.stationed(d)) {
                Div div = GAME.ARMIES().player().divisions().get(d);
                if (div.info.men() != div.menNrOf()) {
                    div.info.menSet(div.menNrOf()); // the hook keeps the city's recruits out
                }
            }
        }
        if (++steps % NEEDS_EVERY == 0) {
            ENTITY[] all = SETT.ENTITIES().getAllEnts();
            for (int i = 0; i < all.length; i++) {
                if (all[i] instanceof Humanoid) {
                    Humanoid h = (Humanoid) all[i];
                    if (h.division() != null && divs.stationed(h.division().index())) {
                        STATS.NEEDS().clear(h.indu());
                    }
                }
            }
        }
    }

    private static boolean sent(GameStations.Fighters f, int key) {
        for (int k = 0; k < f.size(); k++) {
            if (f.keys[k] == key) {
                return true;
            }
        }
        return false;
    }

    private int find(int army, int iteration, int key) {
        for (int d = 0; d < divs.slots(); d++) {
            if (divs.stationed(d) && divs.army(d) == army && divs.iteration(d) == iteration && divs.key(d) == key) {
                return d;
            }
        }
        return -1;
    }

    /** Division {@code d}'s soldiers alive, in the entities' order, the same on every PC that runs the city. */
    private static java.util.List<Humanoid> soldiers(int d) {
        java.util.List<Humanoid> out = new java.util.ArrayList<>();
        for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
            if (e instanceof Humanoid && !e.isRemoved()) {
                Humanoid h = (Humanoid) e;
                if (h.division() != null && h.division().index() == d) {
                    out.add(h);
                }
            }
        }
        return out;
    }

    /**
     * The world counts the soldiers who died since the last report off division {@code d} and takes their experience.
     * Nothing while nobody died: a player's own division ({@code WDivStored}) re-registers every man at a resolve, which
     * a quiet refresh mustn't repeat; soldiers gain experience only in a fight, where men fall.
     */
    private void report(int d, java.util.List<Humanoid> alive) {
        int dead = Math.max(0, spawned[d] - alive.size());
        if (dead == 0) {
            return;
        }
        double experience = 0;
        for (Humanoid h : alive) {
            experience += STATS.BATTLE().COMBAT_EXPERIENCE.indu().getD(h.indu());
        }
        double mean = alive.isEmpty() ? 0 : experience / alive.size();
        sosdet.Hooks.stationResolve(divs.owner(d), new StationedDivs.Resolve(divs.army(d), divs.iteration(d),
                divs.key(d), dead, mean).toBytes());
        spawned[d] -= dead;
    }

    /**
     * Division {@code d} takes its world division's men, equipment and training ({@code g}, made before this city's
     * report of its dead reached the world): its men less the dead reported now.
     */
    private void refresh(int d, DivGeneration g, COORDINATE entry) {
        java.util.List<Humanoid> alive = soldiers(d);
        int unreported = Math.max(0, spawned[d] - alive.size());
        report(d, alive);
        int target = Math.max(0, g.indus.length - unreported);
        Div div = GAME.ARMIES().player().divisions().get(d);
        for (int k = alive.size() - 1; k >= target; k--) {
            leaveCity(alive.get(k)); // the world army lost them: deserters, the starved, the fallen of a world battle
        }
        int kept = Math.min(alive.size(), target);
        for (int k = 0; k < kept; k++) {
            equip(alive.get(k), g.indus[k]);
        }
        if (target > kept) {
            int[] at = spot(entry, (int) Math.ceil((target - kept) / 5.0));
            for (int k = kept; k < target && at != null; k++) {
                int x = at[0] + (k - kept) / 5;
                int y = at[1] + (k - kept) % 5;
                Humanoid h = new Humanoid(x * C.TILE_SIZE + C.TILE_SIZEH, y * C.TILE_SIZE + C.TILE_SIZEH, g.race(),
                        HTYPES.SOLDIER(), null);
                if (h.isRemoved()) {
                    continue;
                }
                STATS.NEEDS().clear(h.indu());
                h.indu().copyFrom(g.indus[k]);
                equip(h, g.indus[k]);
                h.setDivision(div); // mustered: they walk to their place in the division
            }
        }
        div.info.menSet(div.menNrOf());
        spawned[d] = div.menNrOf();
    }

    /** A soldier takes the world's equipment and training of the man it stands for. */
    private static void equip(Humanoid h, settlement.stats.Induvidual from) {
        STATS.BATTLE().basicTraining.setD(h.indu(), 1.0);
        for (StatTraining t : STATS.BATTLE().TRAINING_ALL) {
            t.stat.indu().set(h.indu(), t.stat.indu().get(from));
        }
        for (EquipBattle m : STATS.EQUIP().BATTLE_ALL()) {
            m.set(h.indu(), m.get(from));
        }
    }

    /** Out of its division first, so the population count stays balanced, then out of the city. */
    private static void leaveCity(Humanoid h) {
        h.setDivision(null);
        h.kill(false, CAUSE_LEAVES.ARMY());
    }

    /** Division {@code d}'s soldiers leave the city, its dead and their experience reported; it is the host's again, empty. */
    private void leaveDiv(int d) {
        java.util.List<Humanoid> alive = soldiers(d);
        report(d, alive);
        for (Humanoid h : alive) {
            leaveCity(h);
        }
        spawned[d] = 0;
        Div div = GAME.ARMIES().player().divisions().get(d);
        div.settings().musteringSet(false);
        div.info.menSet(0);
        divs.clear(d);
    }

    private Div freeDiv() {
        for (Div d : GAME.ARMIES().player().divisions()) {
            if (d.info.men() == 0 && d.menNrOf() == 0 && AD.cityDivs().get(d).men() == 0 && !divs.stationed(d.index())
                    && AD.cityDivs().attachedArmy(d) == null) {
                return d;
            }
        }
        return null;
    }

    /**
     * As {@code DivDeployer.place} does for invaders: rows of five, near the entry point {@code entry}, mustered. The
     * host's division keeps its own name, banner, race and targets: the people bring their own training and equipment.
     */
    private static void place(DivGeneration g, Div div, COORDINATE entry) {
        int w = (int) Math.ceil(g.indus.length / 5.0);
        int[] at = spot(entry, w);
        if (at == null) {
            return;
        }
        div.settings().musteringSet(true);
        div.info.menSet(g.indus.length);
        int left = g.indus.length;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < 5 && left > 0; y++) {
                left--;
                int cx = (at[0] + x) * C.TILE_SIZE + C.TILE_SIZEH;
                int cy = (at[1] + y) * C.TILE_SIZE + C.TILE_SIZEH;
                Humanoid h = new Humanoid(cx, cy, g.race(), HTYPES.SOLDIER(), null);
                if (h.isRemoved()) {
                    continue;
                }
                STATS.NEEDS().clear(h.indu());
                h.indu().copyFrom(g.indus[left]);
                STATS.BATTLE().basicTraining.setD(h.indu(), 1.0);
                for (EquipBattle m : STATS.EQUIP().BATTLE_ALL()) {
                    m.set(h.indu(), m.get(g.indus[left]));
                }
                h.setDivision(div);
            }
        }
        div.info.menSet(div.menNrOf());
        int x1 = at[0] * C.TILE_SIZE + C.TILE_SIZEH;
        int y1 = at[1] * C.TILE_SIZE + C.TILE_SIZEH;
        int x2 = x1 + w * C.TILE_SIZE;
        GAME.ARMIES().placer.deploy(div, x1, x2, y1, y1);
        ArrayList<Div> one = new ArrayList<>(1);
        one.add(div);
        GAME.ARMIES().initAndTeleport(one);
        GAME.ARMIES().placer.deploy(div, x1, x2, y1, y1);
    }

    /** The open block of {@code w} by 5 tiles nearest {@code t}, in rings, the same on every PC; null for none. */
    private static int[] spot(COORDINATE t, int w) {
        if (t == null) {
            return null;
        }
        for (int r = 0; r < 80; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) == r && open(t.x() + dx, t.y() + dy, w)) {
                        return new int[] {t.x() + dx, t.y() + dy};
                    }
                }
            }
        }
        return null;
    }

    private static boolean open(int sx, int sy, int w) {
        for (int x = sx; x < sx + w; x++) {
            for (int y = sy; y < sy + 5; y++) {
                if (!SETT.IN_BOUNDS(x, y) || SETT.PATH().availability.get(x, y).isSolid(GAME.ARMIES().player())) {
                    return false;
                }
                for (ENTITY e : SETT.ENTITIES().getAtTile(x, y)) {
                    if (e instanceof Humanoid && ((Humanoid) e).division() != null) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Applies a {@code STATION_RESOLVE} input's bytes ({@link StationedDivs.Resolve}) in the world part. */
    public static void applyResolve(byte[] data) {
        StationedDivs.Resolve r = StationedDivs.Resolve.of(data);
        GameStations.resolve(r.army(), r.iteration(), r.key(), r.dead(), r.experience());
    }
}

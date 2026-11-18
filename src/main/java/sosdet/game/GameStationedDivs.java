package sosdet.game;

import java.io.IOException;
import java.lang.reflect.Field;

import game.GAME;
import game.battle.div.Div;
import game.battle.util.DivGeneration;
import game.save.Savable;
import init.constant.C;
import init.constant.Config;
import init.type.CAUSE_LEAVES;
import init.type.HTYPES;
import settlement.battle.invasion.InvasionSpec;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.room.main.throne.THRONE;
import settlement.stats.STATS;
import settlement.stats.equip.EquipBattle;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.sets.ArrayList;
import sosdet.game.station.StationedDivs;
import world.army.AD;

/**
 * A stationed ally's soldiers in this city's fight (SOS-234, ADR 0011): the divisions waiting for the invaders to
 * deploy, and the divisions of army 0 that hold them while the fight lasts ({@link StationedDivs}). City state, so not
 * in the world save ({@link GameWorldLoad#KEYS}): a world load or a resync leaves it as it is, and a view's copy of the
 * city loads it with the city. A save without it (any one before SOS-234) has none.
 *
 * <p>Everything here runs in the city tail of the city that holds it, on every PC that runs that city (its owner's,
 * and its viewers' alike), from the same state at the same step.
 */
public final class GameStationedDivs extends Savable {

    static final String KEY = "SOSDET_STATIONED_DIVS";
    private static final int VERSION = 1;
    /** After the section: vanilla's FileGetter.d refuses a file's last 8 bytes, and this may be the save's last section. */
    private static final int END = 0x53445344; // "SDSD"
    /** Steps between clearing the stationed soldiers' needs: they don't eat, drink or shop in the host's city. */
    private static final int NEEDS_EVERY = 60;
    private static final String GAME_VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Field SPEC_REF = specField("ref");
    private static final Field SPEC_ATTACKABLE = specField("canBeAttacked");

    private StationedDivs divs = new StationedDivs(Config.battle().DIVISIONS_PER_ARMY);
    /** The fight's invasion in this city, by its reference; -1 for none. */
    private int ref = -1;
    /** The stationed divisions that wait for the invaders to deploy; null when none wait. */
    private GameStations.Fighters pending;
    /** The men each stationed division came in with, by division of army 0. */
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

    /** Nothing waits and no division holds stationed soldiers: the city tail does nothing. */
    public boolean idle() {
        return pending == null && divs.empty();
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
        file.i(ref);
        file.i(spawned.length);
        for (int i : spawned) {
            file.i(i);
        }
        int n = pending == null ? 0 : pending.size();
        file.i(n);
        for (int k = 0; k < n; k++) {
            file.i(pending.owners[k]);
            file.i(pending.armies[k]);
            file.i(pending.iterations[k]);
            file.i(pending.places[k]);
            pending.divs.get(k).save(file);
        }
        file.i(END);
    }

    @Override
    protected void load(FileGetter file) throws IOException {
        int version = file.i();
        if (version != VERSION) {
            throw new IOException("SOSDET: a stationed divisions section of version " + version + ", this engine reads "
                    + VERSION);
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
        ref = file.i();
        int slots = file.i();
        if (slots != divs.slots()) {
            throw new IOException("SOSDET: " + slots + " stationed division counts for " + divs.slots() + " divisions");
        }
        spawned = new int[slots];
        for (int i = 0; i < slots; i++) {
            spawned[i] = file.i();
        }
        int waiting = file.i();
        if (waiting < 0 || waiting > divs.slots()) {
            throw new IOException("SOSDET: " + waiting + " stationed divisions waiting");
        }
        pending = null;
        if (waiting > 0) {
            int[] owners = new int[waiting];
            int[] armies = new int[waiting];
            int[] iterations = new int[waiting];
            int[] places = new int[waiting];
            java.util.List<DivGeneration> gens = new java.util.ArrayList<>();
            for (int k = 0; k < waiting; k++) {
                owners[k] = file.i();
                armies[k] = file.i();
                iterations[k] = file.i();
                places[k] = file.i();
                gens.add(new DivGeneration(file));
            }
            pending = new GameStations.Fighters(owners, armies, iterations, places, gens);
        }
        if (file.i() != END) {
            throw new IOException("SOSDET: a stationed divisions section without its end marker");
        }
    }

    // ---------------------------------------------------------------- the city tail

    /**
     * {@code TxnKind.STATION_DIVS}, or the assault's start: the stationed divisions for the invasion {@code spec} wait
     * until its invaders deploy. A fight already under way here keeps its own: a second one gets none.
     */
    public void arrive(Object spec, GameStations.Fighters f) {
        if (f == null || !idle()) {
            return;
        }
        try {
            ref = SPEC_REF.getInt(spec);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read InvasionSpec.ref" + GAME_VERSION, e);
        }
        pending = f;
    }

    /** Every step, in the city tail of {@code host}'s city, while {@link #idle} isn't true. */
    public void cityStep(int host) {
        InvasionSpec spec = SETT.INVADOR().spec(ref);
        if (spec == null) {
            end(); // the invasion is over: won, lost, called off or stopped
            return;
        }
        if (pending != null && !attackable(spec) && STATS.POP().pop(HTYPES.ENEMY()) > 0) {
            // the invaders deploy: the stationed soldiers take their place in the city. Not a won Attack's end, which
            // also closes the invasion to attacks but leaves no invader in the city
            spawn(host);
        }
        for (int d = 0; d < divs.slots(); d++) {
            if (divs.stationed(d)) {
                Div div = GAME.ARMIES().player().divisions().get(d);
                if (div.info.men() != div.menNrOf()) {
                    div.info.menSet(div.menNrOf()); // the battle AI keeps them; the hook keeps the city's recruits out
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

    private static boolean attackable(InvasionSpec spec) {
        try {
            return SPEC_ATTACKABLE.getBoolean(spec);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read InvasionSpec.canBeAttacked" + GAME_VERSION, e);
        }
    }

    /**
     * The waiting divisions take free divisions of army 0, as {@code SOLDIER} people (the battle map's kind, battle AI
     * only: the rig's probe SP1, 2026-11-18), mustered beside the throne. A division whose army no longer stands at
     * {@code host}'s capital stays out, and one that lost men since (an Attack before the deploy) comes with as many as
     * it has now. One with no free division left stays out.
     */
    private void spawn(int host) {
        GameStations.Fighters f = pending;
        pending = null;
        for (int k = 0; k < f.size(); k++) {
            int men = GameStations.men(f.armies[k], f.iterations[k], f.places[k], host);
            if (men <= 0) {
                continue;
            }
            Div div = freeDiv();
            if (div == null) {
                break;
            }
            DivGeneration g = f.divs.get(k);
            if (g.indus.length > men) {
                g.setMen(men);
            }
            divs.put(div.index(), f.owners[k], f.armies[k], f.iterations[k], f.places[k]);
            place(g, div);
            spawned[div.index()] = div.menNrOf();
        }
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
     * As {@code DivDeployer.place} does for invaders: rows of five, near the throne, mustered. The host's division keeps
     * its own name, banner, race and targets: the people bring their own training and equipment.
     */
    private static void place(DivGeneration g, Div div) {
        int w = (int) Math.ceil(g.indus.length / 5.0);
        int[] at = spot(w);
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

    /** The open block of {@code w} by 5 tiles nearest the throne, in rings, the same on every PC; null for none. */
    private static int[] spot(int w) {
        COORDINATE t = THRONE.coo();
        if (t == null) {
            return null;
        }
        for (int r = 3; r < 80; r++) {
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

    /**
     * The fight is over: each stationed division's dead and its survivors' mean experience go to its world division in
     * the city's next report ({@code InputKind.STATION_RESOLVE}), its people leave the city, and the division is the
     * host's again, empty. Divisions that waited and never came in are dropped: their world divisions are untouched.
     */
    private void end() {
        int[] men = new int[divs.slots()];
        double[] experience = new double[divs.slots()];
        java.util.List<Humanoid> leaving = new java.util.ArrayList<>();
        for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
            if (e instanceof Humanoid && !e.isRemoved()) {
                Humanoid h = (Humanoid) e;
                if (h.division() != null && divs.stationed(h.division().index())) {
                    men[h.division().index()]++;
                    experience[h.division().index()] += STATS.BATTLE().COMBAT_EXPERIENCE.indu().getD(h.indu());
                    leaving.add(h);
                }
            }
        }
        for (Humanoid h : leaving) {
            h.kill(false, CAUSE_LEAVES.ARMY());
        }
        for (int d = 0; d < divs.slots(); d++) {
            if (!divs.stationed(d)) {
                continue;
            }
            double mean = men[d] == 0 ? 0 : experience[d] / men[d];
            sosdet.Hooks.stationResolve(divs.owner(d), new StationedDivs.Resolve(divs.army(d), divs.iteration(d),
                    divs.place(d), spawned[d] - men[d], mean).toBytes());
            spawned[d] = 0;
            Div div = GAME.ARMIES().player().divisions().get(d);
            div.settings().musteringSet(false);
            div.info.menSet(0);
            divs.clear(d);
        }
        pending = null;
        ref = -1;
        steps = 0;
    }

    /** Applies a {@code STATION_RESOLVE} input's bytes ({@link StationedDivs.Resolve}) in the world part. */
    public static void applyResolve(byte[] data) {
        StationedDivs.Resolve r = StationedDivs.Resolve.of(data);
        GameStations.resolve(r.army(), r.iteration(), r.place(), r.dead(), r.experience());
    }

    private static Field specField(String name) {
        try {
            Field f = InvasionSpec.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("SOSDET: no InvasionSpec." + name + GAME_VERSION, e);
        }
    }
}

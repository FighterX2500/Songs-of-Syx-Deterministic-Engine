package sosdet.game;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import game.battle.util.DivGeneration;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.player.Player;
import init.trade.TR;
import init.trade.TRADE_TYPE;
import snake2d.PathTile;
import snake2d.util.datatypes.DIR;
import sosdet.game.cmd.Args;
import util.GUTIL;
import world.WORLD;
import world.army.AD;
import world.entity.army.WArmy;
import world.entity.caravan.Shipment;
import world.region.RD;

/**
 * The sally against an invasion of a human's capital (SOS-145): vanilla's {@code Attack} in the invasion message, run as
 * a local battle. Patch 99 adds three static methods to the package-private {@code Attack}; the engine compiles against
 * the shipped game, so it calls them through handles resolved once, on first use (the class is not loaded at the menu).
 */
public final class GameSally {

    private static final String ATTACK = "settlement.battle.invasion.Attack";
    private static final String BUILD = " (patch 99; V71, build 71.44)";

    private GameSally() {
    }

    /** In the world part, as the defender, on every PC: the armies the sally takes, by army index. */
    public static int[] armies() {
        return (int[]) call(Handles.ARMIES);
    }

    /** The battle PC, at the freeze step: generates the sally's battle; false when the invasion can't be attacked now. */
    public static boolean generate(int ref) {
        return (Boolean) call(Handles.GENERATE, ref);
    }

    /** The defender's city tail: vanilla's after-battle, from the result command's arrays. */
    public static void after(int ref, Object[] result) {
        call(Handles.AFTER, ref, result);
    }

    // ---------------------------------------------------------------- allies in the sally (SOS-232)

    /** Armies near the defender's capital: each army's index, its human's slot and its distance along the map. */
    public static final class Near {
        public final int[] armies;
        public final int[] owners;
        public final double[] distances;

        Near(int[] armies, int[] owners, double[] distances) {
            this.armies = armies;
            this.owners = owners;
            this.distances = distances;
        }
    }

    /**
     * In the world part, as the defender, on every PC: every other human's army with men within {@code reach} of the
     * defender's capital along the map, found as vanilla's Attack finds the defender's own ({@code Attack.armies}).
     */
    public static Near near(double reach) {
        Faction d = FACTIONS.player();
        int max = WORLD.ENTITIES().armies.max();
        int[] armies = new int[max];
        int[] owners = new int[max];
        double[] distances = new double[max];
        boolean[] seen = new boolean[max];
        int n = 0;
        GUTIL.flooder().init(GameSally.class);
        GUTIL.flooder().pushSloppy(d.cx(), d.cy(), 0);
        while (GUTIL.flooder().hasMore()) {
            PathTile t = GUTIL.flooder().pollSmallest();
            if (t.getValue() > reach) {
                break;
            }
            for (WArmy a : WORLD.ENTITIES().armies.fillTile(t.x(), t.y())) {
                if (a == null || seen[a.armyIndex()] || AD.men(null).get(a) == 0 || !(a.faction() instanceof Player)
                        || a.faction() == d) {
                    continue;
                }
                seen[a.armyIndex()] = true;
                armies[n] = a.armyIndex();
                owners[n] = a.faction().index();
                distances[n++] = t.getValue();
            }
            for (int di = 0; di < DIR.ALL.size(); di++) {
                DIR dir = DIR.ALL.get(di);
                if (WORLD.PATH().map.can(t, dir)) {
                    GUTIL.flooder().pushSmaller(t, dir, t.getValue() + dir.tileDistance());
                }
            }
        }
        GUTIL.flooder().done();
        return new Near(java.util.Arrays.copyOf(armies, n), java.util.Arrays.copyOf(owners, n),
                java.util.Arrays.copyOf(distances, n));
    }

    /** World state: the humans in slots {@code a} and {@code b} are allies (vanilla's {@code Util.allies}). */
    public static boolean allied(int a, int b) {
        return DIP.get(FACTIONS.getByIndex(a), FACTIONS.getByIndex(b)).ally;
    }

    /** World state: the human {@code h} is at war with the invader's faction; raiders and rebels (-1) with everyone. */
    public static boolean atWar(int h, int invader) {
        return invader < 0 || DIP.WAR().is(FACTIONS.getByIndex(invader), FACTIONS.getByIndex(h));
    }

    /** In the world part, as the defender: its capital's garrison divisions, which vanilla's Attack puts in first. */
    public static int garrison() {
        return RD.MILITARY().divisions(FACTIONS.player().capitolRegion()).size();
    }

    /** In the world part, as the defender: its garrison's strength, as the game measures a region's. */
    public static int garrisonPower() {
        return (int) RD.MILITARY().power.getD(FACTIONS.player().capitolRegion());
    }

    /** World state: an army's divisions and strength ({@code AD.power}); 0 for an army gone. */
    public static int divisions(int army) {
        WArmy a = WORLD.ENTITIES().armies.get(army);
        return a == null || !a.added() ? 0 : a.divs().size();
    }

    public static int power(int army) {
        WArmy a = WORLD.ENTITIES().armies.get(army);
        return a == null || !a.added() ? 0 : AD.power().get(a);
    }

    public static int iteration(int army) {
        return WORLD.ENTITIES().armies.get(army).iteration();
    }

    /**
     * The battle PC, in the world part at the sally's Engage: the divisions of an ally's {@code armies}, up to
     * {@code count}, built where the world reads every human's divisions alike, as a world battle's Engage builds its
     * sides. An army gone since adds none.
     */
    public static java.util.List<DivGeneration> generate(int[] armies, int[] iterations, int count) {
        java.util.List<DivGeneration> divs = new java.util.ArrayList<>();
        for (int k = 0; k < armies.length && divs.size() < count; k++) {
            WArmy a = Args.army(armies[k], iterations[k]);
            for (int di = 0; a != null && di < a.divs().size() && divs.size() < count; di++) {
                divs.add(a.divs().get(di).generate());
            }
        }
        return divs;
    }

    /**
     * Every PC, in the world part at the sally's result: the division in position {@code place} of an ally's armies'
     * divisions gets its survivors, a count and their mean experience. -1 survivors: it wasn't in the battle.
     */
    public static void resolve(int[] armies, int[] iterations, int place, int survivors, double experience) {
        if (survivors < 0) {
            return;
        }
        int p = place;
        for (int k = 0; k < armies.length; k++) {
            WArmy a = Args.army(armies[k], iterations[k]);
            if (a == null) {
                return; // an army gone since: its survivors and the places after it are lost
            }
            if (p < a.divs().size()) {
                a.divs().get(p).resolve(survivors, experience);
                return;
            }
            p -= a.divs().size();
        }
    }

    /**
     * Every PC, in the world part as the defender's report applies: an ally's share of the sally's spoils and captives
     * travels by caravan from the defender's capital to the ally's, as vanilla ships a battle's spoils.
     */
    public static void ship(int defender, int ally, int[] share) {
        Faction from = FACTIONS.getByIndex(defender);
        Faction to = FACTIONS.getByIndex(ally);
        if (!(to instanceof Player) || to.capitolRegion() == null || from.capitolRegion() == null) {
            return; // the ally is gone: its share is lost
        }
        Shipment s = null;
        for (int t = 0; t < share.length && t < TR.ALL().size(); t++) {
            if (share[t] <= 0) {
                continue;
            }
            if (s == null) {
                s = WORLD.ENTITIES().caravans.create(from.capitolRegion().cx(), from.capitolRegion().cy(),
                        to.capitolRegion(), TRADE_TYPE.spoils);
            }
            if (s != null) {
                s.loadAndReserve(TR.ALL().get(t), share[t]);
            } else {
                to.buyer(TR.ALL().get(t)).addReserveAndDeliver(share[t], TRADE_TYPE.spoils);
            }
        }
    }

    private static Object call(Method m, Object... args) {
        try {
            return m.invoke(null, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot call Attack." + m.getName() + BUILD, e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: Attack." + m.getName() + " failed", e.getCause());
        }
    }

    /** Resolved on first use. */
    private static final class Handles {

        static final Method ARMIES = method("sosdetArmies");
        static final Method GENERATE = method("sosdetGenerate", int.class);
        static final Method AFTER = method("sosdetAfter", int.class, Object[].class);

        private static Method method(String name, Class<?>... types) {
            try {
                Method m = Class.forName(ATTACK, false, GameSally.class.getClassLoader()).getMethod(name, types);
                m.setAccessible(true); // the class is package-private
                return m;
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new IllegalStateException("SOSDET: no method Attack." + name + " in this game build" + BUILD, e);
            }
        }
    }
}

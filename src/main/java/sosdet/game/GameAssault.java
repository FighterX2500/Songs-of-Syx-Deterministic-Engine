package sosdet.game;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import game.GAME;
import game.time.TIME;
import init.type.HTYPES;
import settlement.battle.invasion.InvasionSpec;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.room.military.artillery.ArtilleryInstance;
import settlement.room.military.artillery.ROOM_ARTILLERY;
import sosdet.Assault;
import sosdet.Hooks;
import sosdet.report.Field;
import world.WORLD;
import world.army.AD;
import world.army.ADDiv;
import world.battle.spec.WBattleSiege;
import world.entity.army.WArmy;

/**
 * The assault on a human's capital in the game (SOS-92 step B): the invasion built from the besieging armies, started
 * in the defender's city, and its outcome in the world. The engine's {@link sosdet.Assault} keeps the state.
 */
public final class GameAssault {

    private static final String VERSION = " (patch 15; V71, build 71.44)";

    private GameAssault() {
    }

    /**
     * In the world part at Storm's step: the besiegers' invasion and their armies' indices, from the siege prompt's
     * sides ({@code ResolverPlayer}'s {@code sosdetAssault}). Built alike on every PC from world state.
     */
    public static Object[] build(WBattleSiege siege) {
        try {
            Method m = siege.getClass().getMethod("sosdetAssault");
            m.setAccessible(true); // a public method of the prompt's anonymous class
            return (Object[]) m.invoke(siege);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: no ResolverPlayer siege sosdetAssault in this game build" + VERSION, e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: building the assault failed", e.getCause());
        }
    }

    /** The men of {@code slot}'s armies among {@code armies}. */
    public static int menOf(int slot, int[] armies) {
        int men = 0;
        for (int i : armies) {
            WArmy a = WORLD.ENTITIES().armies.get(i);
            if (a != null && a.added() && a.faction().index() == slot) {
                men += AD.men(null).get(a);
            }
        }
        return men;
    }

    /** The men in the world's armies at {@code armies}. */
    public static int men(int[] armies) {
        int men = 0;
        for (int i : armies) {
            WArmy a = WORLD.ENTITIES().armies.get(i);
            if (a != null && a.added()) {
                men += AD.men(null).get(a);
            }
        }
        return men;
    }

    /** In the city tail of the defender's city, on every PC that runs it: the invasion starts. Returns its reference. */
    public static int invade(Object spec) {
        return SETT.INVADOR().invade((InvasionSpec) spec, null);
    }

    /**
     * In the city tail of the defender's city, on every PC that runs it, for a player who left or a rig test: the assault's
     * invasion stops now, and its invaders and their artillery leave the city, as {@code Invador.update} clears them after
     * an invasion.
     */
    public static void stop(int ref) {
        SETT.INVADOR().cancel(ref);
        for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
            if (e instanceof Humanoid && ((Humanoid) e).indu().hType() == HTYPES.ENEMY()) {
                ((Humanoid) e).helloMyNameIsInigoMontoyaYouKilledMyFatherPrepareToDie();
            }
        }
        for (ROOM_ARTILLERY b : SETT.ROOMS().ARTILLERY) {
            for (int i = 0; i < b.instancesSize(); i++) {
                ArtilleryInstance ins = b.getInstance(i);
                if (ins.army() == GAME.ARMIES().enemy()) {
                    ins.destroyTile(ins.mX(), ins.mY());
                    i--;
                }
            }
        }
    }

    /** The invaders alive in the city now: the same on every PC that runs it. */
    public static int invaders() {
        return GAME.ARMIES().enemy().men();
    }

    private static void takeLosses(int[] armies, int men, int survivors) {
        if (survivors < 0 || survivors >= men) {
            return;
        }
        for (int i : armies) {
            WArmy a = WORLD.ENTITIES().armies.get(i);
            if (a == null || !a.added()) {
                continue;
            }
            for (int d = a.divs().size() - 1; d >= 0; d--) {
                ADDiv div = a.divs().get(d);
                div.resolve((int) ((long) div.men() * survivors / men));
            }
            if (AD.men(null).get(a) == 0) {
                a.disband();
            }
        }
    }

    /** True while an invasion runs in this PC's city. */
    public static boolean invading() {
        return SETT.INVADOR().invading();
    }

    /**
     * In the world part, every PC, once every view of the assault has thawed: the outcome reaches the world (mod plan
     * step B; the outcome windows are steps C and D). The defender held: the besieging armies are lost, as in vanilla,
     * and the siege ends. The invaders withdrew: the armies stay at the siege, and its wait starts over. The throne
     * taken: the siege goes on until step D's choices exist. In both those cases the armies keep only the survivors of
     * the {@code men} they sent, the same share of each division.
     */
    public static void resolve(int defender, int outcome, int[] armies, int men, int survivors) {
        if (outcome != Assault.HELD) {
            takeLosses(armies, men, survivors);
        }
        if (outcome == Assault.HELD) {
            for (int i : armies) {
                WArmy a = WORLD.ENTITIES().armies.get(i);
                if (a != null && a.added()) {
                    a.disband();
                }
            }
            GameHumans.get().sieges().book().end(defender);
        } else if (outcome == Assault.RETREAT) {
            GameHumans.get().sieges().book().end(defender); // a running siege stays in the book: the new wait replaces it
            GameHumans.get().sieges().book().begin(defender, (int) TIME.currentSecond(),
                    Math.max(0, Hooks.reportI(defender, Field.POP_TOTAL, 0)));
        }
    }
}

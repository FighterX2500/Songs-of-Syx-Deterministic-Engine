package sosdet.game;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * The world's static state that vanilla never clears between games in one process, reset at every load in a session
 * (SOS-35), so a PC that loads in a fresh process and one that played before both start from the same values. One
 * reflected member each (AGENTS.md "Adapter Boundary"); resolved when a load in a session first needs them.
 *
 * <ul>
 * <li>{@code world.battle.Rnd}: the random table the world's battle results draw from (conquests). Its {@code clear}
 * never runs, so a fresh process starts from zeros and a warm one from its last game's draws. Back to zeros.</li>
 * <li>{@code RDMilitaryGar.WDivsCapitol.upI}: the step the capital garrison's supplies were last counted at. Back to
 * -1, so the first count after the load always runs.</li>
 * </ul>
 */
public final class WorldStatics {

    private static final String VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Class<?> RND = className("world.battle.Rnd");
    private static final Field RNDS = field(RND, "rnds");
    private static final Field RI = field(RND, "ri");
    private static final Field SUPPLIES_UP = field(className("world.region.RDMilitaryGar$WDivsCapitol"), "upI");

    private WorldStatics() {
    }

    /** Game thread, at the start of a load in a session. */
    public static void reset() {
        try {
            Arrays.fill((int[]) RNDS.get(null), 0);
            RI.setInt(null, 0);
            SUPPLIES_UP.setInt(null, -1);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot reset the world's static state" + VERSION, e);
        }
    }

    private static Class<?> className(String name) {
        try {
            return Class.forName(name, false, WorldStatics.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SOSDET: no class " + name + " in this game build" + VERSION, e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no field " + owner.getName() + "." + name + " in this game build" + VERSION, e);
        }
    }
}

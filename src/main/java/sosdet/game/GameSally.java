package sosdet.game;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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

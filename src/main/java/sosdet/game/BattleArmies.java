package sosdet.game;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import snake2d.util.sets.LIST;
import world.battle.spec.WBattleResult;
import world.battle.spec.WBattleSide;
import world.battle.spec.WBattleSpec;
import world.entity.army.WArmy;

/**
 * The world armies of an open world-battle prompt, both sides, which wait in place while it is open (SOS-45). What
 * the prompt decides holds its sides in the game's package-private types ({@code ResolverSide}, {@code Side},
 * {@code SideUnit}, and the captured settlement's {@code Conquer}), so they are reached by reflection, resolved once.
 */
public final class BattleArmies {

    private static final String VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Class<?> RESOLVER_SIDE = type("world.battle.ResolverSide");
    private static final Field SIDE_OF = field(RESOLVER_SIDE, "side");
    private static final Field UNITS = field(type("world.battle.Side"), "us");
    private static final Method ARMY = method(type("world.battle.Side$SideUnit"), "a");
    private static final Class<?> CONQUER = type("world.battle.ResolverPlayer$Conquer");
    private static final Field CONQUER_PLAYER = field(CONQUER, "player");
    private static final Field CONQUER_ENEMY = field(CONQUER, "enemy");

    private BattleArmies() {
    }

    /** The indices ({@code WArmy.armyIndex}) of the armies on both sides of what the prompt decides; garrisons aren't armies. */
    public static int[] of(Object decides) {
        Object a;
        Object b;
        if (decides instanceof WBattleSpec) {
            a = side(((WBattleSpec) decides).player);
            b = side(((WBattleSpec) decides).enemy);
        } else if (decides instanceof WBattleResult) {
            a = side(((WBattleResult) decides).player);
            b = side(((WBattleResult) decides).enemy);
        } else if (CONQUER.isInstance(decides)) {
            a = get(CONQUER_PLAYER, decides);
            b = get(CONQUER_ENEMY, decides);
        } else {
            return new int[0];
        }
        int[] armies = new int[count(a) + count(b)];
        int n = fill(a, armies, 0);
        fill(b, armies, n);
        return armies;
    }

    private static Object side(WBattleSide s) {
        return RESOLVER_SIDE.isInstance(s) ? get(SIDE_OF, s) : null;
    }

    private static int count(Object side) {
        int n = 0;
        LIST<?> units = units(side);
        for (int i = 0; units != null && i < units.size(); i++) {
            if (army(units.get(i)) != null) {
                n++;
            }
        }
        return n;
    }

    private static int fill(Object side, int[] armies, int from) {
        LIST<?> units = units(side);
        for (int i = 0; units != null && i < units.size(); i++) {
            WArmy a = army(units.get(i));
            if (a != null) {
                armies[from++] = a.armyIndex();
            }
        }
        return from;
    }

    /** The side's units, or null for a side that isn't the resolver's. */
    private static LIST<?> units(Object side) {
        return side == null ? null : (LIST<?>) get(UNITS, side);
    }

    private static WArmy army(Object unit) {
        try {
            return (WArmy) ARMY.invoke(unit);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SOSDET: Side.SideUnit.a() failed" + VERSION, e);
        }
    }

    private static Object get(Field f, Object owner) {
        try {
            return f.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: reading " + f + " failed" + VERSION, e);
        }
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
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

    private static Method method(Class<?> owner, String name) {
        try {
            Method m = owner.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no method " + owner.getName() + "." + name + " in this game build" + VERSION, e);
        }
    }
}

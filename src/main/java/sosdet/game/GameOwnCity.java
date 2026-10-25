package sosdet.game;

import java.lang.reflect.Field;

import game.GAME;
import game.faction.FACTIONS;
import settlement.main.SETT;
import settlement.recipe.Recipes;
import snake2d.util.sets.LIST;

/**
 * A PC's own city at setup (mod SOS-56): every PC loads the save slot 0's PC built, which holds slot 0's city, and a PC
 * whose human is another makes its own at its capital, as vanilla's finish stage makes the player's
 * ({@code StageFinish}: {@code CreateFromWorldMap(playerX - 1, playerY - 1)}).
 *
 * <p>The city's generation touches one thing the world reads: clearing the recipes re-rolls the NPCs' production boosts
 * ({@code Recipes.clear} → {@code FBoost.randomize}; {@code FBoost.vGet(FactionNPC)}), which only this PC would do. Hook
 * 18 keeps them around every city generation in a session ({@code Hooks.cityGenerateBegin}), so the landing view's
 * Regenerate is safe too. Everything else it writes is the city's or the view's (audit of
 * {@code SETT.CreateFromWorldMap}, 2026-10-25). Outside the step: the city's random draws go to the cosmetic stream.
 */
public final class GameOwnCity {

    private static final String GAME_VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Field FBOOSTS = field(Recipes.class, "fboosts");
    private static final Field MUL = field(boostClass(), "mul");

    private GameOwnCity() {
    }

    /** This PC's city at the capital whose centre is {@code tx, ty}, replacing the loaded one. */
    public static void make(int tx, int ty) {
        // a soldier of the city on a world army is counted in the world's army data by the city's divisions, which the
        // generation resets; a new world has none, and this is for a new world only
        if (FACTIONS.player().armies().all().size() > 0) {
            throw new IllegalStateException("SOSDET: an own city is made at setup, with no armies out ("
                    + FACTIONS.player().armies().all().size() + ")");
        }
        GAME.s().CreateFromWorldMap(tx - 1, ty - 1, false); // hook 18 keeps the NPCs' boosts
    }

    /** The NPCs' production boosts' multipliers, in order. */
    public static double[] boosts() {
        LIST<?> boosts = fboosts();
        double[] kept = new double[boosts.size()];
        for (int i = 0; i < kept.length; i++) {
            kept[i] = mul(boosts.get(i));
        }
        return kept;
    }

    /** Puts back multipliers {@link #boosts} took. */
    public static void restore(double[] kept) {
        LIST<?> boosts = fboosts();
        if (boosts.size() != kept.length) {
            throw new IllegalStateException("SOSDET: " + boosts.size() + " production boosts, " + kept.length + " kept");
        }
        for (int i = 0; i < kept.length; i++) {
            setMul(boosts.get(i), kept[i]);
        }
    }

    private static LIST<?> fboosts() {
        try {
            return (LIST<?>) FBOOSTS.get(SETT.RECIPES());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read Recipes.fboosts" + GAME_VERSION, e);
        }
    }

    private static double mul(Object boost) {
        try {
            return MUL.getDouble(boost);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read FBoost.mul" + GAME_VERSION, e);
        }
    }

    private static void setMul(Object boost, double mul) {
        try {
            MUL.setDouble(boost, mul);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot set FBoost.mul" + GAME_VERSION, e);
        }
    }

    private static Class<?> boostClass() {
        try {
            return Class.forName("settlement.recipe.FBoost"); // package-private in the game
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SOSDET: no class settlement.recipe.FBoost" + GAME_VERSION, e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no field " + owner.getSimpleName() + "." + name + GAME_VERSION, e);
        }
    }
}

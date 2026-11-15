package sosdet.game;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import game.GAME;
import game.event.engine.EVENT_HANDLER;
import game.faction.FACTIONS;
import game.faction.player.BoostCompound;
import game.nobility.NOBLES;
import game.nobility.Noble;
import game.tourism.Review;
import game.tourism.TOURISM;
import settlement.main.SETT;
import settlement.recipe.Recipes;
import snake2d.util.file.SAVABLE;
import snake2d.util.sets.ArrayList;
import snake2d.util.sets.Bitmap1D;
import snake2d.util.sets.LIST;
import util.statistics.HistoryInt;
import view.main.VIEW;
import view.ui.message.Messages;

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
 *
 * <p>A hot-join's newcomer makes its city the same way, on the host's played campaign: what that save holds for the
 * host's city outside {@code SETT} starts afresh ({@link #startAfresh}, SOS-215).
 */
public final class GameOwnCity {

    private static final String GAME_VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Field FBOOSTS = field(Recipes.class, "fboosts");
    private static final Field MUL = field(boostClass(), "mul");
    private static final Field NOBLE_SAVER = field(Noble.class, "saver");
    private static final Field NOBLES_ACTIVE = field(NOBLES.class, "active");
    private static final Field NOBLES_RI = field(NOBLES.class, "ri");
    private static final Field NOBLES_BOS = field(NOBLES.class, "bos");
    private static final Field TOURISM_SELF = field(TOURISM.class, "self");
    private static final Field TOURISM_REVIEWS = field(TOURISM.class, "reviews");
    private static final Field TOURISM_HISTORY = field(TOURISM.class, "history");
    private static final Field TOURISM_PERMIT = field(TOURISM.class, "permit");
    private static final Field TOURISM_SCORE = field(TOURISM.class, "score");
    private static final Field TOURISM_RACES = field(TOURISM.class, "races");
    private static final Field MESSAGES_ALL = field(Messages.class, "all");
    private static final Field MESSAGES_UNREAD = field(Messages.class, "unread");
    /** {@code EVENT_HANDLER.loadFail}: its private {@code clear}, the event in progress and every event's count. */
    private static final Method HANDLER_CLEAR = method(EVENT_HANDLER.class, "loadFail");

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
        startAfresh();
    }

    /**
     * The city's state outside {@code SETT} that the loaded save still holds for the loaded city, started as a new game
     * does (SOS-215): on a hot-join the save is the host's played campaign, whose nobles name the host's citizens (the
     * Nobility panel crashed on one with none; one landing on a new citizen kept the host's office and rank). The
     * nobles, the city's events and its population classes' temporary boosts ({@link GameWorldLoad#clearCityParts}),
     * the scripted event in progress, the tourists' reviews and score, and the messages. A setup's save is a new
     * game's, so there this changes only the messages slot 0's PC got.
     */
    private static void startAfresh() {
        try {
            NOBLES nobles = GAME.NOBLE();
            for (Noble n : nobles.ALL()) {
                ((SAVABLE) NOBLE_SAVER.get(n)).clear();
            }
            ((ArrayList<?>) NOBLES_ACTIVE.get(nobles)).clear();
            NOBLES_RI.setInt(nobles, -1);
            ((BoostCompound<?>) NOBLES_BOS.get(nobles)).clearChache();

            Object tourism = TOURISM_SELF.get(null);
            for (Review r : (Review[]) TOURISM_REVIEWS.get(tourism)) {
                r.clear();
            }
            ((HistoryInt) TOURISM_HISTORY.get(tourism)).clear();
            ((Bitmap1D) TOURISM_PERMIT.get(tourism)).setAll(true);
            TOURISM_SCORE.setDouble(tourism, 0);
            Arrays.fill((double[]) TOURISM_RACES.get(tourism), 0);

            Messages messages = VIEW.messages();
            ((ArrayList<?>) MESSAGES_ALL.get(messages)).clear();
            MESSAGES_UNREAD.setInt(messages, 0);

            HANDLER_CLEAR.invoke(GAME.EVENT());
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: cannot start the new city's nobles, tourists, messages or event"
                    + GAME_VERSION, e);
        }
        GameWorldLoad.clearCityParts();
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

    private static Method method(Class<?> owner, String name) {
        try {
            Method m = owner.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no method " + owner.getSimpleName() + "." + name + GAME_VERSION, e);
        }
    }
}

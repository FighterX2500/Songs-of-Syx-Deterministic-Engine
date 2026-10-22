package sosdet.game;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import game.GAME;
import game.battle.state.BattleState;
import game.battle.thread.general.Strategos2000;
import game.battle.thread.general.offence.Strategos2000UpdaterOffense;
import game.save.GameLoader;
import game.save.GameSaver;
import settlement.main.SETT;
import snake2d.util.file.FilePutter;
import snake2d.util.misc.ACTION;
import view.main.VIEW;

/**
 * Saving and loading a game at a resync step (hook B14), and the state vanilla's save leaves out: the battle's
 * throne timer and progress flags, and the AI general's timers and {@code flanking} value (which only matter when
 * the general runs on every PC, in-step). One reflected member each (AGENTS.md "Adapter Boundary").
 */
public final class GameResync {

    private static final String VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Method SAVE = method(GameSaver.class, "save", FilePutter.class);
    private static final Field THRONE_TIMER = field(BattleState.class, "throneTimer");
    private static final Field DEPLOYING = field(BattleState.class, "deploying");
    private static final Field CONCLUDED = field(BattleState.class, "concluded");
    private static final Field PREV_UPDATE = field(Strategos2000.class, "prevUpdate");
    private static final Field OLD_DIVS = field(Strategos2000.class, "oldDivs");
    private static final Field OFFENSE = field(Strategos2000.class, "offense");
    private static final Field CONTEXT = field(Strategos2000UpdaterOffense.class, "c");
    private static final Field FLANKING = field(className("game.battle.thread.general.offence.Context"), "flanking");
    /** The save's own size limit, as vanilla's GameSaver uses. */
    private static final int SAVE_BYTES_PER_TILE = 120;
    private static final ACTION NO_PROGRESS = () -> { };

    private GameResync() {
    }

    /** Saves the running game to {@code file} through the game's own save pipeline, without the save screen. */
    public static void save(Path file) {
        try {
            Files.deleteIfExists(file);
            FilePutter putter = new FilePutter(file, SAVE_BYTES_PER_TILE * SETT.TAREA);
            SAVE.invoke(GAME.saver(), putter);
            if (!putter.zip(NO_PROGRESS)) {
                throw new IllegalStateException("SOSDET: cannot write the resync save " + file);
            }
        } catch (IOException | IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot save the resync to " + file, e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: the game's save failed for " + file, e.getCause());
        }
    }

    /** Loads {@code file}; once the game is set, runs {@code afterSet}, then restores {@code state} (from {@link #capture}). */
    public static void load(Path file, Runnable afterSet, byte[] state) {
        GAME.BATTLE_THREADS().pause(); // the game being replaced keeps no job running (the host's general)
        new GameLoader(file) {
            @Override
            public void doAfterSet() {
                afterSet.run();
                apply(state);
            }
        }.set();
    }

    /** What the save leaves out, now. */
    public static byte[] capture() {
        BattleState battle = VIEW.b().state();
        Strategos2000 general = GAME.BATTLE_THREADS().strat;
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + 1 + 8 + 4 + 8);
        b.put((byte) (battle != null ? 1 : 0));
        b.putDouble(battle != null ? getDouble(THRONE_TIMER, battle) : 0);
        b.put((byte) (battle != null && getBoolean(CONCLUDED, battle) ? 1 : 0));
        b.putDouble(getDouble(PREV_UPDATE, general));
        b.putInt(getInt(OLD_DIVS, general));
        b.putDouble(getDouble(FLANKING, get(CONTEXT, get(OFFENSE, general))));
        return b.array();
    }

    /**
     * Restores what {@link #capture} took. A battle continues where it was: past deployment, its jobs running, without
     * {@code BattleState.deploy}, which would also restart the game's tick count.
     */
    private static void apply(byte[] state) {
        ByteBuffer b = ByteBuffer.wrap(state);
        boolean inBattle = b.get() != 0;
        double throneTimer = b.getDouble();
        boolean concluded = b.get() != 0;
        BattleState battle = VIEW.b().state();
        if (inBattle != (battle != null)) {
            throw new IllegalStateException("SOSDET: the resync save was taken " + (inBattle ? "in" : "outside")
                    + " a battle, but the loaded game is " + (battle != null ? "in" : "outside") + " one");
        }
        if (battle != null) {
            set(THRONE_TIMER, battle, throneTimer);
            set(CONCLUDED, battle, concluded);
            set(DEPLOYING, battle, false);
            GAME.BATTLE_THREADS().unpause(false); // opening the battle view paused the jobs
        }
        Strategos2000 general = GAME.BATTLE_THREADS().strat;
        set(PREV_UPDATE, general, b.getDouble());
        set(OLD_DIVS, general, b.getInt());
        set(FLANKING, get(CONTEXT, get(OFFENSE, general)), b.getDouble());
    }

    // ------------------------------------------------------------ reflection, resolved once

    private static Class<?> className(String name) {
        try {
            return Class.forName(name, false, GameResync.class.getClassLoader());
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

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no method " + owner.getName() + "." + name + " in this game build" + VERSION, e);
        }
    }

    private static Object get(Field f, Object o) {
        try {
            return f.get(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + f, e);
        }
    }

    private static double getDouble(Field f, Object o) {
        try {
            return f.getDouble(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + f, e);
        }
    }

    private static int getInt(Field f, Object o) {
        try {
            return f.getInt(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + f, e);
        }
    }

    private static boolean getBoolean(Field f, Object o) {
        try {
            return f.getBoolean(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + f, e);
        }
    }

    private static void set(Field f, Object o, Object value) {
        try {
            f.set(o, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot write " + f, e);
        }
    }
}

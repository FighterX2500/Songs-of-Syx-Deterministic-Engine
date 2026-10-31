package sosdet.game;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import init.race.RACES;
import init.type.HTYPES;
import settlement.stats.Induvidual;
import settlement.stats.STATS;
import settlement.stats.colls.StatsHome;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import util.data.DataO;
import util.data.INT_O.INT_OE;
import util.keymap.RMAPS;

/**
 * Soldiers ({@code Induvidual}) to bytes and back, for the inputs that carry them (B1, B4's survivors), in the game's
 * own save format. {@code Induvidual.save} and its load constructor use three of the game's savers and loaders: the
 * stats ({@code STATS.count()}), the races and the human types. The game makes a saver only while it saves a game,
 * and its loader follows the layout of the save it loaded last; so the engine makes one pair for the running game's
 * layout, once, and lends them to the game for each call (SOS-38, found by the M3 smoke test).
 */
final class GameSoldiers {

    private static final String VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Method STATS_COUNT = method(STATS.class, "count");
    private static final Field DATA_SAVER = field(DataO.class, "saver");
    private static final Field DATA_LOADER = field(DataO.class, "loader");
    private static final Field MAP_SAVER = field(RMAPS.class, "saver");
    private static final Field MAP_LOADER = field(RMAPS.class, "loader");
    private static final Field ADDED = field(Induvidual.class, "added");
    private static final Constructor<?> NEW_DATA_SAVER = constructor(DataO.DataOSaver.class, DataO.class, FilePutter.class);
    private static final Constructor<?> NEW_DATA_LOADER = constructor(DataO.DataOLoader.class, DataO.class, FileGetter.class);
    private static final Constructor<?> NEW_MAP_SAVER = constructor(RMAPS.Saver.class, RMAPS.class, FilePutter.class);
    private static final Constructor<?> NEW_MAP_LOADER = constructor(RMAPS.Loader.class, RMAPS.class, FileGetter.class);

    private final Object[] owners = {statsCount(), RACES.map(), HTYPES.MAP()};
    private final Field[] saverFields = {DATA_SAVER, MAP_SAVER, MAP_SAVER};
    private final Field[] loaderFields = {DATA_LOADER, MAP_LOADER, MAP_LOADER};
    private final Object[] savers = new Object[owners.length];
    private final Object[] loaders = new Object[owners.length];
    /** The game's own saver or loader while ours is lent. */
    private final Object[] kept = new Object[owners.length];
    private final INT_OE<Induvidual> workRoom = stat(STATS.WORK().EMPLOYED.getClass(), STATS.WORK().EMPLOYED, "data");
    private final INT_OE<Induvidual> homeTileX = stat(StatsHome.StatHome.class, STATS.HOME().GETTER, "xx");
    private final INT_OE<Induvidual> homeTileY = stat(StatsHome.StatHome.class, STATS.HOME().GETTER, "yy");

    /** Makes the savers and loaders of the running game's layout: each loader reads the header its saver writes. */
    GameSoldiers() {
        GameCodec header = new GameCodec(1 << 20);
        FilePutter p = header.hashBegin();
        savers[0] = make(NEW_DATA_SAVER, owners[0], p);
        savers[1] = make(NEW_MAP_SAVER, owners[1], p);
        savers[2] = make(NEW_MAP_SAVER, owners[2], p);
        FileGetter g = header.reader(header.written());
        loaders[0] = make(NEW_DATA_LOADER, owners[0], g);
        loaders[1] = make(NEW_MAP_LOADER, owners[1], g);
        loaders[2] = make(NEW_MAP_LOADER, owners[2], g);
    }

    /**
     * The soldier as they leave their city, which is all another PC can read: not in any city's stats, and with no
     * workplace or home, whose numbers mean something else in that PC's city (SOS-114). Vanilla's stored soldiers aren't
     * in the stats either. The soldier itself stays as it was.
     */
    void write(Induvidual i, FilePutter p) {
        boolean added = i.added();
        int room = workRoom.get(i);
        int homeX = homeTileX.get(i);
        int homeY = homeTileY.get(i);
        setCity(i, false, 0, 0, 0);
        lend(saverFields, savers);
        try {
            i.save(p);
        } finally {
            giveBack(saverFields);
            setCity(i, added, room, homeX, homeY);
        }
    }

    private void setCity(Induvidual i, boolean added, int room, int homeX, int homeY) {
        try {
            ADDED.setBoolean(i, added);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot mark a soldier as out of their city" + VERSION, e);
        }
        workRoom.set(i, room);
        homeTileX.set(i, homeX);
        homeTileY.set(i, homeY);
    }

    Induvidual read(FileGetter g) throws IOException {
        lend(loaderFields, loaders);
        try {
            return new Induvidual(g);
        } finally {
            giveBack(loaderFields);
        }
    }

    private void lend(Field[] fields, Object[] ours) {
        try {
            for (int k = 0; k < owners.length; k++) {
                kept[k] = fields[k].get(owners[k]);
                fields[k].set(owners[k], ours[k]);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot lend a soldier's saver or loader" + VERSION, e);
        }
    }

    private void giveBack(Field[] fields) {
        try {
            for (int k = 0; k < owners.length; k++) {
                fields[k].set(owners[k], kept[k]);
                kept[k] = null;
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot give back a soldier's saver or loader" + VERSION, e);
        }
    }

    /** A stat's private {@code INT_OE<Induvidual>} data in {@code holder}; the game declares the fields with that type. */
    @SuppressWarnings("unchecked")
    private static INT_OE<Induvidual> stat(Class<?> owner, Object holder, String name) {
        try {
            return (INT_OE<Induvidual>) field(owner, name).get(holder);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + owner.getName() + "." + name + VERSION, e);
        }
    }

    /** {@code STATS.count()}, package-private: the stats' {@code DataO}. */
    private static Object statsCount() {
        try {
            return STATS_COUNT.invoke(null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: cannot read STATS.count()" + VERSION, e);
        }
    }

    private static Object make(Constructor<?> c, Object owner, Object file) {
        try {
            return c.newInstance(owner, file);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: cannot make " + c.getDeclaringClass().getName() + VERSION, e);
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

    private static Constructor<?> constructor(Class<?> owner, Class<?>... params) {
        try {
            Constructor<?> c = owner.getDeclaredConstructor(params);
            c.setAccessible(true);
            return c;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no constructor of " + owner.getName() + " in this game build" + VERSION, e);
        }
    }
}

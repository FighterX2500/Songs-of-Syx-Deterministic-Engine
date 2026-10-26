package sosdet.game;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import game.faction.FACTIONS;
import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import world.army.AD;

/**
 * The stored city divisions of every human but the one in slot 0 (SOS-46): each human's soldiers on the world map wait
 * in their own city's store, and slot 0's store stays in {@code AD}'s own section, as in vanilla. The section is in the
 * "all" list, after {@code AD}; a save without it (any vanilla or single-player one) leaves the other stores empty.
 */
final class GameCityDivs extends Savable {

    static final String KEY = "SOSDET_CITY_DIVS";
    private static final int VERSION = 1;
    private static final String GAME_VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Method SAVE = method("sosdetSaveCityDivs", FilePutter.class);
    private static final Method LOAD = method("sosdetLoadCityDivs", FileGetter.class);

    private final GameHumans humans;

    GameCityDivs(GameHumans humans) {
        super(KEY);
        this.humans = humans;
    }

    @Override
    protected void save(FilePutter file) {
        int[] s = humans.slots();
        int n = 0;
        for (int slot : s) {
            if (stored(slot)) {
                n++;
            }
        }
        file.i(VERSION);
        file.i(n);
        for (int slot : s) {
            if (stored(slot)) {
                file.i(slot);
                call(SAVE, slot, file);
            }
        }
    }

    /**
     * Whether the section holds this human's store. A retired human's is left out (SOS-66): its armies are gone, nothing
     * reads the store, and loading it would read the city report the human no longer has.
     */
    private boolean stored(int slot) {
        return slot != 0 && !humans.retired(slot);
    }

    @Override
    protected void load(FileGetter file) throws IOException {
        int version = file.i();
        if (version != VERSION) {
            throw new IOException("SOSDET: a city divisions section of version " + version + ", this engine reads " + VERSION);
        }
        int n = file.i();
        for (int i = 0; i < n; i++) {
            int slot = file.i();
            if (slot <= 0 || slot >= FACTIONS.MAX()) {
                throw new IOException("SOSDET: city divisions of slot " + slot);
            }
            call(LOAD, slot, file);
        }
    }

    /** The patched {@code AD}'s static {@code name(int slot, file)}: the engine compiles against the vanilla jar. */
    private static Method method(String name, Class<?> file) {
        try {
            return AD.class.getMethod(name, int.class, file);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no method AD." + name + " in this game build" + GAME_VERSION, e);
        }
    }

    private static void call(Method m, int slot, Object file) {
        try {
            m.invoke(null, slot, file);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot call AD." + m.getName() + GAME_VERSION, e);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            throw t instanceof RuntimeException ? (RuntimeException) t : new IllegalStateException("SOSDET: AD." + m.getName() + " of slot " + slot, t);
        }
    }
}

package sosdet.game;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import game.GAME;
import game.faction.FACTIONS;
import game.faction.FactionResource;
import game.faction.player.PLevels;
import game.faction.player.PTech;
import game.faction.player.PTitles;
import game.faction.player.PTrade;
import game.faction.player.Player;
import game.faction.player.PlayerColors;
import game.save.GameSaver;
import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.SAVABLE;
import snake2d.util.sets.KeyMap;

/**
 * Loads the world's sections of a save into the running game and keeps this PC's city (ADR 0005 §5, SOS-38): the
 * world-load spike's reading loop, as {@code GameSaver.load} reads a save, for the sections the world is in. Their
 * player faction holds the city's own data too (credits, goods history, tech, titles, level, race order, bonus
 * settings, trade settings, colours): it is saved aside before the load and put back after it.
 */
public final class GameWorldLoad {

    /** The sections the world is in (the world-load spike): the map's resources, every faction, the raiders. */
    public static final List<String> KEYS = Arrays.asList("WORLD", "FACTIONS", "RAIDING");

    private static final String VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Field SMAP = field(GameSaver.class, "smap");
    private static final Method SECTION_LOAD = method(Savable.class, "load", FileGetter.class);
    private static final Method RESOURCE_SAVE = method(FactionResource.class, "save", FilePutter.class);
    private static final Method RESOURCE_LOAD = method(FactionResource.class, "load", FileGetter.class);
    private static final Field TECH = field(PTech.class, "saver");
    private static final Field TITLES = field(PTitles.class, "saver");
    private static final Field LEVEL = field(PLevels.class, "saver");
    private static final Field RACES = field(Player.PlayerRaces.class, "saver");
    private static final Field TRADE = field(PTrade.class, "saver");
    /** Room for the player's city-owned data: a late-game city's is a few hundred KB. */
    private static final int KEEP_BYTES = 1 << 24;

    private GameWorldLoad() {
    }

    /**
     * Loads {@link #KEYS} from {@code save} into the running game, between steps; {@code scratch} holds the city's
     * own part of the player faction meanwhile, and is deleted after.
     */
    public static void load(Path save, Path scratch) {
        Player p = FACTIONS.player();
        FilePutter keep = new FilePutter(scratch, KEEP_BYTES);
        save(p.credits(), keep);
        save(p.res(), keep);
        saver(TECH, p.tech).save(keep);
        saver(TITLES, p.titles).save(keep);
        saver(LEVEL, p.level()).save(keep);
        saver(RACES, p.races).save(keep);
        p.bonusesCustom.save(keep);
        saver(TRADE, p.trade).save(keep);
        PlayerColors.saver.save(keep);
        keep.save();
        sections(save);
        try {
            FileGetter back = new FileGetter(scratch);
            load(p.credits(), back);
            load(p.res(), back);
            saver(TECH, p.tech).load(back);
            saver(TITLES, p.titles).load(back);
            saver(LEVEL, p.level()).load(back);
            saver(RACES, p.races).load(back);
            p.bonusesCustom.load(back);
            saver(TRADE, p.trade).load(back);
            PlayerColors.saver.load(back);
            Files.delete(scratch);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: the city's part of the player faction could not be put back from "
                    + scratch, e);
        }
    }

    private static void sections(Path save) {
        try {
            @SuppressWarnings("unchecked") // GameSaver.smap is a KeyMap<Savable>
            KeyMap<Savable> smap = (KeyMap<Savable>) SMAP.get(GAME.saver());
            FileGetter f = new FileGetter(save, true);
            int spec = f.i();
            f.setPosition(f.getPosition() + spec); // GameSpec
            for (int list = 0; list < 2; list++) { // GameSaver's "before" list, then "all"
                int am = f.i();
                for (int i = 0; i < am; i++) {
                    String k = f.chars();
                    int le = f.i();
                    int end = f.getPosition() + le;
                    if (!KEYS.contains(k)) {
                        f.setPosition(end);
                        continue;
                    }
                    Savable s = smap.get(k);
                    if (s == null) {
                        throw new IllegalStateException("SOSDET: no save section " + k + " in this game" + VERSION);
                    }
                    SECTION_LOAD.invoke(s, f);
                    if (f.getPosition() != end) {
                        throw new IllegalStateException("SOSDET: save section " + k + " read " + (f.getPosition() - end + le)
                                + " of " + le + " bytes" + VERSION);
                    }
                }
            }
        } catch (IOException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: the world could not be loaded from " + save, e);
        }
    }

    private static void save(FactionResource r, FilePutter f) {
        try {
            RESOURCE_SAVE.invoke(r, f);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: cannot save " + r.getClass().getName() + VERSION, e);
        }
    }

    private static void load(FactionResource r, FileGetter f) {
        try {
            RESOURCE_LOAD.invoke(r, f);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: cannot load " + r.getClass().getName() + VERSION, e);
        }
    }

    private static SAVABLE saver(Field f, Object owner) {
        try {
            return (SAVABLE) f.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + f + VERSION, e);
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
}

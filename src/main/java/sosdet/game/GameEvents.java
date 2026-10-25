package sosdet.game;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import game.GAME;
import game.events.faction.player.EventDiplomacy;
import game.events.world.EventWorldRebellion;
import game.raiding.RAIDING;
import game.raiding.Raider;
import game.raiding.RaidingCurrent;
import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.Hooks;
import sosdet.hash.HashLog;

/**
 * The event timers and raids of every human but the one in slot 0 (SOS-46 M4): each human's diplomacy entries and war
 * timer ({@code EventDiplomacy}), rebellion timer, realm cursor and warning map ({@code EventWorldRebellion}), and raid
 * and raid timer ({@code RAIDING}; the raider as its place in the shared pool, which {@code RAIDING}'s own section
 * loads first). Slot 0's stay in the game's own sections, as in vanilla. The section is in the "all" list, after
 * {@code EVENTS}'s and {@code RAIDING}'s; a save without it (any vanilla or single-player one, and an M1 to M3 save of
 * several humans) gives the other humans fresh timers and no raid. Each human is named by its slot; its slice is its
 * ordinal among the human slots of the loaded game.
 */
final class GameEvents extends Savable {

    static final String KEY = "SOSDET_EVENTS";
    private static final int VERSION = 1;
    private static final Method DIP_SAVE = GameRelations.method(EventDiplomacy.class, "sosdetSave", int.class, FilePutter.class);
    private static final Method DIP_LOAD = GameRelations.method(EventDiplomacy.class, "sosdetLoad", int.class, FileGetter.class);
    private static final Method REBELLION_SAVE = GameRelations.method(EventWorldRebellion.class, "sosdetSave", int.class, FilePutter.class);
    private static final Method REBELLION_LOAD = GameRelations.method(EventWorldRebellion.class, "sosdetLoad", int.class, FileGetter.class);
    private static final Method RAID_SAVE = GameRelations.method(RAIDING.class, "sosdetSave", int.class, FilePutter.class);
    private static final Method RAID_LOAD = GameRelations.method(RAIDING.class, "sosdetLoad", int.class, FileGetter.class);
    private static final MethodHandle WAR_COUNT = handle(EventDiplomacy.class, "sosdetWarCount", double.class);
    private static final MethodHandle REBELLION_TIMER = handle(EventWorldRebellion.class, "sosdetTimer", double.class);
    private static final MethodHandle RAID = handle(RAIDING.class, "current", RaidingCurrent.class);

    private final GameHumans humans;

    GameEvents(GameHumans humans) {
        super(KEY);
        this.humans = humans;
    }

    @Override
    protected void save(FilePutter file) {
        int[] s = humans.slots();
        file.i(VERSION);
        file.i(s.length - 1);
        for (int k = 1; k < s.length; k++) {
            file.i(s[k]);
            GameRelations.call(DIP_SAVE, GAME.events().world.dip, k, file);
            GameRelations.call(REBELLION_SAVE, GAME.events().rebellion, k, file);
            GameRelations.call(RAID_SAVE, GAME.raiders(), k, file);
        }
    }

    @Override
    protected void load(FileGetter file) throws IOException {
        int version = file.i();
        if (version != VERSION) {
            throw new IOException("SOSDET: an events section of version " + version + ", this engine reads " + VERSION);
        }
        int[] s = humans.slots();
        int n = file.i();
        for (int i = 0; i < n; i++) {
            int k = HumanSlices.ordinal(s, file.i());
            GameRelations.load(DIP_LOAD, GAME.events().world.dip, k, file);
            GameRelations.load(REBELLION_LOAD, GAME.events().rebellion, k, file);
            GameRelations.load(RAID_LOAD, GAME.raiders(), k, file);
        }
    }

    /**
     * Mixes every human's but slot 0's war timer, rebellion timer and current raider into {@code h}. Slot 0's stay as
     * the hash had them before M4 (its raider only), so a one-human game hashes as it did.
     */
    static long hash(long h) {
        int n = Hooks.humanSlots().length;
        EventDiplomacy dip = GAME.events().world.dip;
        EventWorldRebellion rebellion = GAME.events().rebellion;
        RAIDING raids = GAME.raiders();
        for (int k = 1; k < n; k++) {
            h = HashLog.mix(h, Double.doubleToLongBits(warCount(dip, k)));
            h = HashLog.mix(h, Double.doubleToLongBits(rebellionTimer(rebellion, k)));
            Raider r = raid(raids, k).current();
            h = HashLog.mix(h, r == null ? -1 : r.army.power);
        }
        return h;
    }

    /**
     * Each human's values {@link #hash} mixes, from the second human on: war timer, rebellion timer and current
     * raider's power, three per human into {@code out}; returns the humans written (desync report, no allocation).
     */
    static int keep(double[] out) {
        int n = Math.min(Hooks.humanSlots().length, out.length / 3 + 1);
        EventDiplomacy dip = GAME.events().world.dip;
        EventWorldRebellion rebellion = GAME.events().rebellion;
        RAIDING raids = GAME.raiders();
        for (int k = 1; k < n; k++) {
            int o = (k - 1) * 3;
            out[o] = warCount(dip, k);
            out[o + 1] = rebellionTimer(rebellion, k);
            Raider r = raid(raids, k).current();
            out[o + 2] = r == null ? -1 : r.army.power;
        }
        return Math.max(0, n - 1);
    }

    private static double warCount(EventDiplomacy dip, int k) {
        try {
            return (double) WAR_COUNT.invokeExact(dip, k);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("SOSDET: EventDiplomacy.sosdetWarCount" + GameRelations.GAME_VERSION, t);
        }
    }

    private static double rebellionTimer(EventWorldRebellion rebellion, int k) {
        try {
            return (double) REBELLION_TIMER.invokeExact(rebellion, k);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("SOSDET: EventWorldRebellion.sosdetTimer" + GameRelations.GAME_VERSION, t);
        }
    }

    private static RaidingCurrent raid(RAIDING raids, int k) {
        try {
            return (RaidingCurrent) RAID.invokeExact(raids, k);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("SOSDET: RAIDING.current(int)" + GameRelations.GAME_VERSION, t);
        }
    }

    /** A patch-added public {@code name(int)} returning {@code type}, as a handle (the engine compiles against the vanilla jar). */
    private static MethodHandle handle(Class<?> owner, String name, Class<?> type) {
        try {
            return MethodHandles.publicLookup().findVirtual(owner, name, MethodType.methodType(type, int.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no method " + owner.getSimpleName() + "." + name + "(int) in this game build"
                    + GameRelations.GAME_VERSION, e);
        }
    }
}

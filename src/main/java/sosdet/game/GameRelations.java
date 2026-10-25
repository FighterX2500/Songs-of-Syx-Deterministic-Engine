package sosdet.game;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import game.boosting.superb.SuperData;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.npc.FactionNPC;
import game.faction.npc.NPCRequest;
import game.faction.royalty.Royalty;
import game.save.Savable;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.sets.LIST;
import sosdet.Hooks;
import sosdet.hash.HashLog;
import world.region.RD;
import world.region.RDOwner;

/**
 * The NPCs' relations with every human but the one in slot 0 (SOS-46 M3): each royal's opinion, each NPC's pending
 * request, each human's war and coalition names, and each human's support in every region. Slot 0's stay in the game's
 * own sections, as in vanilla. The section is in the "all" list, after {@code FACTIONS} and the world; a save without
 * it (any vanilla or single-player one, and an M1/M2 save of several humans) gives the other humans fresh relations.
 * Each human is named by its slot; its slice is its ordinal among the human slots of the loaded game.
 */
final class GameRelations extends Savable {

    static final String KEY = "SOSDET_RELATIONS";
    private static final int VERSION = 1;
    static final String GAME_VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Method DIP_SAVE = method(DIP.class, "sosdetSave", int.class, FilePutter.class);
    private static final Method DIP_LOAD = method(DIP.class, "sosdetLoad", int.class, FileGetter.class);
    private static final Method REQUEST_SAVE = method(NPCRequest.class, "sosdetSave", int.class, FilePutter.class);
    private static final Method REQUEST_LOAD = method(NPCRequest.class, "sosdetLoad", int.class, FileGetter.class);
    private static final Method SUPPORT_SAVE = method(RDOwner.class, "sosdetSave", int.class, FilePutter.class);
    private static final Method SUPPORT_LOAD = method(RDOwner.class, "sosdetLoad", int.class, FileGetter.class);
    private static final MethodHandle OPINION = opinion();
    private static final MethodHandle VALUES = values();

    private final GameHumans humans;

    GameRelations(GameHumans humans) {
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
            call(DIP_SAVE, null, k, file);
            for (Faction f : FACTIONS.all()) {
                if (f instanceof FactionNPC) {
                    FactionNPC npc = (FactionNPC) f;
                    file.i(npc.index());
                    call(REQUEST_SAVE, npc.request, k, file);
                    LIST<Royalty> roys = npc.court().all();
                    file.i(roys.size());
                    for (int r = 0; r < roys.size(); r++) {
                        opinion(roys.get(r), k).save(file);
                    }
                }
            }
            file.i(-1);
            call(SUPPORT_SAVE, RD.OWNER(), k, file);
        }
    }

    @Override
    protected void load(FileGetter file) throws IOException {
        int version = file.i();
        if (version != VERSION) {
            throw new IOException("SOSDET: a relations section of version " + version + ", this engine reads " + VERSION);
        }
        int[] s = humans.slots();
        int n = file.i();
        for (int i = 0; i < n; i++) {
            int k = HumanSlices.ordinal(s, file.i());
            load(DIP_LOAD, null, k, file);
            for (int slot = file.i(); slot >= 0; slot = file.i()) {
                Faction f = slot < FACTIONS.MAX() ? FACTIONS.getByIndex(slot) : null;
                if (!(f instanceof FactionNPC)) {
                    throw new IOException("SOSDET: relations of slot " + slot + ", which is no NPC");
                }
                FactionNPC npc = (FactionNPC) f;
                load(REQUEST_LOAD, npc.request, k, file);
                LIST<Royalty> roys = npc.court().all();
                int r = file.i();
                if (r != roys.size()) {
                    throw new IOException("SOSDET: " + r + " royals' opinions for slot " + slot + ", which has " + roys.size());
                }
                for (int j = 0; j < r; j++) {
                    opinion(roys.get(j), k).load(file);
                }
            }
            load(SUPPORT_LOAD, RD.OWNER(), k, file);
        }
    }

    /**
     * Mixes every NPC king's opinion of every human but slot 0's into {@code h}: the slices' stored values (the boost
     * framework's data), each one not 0 with its place, so a slice this PC's UI made but never changed counts as none.
     * Slot 0's stay out, as before M3 (audit C20), so a one-human game hashes as it did.
     */
    static long hash(long h) {
        int humanCount = Hooks.humanSlots().length;
        LIST<FactionNPC> npcs = FACTIONS.NPCs();
        for (int i = 0; i < npcs.size(); i++) {
            FactionNPC f = npcs.get(i);
            if (f.court().king() == null || f.court().king().roy() == null) {
                continue;
            }
            Royalty king = f.court().king().roy();
            for (int k = 1; k < humanCount; k++) {
                double[] v = values(opinion(king, k));
                for (int j = 0; v != null && j < v.length; j++) {
                    if (v[j] != 0) {
                        h = HashLog.mix(h, (f.index() << 16) | (k << 8) | j);
                        h = HashLog.mix(h, Double.doubleToLongBits(v[j]));
                    }
                }
            }
        }
        return h;
    }

    /** The patched {@code Royalty.sosdetData(int)}: a royal's opinion of the human of slice {@code k}. */
    static SuperData opinion(Royalty r, int k) {
        try {
            return (SuperData) OPINION.invokeExact(r, k);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("SOSDET: Royalty.sosdetData" + GAME_VERSION, t);
        }
    }

    private static double[] values(SuperData d) {
        try {
            return (double[]) VALUES.invokeExact(d);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("SOSDET: SuperData.values" + GAME_VERSION, t);
        }
    }

    private static MethodHandle opinion() {
        try {
            return MethodHandles.publicLookup().findVirtual(Royalty.class, "sosdetData", MethodType.methodType(SuperData.class, int.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no method Royalty.sosdetData in this game build" + GAME_VERSION, e);
        }
    }

    private static MethodHandle values() {
        try {
            return MethodHandles.privateLookupIn(SuperData.class, MethodHandles.lookup()).findGetter(SuperData.class, "values", double[].class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no field SuperData.values in this game build" + GAME_VERSION, e);
        }
    }

    /** A patch-added public method (the engine compiles against the vanilla jar). */
    static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no method " + owner.getSimpleName() + "." + name + " in this game build" + GAME_VERSION, e);
        }
    }

    /** Calls a patch-added {@code sosdetLoad(int, FileGetter)}, unwrapping what it throws. */
    static void load(Method m, Object target, int k, FileGetter file) throws IOException {
        try {
            m.invoke(target, k, file);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot call " + m.getDeclaringClass().getSimpleName() + "." + m.getName() + GAME_VERSION, e);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw t instanceof RuntimeException ? (RuntimeException) t
                    : new IllegalStateException("SOSDET: " + m.getDeclaringClass().getSimpleName() + "." + m.getName(), t);
        }
    }

    /** Calls a patch-added method, unwrapping what it throws. */
    static Object call(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot call " + m.getDeclaringClass().getSimpleName() + "." + m.getName() + GAME_VERSION, e);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            throw t instanceof RuntimeException ? (RuntimeException) t
                    : new IllegalStateException("SOSDET: " + m.getDeclaringClass().getSimpleName() + "." + m.getName(), t);
        }
    }
}

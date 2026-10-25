package sosdet.game;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import game.GAME;
import game.boosting.BOOSTING;
import game.boosting.Boostable;
import game.boosting.Booster;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.npc.FactionNPC;
import game.faction.player.Player;
import game.save.Savable;
import init.value.GVALUES;
import init.value.GValueCat;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.misc.ACTION;
import snake2d.util.sets.ArrayList;
import snake2d.util.sets.ArrayListGrower;
import snake2d.util.sets.LIST;
import snake2d.util.sets.KeyMap;
import snake2d.util.sets.LinkedList;

/**
 * The human factions of one game (ADR 0004): each human is a {@link Player} in its own slot of {@code FACTIONS}, the
 * same slot on every PC. Slot 0 is always a human, as in vanilla. A game has {@link #SPARES} spare Players, built with
 * {@code FACTIONS}; a slot becomes a human by taking a spare, and the NPC that was there leaves the game.
 *
 * <p>The save names the human slots in a section of the "before" list ({@link #KEY}), which loads ahead of
 * {@code FACTIONS}: its load puts a spare in each slot, and {@code FACTIONS} then loads each human slot in
 * {@code Player}'s layout. A save without the section (any vanilla or single-player one) has one human, in slot 0.
 */
public final class GameHumans extends Savable {

    /** Spare Players, so a game holds at most this many humans plus the one in slot 0 (spec: 2 to 16 humans). */
    public static final int SPARES = 15;
    static final String KEY = "SOSDET_HUMANS";
    private static final int VERSION = 1;
    private static final String GAME_VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";

    private static final Field SELF = field(FACTIONS.class, "self");
    private static final Field HUMANS = field(FACTIONS.class, "sosdetHumans");
    private static final Field DIRTY = field(FACTIONS.class, "dirty");
    private static final Field INDEX = field(Faction.class, "index");
    private static final Field WAS_ACTIVE = field(Faction.class, "wasActive");
    private static final Field LISTENERS = field(Faction.FactionActivityListener.class, "all");
    private static final Field CONNECTERS = field(BOOSTING.class, "connecters");
    private static final Field LOCKS = field(GValueCat.Locks.class, "map");
    private static final Field LOCK_INITS = field(GValueCat.Locks.class, "inits");
    private static final Field BOOSTERS = field(Boostable.class, "all");

    private final ArrayList<Faction> all;
    private final ArrayList<FactionNPC> npcs;
    /** Built with the game: a Player's titles compose their icons, which works only while the game is created. */
    private final Player[] spares = new Player[SPARES];
    private int sparesUsed;
    /** Each slot's id for the mod (its player), 0 until the mod sets one; saved with the slots. */
    private final long[] ids = new long[FACTIONS.MAX()];

    /** {@code FACTIONS}'s constructor, at its end: {@code all} and {@code npcs} are its own lists. */
    public GameHumans(ArrayList<Faction> all, ArrayList<FactionNPC> npcs) throws IOException {
        super(KEY);
        this.all = all;
        this.npcs = npcs;
        for (int i = 0; i < SPARES; i++) {
            spares[i] = spare();
        }
        GAME.saver().addSpecialSaver(this);
        GAME.saver().add(new GameCityDivs(this)); // after AD's and FACTIONS's sections: the world is built first (SOS-46)
    }

    /** This game's humans: the {@code GameHumans} its {@code FACTIONS} holds. */
    public static GameHumans get() {
        try {
            return (GameHumans) HUMANS.get(SELF.get(null));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read FACTIONS.sosdetHumans" + GAME_VERSION, e);
        }
    }

    /** True for a human faction: every Player in the game is one (the spares are in no slot). */
    public static boolean isHuman(Object f) {
        return f instanceof Player;
    }

    /** The human slots, in slot order. */
    public int[] slots() {
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            n += isHuman(all.get(i)) ? 1 : 0;
        }
        int[] s = new int[n];
        n = 0;
        for (int i = 0; i < all.size(); i++) {
            if (isHuman(all.get(i))) {
                s[n++] = i;
            }
        }
        return s;
    }

    public long id(int slot) {
        human(slot);
        return ids[slot];
    }

    public void id(int slot, long id) {
        human(slot);
        ids[slot] = id;
    }

    /**
     * Setup, before a session: the NPC in {@code slot} becomes a human with its name, race, credits and realm. Other
     * game objects may still hold the NPC, so the game is saved and loaded before the session starts, the way every
     * PC then loads it. Humans join in slot order: a slot below an existing human's is refused.
     */
    public Player add(int slot) {
        if (slot <= 0 || slot >= all.size()) {
            throw new IllegalArgumentException("SOSDET: no NPC slot " + slot + " (slots 1 to " + (all.size() - 1) + ")");
        }
        Faction old = all.get(slot);
        if (!(old instanceof FactionNPC)) {
            throw new IllegalArgumentException("SOSDET: slot " + slot + " is already a human");
        }
        FactionNPC npc = (FactionNPC) old;
        if (npc == FACTIONS.otherFaction()) {
            throw new IllegalArgumentException("SOSDET: slot " + slot + " is the game's \"other faction\", which stays an NPC");
        }
        if (!npc.isActive() || npc.capitolRegion() == null) {
            throw new IllegalArgumentException("SOSDET: slot " + slot + " has no realm; a human starts with a capitol");
        }
        int[] humans = slots();
        if (humans[humans.length - 1] > slot) {
            // each human's own data (relations, events) is kept by its place among the humans; a human added below
            // another would take that one's place before the setup's save
            throw new IllegalArgumentException("SOSDET: slot " + slot + " is below human slot " + humans[humans.length - 1]
                    + "; humans join in slot order");
        }
        Player p = put(slot);
        p.name.clear().add(npc.name);
        p.setRace(npc.race());
        p.credits().set(npc.credits().getD());
        try {
            WAS_ACTIVE.setBoolean(p, npc.isActive());
            @SuppressWarnings("unchecked") // Faction.FactionActivityListener.all is a LinkedList of them
            LinkedList<Faction.FactionActivityListener> listeners = (LinkedList<Faction.FactionActivityListener>) LISTENERS
                    .get(null);
            for (Faction.FactionActivityListener li : listeners) {
                li.remove(npc);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read a faction's activity" + GAME_VERSION, e);
        }
        return p;
    }

    /** A spare takes slot {@code slot}, and the NPC there leaves the slots and the NPC list. */
    private Player put(int slot) {
        if (sparesUsed == SPARES) {
            throw new IllegalStateException("SOSDET: no spare Player left for slot " + slot + " (a game holds at most "
                    + (SPARES + 1) + " humans)");
        }
        Player p = spares[sparesUsed];
        spares[sparesUsed++] = null;
        try {
            INDEX.setInt(p, slot);
            all.replace(slot, p);
            npcs.clear();
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i) instanceof FactionNPC) {
                    npcs.add((FactionNPC) all.get(i));
                }
            }
            DIRTY.setBoolean(SELF.get(null), true);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot put a human in slot " + slot + GAME_VERSION, e);
        }
        return p;
    }

    private void human(int slot) {
        if (slot < 0 || slot >= all.size() || !isHuman(all.get(slot))) {
            throw new IllegalArgumentException("SOSDET: slot " + slot + " is not a human");
        }
    }

    @Override
    protected void save(FilePutter file) {
        int[] s = slots();
        file.i(VERSION);
        file.i(s.length);
        for (int slot : s) {
            file.i(slot);
            file.l(ids[slot]);
        }
    }

    @Override
    protected void load(FileGetter file) throws IOException {
        int version = file.i();
        if (version != VERSION) {
            throw new IOException("SOSDET: a humans section of version " + version + ", this engine reads " + VERSION);
        }
        int n = file.i();
        int last = -1;
        for (int i = 0; i < n; i++) {
            int slot = file.i();
            if (slot <= last || slot >= all.size()) {
                throw new IOException("SOSDET: human slot " + slot + " after " + last);
            }
            if (slot != 0) {
                put(slot); // a fresh game: FACTIONS loads the slot into the Player next
            }
            ids[slot] = file.l();
            last = slot;
        }
    }

    @Override
    protected void loadFail() {
        throw new IllegalStateException("SOSDET: the save's human slots did not load");
    }

    /**
     * A Player that registers nothing game-wide. Its parts push values and locks under fixed keys, where a second push
     * crashes ({@code PCredits}, {@code PBonusSetting}, {@code PLevels}, {@code PTitles}), and connect boosters to the
     * boostables ({@code BoostCompound}). The registries are set aside while it is built and put back after, so only
     * slot 0's Player is registered; its values and boosters read the Player they are asked about. Its unlocks
     * ({@code Lockers}: a level's or a title's) are dropped too: each would add a lock to what it unlocks, and the game
     * passes a lockable only when every lock does, so an unused spare's title would lock its unlocks for everyone
     * (SOS-46); slot 0's read the human asked. So are the boosters it adds to a boostable itself ({@code Emissaries}):
     * each would count once more for every human.
     */
    private static Player spare() throws IOException {
        try {
            @SuppressWarnings("unchecked") // BOOSTING.connecters is a LinkedList<ACTION>
            LinkedList<ACTION> connecters = (LinkedList<ACTION>) CONNECTERS.get(null);
            java.util.ArrayList<ACTION> keptConnecters = new java.util.ArrayList<>();
            for (ACTION a : connecters) {
                keptConnecters.add(a);
            }
            java.util.ArrayList<KeyMap<Object>> maps = registries();
            java.util.ArrayList<Map<String, Object>> kept = new java.util.ArrayList<>();
            for (KeyMap<Object> m : maps) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (String k : new java.util.ArrayList<>(m.keys())) {
                    copy.put(k, m.get(k));
                }
                kept.add(copy);
                m.clear();
            }
            java.util.ArrayList<java.util.Set<ACTION>> lockInits = new java.util.ArrayList<>();
            for (GValueCat<?> c : CATEGORIES) {
                java.util.Set<ACTION> before = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (ACTION a : lockInits(c)) {
                    before.add(a);
                }
                lockInits.add(before);
            }
            LIST<Boostable> boostables = BOOSTING.ALL();
            int[] boosters = new int[boostables.size()];
            int[] globals = new int[boostables.size()];
            for (int i = 0; i < boostables.size(); i++) {
                boosters[i] = boosters(boostables.get(i)).size();
                globals[i] = boostables.get(i).fGlobal.size();
            }
            Player p = new Player(new ArrayList<Faction>(1));
            for (int i = 0; i < boostables.size(); i++) {
                dropFrom(boosters(boostables.get(i)), boosters[i]);
                dropFrom(boostables.get(i).fGlobal, globals[i]);
            }
            for (int i = 0; i < CATEGORIES.length; i++) {
                LinkedList<ACTION> l = lockInits(CATEGORIES[i]);
                java.util.ArrayList<ACTION> unlocks = new java.util.ArrayList<>();
                for (ACTION a : l) {
                    if (!lockInits.get(i).contains(a) && a.getClass().getName().equals(UNLOCK_PROMISE)) {
                        unlocks.add(a);
                    }
                }
                for (ACTION a : unlocks) {
                    l.remove(a);
                }
            }
            for (int i = 0; i < maps.size(); i++) {
                maps.get(i).clear();
                for (Map.Entry<String, Object> e : kept.get(i).entrySet()) {
                    maps.get(i).putReplace(e.getKey(), e.getValue());
                }
            }
            connecters.clear();
            for (ACTION a : keptConnecters) {
                connecters.add(a);
            }
            return p;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot set the game's registries aside" + GAME_VERSION, e);
        }
    }

    private static final GValueCat<?>[] CATEGORIES = { GVALUES.INDU, GVALUES.REGION, GVALUES.FACTION, GVALUES.ROYALTY };
    /** The promise a {@code Lockers} entry queues: an unlock, resolved into a lock when the values are set up. */
    private static final String UNLOCK_PROMISE = "init.value.Lockers$LPromise";

    /** A boostable's boosters ({@code Boostable.all}). */
    private static ArrayListGrower<Booster> boosters(Boostable b) throws IllegalAccessException {
        @SuppressWarnings("unchecked") // Boostable.all is an ArrayListGrower<Booster>
        ArrayListGrower<Booster> l = (ArrayListGrower<Booster>) BOOSTERS.get(b);
        return l;
    }

    /** Removes the boosters added after the first {@code keep}. */
    private static void dropFrom(ArrayListGrower<Booster> l, int keep) {
        java.util.ArrayList<Booster> added = new java.util.ArrayList<>();
        for (int i = keep; i < l.size(); i++) {
            added.add(l.get(i));
        }
        for (Booster b : added) {
            l.remove(b);
        }
    }

    /** A category's queued lock promises ({@code GValueCat.Locks.inits}). */
    private static LinkedList<ACTION> lockInits(GValueCat<?> c) throws IllegalAccessException {
        @SuppressWarnings("unchecked") // GValueCat.Locks.inits is a LinkedList<ACTION>
        LinkedList<ACTION> l = (LinkedList<ACTION>) LOCK_INITS.get(c.LOCK);
        return l;
    }

    /** Every GVALUES category's values and locks, by key. */
    private static java.util.ArrayList<KeyMap<Object>> registries() throws IllegalAccessException {
        java.util.ArrayList<KeyMap<Object>> l = new java.util.ArrayList<>();
        for (GValueCat<?> c : CATEGORIES) {
            @SuppressWarnings("unchecked") // only emptied and refilled with its own entries
            KeyMap<Object> values = (KeyMap<Object>) (KeyMap<?>) c.map();
            @SuppressWarnings("unchecked") // GValueCat.Locks.map is a KeyMap of lockables
            KeyMap<Object> locks = (KeyMap<Object>) LOCKS.get(c.LOCK);
            l.add(values);
            l.add(locks);
        }
        return l;
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no field " + owner.getName() + "." + name + " in this game build"
                    + GAME_VERSION, e);
        }
    }
}

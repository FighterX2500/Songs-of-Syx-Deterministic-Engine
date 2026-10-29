package sosdet.game;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

import game.GAME;
import game.boosting.tmp.TmpBoostable;
import game.boosting.tmp.TmpBoosting;
import game.events.EVENTS;
import game.events.faction.EventFactionWar;
import game.faction.FACTIONS;
import game.faction.FactionResource;
import game.faction.npc.FactionNPC;
import game.faction.player.PLevels;
import game.faction.player.PTech;
import game.faction.player.PTitles;
import game.faction.player.PTrade;
import game.faction.player.Player;
import game.faction.player.PlayerColors;
import game.faction.royalty.NPCCourt;
import game.raiding.RAIDING;
import game.raiding.RaidingMap;
import game.save.GameSaver;
import game.save.Savable;
import settlement.main.SETT;
import settlement.recipe.Recipe;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.SAVABLE;
import snake2d.util.misc.ACTION;
import snake2d.util.sets.KeyMap;
import snake2d.util.sets.LIST;
import snake2d.util.sets.Stack;
import util.data.DataO;
import util.keymap.RMAPS;
import world.WORLD;
import world.entity.WEntity;
import world.entity.army.WArmy;
import world.entity.army.WArmyConstructor;
import world.entity.caravan.Shipment;
import world.entity.caravan.Shipments;
import world.entity.haven.WHaven;
import world.entity.haven.WHavens;
import world.map.pathing.WPath;

/**
 * Loads the world's sections of a save into the running game and keeps this PC's city (ADR 0005 §5, SOS-38): the
 * world-load spike's reading loop, as {@code GameSaver.load} reads a save, for the sections the world is in. Their
 * player faction holds the city's own data too (credits, goods history, tech, titles, level, race order, bonus
 * settings, trade settings, colours), and so do the temporary boosts of the city's population classes: they are saved
 * aside before the load and put back after it.
 */
public final class GameWorldLoad {

    /**
     * The sections the world is in: the map's resources, every faction, the raiders (the world-load spike); the
     * calendar, which the world's events and wars count by; the temporary boosts of regions and factions; and the
     * world's events, without the city's ({@link #EVENTS_KEY}) (SOS-32); and the engine's stores, relations and event
     * timers of every human but slot 0 (SOS-52); the sieges of the humans' capitals, with vanilla's sieges in WORLD (SOS-92). Not the human slots: they are set once, and a resync checks they match.
     */
    public static final List<String> KEYS = Arrays.asList("TIME", "WORLD", "FACTIONS", "RAIDING", "TEMP_EVENTS", "EVENTS",
            GameCityDivs.KEY, GameRelations.KEY, GameEvents.KEY, GameSieges.KEY);
    /** Saved and loaded with only the world's events ({@link StepParts#worldEvent}): the city's are each PC's own. */
    private static final String EVENTS_KEY = "EVENTS";

    private static final String VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Field SMAP = field(GameSaver.class, "smap");
    private static final Method SECTION_LOAD = method(Savable.class, "load", FileGetter.class);
    private static final Method SECTION_SAVE = method(Savable.class, "save", FilePutter.class);
    /** {@code GameSaver}'s first list: the layout headers ({@code DataO}, {@code RMAPS}) and a few other sections. */
    private static final Field BEFORE = field(GameSaver.class, "before");
    /** {@code GameSaver}'s second list: every other section. */
    private static final Field ALL = field(GameSaver.class, "all");
    /** As a whole save's buffer ({@code GameResync}): the world's sections are most of a save. */
    private static final int SAVE_BYTES_PER_TILE = 120;
    private static final ACTION NO_PROGRESS = () -> { };
    private static final Method RESOURCE_SAVE = method(FactionResource.class, "save", FilePutter.class);
    private static final Method RESOURCE_LOAD = method(FactionResource.class, "load", FileGetter.class);
    private static final Field TECH = field(PTech.class, "saver");
    private static final Field TITLES = field(PTitles.class, "saver");
    private static final Field LEVEL = field(PLevels.class, "saver");
    private static final Field RACES = field(Player.PlayerRaces.class, "saver");
    private static final Field TRADE = field(PTrade.class, "saver");
    private static final Method EVENT_SAVE = method(EVENTS.EventResource.class, "save", FilePutter.class);
    private static final Method EVENT_LOAD = method(EVENTS.EventResource.class, "load", FileGetter.class);
    private static final Method EVENT_CLEAR = method(EVENTS.EventResource.class, "clear");
    /** {@code TmpBoosting.datas}: each boostable's active temporary boosts, regions first, then population classes. */
    private static final Field BOOST_DATAS = field(TmpBoosting.class, "datas");
    /** Room for the player's city-owned data: a late-game city's is a few hundred KB. */
    private static final int KEEP_BYTES = 1 << 24;
    /** An entity's sub-pixel move remainder, which no save holds and a load leaves as it was (SOS-52). */
    private static final Field MOVEMENT = field(WPath.class, "movement");
    /** The pooled entities' makers, which reuse a removed entity before they make one: caravans, armies, camps. */
    private static final Constructor<?>[] POOLS = {constructor(Shipment.class), constructor(WArmy.class),
            constructor(WHaven.class)};
    private static final Field[] POOL_FIELDS = {field(Shipments.class, "free"), field(WArmyConstructor.class, "free"),
            field(WHavens.class, "free")};
    private static final Field STACK_ITEMS = field(Stack.class, "ints");
    /** The faction war event's timer, which its load sets to 16 seconds. */
    private static final Field WAR_TIMER = field(EventFactionWar.class, "timer");
    /** An NPC court's day counter: a day ages its royals and may draw a successor (V82X). */
    private static final Field COURT_DAY = field(NPCCourt.class, "addT");
    /** Every human's raid map ({@code RAIDING}, patch 16), whose army power and regions are kept between updates. */
    private static final Field RAID_MAPS = field(RAIDING.class, "sosdetEntries");
    private static final Field RAID_ARMY_UPDATED = field(RaidingMap.class, "AUp");
    private static final Field RAID_DIRTY = field(RaidingMap.class, "dirty");
    /**
     * A recipe's NPC production boost, which a new game draws: the slave recipes' aren't in {@code Recipes.fboosts}, so
     * no save holds them, and a whole game's load draws them anew (V82AA).
     */
    private static final Field FBOOST_MUL = field(fboostClass(), "mul");

    private GameWorldLoad() {
    }

    /**
     * Loads {@link #KEYS} from {@code save} into the running game, between steps; {@code scratch} holds the city's
     * own part of the player faction meanwhile, and is deleted after.
     */
    public static void load(Path save, Path scratch) {
        layoutSavers();
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
        SAVABLE[] popBoosts = popBoosts();
        for (SAVABLE b : popBoosts) {
            b.save(keep);
        }
        keep.save();
        sections(save);
        resetMoveRemainders();
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
            for (SAVABLE b : popBoosts) {
                b.load(back);
            }
            Files.delete(scratch);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: the city's part of the player faction could not be put back from "
                    + scratch, e);
        }
    }

    /**
     * Saves the running game's world sections ({@link #KEYS}) to {@code file} for {@link #load}: a save as
     * {@code GameSaver} writes one, with no game spec and only these sections ({@code EVENTS} with the world's events
     * only). A whole save can't serve. The game
     * writes every section's objects into one Java object stream, and its back-references count from the start of the
     * file, so a load that skips the city's sections misreads the raiders: {@code RAIDING} then makes new ones from
     * each PC's own draws (found by a probe in the S3 smoke run).
     */
    public static void save(Path file) {
        layoutSavers();
        try {
            @SuppressWarnings("unchecked") // GameSaver.before and .all are ArrayListGrower<Savable>
            LIST<Savable> before = (LIST<Savable>) BEFORE.get(GAME.saver());
            @SuppressWarnings("unchecked")
            LIST<Savable> all = (LIST<Savable>) ALL.get(GAME.saver());
            Files.deleteIfExists(file);
            FilePutter f = new FilePutter(file, SAVE_BYTES_PER_TILE * SETT.TAREA);
            f.i(0); // the game spec's length: none
            saveSections(f, before);
            saveSections(f, all);
            if (!f.zip(NO_PROGRESS)) {
                throw new IllegalStateException("SOSDET: cannot write the world save " + file);
            }
        } catch (IOException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: the world could not be saved to " + file, e);
        }
    }

    /** One of {@code GameSaver}'s lists, as {@code GameSaver.save} writes it, with only the world's sections. */
    private static void saveSections(FilePutter f, LIST<Savable> list) throws IllegalAccessException,
            InvocationTargetException {
        int am = 0;
        for (int i = 0; i < list.size(); i++) {
            if (KEYS.contains(list.get(i).key)) {
                am++;
            }
        }
        f.i(am);
        for (int i = 0; i < list.size(); i++) {
            Savable s = list.get(i);
            if (!KEYS.contains(s.key)) {
                continue;
            }
            f.chars(s.key);
            int pos = f.getPosition();
            f.i(0);
            if (s.key.equals(EVENTS_KEY)) {
                saveWorldEvents(f);
            } else {
                SECTION_SAVE.invoke(s, f);
            }
            f.setAtPosition(pos, f.getPosition() - pos - 4);
        }
    }

    /** The world's events, as {@code EVENTS}' {@code SuperSaver} writes all of them: a count, then key, length, data. */
    private static void saveWorldEvents(FilePutter f) throws IllegalAccessException, InvocationTargetException {
        LIST<EVENTS.EventResource> all = GAME.events().all();
        int am = 0;
        for (int i = 0; i < all.size(); i++) {
            if (StepParts.worldEvent(all.get(i).key)) {
                am++;
            }
        }
        f.i(am);
        for (int i = 0; i < all.size(); i++) {
            EVENTS.EventResource e = all.get(i);
            if (!StepParts.worldEvent(e.key)) {
                continue;
            }
            f.chars(e.key);
            int pos = f.getPosition();
            f.i(0);
            EVENT_SAVE.invoke(e, f);
            f.setAtPosition(pos, f.getPosition() - pos - 4);
        }
    }

    /** The world's events from {@link #saveWorldEvents}, each cleared and loaded as {@code SuperSaver} does; the city's stay. */
    private static void loadWorldEvents(FileGetter f) throws IOException, IllegalAccessException,
            InvocationTargetException {
        LIST<EVENTS.EventResource> all = GAME.events().all();
        int am = f.i();
        for (int i = 0; i < am; i++) {
            String k = f.chars();
            int le = f.i();
            int end = f.getPosition() + le;
            EVENTS.EventResource e = null;
            for (int j = 0; j < all.size(); j++) {
                if (all.get(j).key.equals(k)) {
                    e = all.get(j);
                }
            }
            if (e == null || !StepParts.worldEvent(k)) {
                throw new IllegalStateException("SOSDET: no world event " + k + " in this game" + VERSION);
            }
            EVENT_CLEAR.invoke(e);
            EVENT_LOAD.invoke(e, f);
            if (f.getPosition() != end) {
                throw new IllegalStateException("SOSDET: world event " + k + " read " + (f.getPosition() - end + le)
                        + " of " + le + " bytes" + VERSION);
            }
        }
    }

    /**
     * The world's state no save holds, which a viewer keeps across its loads (SOS-82): every world entity's move
     * remainder, fast entities first; then for each entity pool ({@link #POOLS}) its size and its entities' move
     * remainders, bottom first; last the faction war event's timer. A loaded game's pools aren't the running game's:
     * where one reuses a pooled caravan, the other makes a new one, whose path draws its direction (rig run V82E), and a
     * reused one keeps the move remainder of its last trip (V82F). The war event's load sets its timer to 16 seconds,
     * whatever was saved (V82I). Then each NPC court's day counter (V82X) and each recipe's NPC production boost
     * ({@link #FBOOST_MUL}), which no save holds either.
     */
    public static double[] unsaved() {
        LIST<WEntity> fast = WORLD.ENTITIES().allFast();
        LIST<WEntity> slow = WORLD.ENTITIES().allSlow();
        LIST<FactionNPC> npcs = FACTIONS.NPCs();
        List<Object> fboosts = fboosts();
        int n = fast.size() + slow.size();
        int size = n + 1 + npcs.size() + fboosts.size();
        for (int k = 0; k < POOLS.length; k++) {
            size += 1 + pool(k).size();
        }
        double[] m = new double[size];
        for (int i = 0; i < n; i++) {
            m[i] = remainder(i < fast.size() ? fast.get(i) : slow.get(i - fast.size()));
        }
        int at = n;
        for (int k = 0; k < POOLS.length; k++) {
            Stack<Object> pool = pool(k);
            Object[] in = pooled(pool);
            m[at++] = pool.size();
            for (int i = 0; i < pool.size(); i++) {
                m[at++] = remainder((WEntity) in[i]);
            }
        }
        m[at++] = warTimer();
        for (int i = 0; i < npcs.size(); i++) {
            m[at++] = courtDay(npcs.get(i).court());
        }
        for (Object b : fboosts) {
            m[at++] = fboostMul(b);
        }
        return m;
    }

    /**
     * Puts back what {@link #unsaved} took, in a world of the same entities, outside a step: each pool holds new
     * entities with the remainders taken (their constructors' draws are cosmetic there). Every raid map is then set as
     * a new game's, so it works its army power and regions out afresh: a loaded game's are, and a running game's
     * were last worked out up to 100 seconds before (V82X).
     */
    public static void unsaved(double[] m) {
        LIST<WEntity> fast = WORLD.ENTITIES().allFast();
        LIST<WEntity> slow = WORLD.ENTITIES().allSlow();
        int n = fast.size() + slow.size();
        if (m.length < n + POOLS.length + 1) {
            throw new IllegalStateException("SOSDET: " + m.length + " unsaved values for " + n + " world entities");
        }
        for (int i = 0; i < n; i++) {
            remainder(i < fast.size() ? fast.get(i) : slow.get(i - fast.size()), (float) m[i]);
        }
        int at = n;
        for (int k = 0; k < POOLS.length; k++) {
            Stack<Object> pool = pool(k);
            pool.clear();
            int size = (int) m[at++];
            for (int i = 0; i < size; i++) {
                WEntity e;
                try {
                    e = (WEntity) POOLS[k].newInstance();
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("SOSDET: cannot make a " + POOLS[k].getDeclaringClass().getName()
                            + VERSION, ex);
                }
                remainder(e, (float) m[at++]);
                pool.push(e);
            }
        }
        warTimer(m[at++]);
        LIST<FactionNPC> npcs = FACTIONS.NPCs();
        for (int i = 0; i < npcs.size(); i++) {
            courtDay(npcs.get(i).court(), m[at++]);
        }
        for (Object b : fboosts()) {
            fboostMul(b, m[at++]);
        }
        resetRaidMaps();
        if (at != m.length) {
            throw new IllegalStateException("SOSDET: " + m.length + " unsaved values, " + at + " for this world");
        }
    }

    private static float remainder(WEntity e) {
        WPath p = e.path();
        return p == null ? 0 : getFloat(p);
    }

    private static void remainder(WEntity e, float m) {
        WPath p = e.path();
        if (p == null) {
            return;
        }
        try {
            MOVEMENT.setFloat(p, m);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("SOSDET: cannot write WPath.movement" + VERSION, ex);
        }
    }

    private static double warTimer() {
        try {
            return WAR_TIMER.getDouble(GAME.events().world.war);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read EventFactionWar.timer" + VERSION, e);
        }
    }

    private static void warTimer(double t) {
        try {
            WAR_TIMER.setDouble(GAME.events().world.war, t);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot write EventFactionWar.timer" + VERSION, e);
        }
    }

    private static double courtDay(NPCCourt c) {
        try {
            return COURT_DAY.getDouble(c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read NPCCourt.addT" + VERSION, e);
        }
    }

    private static void courtDay(NPCCourt c, double t) {
        try {
            COURT_DAY.setDouble(c, t);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot write NPCCourt.addT" + VERSION, e);
        }
    }

    /** Every recipe's NPC production boost, in recipe order, each once. */
    private static List<Object> fboosts() {
        Class<?> c = FBOOST_MUL.getDeclaringClass();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        List<Object> all = new ArrayList<>();
        for (Recipe r : SETT.RECIPES().all()) {
            for (int i = 0; i < r.bo.all().size(); i++) {
                Object b = r.bo.all().get(i);
                if (b.getClass() == c && seen.put(b, Boolean.TRUE) == null) {
                    all.add(b);
                }
            }
        }
        return all;
    }

    private static double fboostMul(Object b) {
        try {
            return FBOOST_MUL.getDouble(b);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read FBoost.mul" + VERSION, e);
        }
    }

    private static void fboostMul(Object b, double mul) {
        try {
            FBOOST_MUL.setDouble(b, mul);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot write FBoost.mul" + VERSION, e);
        }
    }

    private static Class<?> fboostClass() {
        try {
            return Class.forName("settlement.recipe.FBoost");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SOSDET: no class settlement.recipe.FBoost in this game build" + VERSION, e);
        }
    }

    /** As {@code RaidingMap}'s field initialisers: its army power is old, and its regions are to be worked out. */
    private static void resetRaidMaps() {
        try {
            for (RaidingMap r : (RaidingMap[]) RAID_MAPS.get(GAME.raiders())) {
                RAID_ARMY_UPDATED.setDouble(r, -100);
                RAID_DIRTY.setBoolean(r, true);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot reset RAIDING.sosdetEntries" + VERSION, e);
        }
    }

    /** A pool's entities, bottom first, up to its size. */
    private static Object[] pooled(Stack<Object> pool) {
        try {
            return (Object[]) STACK_ITEMS.get(pool);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read Stack.ints" + VERSION, e);
        }
    }

    private static float getFloat(WPath p) {
        try {
            return MOVEMENT.getFloat(p);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read WPath.movement" + VERSION, e);
        }
    }

    /** The world entity pools, as {@link #POOLS}: the caravans', the armies', the camps'. */
    @SuppressWarnings("unchecked") // each is a Stack of its constructor's entities
    private static Stack<Object> pool(int k) {
        try {
            return (Stack<Object>) POOL_FIELDS[k].get(k == 0 ? WORLD.ENTITIES().caravans
                    : k == 1 ? WORLD.ENTITIES().armies : WORLD.ENTITIES().havens);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + POOL_FIELDS[k] + VERSION, e);
        }
    }

    /**
     * Zeroes every world entity's move remainder (armies, caravans). The save doesn't hold it, so a load into the
     * running game would keep each PC's own, and a remainder that differs between PCs (the PT3 kind of desync) would
     * outlive the resync. Every PC, the host included, loads the resync, so every PC then has 0 (owner, 2026-10-25).
     */
    private static void resetMoveRemainders() {
        resetMoveRemainders(WORLD.ENTITIES().allFast());
        resetMoveRemainders(WORLD.ENTITIES().allSlow());
    }

    private static void resetMoveRemainders(LIST<WEntity> entities) {
        try {
            for (int i = 0; i < entities.size(); i++) { // an index loop: snake2d lists share one iterator
                WPath p = entities.get(i).path();
                if (p != null) {
                    MOVEMENT.setFloat(p, 0);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot reset " + MOVEMENT + VERSION, e);
        }
    }

    /** The temporary boosts of the city's population classes ({@code TEMP_EVENTS}' {@code popcl} range). */
    private static SAVABLE[] popBoosts() {
        try {
            Object[] datas = (Object[]) BOOST_DATAS.get(GAME.BOOST());
            TmpBoostable<?> pop = GAME.BOOST().popcl;
            SAVABLE[] r = new SAVABLE[pop.max];
            for (int i = 0; i < pop.max; i++) {
                r[i] = (SAVABLE) datas[pop.startIndex + i];
            }
            return r;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + BOOST_DATAS + VERSION, e);
        }
    }

    /**
     * Makes the game's layout savers, as the start of a save does: the player's city-owned data saves through them
     * (goods and trade through {@code TR.MAP()}, for one), and a PC that hasn't saved since it loaded has none
     * (SOS-38, found by the M3 smoke test). Only the header savers run; the list's other sections hold state.
     */
    private static void layoutSavers() {
        try {
            @SuppressWarnings("unchecked") // GameSaver.before is an ArrayListGrower<Savable>
            LIST<Savable> before = (LIST<Savable>) BEFORE.get(GAME.saver());
            FilePutter headers = new GameCodec(1 << 22).hashBegin();
            for (int i = 0; i < before.size(); i++) {
                Savable s = before.get(i);
                Class<?> owner = s.getClass().getEnclosingClass();
                if (owner == DataO.class || owner == RMAPS.class) {
                    SECTION_SAVE.invoke(s, headers);
                }
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: the game's layout savers could not be made" + VERSION, e);
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
                    if (k.equals(EVENTS_KEY)) {
                        loadWorldEvents(f);
                    } else {
                        SECTION_LOAD.invoke(s, f);
                    }
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

    private static Constructor<?> constructor(Class<?> owner) {
        try {
            Constructor<?> c = owner.getDeclaredConstructor();
            c.setAccessible(true);
            return c;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no constructor " + owner.getName() + "() in this game build" + VERSION, e);
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

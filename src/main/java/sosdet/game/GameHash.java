package sosdet.game;

import java.nio.BufferOverflowException;
import java.util.Locale;

import game.GAME;
import game.battle.div.Div;
import game.battle.setting.DivSettings;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.player.Player;
import game.raiding.Raider;
import game.time.TIME;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.STATS;
import snake2d.util.sets.LIST;
import sosdet.Hooks;
import sosdet.hash.HashLog;
import sosdet.hash.StateSource;
import world.WORLD;
import world.entity.WEntity;
import world.army.AD;
import world.entity.army.WArmy;
import world.map.regions.Region;

/**
 * The battle state for the state hash (hook B13), read through the game's public members. Soldiers and other
 * entities as the rig hashed them (SOS-11, SOS-23); divisions by their counts, settings and orders; projectiles by
 * the bytes of their own save; the world map by its armies, region owners, credits and wars (SOS-35).
 */
public final class GameHash implements StateSource {

    /** Projectiles in flight at once can be many thousands, about 40 bytes each. */
    private static final int MAX_SAVE_BYTES = 1 << 23;

    /** The divisions' fields of the last few hashes, for the desync report: count, settings, orders and set counts. */
    private static final int KEPT = 8;
    private static final int FIELDS = 12;

    private final GameCodec codec = new GameCodec(MAX_SAVE_BYTES);
    private final long[] keptStep = new long[KEPT];
    private long[][] kept;
    private int keptCount;
    /** The other entities of the same hashes: index, id, tile, row hash and class, for the desync report. */
    private int[][] entIndex;
    private int[][] entId;
    private int[][] entX;
    private int[][] entY;
    private long[][] entHash;
    private Class<?>[][] entClass;
    private final int[] entCount = new int[KEPT];
    /** The world of the same hashes: each part's running hash and each army's row, for the desync report. */
    private final WorldKept[] world = new WorldKept[KEPT];

    @Override
    public int divisions() {
        return GAME.ARMIES().divisions().size();
    }

    @Override
    public void hash(long[] sections, long[] divisions) {
        long others = HashLog.start();
        ENTITY[] all = SETT.ENTITIES().getAllEnts();
        int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
        if (entIndex == null) {
            entIndex = new int[KEPT][all.length];
            entId = new int[KEPT][all.length];
            entX = new int[KEPT][all.length];
            entY = new int[KEPT][all.length];
            entHash = new long[KEPT][all.length];
            entClass = new Class<?>[KEPT][all.length];
        }
        int es = keptCount % KEPT; // divisionsHash below takes this slot for the same step
        int en = 0;
        for (int i = 0; i < max; i++) {
            ENTITY e = all[i];
            if (e == null || e.isRemoved()) {
                continue;
            }
            Div d = e instanceof Humanoid ? ((Humanoid) e).division() : null;
            long h = d != null ? divisions[d.index()] : others;
            h = HashLog.mix(h, i);
            h = HashLog.mix(h, e.id());
            h = HashLog.mix(h, e.body().x1());
            h = HashLog.mix(h, e.body().y1());
            h = HashLog.mix(h, Double.doubleToLongBits(e.speed.x()));
            h = HashLog.mix(h, Double.doubleToLongBits(e.speed.y()));
            if (e instanceof Humanoid) {
                h = HashLog.mix(h, Double.doubleToLongBits(STATS.NEEDS().INJURIES.COUNT.indu().getD(((Humanoid) e).indu())));
            } else {
                h = HashLog.mix(h, e.getClass().getName().hashCode());
            }
            if (d != null) {
                divisions[d.index()] = h;
            } else {
                others = h;
                entIndex[es][en] = i;
                entId[es][en] = e.id();
                entX[es][en] = e.body().x1();
                entY[es][en] = e.body().y1();
                entHash[es][en] = h;
                entClass[es][en] = e.getClass();
                en++;
            }
        }
        entCount[es] = en;
        sections[HashLog.ENTITIES] = others;
        sections[HashLog.DIVISIONS] = divisionsHash();
        sections[HashLog.PROJECTILES] = projectilesHash();
        if (world[es] == null) {
            world[es] = new WorldKept(WORLD.ENTITIES().armies.max());
        }
        sections[HashLog.WORLD] = worldHash(world[es]);
    }

    /**
     * The world map: every army's slot, faction, tile, state and men; every world entity; every region's owner; every
     * faction's credits, overlord and stance with every other (war, peace, pact, ...); every NPC king's opinion of
     * every human but slot 0's (SOS-46 M3); every raider's power, defeat and
     * raids, and the raider now raiding (and each other human's, with its war and rebellion timers: SOS-46 M4); the calendar. In own-city mode the humans' credits are their cities' and are
     * left out (ADR 0005, ADR 0004).
     * Public for the rig's per-step log of a follower run.
     */
    public static long worldHash() {
        return worldHash(null);
    }

    /** The world hash; with {@code w}, also each part's running hash and each army's row (no allocation). */
    private static long worldHash(WorldKept w) {
        long h = HashLog.start();
        int n = 0;
        for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
            WArmy a = WORLD.ENTITIES().armies.get(i);
            if (a == null || !a.added()) {
                continue;
            }
            int faction = a.faction() == null ? -1 : a.faction().index();
            int men = AD.men(null).get(a);
            h = HashLog.mix(h, i);
            h = HashLog.mix(h, a.iteration());
            h = HashLog.mix(h, faction);
            h = HashLog.mix(h, a.body().x1());
            h = HashLog.mix(h, a.body().y1());
            h = HashLog.mix(h, a.state().index());
            h = HashLog.mix(h, men);
            if (w != null && n < w.armyHash.length) {
                int o = n * WorldKept.ARMY_FIELDS;
                w.army[o] = i;
                w.army[o + 1] = faction;
                w.army[o + 2] = a.body().x1();
                w.army[o + 3] = a.body().y1();
                w.army[o + 4] = a.state().index();
                w.army[o + 5] = men;
                w.armyHash[n++] = h;
            }
        }
        if (w != null) {
            w.armies = n;
            w.parts[0] = h;
        }
        h = entitiesHash(h, WORLD.ENTITIES().allFast());
        h = part(w, 1, entitiesHash(h, WORLD.ENTITIES().allSlow()));
        LIST<Region> regions = WORLD.REGIONS().all();
        for (int i = 0; i < regions.size(); i++) {
            Faction f = regions.get(i).faction();
            h = HashLog.mix(h, f == null ? -1 : f.index());
        }
        h = part(w, 2, h);
        LIST<Faction> factions = FACTIONS.active();
        boolean ownCity = Hooks.ownCity();
        for (int i = 0; i < factions.size(); i++) {
            Faction f = factions.get(i);
            h = HashLog.mix(h, f.index());
            if (!ownCity || !(f instanceof Player)) {
                h = HashLog.mix(h, Double.doubleToLongBits(f.credits().getD()));
            }
            Faction overlord = DIP.overlord(f);
            h = HashLog.mix(h, overlord == null ? -1 : overlord.index());
            for (int k = i + 1; k < factions.size(); k++) {
                h = HashLog.mix(h, DIP.get(f, factions.get(k)).index()); // every stance, not only war (SOS-46)
            }
        }
        h = part(w, 3, h);
        h = part(w, 4, GameRelations.hash(h));
        LIST<Raider> raiders = GAME.raiders().ALL();
        for (int i = 0; i < raiders.size(); i++) {
            Raider r = raiders.get(i);
            h = HashLog.mix(h, r.army.power);
            h = HashLog.mix(h, r.defeated ? 1 : 0);
            h = HashLog.mix(h, r.raids);
        }
        Raider current = GAME.raiders().current.current();
        h = part(w, 5, HashLog.mix(h, current == null ? -1 : current.army.power));
        h = part(w, 6, GameEvents.hash(h));
        if (w != null) {
            w.eventHumans = GameEvents.keep(w.events);
        }
        return part(w, 7, HashLog.mix(h, Double.doubleToLongBits(TIME.currentSecond())));
    }

    private static long part(WorldKept w, int part, long h) {
        if (w != null) {
            w.parts[part] = h;
        }
        return h;
    }

    /** One hashed step's world, kept for the desync report. */
    private static final class WorldKept {
        static final int ARMY_FIELDS = 6;
        static final String[] PARTS = {"armies", "entities", "region owners", "factions and stances",
            "NPC relations per human", "raiders", "events per human", "calendar"};
        final long[] parts = new long[PARTS.length];
        final int[] army;
        final long[] armyHash;
        int armies;
        /** From the second human on: war timer, rebellion timer, current raider's power. */
        final double[] events = new double[3 * (Hooks.HUMANS_MAX - 1)];
        int eventHumans;

        WorldKept(int maxArmies) {
            army = new int[maxArmies * ARMY_FIELDS];
            armyHash = new long[maxArmies];
        }

        String describe() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < PARTS.length; i++) {
                b.append(String.format(Locale.ROOT, "  after %-24s %016x%n", PARTS[i], parts[i]));
            }
            for (int k = 0; k < armies; k++) {
                int o = k * ARMY_FIELDS;
                b.append(String.format(Locale.ROOT, "  army %d: faction %d at %d,%d, state %d, men %d, running hash %016x%n",
                        army[o], army[o + 1], army[o + 2], army[o + 3], army[o + 4], army[o + 5], armyHash[k]));
            }
            for (int k = 0; k < eventHumans; k++) {
                b.append(String.format(Locale.ROOT, "  human %d: war timer %s, rebellion timer %s, raider power %s%n",
                        k + 1, events[3 * k], events[3 * k + 1], events[3 * k + 2]));
            }
            return b.toString();
        }
    }

    /** Every world entity, caravans and camps too: its slot, kind, tile and faction (SOS-38). */
    private static long entitiesHash(long h, LIST<WEntity> ents) {
        h = HashLog.mix(h, ents.size());
        for (int i = 0; i < ents.size(); i++) {
            WEntity e = ents.get(i);
            h = HashLog.mix(h, e.getClass().getName().hashCode());
            h = HashLog.mix(h, e.body().x1());
            h = HashLog.mix(h, e.body().y1());
            h = HashLog.mix(h, e.faction() == null ? -1 : e.faction().index());
        }
        return h;
    }

    private long divisionsHash() {
        LIST<Div> divs = GAME.ARMIES().divisions();
        if (kept == null) {
            kept = new long[KEPT][divs.size() * FIELDS];
        }
        int slot = keptCount++ % KEPT;
        keptStep[slot] = Hooks.step();
        long[] f = kept[slot];
        long h = HashLog.start();
        for (int i = 0; i < divs.size(); i++) {
            Div d = divs.get(i);
            DivSettings s = d.settings();
            int o = i * FIELDS;
            f[o] = d.men();
            f[o + 1] = d.deployed();
            f[o + 2] = Double.doubleToLongBits(d.morale());
            f[o + 3] = (s.guard ? 1 : 0) | (s.charging ? 2 : 0) | (s.running ? 4 : 0) | (s.fireAtWill ? 8 : 0)
                    | (s.shouldNotMoveToFire ? 16 : 0) | (s.shouldFire ? 32 : 0) | (s.shouldbreak ? 64 : 0)
                    | (s.chargeSpeed ? 128 : 0) | (s.mustering() ? 256 : 0) | (s.moppingUp() ? 512 : 0);
            f[o + 4] = s.ammoI;
            f[o + 5] = s.formation.ordinal();
            f[o + 6] = codec.hash(HashLog.start(), d.order().path);
            f[o + 7] = codec.hash(HashLog.start(), d.order().dest);
            f[o + 8] = codec.hash(HashLog.start(), d.order().task);
            f[o + 9] = d.order().path.setI();
            f[o + 10] = d.order().dest.setI();
            f[o + 11] = d.order().task.setI();
            for (int k = 0; k < FIELDS; k++) {
                h = HashLog.mix(h, f[o + k]);
            }
        }
        return h;
    }

    private long projectilesHash() {
        try {
            SETT.PROJS().save(codec.hashBegin());
        } catch (BufferOverflowException e) {
            throw new IllegalStateException("SOSDET: projectiles' save data over " + MAX_SAVE_BYTES + " bytes", e);
        }
        return codec.hashEnd(HashLog.start());
    }

    @Override
    public String describeDivisions(long step) {
        for (int slot = 0; kept != null && slot < KEPT; slot++) {
            if (keptCount > slot && keptStep[slot] == step) {
                return describe(kept[slot]);
            }
        }
        return "  (not kept)" + System.lineSeparator();
    }

    @Override
    public String describeEntities(long step) {
        for (int slot = 0; kept != null && slot < KEPT; slot++) {
            if (keptCount > slot && keptStep[slot] == step) {
                StringBuilder b = new StringBuilder();
                for (int k = 0; k < entCount[slot]; k++) {
                    b.append(String.format(Locale.ROOT, "  %d: %s id %d at %d,%d, running hash %016x%n",
                            entIndex[slot][k], entClass[slot][k].getSimpleName(), entId[slot][k], entX[slot][k],
                            entY[slot][k], entHash[slot][k]));
                }
                return b.toString();
            }
        }
        return "  (not kept)" + System.lineSeparator();
    }

    @Override
    public String describeWorld(long step) {
        for (int slot = 0; kept != null && slot < KEPT; slot++) {
            if (keptCount > slot && keptStep[slot] == step && world[slot] != null) {
                return world[slot].describe();
            }
        }
        return "  (not kept)" + System.lineSeparator();
    }

    private static String describe(long[] f) {
        StringBuilder b = new StringBuilder();
        for (int o = 0; o < f.length; o += FIELDS) {
            if (f[o] == 0 && f[o + 1] == 0) {
                continue;
            }
            b.append(String.format(Locale.ROOT, "  %d: men %d, deployed %d, morale %016x, flags %x, ammo %d,"
                            + " formation %d, path %016x #%d, dest %016x #%d, task %016x #%d%n", o / FIELDS, f[o],
                    f[o + 1], f[o + 2], f[o + 3], f[o + 4], f[o + 5], f[o + 6], f[o + 9], f[o + 7], f[o + 10], f[o + 8],
                    f[o + 11]));
        }
        return b.toString();
    }

    @Override
    public String divisionName(int index) {
        Div d = GAME.ARMIES().divisions().get(index);
        return (d.army() == GAME.ARMIES().player() ? "player" : "enemy") + " division " + d.indexArmy() + ", "
                + d.men() + " men here";
    }
}

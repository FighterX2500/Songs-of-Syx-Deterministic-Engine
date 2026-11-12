package sosdet.game;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.npc.FactionNPC;
import game.faction.player.Player;
import snake2d.PathTile;
import snake2d.PathUtilOnline.Flooder;
import snake2d.util.datatypes.DIR;
import snake2d.util.datatypes.Rec;
import snake2d.util.sets.Bitsmap2D;
import sosdet.game.carve.CarveBook;
import sosdet.game.carve.CarveRecord;
import sosdet.game.carve.CarveRules;
import util.GUTIL;
import world.WORLD;
import world.map.pathing.WDirMap;
import world.map.regions.Region;
import world.map.regions.RegionInfo;
import world.map.regions.RegionMap;
import world.map.regions.WREGIONS;
import world.map.regions.centre.WCentre;
import world.map.road.WTRAV;
import world.region.RD;

/**
 * Carved city-states (SOS-184, SOSGSGMP docs/plan/hot-join.md §3): a new 3x3 region cut out of a finished, living world,
 * linked by road and path to the town of each region it touches, and the undo of that. A player's capital is such a
 * square ({@code GenPlayer}), but vanilla only ever makes one before it grows the other regions; it never re-partitions
 * a running world.
 *
 * <p>On one PC (the host), between steps, never inside one. The world then reaches every PC through a world save and
 * load ({@link GameWorldLoad}), which rebuilds what vanilla derives from the layout at a load: borders, centre tiles, the
 * active list, the path graph ({@code Comps}). What a load doesn't rebuild is redone here before the save, and the one
 * cache that neither does is reset by {@link #afterLoad} on every PC.
 */
public final class GameCarve {

    /** A capital's square: {@code WCentre.TILE_DIM} tiles a side (3). */
    public static final int DIM = WCentre.TILE_DIM;
    /** Tiles around the square kept free of roads, paths, water and other towns' squares. */
    static final int RING = 1;
    /**
     * Least Chebyshev distance between the new centre and any other region's: a centre's siege ring is its centre ±2
     * ({@code WREGIONS.init}), so at 5 neither square nor ring touches another town's, and no old centre has to move.
     */
    static final int CENTRE_GAP = 5;
    /** As {@code HumanStart.MIN_DISTANCE}: a human's capital at least this many tiles from another's (mod spec §5). */
    static final double HUMAN_GAP = 6;

    private static final String GAME_VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Field DIR_BITS = field(WDirMap.class, "m");
    private static final Method DIR_ADD = method(WDirMap.class, "add", int.class, int.class, DIR.class);
    private static final Method MAKE_ROAD = method(WTRAV.class, "makeRoad", PathTile.class);
    private static final Field RD_INIT = field(RD.class, "init");
    private static final Method INFO_CLEAR = method(RegionInfo.class, "clear");
    private static final Object FLOODER_USER = new Object();

    private GameCarve() {
    }

    // ------------------------------------------------------------ placement (plan §3.1)

    /** Why a city-state can't be carved with its centre at {@code cx, cy}, or null. */
    public static String problem(int cx, int cy) {
        String t = GameWorldSetup.terrainProblem(cx, cy);
        if (t != null) {
            return t;
        }
        int r = DIM / 2 + RING;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                if (!WORLD.IN_BOUNDS(x, y)) {
                    return "Too near the map's edge.";
                }
                if (WORLD.WATER().isBig.is(x, y)) {
                    return "Too near the sea or a lake.";
                }
                if (WORLD.ROADS().is(x, y) || WORLD.PATH().map.is.is(x, y)) {
                    return "On or beside a road.";
                }
                if (inSquare(cx, cy, x, y) && WORLD.REGIONS().map.get(x, y) == null) {
                    return "Outside every region.";
                }
            }
        }
        for (Region a : WORLD.REGIONS().active()) {
            if (Math.max(Math.abs(a.cx() - cx), Math.abs(a.cy() - cy)) < CENTRE_GAP) {
                return "Too near the town of " + a.info.name() + ".";
            }
        }
        for (int s : GameHumans.get().slots()) {
            Region c = FACTIONS.getByIndex(s).capitolRegion();
            if (c != null && Math.hypot(c.cx() - cx, c.cy() - cy) < HUMAN_GAP) {
                return "Closer than " + (int) HUMAN_GAP + " tiles to a player's capital.";
            }
        }
        if (WORLD.ENTITIES().fillTiles(cx - DIM / 2, cx + DIM / 2 + 1, cy - DIM / 2, cy + DIM / 2 + 1).size() > 0) {
            return "An army, a caravan or a camp stands there.";
        }
        boolean[] cut = cutRegions(cx, cy);
        for (int i = 0; i < cut.length; i++) {
            if (!cut[i]) {
                continue;
            }
            Region reg = WORLD.REGIONS().getByIndex(i);
            if (WORLD.BATTLES().besiged(reg)) {
                return reg.info.name() + " is under siege.";
            }
            if (reg.faction() instanceof Player && reg.capitol()) {
                return "On a player's city-state.";
            }
            if (splits(reg, cx, cy)) {
                return "It would cut " + reg.info.name() + " in two.";
            }
        }
        if (freeRegion() == null) {
            return "The world has no room for another region.";
        }
        boolean[] near = touching(cx, cy);
        for (int i = 0; i < near.length; i++) {
            if (near[i] && route(cx, cy, WORLD.REGIONS().getByIndex(i), false) == null) {
                return "No road could reach the town of " + WORLD.REGIONS().getByIndex(i).info.name() + ".";
            }
        }
        return null;
    }

    /**
     * The first centre, row by row, where a city-state can be carved: {@code border} false, inside one owned region and
     * touching only it; true, touching at least two regions. Null when there is none. For tests and tools.
     */
    public static int[] spot(boolean border, int after) {
        int start = after < 0 ? 0 : after + 1;
        for (int tile = start; tile < WORLD.TAREA(); tile++) {
            int x = tile % WORLD.TWIDTH();
            int y = tile / WORLD.TWIDTH();
            Region here = WORLD.REGIONS().map.get(x, y);
            if (here == null || !here.active() || here.faction() == null || WORLD.ROADS().is(x, y)
                    || WORLD.WATER().isBig.is(x, y)) {
                continue;
            }
            boolean[] near = touching(x, y);
            int n = 0;
            for (boolean b : near) {
                n += b ? 1 : 0;
            }
            if (border ? n < 2 : n != 1 || !near[here.index()]) {
                continue;
            }
            if (problem(x, y) == null) {
                return new int[] {x, y, tile};
            }
        }
        return null;
    }

    private static boolean inSquare(int cx, int cy, int x, int y) {
        return Math.abs(x - cx) <= DIM / 2 && Math.abs(y - cy) <= DIM / 2;
    }

    /** The regions holding the square's tiles, by index. */
    private static boolean[] cutRegions(int cx, int cy) {
        boolean[] cut = new boolean[WREGIONS.MAX];
        for (int y = cy - DIM / 2; y <= cy + DIM / 2; y++) {
            for (int x = cx - DIM / 2; x <= cx + DIM / 2; x++) {
                Region reg = WORLD.REGIONS().map.get(x, y);
                if (reg != null) {
                    cut[reg.index()] = true;
                }
            }
        }
        return cut;
    }

    /**
     * The active regions the new square will touch once carved, by index: those on the square's outer edge, the
     * square's own included where they lose tiles to it (each region the square touches gets one link, as {@code GenLand}
     * lays one between touching regions).
     */
    private static boolean[] touching(int cx, int cy) {
        boolean[] near = new boolean[WREGIONS.MAX];
        int r = DIM / 2 + 1;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                boolean edge = Math.abs(x - cx) == r || Math.abs(y - cy) == r;
                boolean corner = Math.abs(x - cx) == r && Math.abs(y - cy) == r;
                if (!edge || corner) {
                    continue; // only the four sides: regions meet across an edge, never a corner (WTRAV.can)
                }
                Region reg = WORLD.REGIONS().map.get(x, y);
                if (reg != null && reg.active()) {
                    near[reg.index()] = true;
                }
            }
        }
        return near;
    }

    /** Whether taking the square out of {@code reg} leaves part of it cut off from its centre. */
    private static boolean splits(Region reg, int cx, int cy) {
        Rec b = new Rec(reg.info.bounds());
        int w = b.width() + 2;
        int h = b.height() + 2;
        int x0 = b.x1() - 1;
        int y0 = b.y1() - 1;
        int all = reached(reg, x0, y0, w, h, cx, cy, false);
        int left = reached(reg, x0, y0, w, h, cx, cy, true);
        int taken = 0;
        for (int y = cy - DIM / 2; y <= cy + DIM / 2; y++) {
            for (int x = cx - DIM / 2; x <= cx + DIM / 2; x++) {
                if (WORLD.REGIONS().map.get(x, y) == reg) {
                    taken++;
                }
            }
        }
        return left < all - taken;
    }

    /** How many of {@code reg}'s tiles its centre reaches, orthogonally, with or without the square cut out. */
    private static int reached(Region reg, int x0, int y0, int w, int h, int cx, int cy, boolean withoutSquare) {
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0;
        int tail = 0;
        if (withoutSquare && inSquare(cx, cy, reg.cx(), reg.cy())) {
            return 0;
        }
        queue[tail++] = (reg.cy() - y0) * w + (reg.cx() - x0);
        seen[queue[0]] = true;
        while (head < tail) {
            int i = queue[head++];
            int x = x0 + i % w;
            int y = y0 + i / w;
            for (DIR d : DIR.ORTHO) {
                int nx = x + d.x();
                int ny = y + d.y();
                int lx = nx - x0;
                int ly = ny - y0;
                if (lx < 0 || ly < 0 || lx >= w || ly >= h) {
                    continue;
                }
                int j = ly * w + lx;
                if (seen[j] || WORLD.REGIONS().map.get(nx, ny) != reg || (withoutSquare && inSquare(cx, cy, nx, ny))) {
                    continue;
                }
                seen[j] = true;
                queue[tail++] = j;
            }
        }
        return tail;
    }

    /** An unused region: no tiles, no owner. Null when every index is in use. */
    private static Region freeRegion() {
        for (int i = 1; i < WREGIONS.MAX; i++) {
            Region reg = WORLD.REGIONS().getByIndex(i);
            if (reg.info.area() == 0 && reg.faction() == null && !reg.active()) {
                return reg;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ carving (plan §3.2)

    /**
     * Carves a city-state named {@code name} with its centre at {@code cx, cy} and records it in the world's carve book.
     * The square's region has no owner: the caller gives it one before the world is saved. The land it is carved in
     * follows {@link CarveRules}; the record's {@link CarveRecord#newNpc} is the NPC it went to, and the result's
     * {@code noSlot} says a new NPC was wanted but no faction slot was free. Throws when {@link #problem} isn't null.
     */
    public static Result carve(int cx, int cy, CharSequence name) {
        String p = problem(cx, cy);
        if (p != null) {
            throw new IllegalArgumentException("SOSDET: no city-state at " + cx + "," + cy + ": " + p);
        }
        boolean[] near = touching(cx, cy);
        Region home = WORLD.REGIONS().map.get(cx, cy);
        Faction holder = home.faction();
        CarveRules.Holder kind = holder == null ? CarveRules.Holder.NOBODY
                : holder instanceof FactionNPC ? CarveRules.Holder.NPC : CarveRules.Holder.HUMAN;
        int realmRegions = holder == null ? 0 : holder.realm().regions();
        boolean realmCapital = home.capitol();
        Region reg = freeRegion();
        CarveRecord c = new CarveRecord(reg.index(), cx, cy);
        RegionMap map = (RegionMap) WORLD.REGIONS().map;
        int k = 0;
        for (int y = cy - DIM / 2; y <= cy + DIM / 2; y++) {
            for (int x = cx - DIM / 2; x <= cx + DIM / 2; x++) {
                Region old = map.get(x, y);
                c.square(k++, x + y * WORLD.TWIDTH(), old == null ? -1 : old.index());
                map.set(x, y, reg);
            }
        }
        if (!reg.info.init(cx - DIM / 2, cy - DIM / 2, new Rec(DIM, DIM).moveX1Y1(cx - DIM / 2, cy - DIM / 2))) {
            throw new IllegalStateException("SOSDET: region " + reg.index() + "'s info refused its square at " + cx + ","
                    + cy + " (patch 17's RegionInfo change missing?)" + GAME_VERSION);
        }
        if (reg.cx() != cx || reg.cy() != cy) {
            throw new IllegalStateException("SOSDET: region " + reg.index() + "'s centre went to " + reg.cx() + ","
                    + reg.cy() + ", not " + cx + "," + cy);
        }
        name(reg, name);
        reinit(c.cutFrom());
        prime(reg);
        for (int i = 0; i < near.length; i++) {
            if (near[i]) {
                link(c, reg, WORLD.REGIONS().getByIndex(i));
            }
        }
        CarveRules.Outcome wanted = CarveRules.wanted(kind, realmRegions, realmCapital);
        CarveRules.Outcome outcome = CarveRules.outcome(kind, realmRegions, realmCapital, GameHumans.get().npcSlotFree());
        if (outcome == CarveRules.Outcome.BREAK_AWAY) {
            RD.setFaction(home, null, false);
        }
        if (outcome != CarveRules.Outcome.KEEP) {
            FactionNPC npc = GameHumans.get().activateNpc(home, true);
            if (npc == null) {
                throw new IllegalStateException("SOSDET: no NPC slot for region " + home.index() + " though one was free");
            }
            c.newNpc(npc.index());
        }
        book().add(c);
        return new Result(c, home.index(), outcome, wanted != outcome);
    }

    /** What {@link #carve} did. */
    public static final class Result {
        public final CarveRecord record;
        /** The region the square's centre was carved out of. */
        public final int home;
        public final CarveRules.Outcome outcome;
        /** A new NPC was wanted for the home region, but every faction slot was in use. */
        public final boolean noSlot;

        Result(CarveRecord record, int home, CarveRules.Outcome outcome, boolean noSlot) {
            this.record = record;
            this.home = home;
            this.outcome = outcome;
            this.noSlot = noSlot;
        }

        @Override
        public String toString() {
            return record + "; region " + home + ": " + outcome + (noSlot ? " (no free faction slot)" : "");
        }
    }

    private static CarveBook book() {
        return GameHumans.get().carves().book();
    }

    /** Names the region, cut to what a region name holds. */
    private static void name(Region reg, CharSequence name) {
        CharSequence n = name.length() > RegionInfo.nameSize ? name.subSequence(0, RegionInfo.nameSize) : name;
        reg.info.name().clear().add(n);
    }

    /** Puts the regions' info right after their tiles changed; their centres stay where they were (rule 5). */
    private static void reinit(Iterable<Integer> regions) {
        for (int i : regions) {
            Region r = WORLD.REGIONS().getByIndex(i);
            if (r.info.area() == 0) {
                continue;
            }
            int sx = r.cx();
            int sy = r.cy();
            if (WORLD.REGIONS().map.get(sx, sy) != r) {
                throw new IllegalStateException("SOSDET: region " + i + "'s centre " + sx + "," + sy + " left it");
            }
            r.info.init(sx, sy, new Rec(r.info.bounds()));
        }
    }

    /** A new region's data as the world generator primes every region ({@code RD.prime}): random bits, then buildings and each updater's start. */
    private static void prime(Region reg) {
        RD.RAN().randomize(reg);
        RD.RDInit init;
        try {
            init = (RD.RDInit) RD_INIT.get(RD.self);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read RD.init" + GAME_VERSION, e);
        }
        for (int i = 0; i < 3; i++) {
            RD.UPDATER().BUILD(reg);
            for (RD.RDUpdatable up : init.upers) {
                up.init(reg);
            }
        }
    }

    // ------------------------------------------------------------ links (plan §3.2 steps 6 and 7)

    /**
     * A road and path link from the new town to {@code to}'s, over the two regions' land only (as {@code GenLand} links
     * two touching regions); an existing path or road step is cheaper, so the link joins the network where it can.
     */
    private static void link(CarveRecord c, Region from, Region to) {
        PathTile end = route(from.cx(), from.cy(), to, true);
        if (end == null) {
            GUTIL.flooder().done();
            throw new IllegalStateException("SOSDET: no road from " + from.cx() + "," + from.cy() + " to the town of region "
                    + to.index());
        }
        int x1 = Integer.MAX_VALUE;
        int y1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE;
        int y2 = Integer.MIN_VALUE;
        for (PathTile t = end; t != null; t = t.getParent()) {
            x1 = Math.min(x1, t.x());
            y1 = Math.min(y1, t.y());
            x2 = Math.max(x2, t.x());
            y2 = Math.max(y2, t.y());
        }
        x1 -= 2;
        y1 -= 2;
        x2 += 2;
        y2 += 2;
        Bitsmap2D bits = bits();
        boolean[] roadBefore = new boolean[(x2 - x1 + 1) * (y2 - y1 + 1)];
        int[] pathBefore = new int[roadBefore.length];
        int w = x2 - x1 + 1;
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                if (WORLD.IN_BOUNDS(x, y)) {
                    roadBefore[(y - y1) * w + (x - x1)] = WORLD.ROADS().is(x, y);
                    pathBefore[(y - y1) * w + (x - x1)] = bits.get(x, y);
                }
            }
        }
        try {
            MAKE_ROAD.invoke(null, end);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: cannot lay a road" + GAME_VERSION, e);
        }
        for (PathTile t = end; t.getParent() != null; t = t.getParent()) {
            PathTile p = t.getParent();
            add(t.x(), t.y(), DIR.get(t, p));
            add(p.x(), p.y(), DIR.get(p, t));
        }
        GUTIL.flooder().done();
        densify(x1, y1, x2, y2);
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                if (!WORLD.IN_BOUNDS(x, y)) {
                    continue;
                }
                int i = (y - y1) * w + (x - x1);
                int tile = x + y * WORLD.TWIDTH();
                if (!roadBefore[i] && WORLD.ROADS().is(x, y)) {
                    c.road(tile);
                }
                if (pathBefore[i] != bits.get(x, y)) {
                    c.path(tile, pathBefore[i], bits.get(x, y));
                }
            }
        }
        c.linked(to.index());
    }

    /**
     * The cheapest way from {@code sx, sy} to {@code to}'s centre over the land of {@code to} and of the region at
     * {@code sx, sy}: vanilla's path graph joins a region's town to its borders only, so a link through a third region
     * would never be walked (SOS-187's setup run: two humans 14.6 tiles apart walked 253 tiles beside such a road). With
     * {@code keep} the flooder stays open for the caller to read the way and close; without, it's closed and a non-null
     * result only says a way exists.
     */
    private static PathTile route(int sx, int sy, Region to, boolean keep) {
        Region home = WORLD.REGIONS().map.get(sx, sy);
        Flooder f = GUTIL.flooder();
        f.init(FLOODER_USER);
        f.pushSloppy(sx, sy, 0);
        PathTile found = null;
        while (f.hasMore()) {
            PathTile t = f.pollSmallest();
            if (t.isSameAs(to.cx(), to.cy())) {
                found = t;
                break;
            }
            for (DIR d : DIR.ALL) {
                if (!WORLD.IN_BOUNDS(t, d)) {
                    continue;
                }
                Region there = WORLD.REGIONS().map.get(t, d);
                boolean ours = there == to || there == home
                        || (!keep && inSquare(sx, sy, t.x() + d.x(), t.y() + d.y()));
                if (!ours) {
                    continue;
                }
                double v = cost(t, d);
                if (v > 0) {
                    f.pushSmaller(t, d, t.getValue() + v * d.tileDistance(), t);
                }
            }
        }
        if (!keep) {
            f.done();
        }
        return found;
    }

    /** A step's price: the network, a road, new land; 0 for no step (water, mountains, a diagonal between regions). */
    private static double cost(PathTile t, DIR d) {
        if (WORLD.PATH().map.can(t, d)) {
            return 0.5;
        }
        if (!WTRAV.can(t.x(), t.y(), d, false)) {
            return 0;
        }
        int tx = t.x() + d.x();
        int ty = t.y() + d.y();
        if (WORLD.WATER().isBig.is(tx, ty)) {
            return 0;
        }
        return WORLD.ROADS().is(tx, ty) ? 0.75 : 1;
    }

    /** {@code Gen.generateAll}'s last pass, in the link's box: every road step between path tiles of one region is a path step. */
    private static void densify(int x1, int y1, int x2, int y2) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                if (!WORLD.IN_BOUNDS(x, y) || !WORLD.PATH().map.is.is(x, y)) {
                    continue;
                }
                Region reg = WORLD.PATH().regMap.get(x, y);
                boolean w = WORLD.WATER().isBig.is(x, y);
                for (DIR d : DIR.ALL) {
                    if (!WORLD.IN_BOUNDS(x, y, d)) {
                        continue;
                    }
                    if (w != WORLD.WATER().isBig.is(x, y)) {
                        continue;
                    }
                    if (!w && reg != WORLD.PATH().regMap.get(x, y, d)) {
                        continue;
                    }
                    if (WTRAV.can(x, y, d, true) && WORLD.PATH().map.is.is(x + d.x(), y + d.y())) {
                        add(x, y, d);
                        add(x + d.x(), y + d.y(), d.perpendicular());
                    }
                }
            }
        }
    }

    private static void add(int x, int y, DIR d) {
        try {
            DIR_ADD.invoke(WORLD.PATH().map, x, y, d);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: cannot add a path step" + GAME_VERSION, e);
        }
    }

    private static Bitsmap2D bits() {
        try {
            return (Bitsmap2D) DIR_BITS.get(WORLD.PATH().map);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read the path map" + GAME_VERSION, e);
        }
    }

    // ------------------------------------------------------------ un-carving (plan §3.4)

    /**
     * Un-carves the city-state of region {@code region}: the path steps and roads its links added that no other carve
     * uses, the tiles back to the regions they came from, the region cleared and its owner gone (an NPC with no other
     * region is removed; a human's faction is the caller's). The land's new NPC, if the carve made one, stays. On the PC
     * that saves the world, outside the step.
     */
    public static void uncarve(int region) {
        CarveRecord c = book().find(region);
        if (c == null) {
            throw new IllegalArgumentException("SOSDET: region " + region + " isn't a carved city-state");
        }
        Region reg = WORLD.REGIONS().getByIndex(region);
        Faction owner = reg.faction();
        if (owner instanceof FactionNPC && owner.realm().regions() == 1) {
            FACTIONS.remove((FactionNPC) owner, false);
        } else if (owner != null) {
            RD.setFaction(reg, null, false);
        }
        Bitsmap2D bits = bits();
        for (int tile : c.pathTiles()) {
            int x = tile % WORLD.TWIDTH();
            int y = tile / WORLD.TWIDTH();
            bits.set(x, y, bits.get(x, y) & ~book().bitsToTakeOut(c, tile));
        }
        for (int tile : c.roads()) {
            if (book().roadToTakeOut(c, tile)) {
                WORLD.ROADS().set(tile, false);
            }
        }
        RegionMap map = (RegionMap) WORLD.REGIONS().map;
        for (int k = 0; k < CarveRecord.SQUARE; k++) {
            int prev = c.previous(k);
            map.set(c.tile(k), prev < 0 ? null : WORLD.REGIONS().getByIndex(prev));
        }
        try {
            INFO_CLEAR.invoke(reg.info);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: cannot clear region " + region + "'s info" + GAME_VERSION, e);
        }
        book().remove(c);
        reinit(c.cutFrom());
    }

    // ------------------------------------------------------------ after a load, on every PC

    /**
     * After a world load on every PC: the one layout-derived cache a load doesn't reset. {@code RDProspects}' averages are
     * taken once from the active regions and kept, and NPC production reads them (SOSGSGMP hot-join audit, 2026-11-11).
     */
    public static void afterLoad() {
        RD.PROSPECT().tt();
    }

    /** A digest of the world's layout: each tile's region, roads and path steps, and each region's centre and area. */
    public static long layoutDigest() {
        long h = 0xcbf29ce484222325L;
        Bitsmap2D bits = bits();
        for (int y = 0; y < WORLD.THEIGHT(); y++) {
            for (int x = 0; x < WORLD.TWIDTH(); x++) {
                Region reg = WORLD.REGIONS().map.get(x, y);
                h = mix(h, reg == null ? 0 : reg.index() + 1);
                h = mix(h, WORLD.ROADS().is(x, y) ? 1 : 0);
                h = mix(h, bits.get(x, y));
            }
        }
        for (Region reg : WORLD.REGIONS().all()) {
            h = mix(h, reg.info.area());
            h = mix(h, reg.cx());
            h = mix(h, reg.cy());
        }
        return h;
    }

    private static long mix(long h, int v) {
        return (h ^ v) * 0x100000001b3L;
    }

    /** One region's info for the rig's dumps: centre, area, bounds, climate, terrain shares, moisture, owner. */
    public static String describe(Region reg) {
        StringBuilder b = new StringBuilder();
        b.append("region ").append(reg.index()).append(' ').append(reg.info.name())
                .append(" centre ").append(reg.cx()).append(',').append(reg.cy())
                .append(" area ").append(reg.info.area())
                .append(" active ").append(reg.active())
                .append(String.format(java.util.Locale.ROOT, " climate %.3f moisture %.3f", reg.info.climateI(),
                        reg.info.moisture()));
        b.append(" terrain");
        for (init.type.TERRAIN t : init.type.TERRAINS.ALL()) {
            b.append(String.format(java.util.Locale.ROOT, " %s:%.2f", t.key, reg.info.terrain(t)));
        }
        Faction f = reg.faction();
        b.append(" owner ").append(f == null ? "none" : f.index() + " " + f.name);
        return b.toString();
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

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no method " + owner.getName() + "." + name + " in this game build"
                    + GAME_VERSION, e);
        }
    }
}

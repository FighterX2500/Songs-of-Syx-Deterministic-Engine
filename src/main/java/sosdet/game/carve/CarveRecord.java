package sosdet.game.carve;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import sosdet.hash.HashLog;

/**
 * One carved city-state and what it changed, enough to undo it (SOSGSGMP docs/plan/hot-join.md §3.5): the square's
 * tiles and the region each held before, the road tiles and path steps its links added, the regions it was cut from and
 * linked to, and the NPC the land became, if any. Tiles are world tile indexes ({@code x + y * width}); regions and
 * factions are indexes. Plain Java, no game classes.
 */
public final class CarveRecord {

    /** Tiles in a square: 3 by 3. */
    public static final int SQUARE = 9;

    /** The new region's index. */
    public final int region;
    public final int cx;
    public final int cy;
    final int[] tiles = new int[SQUARE];
    /** The region each square tile held before: index + 1, 0 for none. */
    final int[] previous = new int[SQUARE];
    /** Tiles that became road. */
    final TreeSet<Integer> roads = new TreeSet<>();
    /** Path tiles whose step bits changed: tile to {before, after}. */
    final TreeMap<Integer, int[]> paths = new TreeMap<>();
    final TreeSet<Integer> cutFrom = new TreeSet<>();
    final TreeSet<Integer> linked = new TreeSet<>();
    /** The faction the carved-in land went to under {@link CarveRules}, -1 for none. */
    private int newNpc = -1;

    public CarveRecord(int region, int cx, int cy) {
        this.region = region;
        this.cx = cx;
        this.cy = cy;
    }

    /** Square tile {@code k}: the world tile, and the region it held before (-1 for none). */
    public void square(int k, int tile, int previousRegion) {
        tiles[k] = tile;
        previous[k] = previousRegion + 1;
        if (previousRegion >= 0) {
            cutFrom.add(previousRegion);
        }
    }

    public int tile(int k) {
        return tiles[k];
    }

    /** The region square tile {@code k} held before, -1 for none. */
    public int previous(int k) {
        return previous[k] - 1;
    }

    public void road(int tile) {
        roads.add(tile);
    }

    /** A path tile's step bits before and after the links; the first before counts if a tile changes twice. */
    public void path(int tile, int before, int after) {
        int[] ba = paths.get(tile);
        if (ba == null) {
            paths.put(tile, new int[] {before, after});
        } else {
            ba[1] = after;
        }
    }

    public void linked(int region) {
        linked.add(region);
    }

    public void newNpc(int faction) {
        newNpc = faction;
    }

    public int newNpc() {
        return newNpc;
    }

    public Iterable<Integer> roads() {
        return roads;
    }

    public Iterable<Integer> cutFrom() {
        return cutFrom;
    }

    public Iterable<Integer> linkedRegions() {
        return linked;
    }

    public int roadCount() {
        return roads.size();
    }

    public int pathCount() {
        return paths.size();
    }

    /** The step bits this carve added at {@code tile}, 0 if none. */
    public int addedBits(int tile) {
        int[] ba = paths.get(tile);
        return ba == null ? 0 : ba[1] & ~ba[0];
    }

    /** The path tiles this carve changed, in order. */
    public Iterable<Integer> pathTiles() {
        return paths.keySet();
    }

    @Override
    public String toString() {
        return "carve region " + region + " at " + cx + "," + cy + ": cut from " + cutFrom + ", linked to " + linked + ", "
                + roads.size() + " road tiles, " + paths.size() + " path tiles changed"
                + (newNpc >= 0 ? ", the land to faction " + newNpc : "");
    }

    // ------------------------------------------------------------ ints

    int[] toInts() {
        int n = 4 + 2 * SQUARE + 1 + roads.size() + 1 + 3 * paths.size() + 1 + cutFrom.size() + 1 + linked.size();
        int[] v = new int[n];
        int at = 0;
        v[at++] = region;
        v[at++] = cx;
        v[at++] = cy;
        v[at++] = newNpc;
        for (int k = 0; k < SQUARE; k++) {
            v[at++] = tiles[k];
            v[at++] = previous[k];
        }
        v[at++] = roads.size();
        for (int t : roads) {
            v[at++] = t;
        }
        v[at++] = paths.size();
        for (Map.Entry<Integer, int[]> e : paths.entrySet()) {
            v[at++] = e.getKey();
            v[at++] = e.getValue()[0];
            v[at++] = e.getValue()[1];
        }
        v[at++] = cutFrom.size();
        for (int r : cutFrom) {
            v[at++] = r;
        }
        v[at++] = linked.size();
        for (int r : linked) {
            v[at++] = r;
        }
        return v;
    }

    /** A record from {@code v} at {@code from}, {@code len} ints. */
    static CarveRecord fromInts(int[] v, int from, int len) throws IOException {
        Reader r = new Reader(v, from, len);
        CarveRecord c = new CarveRecord(r.next(), r.next(), r.next());
        c.newNpc = r.next();
        for (int k = 0; k < SQUARE; k++) {
            c.tiles[k] = r.next();
            c.previous[k] = r.next();
        }
        for (int i = r.count(1); i > 0; i--) {
            c.roads.add(r.next());
        }
        for (int i = r.count(3); i > 0; i--) {
            c.paths.put(r.next(), new int[] {r.next(), r.next()});
        }
        for (int i = r.count(1); i > 0; i--) {
            c.cutFrom.add(r.next());
        }
        for (int i = r.count(1); i > 0; i--) {
            c.linked.add(r.next());
        }
        if (r.at != from + len) {
            throw new IOException("SOSDET: a carve record of " + len + " ints holds " + (r.at - from));
        }
        return c;
    }

    long hash(long h) {
        int[] v = toInts();
        for (int x : v) {
            h = HashLog.mix(h, x);
        }
        return h;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CarveRecord && Arrays.equals(toInts(), ((CarveRecord) o).toInts());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(toInts());
    }

    private static final class Reader {
        private final int[] v;
        private final int end;
        private int at;

        Reader(int[] v, int from, int len) {
            this.v = v;
            this.at = from;
            this.end = from + len;
        }

        int next() throws IOException {
            if (at >= end) {
                throw new IOException("SOSDET: a carve record ends early at " + at);
            }
            return v[at++];
        }

        /** A count of entries of {@code width} ints each, checked against what is left. */
        int count(int width) throws IOException {
            int n = next();
            if (n < 0 || (long) n * width > end - at) {
                throw new IOException("SOSDET: a carve record with a list of " + n + " at " + (at - 1));
            }
            return n;
        }
    }
}

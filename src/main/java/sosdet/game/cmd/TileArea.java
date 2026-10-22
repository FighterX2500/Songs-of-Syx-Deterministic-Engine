package sosdet.game.cmd;

import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;

/**
 * The tiles of a tool's dragged area as a command argument: its bounding rectangle, then either "every tile" (a
 * rectangle drag, the usual case: 17 bytes) or one bit per tile of the rectangle, row by row, 32 to an int (a 21 by 21
 * oval: 73 bytes; a 100 by 100 hollow square: about 1.3 KB). Game-free, so the codec is tested on its own.
 */
final class TileArea {

    /** Which tiles of the rectangle belong to the area, in map coordinates. */
    interface Tiles {
        boolean is(int tx, int ty);
    }

    /** More tiles than any map holds (V71's largest settlement map is 1024 by 1024): a corrupt payload. */
    private static final int MAX_TILES = 1 << 24;

    final int x1;
    final int y1;
    final int width;
    final int height;
    final int count;
    /** One bit per tile of the rectangle, or null when every tile is in. */
    private final int[] words;

    private TileArea(int x1, int y1, int width, int height, int count, int[] words) {
        this.x1 = x1;
        this.y1 = y1;
        this.width = width;
        this.height = height;
        this.count = count;
        this.words = words;
    }

    static void write(CmdWriter w, int x1, int y1, int width, int height, Tiles tiles) {
        check(width, height);
        int n = width * height;
        int[] words = new int[(n + 31) >>> 5];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (tiles.is(x1 + i % width, y1 + i / width)) {
                words[i >>> 5] |= 1 << (i & 31);
                count++;
            }
        }
        w.i(x1).i(y1).i(width).i(height).b(count == n);
        if (count == n) {
            return;
        }
        for (int word : words) {
            w.i(word);
        }
    }

    static TileArea read(CmdReader r) {
        int x1 = r.i();
        int y1 = r.i();
        int width = r.i();
        int height = r.i();
        check(width, height);
        int n = width * height;
        if (r.b()) {
            return new TileArea(x1, y1, width, height, n, null);
        }
        int[] words = new int[(n + 31) >>> 5];
        int count = 0;
        for (int k = 0; k < words.length; k++) {
            words[k] = r.i();
            count += Integer.bitCount(words[k]);
        }
        return new TileArea(x1, y1, width, height, count, words);
    }

    boolean is(int tx, int ty) {
        int dx = tx - x1;
        int dy = ty - y1;
        if (dx < 0 || dy < 0 || dx >= width || dy >= height) {
            return false;
        }
        if (words == null) {
            return true;
        }
        int i = dx + dy * width;
        return (words[i >>> 5] & (1 << (i & 31))) != 0;
    }

    private static void check(int width, int height) {
        if (width < 0 || height < 0 || (long) width * height > MAX_TILES) {
            throw new IllegalArgumentException("SOSDET: a tool area of " + width + " by " + height + " tiles");
        }
    }
}

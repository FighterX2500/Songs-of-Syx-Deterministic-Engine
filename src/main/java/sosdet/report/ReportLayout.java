package sosdet.report;

import java.util.Arrays;

/**
 * Where each field's values sit in a report: the sizes come from the game's data (tradables, races and so on), the
 * same on every PC that runs the same game and mods. A report from a PC whose sizes differ is refused.
 */
public final class ReportLayout {

    private static final Field[] FIELDS = Field.values();

    private final int[] counts;
    private final int[] offset = new int[FIELDS.length];
    private final int size;

    /** One count per {@link Count}, in its order, each without the "all" slot. */
    public ReportLayout(int... counts) {
        if (counts.length != Count.values().length) {
            throw new IllegalArgumentException("SOSDET: " + counts.length + " report sizes for "
                    + Arrays.toString(Count.values()));
        }
        for (int n : counts) {
            if (n < 0) {
                throw new IllegalArgumentException("SOSDET: report sizes " + Arrays.toString(counts));
            }
        }
        this.counts = counts.clone();
        int at = 0;
        for (Field f : FIELDS) {
            offset[f.ordinal()] = at;
            at += count(f.shape);
        }
        size = at;
    }

    /** All values of a report. */
    public int size() {
        return size;
    }

    public int count(Shape shape) {
        if (shape.first == null) {
            return 1;
        }
        int n = length(shape.first, shape.firstAll);
        return shape.second == null ? n : n * length(shape.second, shape.secondAll);
    }

    private int length(Count c, boolean all) {
        return counts[c.ordinal()] + (all ? 1 : 0);
    }

    /** The slot of a field of one value, or of value {@code i} of a field of one list. */
    public int slot(Field f, int i) {
        if (f.shape.second != null || i < 0 || i >= count(f.shape)) {
            throw new IllegalArgumentException("SOSDET: report field " + f + " has no value " + i);
        }
        return offset[f.ordinal()] + i;
    }

    /** The slot of value {@code i, j} of a field of two lists, such as tradable and trade type slot. */
    public int slot(Field f, int i, int j) {
        if (f.shape.second == null) {
            throw new IllegalArgumentException("SOSDET: report field " + f + " is not of two lists");
        }
        int ni = length(f.shape.first, f.shape.firstAll);
        int nj = length(f.shape.second, f.shape.secondAll);
        if (i < 0 || i >= ni || j < 0 || j >= nj) {
            throw new IllegalArgumentException("SOSDET: report field " + f + " has no value " + i + ", " + j);
        }
        return offset[f.ordinal()] + i * nj + j;
    }

    boolean sameSizes(int[] sizes) {
        return Arrays.equals(counts, sizes);
    }

    int[] sizes() {
        return counts.clone();
    }
}

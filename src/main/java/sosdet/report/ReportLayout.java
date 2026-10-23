package sosdet.report;

/**
 * Where each field's values sit in a report: the sizes come from the game's data (tradables, races and so on), the
 * same on every PC that runs the same game and mods. A report from a PC whose sizes differ is refused.
 */
public final class ReportLayout {

    private static final Field[] FIELDS = Field.values();

    final int tradables;
    final int tradeTypes;
    final int races;
    final int classes;
    final int divisions;
    final int equips;
    private final int[] offset = new int[FIELDS.length];
    private final int size;

    /** Counts without the "all" slot: {@code tradeTypes}, {@code classes} and {@code races} as the game lists them. */
    public ReportLayout(int tradables, int tradeTypes, int races, int classes, int divisions, int equips) {
        if (tradables < 0 || tradeTypes < 0 || races < 0 || classes < 0 || divisions < 0 || equips < 0) {
            throw new IllegalArgumentException("SOSDET: report sizes " + tradables + ", " + tradeTypes + ", " + races
                    + ", " + classes + ", " + divisions + ", " + equips);
        }
        this.tradables = tradables;
        this.tradeTypes = tradeTypes;
        this.races = races;
        this.classes = classes;
        this.divisions = divisions;
        this.equips = equips;
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
        switch (shape) {
        case ONE:
            return 1;
        case TRADABLE:
            return tradables;
        case TRADABLE_TYPE:
            return tradables * (tradeTypes + 1);
        case RACE:
            return races;
        case CLASS_RACE:
            return (classes + 1) * (races + 1);
        case DIVISION:
            return divisions;
        case DIVISION_EQUIP:
            return divisions * equips;
        default:
            throw new IllegalArgumentException("SOSDET: report shape " + shape);
        }
    }

    /** The slot of a field of one value, or of value {@code i} of a field of one list. */
    public int slot(Field f, int i) {
        int n = count(f.shape);
        if (f.shape == Shape.TRADABLE_TYPE || f.shape == Shape.CLASS_RACE || f.shape == Shape.DIVISION_EQUIP
                || i < 0 || i >= n) {
            throw new IllegalArgumentException("SOSDET: report field " + f + " has no value " + i);
        }
        return offset[f.ordinal()] + i;
    }

    /**
     * The slot of a field of two lists: tradable and trade type slot, class slot and race slot, or division and
     * equipment.
     */
    public int slot(Field f, int i, int j) {
        int ni;
        int nj;
        switch (f.shape) {
        case TRADABLE_TYPE:
            ni = tradables;
            nj = tradeTypes + 1;
            break;
        case CLASS_RACE:
            ni = classes + 1;
            nj = races + 1;
            break;
        case DIVISION_EQUIP:
            ni = divisions;
            nj = equips;
            break;
        default:
            throw new IllegalArgumentException("SOSDET: report field " + f + " is not of two lists");
        }
        if (i < 0 || i >= ni || j < 0 || j >= nj) {
            throw new IllegalArgumentException("SOSDET: report field " + f + " has no value " + i + ", " + j);
        }
        return offset[f.ordinal()] + i * nj + j;
    }

    boolean sameSizes(int[] sizes) {
        return sizes.length == 6 && sizes[0] == tradables && sizes[1] == tradeTypes && sizes[2] == races
                && sizes[3] == classes && sizes[4] == divisions && sizes[5] == equips;
    }

    int[] sizes() {
        return new int[] { tradables, tradeTypes, races, classes, divisions, equips };
    }
}

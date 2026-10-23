package sosdet.report;

/**
 * How many values a report field holds: one, one per entry of a list, or one per entry of two lists. A list with an
 * "all" slot has it first ({@code null} in the game), then each entry.
 */
public enum Shape {
    ONE(null, false, null, false),
    TRADABLE(Count.TRADABLES, false, null, false),
    /** One per tradable and trade type; trade type slot 0 is the total. */
    TRADABLE_TYPE(Count.TRADABLES, false, Count.TRADE_TYPES, true),
    RACE(Count.RACES, false, null, false),
    /** One per class and race, each with its "all" slot. */
    CLASS_RACE(Count.CLASSES, true, Count.RACES, true),
    /** One per battle equipment type. */
    EQUIP(Count.EQUIPS, false, null, false),
    /** One per city battle division. */
    DIVISION(Count.DIVISIONS, false, null, false),
    /** One per city battle division and battle equipment type. */
    DIVISION_EQUIP(Count.DIVISIONS, false, Count.EQUIPS, false),
    /** One per city battle division and training. */
    DIVISION_TRAINING(Count.DIVISIONS, false, Count.TRAININGS, false);

    /** The first list, or {@code null} for one value. */
    final Count first;
    final boolean firstAll;
    /** The second list, or {@code null} for one list. */
    final Count second;
    final boolean secondAll;

    Shape(Count first, boolean firstAll, Count second, boolean secondAll) {
        this.first = first;
        this.firstAll = firstAll;
        this.second = second;
        this.secondAll = secondAll;
    }
}

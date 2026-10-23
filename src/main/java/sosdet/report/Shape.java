package sosdet.report;

/** How many values a report field holds: one, or one per tradable, race, division and so on. */
public enum Shape {
    ONE,
    /** One per tradable. */
    TRADABLE,
    /** One per tradable and trade type; trade type slot 0 is the total ({@code null} in the game), then each type. */
    TRADABLE_TYPE,
    RACE,
    /** One per class and race; slot 0 of each is "all" ({@code null} in the game), then each class or race. */
    CLASS_RACE,
    /** One per city battle division. */
    DIVISION,
    /** One per city battle division and battle equipment type. */
    DIVISION_EQUIP
}

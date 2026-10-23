package sosdet.report;

/**
 * The city values the world part reads, one field each (ADR 0005, SOS-38; the codes are the scan's, in
 * docs/plan/notes/city-world-link-scan.md). The world part reads a field instead of the live city; counters the world
 * itself writes ({@code promised}, {@code toBeAdded}, {@code toBeStored}) stay live and are not in the report.
 */
public enum Field {
    // A3, B7: the treasury
    CREDITS(Shape.ONE),
    // A10: the entry
    ENTRY_CLOSED(Shape.ONE),
    ENTRY_POINTS(Shape.ONE),
    // A1, A2: stock and the city's trade tallies
    /** The stock {@code RESOURCE.owned} counts, before what was promised to buyers. */
    HELD(Shape.TRADABLE),
    EXPORT_AMOUNT(Shape.TRADABLE),
    EXPORT_CAP(Shape.TRADABLE),
    IMPORT_CAP(Shape.TRADABLE),
    /** Import space still free ({@code spaceForTribute}). */
    IMPORT_FREE(Shape.TRADABLE),
    STOCK_SPACE(Shape.TRADABLE),
    /** Stockpile amount and reserved space, as an import buyer counts what it owns. */
    STOCK_KEPT(Shape.TRADABLE),
    /** A buyer's deliveries on their way inside the city ({@code attempting}). */
    INCOMING(Shape.TRADABLE_TYPE),
    // A2: the city's trade settings
    SELL_PRICE_CAP(Shape.TRADABLE),
    SELL_LIMIT(Shape.TRADABLE),
    BUY_PRICE_CAP(Shape.TRADABLE),
    BUY_MIN_MONEY(Shape.TRADABLE),
    BUY_LIMIT(Shape.TRADABLE),
    /** The buy limit as a share, as {@code IntImp.getD} gives it. */
    BUY_LIMIT_SHARE(Shape.TRADABLE),
    // A2: slaves
    SLAVES(Shape.RACE),
    /** The war-crime punishment of a bought slave, by its index in {@code CRIME_PUNISHMENTS.ALL()}. */
    SLAVE_PUNISH(Shape.RACE),
    STOCKADE_USED(Shape.ONE),
    STOCKADE_TOTAL(Shape.ONE),
    WAR_CRIMINALS(Shape.ONE),
    // A4, B3: population
    /** {@code POP.physical}: the people in the city. */
    PHYSICAL(Shape.CLASS_RACE),
    /** What {@code POP.next} adds to {@code POP.tot}: parents and people on their way. */
    NEXT_EXTRA(Shape.CLASS_RACE),
    CITIZENS(Shape.RACE),
    /** The population per race the capitol region takes (B3). */
    POP_RACE(Shape.RACE),
    POP_TOTAL(Shape.ONE),
    /** Soldiers in the city's battle divisions. */
    CITY_SOLDIERS(Shape.ONE),
    // A9: laws per race
    PROSECUTION(Shape.RACE),
    WAR_STANDING(Shape.RACE),
    TOURISM_PERMIT(Shape.RACE),
    // B5
    MERCY(Shape.ONE),
    // A6: the city divisions that world divisions are made of
    DIV_RACE(Shape.DIVISION),
    DIV_MEN(Shape.DIVISION),
    DIV_EQUIP(Shape.DIVISION_EQUIP);

    public final Shape shape;

    Field(Shape shape) {
        this.shape = shape;
    }
}

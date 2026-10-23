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
    /** The men a division is set to have ({@code info.men()}). */
    DIV_MEN(Shape.DIVISION),
    /** The equipment a division is set to carry ({@code EquipBattle.target}). */
    DIV_EQUIP(Shape.DIVISION_EQUIP),
    // A5: the capital's garrison and invasions
    /** The division index of each garrison division in the game's order, then -1. */
    GARRISON(Shape.DIVISION),
    /** The men a garrison division has now; 0 for a division not in the garrison, as are the next three. */
    GAR_MEN(Shape.DIVISION),
    GAR_EXPERIENCE(Shape.DIVISION),
    GAR_TRAINING(Shape.DIVISION_TRAINING),
    /** The equipment share a garrison division has, after the city's supplies. */
    GAR_EQUIP(Shape.DIVISION_EQUIP),
    /** The equipment the city's divisions are set to carry, men times kit, as {@code FWorth} counts it. */
    EQUIP_IN_DIVS(Shape.EQUIP),
    INVADING(Shape.ONE),
    INVADING_POWER(Shape.ONE);

    public final Shape shape;

    Field(Shape shape) {
        this.shape = shape;
    }
}

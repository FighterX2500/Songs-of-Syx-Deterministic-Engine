package sosdet.report;

/**
 * The city values the world part reads, one field each (ADR 0005, SOS-38; the codes are the scan's, in
 * docs/plan/notes/city-world-link-scan.md). The world part reads a field instead of the live city, plus the changes its
 * own transactions made since the report was taken ({@link Reports#value}).
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
    /** A buyer's goods bought and not yet sent into the city ({@code toBeAdded + toBeStored}). */
    QUEUED(Shape.TRADABLE_TYPE),
    /** A seller's goods sold and not yet taken out ({@code promised}). */
    PROMISED(Shape.TRADABLE_TYPE),
    /** A seller's goods on their way out inside the city ({@code attempting}). */
    OUTGOING(Shape.TRADABLE_TYPE),
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
    /** The retirement age law, as a share ({@code RETIREMENT_AGE.decree().getD}); 0 in the null rows. */
    RETIREMENT(Shape.CLASS_RACE),
    /** The share of a class and race a scripted event selected ({@code STATS.EVENT()}), for its boosts (B6). */
    EVENT_CLASS_RACE(Shape.CLASS_RACE),
    // B5
    MERCY(Shape.ONE),
    /** The scripted event running ({@code GAME.EVENT().current()}), its index plus one; 0 for none (B6). */
    EVENT_CURRENT(Shape.ONE),
    // A6: the city divisions that world divisions are made of
    DIV_RACE(Shape.DIVISION),
    /** The men a division is set to have ({@code info.men()}). */
    DIV_MEN(Shape.DIVISION),
    /** The share of a division a scripted event selected, for its boosts (B6). */
    EVENT_DIV(Shape.DIVISION),
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
    INVADING_POWER(Shape.ONE),
    /** The ref the city's next invasion gets ({@code Invador.ref}); the world part allocates from it (C6). */
    INVASION_REF(Shape.ONE),
    // A7: boosts
    /** The player's value of each boost value: what every booster reads on the player, a player region or division. */
    BOOST(Shape.BOOST),
    /** {@code RAID_SECURITY} on the city's population class, as raiders' ransom reads it. */
    RAID_SECURITY(Shape.ONE),
    /** The proximity toll boost on the city's citizens, as the toll on the player's trade reads it. */
    PROXIMITY_TOLL(Shape.ONE),
    // A8: data-driven values
    /** The player's value of each faction value: requirement locks and the world camps read them. */
    VALUE(Shape.VALUE);

    public final Shape shape;

    Field(Shape shape) {
        this.shape = shape;
    }
}

package sosdet.report;

/**
 * A list size from the game's data that report fields are laid out by: the same on every PC that runs the same game
 * and mods.
 */
public enum Count {
    TRADABLES,
    /** Trade types as {@code TRADE_TYPE.all} lists them, without the total. */
    TRADE_TYPES,
    /** Races as {@code RACES.all()} lists them, without "all". */
    RACES,
    /** Classes as {@code HCLASSES.ALL()} lists them, without "all". */
    CLASSES,
    /** The player's battle divisions. */
    DIVISIONS,
    /** Battle equipment types, by {@code indexMilitary()}. */
    EQUIPS,
    /** {@code STATS.BATTLE().TRAINING_ALL}. */
    TRAININGS,
    /** The boost values ({@code BValue}) the game's boosters read, as the city report found them. */
    BOOSTS,
    /** {@code GVALUES.FACTION.all()}. */
    VALUES,
    /** The unlocks of techs, levels and titles on a faction or a region, as the city report found them. */
    UNLOCKS
}

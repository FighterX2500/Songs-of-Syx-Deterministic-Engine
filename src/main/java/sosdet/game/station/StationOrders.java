package sosdet.game.station;

/**
 * Who orders a division of the city a PC runs (SOS-209, SOS-234): the rule {@code Hooks.divOrderHere} and the UI's
 * selection apply, with the game's state read for them. Plain Java, no game classes.
 *
 * <p>A stationed ally's division ({@link StationedDivs}) is its player's while their view of the host's city runs, and
 * the host's while that player gives them command (owner, 2026-11-19), in peace, an invasion and an assault alike.
 * Nobody else orders it: without a view or a grant its soldiers hold where they stand. Which divisions are stationed is the city's own state, read
 * only on the PCs that run the city; an assault's commanders table, the same on every PC, never names them: it leaves
 * army 0 to the defender, and a stationed player's order for its own division passes the table on the PCs that run the
 * city ({@link #passesTable}). A division the host made is never in the registry, so it is never given away.
 */
public final class StationOrders {

    private StationOrders() {
    }

    /**
     * Whether a player's order from {@code pc} for a division of the city this PC runs applies here.
     *
     * @param cityPc the PC whose city it is
     * @param ownerPc the stationed player's PC when the division is a stationed ally's, else -1
     * @param ownerViews whether that player's view of this city runs
     * @param granted whether that player gives the host command of the division
     * @param assault whether an assault on this city runs
     * @param pcViews whether {@code pc}'s player views this city (an assault's attacker; the table limits it to its own)
     */
    public static boolean applies(int pc, int cityPc, int ownerPc, boolean ownerViews, boolean granted, boolean assault,
            boolean pcViews) {
        if (ownerPc >= 0) {
            return pc == ownerPc && ownerViews || pc == cityPc && granted;
        }
        if (assault) {
            return pc == cityPc || pcViews;
        }
        return pc == cityPc;
    }

    /**
     * Whether an order passes the commanders table: when the table allows its source, or when it is a stationed
     * player's for its own division while their view of the city runs (the table gives army 0 to the defender).
     */
    public static boolean passesTable(boolean tableAllows, int pc, int ownerPc, boolean ownerViews) {
        return tableAllows || ownerPc >= 0 && pc == ownerPc && ownerViews;
    }

    /**
     * The UI: whether this PC's player may select a stationed ally's division of the city on screen: its player, who
     * views the city, and the host in its own city while that player gives them command. A third player viewing the
     * city never may.
     *
     * @param local this PC's player
     * @param owner the stationed player
     * @param viewing whether this PC views the city rather than running its own
     * @param granted whether the stationed player gives the host command of the division
     */
    public static boolean selectable(int local, int owner, boolean viewing, boolean granted) {
        return local == owner || !viewing && granted;
    }
}

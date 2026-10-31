package sosdet;

/**
 * How a battle between two humans ended, the same on every PC (SOS-83): set as its result prompt opens, for the mod to
 * tell the loser and to log. The winner answers the result prompt; the loser only reads this.
 */
public final class BattleOutcome {

    /** The step the result prompt opened at. */
    public final long step;
    /** The winner's slot, who chooses captives and spoils, and the loser's. */
    public final int winner;
    public final int loser;
    /** Every human on the losing side (SOS-118). */
    private final int[] losers;
    /** True when the loser retreated, false when it was beaten on the field. */
    public final boolean retreat;
    /** Men each side lost, as the result counts them. */
    public final int winnerLosses;
    public final int loserLosses;

    BattleOutcome(long step, int winner, int loser, int[] losers, boolean retreat, int winnerLosses, int loserLosses) {
        this.step = step;
        this.winner = winner;
        this.loser = loser;
        this.losers = losers;
        this.retreat = retreat;
        this.winnerLosses = winnerLosses;
        this.loserLosses = loserLosses;
    }

    /** True when {@code slot} was on the losing side. */
    public boolean lost(int slot) {
        for (int l : losers) {
            if (l == slot) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "winner " + winner + " (lost " + winnerLosses + "), loser " + loser + " (lost " + loserLosses + ")"
                + (retreat ? ", retreated" : "") + " at step " + step;
    }
}

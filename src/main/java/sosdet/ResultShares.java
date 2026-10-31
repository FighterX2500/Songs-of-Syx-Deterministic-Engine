package sosdet;

/**
 * Several humans won a battle (mod spec §8, SOS-118): each gets their share of the captives and spoils in a window of
 * their own, in turn, the same on every PC.
 */
final class ResultShares {

    private final int[] humans;
    private final int[] men;
    private final int[] captured;
    private final int[] spoils;
    private int turn;

    ResultShares(int[] humans, int[] men, int[] captured, int[] spoils) {
        this.humans = humans;
        this.men = men;
        this.captured = captured.clone();
        this.spoils = spoils.clone();
    }

    /** The winner whose window is open. */
    int human() {
        return humans[turn];
    }

    /** Puts this winner's share in the result's own arrays, which its window reads. */
    void fill(int[] toCaptured, int[] toSpoils) {
        for (int i = 0; i < captured.length; i++) {
            toCaptured[i] = BattleRules.share(captured[i], men)[turn];
        }
        for (int i = 0; i < spoils.length; i++) {
            toSpoils[i] = BattleRules.share(spoils[i], men)[turn];
        }
    }

    /** On to the next winner; false when this was the last. */
    boolean next() {
        turn++;
        return turn < humans.length;
    }
}

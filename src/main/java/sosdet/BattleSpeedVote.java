package sosdet;

/**
 * The commanders' speed vote in a battle on every PC (mod spec §8). Each commander's last press stands; a speed settles
 * when more than half of the commanders still in the battle picked it. Plain data: each PC keeps it alike, inside the
 * step.
 */
final class BattleSpeedVote {

    private final double[] votes; // each commander's speed; 0 for no vote yet
    private final boolean[] gone;

    BattleSpeedVote(int commanders) {
        votes = new double[commanders];
        gone = new boolean[commanders];
    }

    /** Commander {@code c} picks {@code speed}. Returns the speed with a majority now, or 0 for none. */
    double vote(int c, double speed) {
        votes[c] = speed;
        return majority();
    }

    /** Commander {@code c} left the battle: their vote stops counting. Returns the speed with a majority now, or 0. */
    double leave(int c) {
        gone[c] = true;
        return majority();
    }

    /** How many commanders still in the battle picked {@code speed}. */
    int count(double speed) {
        int n = 0;
        for (int c = 0; c < votes.length; c++) {
            if (!gone[c] && votes[c] == speed) {
                n++;
            }
        }
        return n;
    }

    /** The commanders still in the battle. */
    int commanders() {
        int n = 0;
        for (boolean g : gone) {
            if (!g) {
                n++;
            }
        }
        return n;
    }

    private double majority() {
        for (int c = 0; c < votes.length; c++) {
            if (!gone[c] && votes[c] > 0 && count(votes[c]) * 2 > commanders()) {
                return votes[c];
            }
        }
        return 0;
    }
}

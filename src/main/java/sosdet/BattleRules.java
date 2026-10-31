package sosdet;

/**
 * The rules of a battle with allies (mod spec §8, ADR 0009), as plain arithmetic: who gets a side's division slots, what
 * a retreating army loses, and how winners share captives and spoils. Each PC works them out alike, inside the step.
 */
final class BattleRules {

    private BattleRules() {
    }

    /**
     * A side's slots: {@code wanted[i]} divisions for each human, and the side's NPC armies as the last entry. The humans
     * take one slot each in turn, so they split evenly and what one doesn't use goes to the others; NPCs get what's left.
     */
    static int[] slots(int[] wanted, int slots) {
        int humans = wanted.length - 1;
        int[] got = new int[wanted.length];
        int left = slots;
        boolean more = true;
        while (left > 0 && more) {
            more = false;
            for (int h = 0; h < humans && left > 0; h++) {
                if (got[h] < wanted[h]) {
                    got[h]++;
                    left--;
                    more = true;
                }
            }
        }
        got[humans] = Math.min(wanted[humans], left);
        return got;
    }

    /**
     * The losses per man of an army that leaves while an ally stays behind: its share by men of what vanilla's retreat
     * costs the side (the penalty on the enemy's men), halved.
     */
    static double leavingLoss(int enemyMen, int armyMen, int sideMen, double penalty) {
        if (armyMen <= 0 || sideMen <= 0) {
            return 0;
        }
        double sideLoss = Math.ceil(enemyMen * penalty);
        return Math.min(sideLoss * armyMen / sideMen / 2 / armyMen, 1);
    }

    /** The men a division of {@code men} loses as its army leaves: a division in melee loses 75% more, as in vanilla. */
    static int divisionLoss(int men, double lossPerMan, boolean fighting) {
        int lost = (int) (men * lossPerMan);
        if (fighting) {
            lost += (int) (men * 0.75);
        }
        return Math.max(0, Math.min(lost, men));
    }

    /** Each winner's part of {@code total}, by men brought; what doesn't divide evenly goes to the one with the most. */
    static int[] share(int total, int[] men) {
        int[] part = new int[men.length];
        long sum = 0;
        int most = 0;
        for (int i = 0; i < men.length; i++) {
            sum += men[i];
            if (men[i] > men[most]) {
                most = i;
            }
        }
        if (sum == 0) {
            part[most] = total;
            return part;
        }
        int given = 0;
        for (int i = 0; i < men.length; i++) {
            part[i] = (int) (total * (long) men[i] / sum);
            given += part[i];
        }
        part[most] += total - given;
        return part;
    }
}

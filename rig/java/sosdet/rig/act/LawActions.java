package sosdet.rig.act;

import settlement.stats.STATS;
import sosdet.Engine;

/** The rig's scripted player: law actions, each the call the game's UI makes (SOS-34). */
public final class LawActions {

    private LawActions() {
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        case "curfew": // curfew 1|0: the law screen's curfew button
            boolean on = a[1].equals("1");
            Engine.betweenSteps(() -> STATS.LAW().getCurfew().setForADay(on));
            return true;
        default:
            return false;
        }
    }
}

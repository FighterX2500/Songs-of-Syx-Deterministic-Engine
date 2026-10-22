package sosdet.rig.act;

import game.GAME;
import sosdet.Engine;

/** The rig's scripted player: military actions, each the call the game's UI makes (SOS-34). */
public final class MilitaryActions {

    private MilitaryActions() {
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        case "men": // men <division index in the player's army> <target>: the army screen's division size
            int div = Integer.parseInt(a[1]);
            int men = Integer.parseInt(a[2]);
            Engine.betweenSteps(() -> GAME.ARMIES().player().divisions().get(div).info.menSet(men));
            return true;
        default:
            return false;
        }
    }
}

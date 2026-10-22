package sosdet.rig.act;

/** The rig's scripted player: room actions, each the call the game's UI makes (SOS-34). */
public final class RoomActions {

    private RoomActions() {
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        default:
            return false;
        }
    }
}

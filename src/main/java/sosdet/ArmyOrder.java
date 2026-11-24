package sosdet;

/**
 * A world army's order or creation in the step's world part (SOS-249): the NPCs' decisions, the rebels' and the
 * humans' alike, since a human's army command goes through the same methods. Every PC records the same orders. Plain
 * data for the logs: nothing reads it back into the step.
 *
 * @param seq     its number in the ring, from 1 ({@link Engine#lastArmyOrder})
 * @param step    the step it was given at
 * @param army    the army's slot ({@code WArmy.armyIndex}); a slot is used again after a disband
 * @param faction the army's faction's index, -1 for none (rebels)
 * @param kind    what was ordered
 * @param x       the target tile's x: the destination, the region's centre, the intercepted army's tile, the army's
 *                own tile for a raid in place, a stop or a disband
 * @param y       the target tile's y, as {@code x}
 * @param target  the region's index (BESIEGE, RAID), the intercepted army's slot (INTERCEPT), else -1
 */
public record ArmyOrder(long seq, long step, int army, int faction, Kind kind, int x, int y, int target) {

    /** What was ordered; {@code name()} is the name for a log. */
    public enum Kind {
        /** A new army ({@code WArmyConstructor.create}): a recruit, rebels or an event's army. */
        CREATE,
        /** {@code setDestination}. */
        MOVE,
        /** {@code besiege}: a siege of the region, or the battle at it when already there. */
        BESIEGE,
        /** {@code raid}: to the region to raid it, or raiding where it stands. */
        RAID,
        /** {@code intercept}. */
        INTERCEPT,
        /** {@code stop}, or a raid called off: a moving, besieging or raiding army fortifies. */
        STOP,
        /** {@code disband}. */
        DISBAND,
        /** {@code teleport}: a retreat after a battle, or an army sent home. */
        TELEPORT
    }
}

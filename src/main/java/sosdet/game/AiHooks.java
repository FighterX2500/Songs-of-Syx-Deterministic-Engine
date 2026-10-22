package sosdet.game;

import game.battle.div.Div;
import game.battle.formation.DIV_FORMATION;
import settlement.room.military.artillery.ArtilleryInstance;
import snake2d.util.file.SAVABLE;
import sosdet.Hooks;
import sosdet.ai.AiEntry;

/**
 * What the AI patches call (hook B15). On the host's general thread every change is captured into the AI log and
 * applied by every PC at its step; anywhere else the change happens at once, as in vanilla.
 */
public final class AiHooks {

    private AiHooks() {
    }

    /** {@code BattleOrder.Locked.set}: true when the order was captured and must not be applied now. */
    public static boolean order(int div, int kind, SAVABLE from) {
        if (!Hooks.aiCapturing()) {
            return false;
        }
        Hooks.aiCapture(kind, div, ((GameAi) Hooks.aiApplier()).encode(from));
        return true;
    }

    public static void guard(Div d, boolean value) {
        if (!setting(AiEntry.GUARD, d, value ? 1 : 0)) {
            d.settings().guard = value;
        }
    }

    public static void running(Div d, boolean value) {
        if (!setting(AiEntry.RUNNING, d, value ? 1 : 0)) {
            d.settings().running = value;
        }
    }

    public static void fireAtWill(Div d, boolean value) {
        if (!setting(AiEntry.FIRE_AT_WILL, d, value ? 1 : 0)) {
            d.settings().fireAtWill = value;
        }
    }

    public static void formation(Div d, DIV_FORMATION value) {
        if (!setting(AiEntry.FORMATION, d, value.ordinal())) {
            d.settings().formation = value;
        }
    }

    /** {@code StepArtilleryBombard}: the artillery is found again at its stamped step by one of its tiles. */
    public static void artilleryTarget(ArtilleryInstance a, int tx, int ty) {
        if (!Hooks.aiCapturing()) {
            a.targetCooSet(tx, ty, false, false);
            return;
        }
        Hooks.aiCapture(AiEntry.ARTILLERY, -1, AiEntry.ints(a.mX(), a.mY(), tx, ty));
    }

    private static boolean setting(int kind, Div d, int value) {
        if (!Hooks.aiCapturing()) {
            return false;
        }
        Hooks.aiCapture(kind, d.index(), AiEntry.ints(value));
        return true;
    }
}

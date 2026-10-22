package sosdet.game;

import game.battle.Army;
import game.battle.div.Div;
import game.battle.formation.DIV_FORMATION;
import settlement.room.military.artillery.ArtilleryInstance;
import snake2d.util.file.SAVABLE;
import snake2d.util.sets.LIST;
import sosdet.Hooks;
import sosdet.ai.AiEntry;

/**
 * What the AI and order patches call (hooks B15, B16). On the host's general thread a change is captured into the
 * log; a player's order, given outside the step with a log running, is captured too (the host stamps it). Both are
 * applied by every PC at their step. Anywhere else the change happens at once, as in vanilla.
 */
public final class AiHooks {

    private AiHooks() {
    }

    /** {@code BattleOrder.Locked.set}: true when the order was captured and must not be applied now. */
    public static boolean order(int div, int kind, SAVABLE from) {
        if (Hooks.aiCapturing()) {
            Hooks.aiCapture(kind, div, game().encode(from));
            return true;
        }
        if (Hooks.ordersCapturing()) {
            Hooks.orderCapture(kind, div, game().encodeOrder(from));
            return true;
        }
        return false;
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

    /** A player's "hold fire" ({@code shouldNotMoveToFire}). */
    public static void holdFire(Div d, boolean value) {
        if (!setting(AiEntry.HOLD_FIRE, d, value ? 1 : 0)) {
            d.settings().shouldNotMoveToFire = value;
        }
    }

    public static void ammo(Div d, short value) {
        if (!setting(AiEntry.AMMO, d, value)) {
            d.settings().ammoI = value;
        }
    }

    public static void muster(Div d, boolean value) {
        if (!setting(AiEntry.MUSTER, d, value ? 1 : 0)) {
            d.settings().musteringSet(value);
        }
    }

    public static void mopUp(Div d, boolean value) {
        if (!setting(AiEntry.MOP_UP, d, value ? 1 : 0)) {
            d.settings().moppingSet(value);
        }
    }

    /**
     * {@code StepBlob}: an army's soldiers. {@code Army.men()} is a lazy count the simulation shares; on the host's
     * general thread it could be refilled in the middle of a step and leave the simulation a stale value (a host-only
     * desync, SOS-32). The general counts afresh instead.
     */
    public static int armyMen(Army army) {
        if (!Hooks.aiCapturing()) {
            return army.men();
        }
        int men = 0;
        for (int i = 0; i < army.divisions().size(); i++) {
            men += army.divisions().get(i).menNrOf();
        }
        return men;
    }

    /** {@code StepArtilleryBombard}: the general's target. */
    public static void artilleryTarget(ArtilleryInstance a, int tx, int ty) {
        artilleryTarget(a, tx, ty, false, false);
    }

    /** An artillery target with the game's two flags; the artillery is found again by one of its tiles. */
    public static void artilleryTarget(ArtilleryInstance a, int x, int y, boolean bombard, boolean userSet) {
        if (!artillery(AiEntry.ARTILLERY, a, x, y, bombard ? 1 : 0, userSet ? 1 : 0)) {
            a.targetCooSet(x, y, bombard, userSet);
        }
    }

    public static void artilleryMuster(ArtilleryInstance a, boolean value) {
        if (!artillery(AiEntry.ARTILLERY_MUSTER, a, value ? 1 : 0)) {
            a.muster(value);
        }
    }

    public static void artilleryFire(ArtilleryInstance a, boolean value) {
        if (!artillery(AiEntry.ARTILLERY_FIRE, a, value ? 1 : 0)) {
            a.fireAtWill(value);
        }
    }

    /** {@code Armies.initAndTeleport} from deployment: true when captured (a player placed divisions). */
    public static boolean teleport(LIST<Div> divs) {
        if (!Hooks.ordersCapturing()) {
            return false;
        }
        int[] indices = new int[divs.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = divs.get(i).index();
        }
        Hooks.orderCapture(AiEntry.TELEPORT, -1, AiEntry.ints(indices));
        return true;
    }

    private static boolean setting(int kind, Div d, int value) {
        if (Hooks.aiCapturing()) {
            Hooks.aiCapture(kind, d.index(), AiEntry.ints(value));
            return true;
        }
        if (Hooks.ordersCapturing()) {
            Hooks.orderCapture(kind, d.index(), AiEntry.ints(value));
            return true;
        }
        return false;
    }

    private static boolean artillery(int kind, ArtilleryInstance a, int... values) {
        int[] all = new int[values.length + 2];
        all[0] = a.mX();
        all[1] = a.mY();
        System.arraycopy(values, 0, all, 2, values.length);
        if (Hooks.aiCapturing()) {
            Hooks.aiCapture(kind, -1, AiEntry.ints(all));
            return true;
        }
        if (Hooks.ordersCapturing()) {
            Hooks.orderCapture(kind, -1, AiEntry.ints(all));
            return true;
        }
        return false;
    }

    private static GameAi game() {
        return (GameAi) Hooks.aiApplier();
    }
}

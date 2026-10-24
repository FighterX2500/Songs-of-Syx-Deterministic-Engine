package sosdet.game.cmd;

import java.util.List;

import game.battle.state.BattleState;
import snake2d.util.color.COLOR;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.ai.CmdWriter;
import view.main.VIEW;

/**
 * A battle fought on the battle map from the world-battle prompt's Engage (ids 7960-7999, SOS-32). Engage is a
 * world-battle decision ({@link WorldBattles#ENGAGE}): inside its step every PC saves the game as it is, generates the
 * battle on the simulation's stream, the same everywhere, and opens it at deployment. Nothing in the battle moves until
 * Start (vanilla pauses it), and Start is a command. Retreat and the victory or defeat panel's Close end the battle inside
 * the step; every PC then loads the game saved before it, the session going on at that step, and the result prompt
 * opens alike. Restarting a battle is refused in a session.
 */
public final class MapBattles {

    static final int DEPLOY = 7960;
    static final int RETREAT = 7961;
    static final int CONCLUDE = 7962;

    static final String RESTART_NOTICE = "SOSDET: restarting a battle doesn't work in a multiplayer session.";

    static final List<Command> ALL = List.of(
            new Command(DEPLOY, "battleDeploy", r -> {
                BattleState b = VIEW.b().state();
                if (b == null || !b.deploying()) {
                    return false;
                }
                b.deploy();
                return true;
            }),
            new Command(RETREAT, "battleRetreat", r -> {
                BattleState b = VIEW.b().state();
                if (b == null || b.deploying() || Hooks.battleOver()) {
                    return false;
                }
                b.liveRetreat();
                return true;
            }),
            new Command(CONCLUDE, "battleConclude", r -> {
                Runnable close = Hooks.battleOver() ? null : Hooks.battleConcludeTake();
                if (close == null) {
                    return false;
                }
                close.run();
                return true;
            }));

    private MapBattles() {
    }

    // ------------------------------------------------------------------ hooks (this PC's player, outside the step)

    /** Hook in the deploy panel's Start: in a session (true) a command; the panel hides once it applies. */
    public static boolean deploy() {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(DEPLOY));
        return true;
    }

    /** Hook in the battle panel's retreat, once confirmed: in a session (true) a command. */
    public static boolean retreat() {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(RETREAT));
        return true;
    }

    /** Hook in the victory or defeat panel's Close: in a session (true) a command; the panel stays until it applies. */
    public static boolean conclude() {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(CONCLUDE));
        return true;
    }

    /** Hook in {@code BattleState.reloadBattle}: in a session (true) refused with a notice. */
    public static boolean restartRefused() {
        if (!Commands.capturing()) {
            return false;
        }
        VIEW.inters().fullScreen.activate(RESTART_NOTICE, COLOR.WHITE100, null);
        return true;
    }

    /**
     * Hook where the battle sets the game speed (to 0 as it opens, to 1 under the result panel) or pauses it (the deploy
     * panel): only without a session. In one the speed is the players' own, and the host's goes into the log (B16).
     */
    public static boolean setsSpeed() {
        return !Engine.active();
    }

    /**
     * Hook in the game loop, inside the step: true while a session's battle is deploying. Nothing in the battle moves
     * until Start, as vanilla's pause holds it; the steps go on, so the deployment orders and Start apply.
     */
    public static boolean deploying() {
        if (!Engine.active()) {
            return false;
        }
        BattleState b = VIEW.b().state();
        return b != null && b.deploying();
    }
}

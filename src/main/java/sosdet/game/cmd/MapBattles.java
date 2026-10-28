package sosdet.game.cmd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import game.battle.state.BattleState;
import init.paths.PATHS;
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
 * opens alike. Restart is a command too: every PC loads the battle as generated, at deployment, the session going on
 * at that step (SOS-41). A world command whose step falls in the battle is skipped, as the battle's end would undo it.
 */
public final class MapBattles {

    static final int DEPLOY = 7960;
    static final int RETREAT = 7961;
    static final int CONCLUDE = 7962;
    static final int RESTART = 7963;

    /** The save the battle's generation writes ({@code BattleState.setGenerate}). */
    private static final String BATTLE_SAVE = "__battle";

    static final String RESTART_NOTICE = "SOSDET: restarting a battle doesn't work with own cities.";

    static final String WORLD_COMMAND_NOTICE = "SOSDET: a world map action came in during the battle and was not done."
            + " Do it again after the battle.";

    static final List<Command> ALL = List.of(
            new Command(DEPLOY, "battleDeploy", r -> {
                BattleState b = VIEW.b().state();
                if (b == null || !b.deploying()) {
                    return false;
                }
                if (Hooks.battleDeployReady()) { // between two humans once both pressed Start (SOS-83)
                    b.deploy();
                }
                return true;
            }),
            new Command(RETREAT, "battleRetreat", r -> {
                BattleState b = VIEW.b().state();
                if (b == null || b.deploying() || Hooks.battleOver()) {
                    return false;
                }
                if (Hooks.battleRetreatSide() < 0) {
                    return false; // only a side's own commanders retreat it (SOS-83)
                }
                b.liveRetreat(); // when army 1 retreats, the patched liveRetreat gives army 0 the victory
                return true;
            }),
            new Command(CONCLUDE, "battleConclude", r -> {
                Runnable close = Hooks.battleOver() ? null : Hooks.battleConcludeTake();
                if (close == null) {
                    return false;
                }
                close.run();
                return true;
            }),
            new Command(RESTART, "battleRestart", r -> {
                BattleState b = VIEW.b().state();
                if (b == null || Hooks.battleOver()) {
                    return false;
                }
                Hooks.battleLeaving(); // the steps hold until the load, which goes on at this step
                b.reloadBattle();
                return true;
            }));

    private MapBattles() {
    }

    /** Every PC, at a step's start: the deployment of a battle between two humans ran out of time, so it starts (SOS-83). */
    public static void deployForAll() {
        BattleState b = VIEW.b().state();
        if (b != null && b.deploying()) {
            b.deploy();
        }
    }

    /**
     * Every PC, at a step's start while the battle's human is being removed (SOS-66): one move toward the battle's end,
     * as the battle commands would make it. Start while it deploys, then Retreat; a battle already won or lost closes
     * its victory panel. Either way every PC then loads its own city, and the removal closes the result prompt
     * unanswered, so the battle changes no army.
     */
    public static void endForRemoval() {
        BattleState b = VIEW.b().state();
        if (b == null || Hooks.battleOver()) {
            return;
        }
        if (b.deploying()) {
            b.deploy();
            return;
        }
        Runnable close = Hooks.battleConcludeTake();
        if (close != null) {
            close.run();
            return;
        }
        b.liveRetreat();
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

    /**
     * Hook in {@code BattleState.reloadBattle} (the battle panel's restart, once confirmed, and the result panel's): in
     * a session (true) a command. Applied inside the step, it runs the reload.
     */
    public static boolean restart() {
        if (Hooks.battleLoadingNow() || !Commands.capturing()) {
            return false; // the engine's own load of the battle PC's battle runs as vanilla's restart (plan 0006)
        }
        if (Hooks.ownCityBattle()) {
            VIEW.inters().fullScreen.activate(RESTART_NOTICE, COLOR.WHITE100, null);
            return true;
        }
        Commands.capture(new CmdWriter(RESTART));
        return true;
    }

    /** The battle PC's battle as generated at Engage: its {@code __battle} save's bytes (plan 0006). */
    public static byte[] battleSave() {
        Path file = PATHS.local().save().get(BATTLE_SAVE);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: the battle save " + file + " can't be read", e);
        }
    }

    /** Loads the battle PC's battle on this PC, at deployment, as the restart does: written over this PC's own first. */
    public static void loadBattle(byte[] bytes) {
        BattleState b = VIEW.b().state();
        if (b == null) {
            throw new IllegalStateException("SOSDET: no battle open to load the battle PC's battle into");
        }
        Path file = PATHS.local().save().get(BATTLE_SAVE);
        try {
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: the battle save " + file + " can't be written", e);
        }
        b.reloadBattle();
    }

    /**
     * Inside the step, a world command at its world part: true while a battle is on the battle map, the same on every
     * PC. The battle's end loads the game saved before it, which would undo the command, so it is skipped; the PC that
     * gave it gets a notice (SOS-41).
     */
    public static boolean worldCommandSkipped(int pc) {
        if (!VIEW.b().isActive()) {
            return false;
        }
        if (Hooks.localPc(pc)) {
            VIEW.inters().fullScreen.activate(WORLD_COMMAND_NOTICE, COLOR.WHITE100, null);
        }
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

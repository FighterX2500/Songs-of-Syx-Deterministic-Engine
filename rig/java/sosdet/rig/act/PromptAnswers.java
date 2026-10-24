package sosdet.rig.act;

import init.race.RACES;
import init.resources.RESOURCES;
import init.trade.TR;
import game.battle.state.BattleState;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.game.cmd.MapBattles;
import sosdet.game.cmd.StepPrompts;
import sosdet.game.cmd.WorldBattles;
import view.main.VIEW;
import world.battle.spec.BATTLE_RESULT;
import world.battle.spec.WBattleResult;
import world.battle.spec.WBattleSiege;
import world.battle.spec.WBattleSpec;

/**
 * The host's player answering the world-battle prompt in a rig run, where nobody clicks: auto-resolve every battle,
 * take no captives or spoils, occupy a captured settlement, and say yes to the simulation's yes/no questions. Each answer goes through the button's own hook, so it
 * becomes the same command a click makes. One per rig script instance; it answers each prompt once.
 *
 * <p>With {@code engage}, a field battle is fought on the battle map instead (SOS-32): Engage, then Start as soon as it
 * deploys, then Retreat {@code retreatAfter} steps later (0: fight on), and Close on the victory or defeat panel. A siege
 * waits instead of being resolved, so the region's defenders march on the besieging army. With {@code restartAfter}, the
 * battle panel's restart {@code restartAfter} steps after Start, once (SOS-41); the battle deploys again, and Start and
 * the retreat count begin again.
 */
public final class PromptAnswers {

    private final boolean engage;
    private final long retreatAfter;
    private final long restartAfter;
    /** The battle map's progress: the step Start applied at (-1: not yet), and what has been asked for. */
    private long battleFrom = -1;
    private boolean startAsked;
    private boolean retreatAsked;
    private boolean closeAsked;
    private boolean restartAsked;

    public PromptAnswers(boolean engage, long retreatAfter, long restartAfter) {
        this.engage = engage;
        this.retreatAfter = retreatAfter;
        this.restartAfter = restartAfter;
    }

    /** The prompt last answered: its decision applies some steps later, until then it stays open. */
    private Object answered;
    /** The simulation's yes/no question last answered (Yes), by its key. */
    private long answeredQuestion = -1;

    /** After a frame, outside the step: answers the open prompt if it is new. Returns its name, or null. */
    public String answer() {
        BattleState battle = VIEW.b().state();
        if (battle != null) {
            return battle(battle);
        }
        battleFrom = -1;
        startAsked = retreatAsked = closeAsked = restartAsked = false;
        long question = Hooks.stepPromptOldest();
        if (question >= 0 && question != answeredQuestion) {
            answeredQuestion = question;
            if (Hooks.stepPromptShown() == question && VIEW.inters().yesNo.isActivated()) {
                VIEW.inters().yesNo.deactivate(); // the button hides the dialog as it is clicked; nobody clicks here
            }
            StepPrompts.answer(question, true);
            return "question yes";
        }
        Object open = Hooks.worldPrompt();
        if (open == null) {
            answered = null; // the game reuses its battle prompt's object for the next battle
            return null;
        }
        if (open == answered) {
            return null;
        }
        answered = open;
        if (engage && open instanceof WBattleSpec && !(open instanceof WBattleSiege)) {
            WorldBattles.engage();
            return "battle engage";
        }
        if (engage && open instanceof WBattleSiege) {
            WorldBattles.decide(WorldBattles.WAIT); // the siege goes on, and the region's defenders come out to the field
            return "siege wait";
        }
        if (open instanceof WBattleSpec) {
            WorldBattles.decide(WorldBattles.AUTO);
            return open instanceof WBattleSiege ? "siege auto" : "battle auto";
        }
        if (open instanceof WBattleResult) {
            if (((WBattleResult) open).result != BATTLE_RESULT.VICTORY) {
                WorldBattles.accept(null, null, 0); // as the Accept button after a defeat
                return "defeat accept";
            }
            WorldBattles.accept(new int[RACES.all().size()], new int[RESOURCES.ALL().size()], 0);
            return "victory accept";
        }
        if (open instanceof WBattleSiege.Result) {
            WorldBattles.siegeResult(WorldBattles.OCCUPY, 0, 0, new int[RACES.all().size()], new int[TR.ALL().size()], 0);
            return "siege occupy";
        }
        throw new IllegalStateException("SOSDET rig: unknown world prompt " + open.getClass().getName());
    }

    /** On the battle map: Start, then Retreat after the set steps, and Close once the battle is won or lost. */
    private String battle(BattleState battle) {
        if (battle.deploying()) {
            if (battleFrom >= 0) { // restarted: it deploys again
                battleFrom = -1;
                startAsked = false;
            }
            if (startAsked) {
                return null;
            }
            startAsked = true;
            MapBattles.deploy();
            return "battle start";
        }
        if (battleFrom < 0) {
            battleFrom = Engine.step();
        }
        if (Hooks.battleConcludeOpen() && !closeAsked) {
            closeAsked = true;
            MapBattles.conclude();
            return "battle close";
        }
        if (restartAfter > 0 && !restartAsked && !closeAsked && Engine.step() - battleFrom >= restartAfter) {
            restartAsked = true;
            battle.reloadBattle(); // as the battle panel's restart, once confirmed: the hook makes it a command
            return "battle restart";
        }
        if (retreatAfter > 0 && !retreatAsked && !closeAsked && Engine.step() - battleFrom >= retreatAfter) {
            retreatAsked = true;
            MapBattles.retreat();
            return "battle retreat";
        }
        return null;
    }
}

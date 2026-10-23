package sosdet.rig.act;

import init.race.RACES;
import init.resources.RESOURCES;
import init.trade.TR;
import sosdet.Hooks;
import sosdet.game.cmd.StepPrompts;
import sosdet.game.cmd.WorldBattles;
import world.battle.spec.BATTLE_RESULT;
import world.battle.spec.WBattleResult;
import world.battle.spec.WBattleSiege;
import world.battle.spec.WBattleSpec;

/**
 * The host's player answering the world-battle prompt in a rig run, where nobody clicks: auto-resolve every battle,
 * take no captives or spoils, occupy a captured settlement, and say yes to the simulation's yes/no questions. Each answer goes through the button's own hook, so it
 * becomes the same command a click makes. One per rig script instance; it answers each prompt once.
 */
public final class PromptAnswers {

    /** The prompt last answered: its decision applies some steps later, until then it stays open. */
    private Object answered;
    /** The simulation's yes/no question last answered (Yes), by its key. */
    private long answeredQuestion = -1;

    /** After a frame, outside the step: answers the open prompt if it is new. Returns its name, or null. */
    public String answer() {
        long question = Hooks.stepPromptOldest();
        if (question >= 0 && question != answeredQuestion) {
            answeredQuestion = question;
            StepPrompts.answer(question, true);
            return "question yes";
        }
        Object open = Hooks.worldPrompt();
        if (open == null || open == answered) {
            return null;
        }
        answered = open;
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
}

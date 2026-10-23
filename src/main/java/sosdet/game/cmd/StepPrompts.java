package sosdet.game.cmd;

import java.util.List;

import snake2d.util.misc.ACTION;
import sosdet.Hooks;
import sosdet.ai.CmdWriter;
import view.main.VIEW;

/**
 * Yes/no questions the simulation asks (id 7950): the step opens them on every PC alike, with the game's own yes and
 * no actions, and in vanilla a click runs the chosen action at once. In a session the engine keeps the actions, keyed
 * by the step that opened the question and its order in that step, and a click becomes a command that runs the chosen
 * action inside the step on every PC. Asked by {@code WArmyState} (declare war and besiege?) and {@code Invasion}
 * (take the routed invaders prisoner?).
 *
 * <p>V0.1 is co-op: every PC shows the question and the first answer applies; a later one finds it gone and is skipped
 * everywhere.
 */
public final class StepPrompts {

    static final int ANSWER = 7950;

    static final List<Command> ALL = List.of(
            new Command(ANSWER, "stepPromptAnswer", r -> {
                long key = ((long) r.i() << 32) | (r.i() & 0xFFFFFFFFL);
                boolean yes = r.b();
                if (Hooks.stepPromptShown() == key) {
                    // Another PC answered: the question closes here too, or this PC would sit under it (SOS-36).
                    Hooks.stepPromptHidden(key);
                    if (VIEW.inters().yesNo.isActivated()) {
                        VIEW.inters().yesNo.deactivate();
                    }
                }
                Object action = Hooks.stepPromptTake(key, yes);
                if (action == null) {
                    return false;
                }
                ((ACTION) action).exe();
                return true;
            }));

    private StepPrompts() {
    }

    /**
     * Hook replacing {@code VIEW.inters().yesNo.activate} where the simulation asks. Without a session, or outside the
     * step, the game's own call; in a session's step the question is kept and its buttons capture the answer.
     */
    public static void yesNo(CharSequence message, ACTION yes, ACTION no, boolean dismissable) {
        if (!Hooks.stepPromptsKept()) {
            VIEW.inters().yesNo.activate(message, yes, no, dismissable);
            return;
        }
        long key = Hooks.stepPromptOpened(yes, no);
        VIEW.inters().yesNo.activate(message, button(key, true), button(key, false), dismissable);
    }

    /** A button of a kept question. */
    private static ACTION button(long key, boolean yes) {
        return () -> answer(key, yes);
    }

    /**
     * Hook in VIEW's update, when an interrupter holds the frame: in a session, game time keeps running under the
     * world-battle prompt and under a kept question. Their answer is a command applied at a later step, so a PC that
     * stopped its steps there would never see it apply (SOS-36). Input still stops at the prompt.
     */
    public static boolean promptsKeepTime() {
        return Hooks.worldPrompt() != null || (Hooks.stepPromptShown() >= 0 && VIEW.inters().yesNo.isActivated());
    }

    /** This PC's player answers a kept question, outside the step: the answer is a command. */
    public static void answer(long key, boolean yes) {
        Hooks.stepPromptHidden(key); // the game's dialog closed itself on the click
        if (Commands.capturing()) {
            Commands.capture(new CmdWriter(ANSWER).i((int) (key >>> 32)).i((int) key).b(yes));
        }
    }
}

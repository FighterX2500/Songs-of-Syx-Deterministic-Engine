package sosdet.game.cmd;

import java.util.List;

import snake2d.util.misc.ACTION;
import sosdet.Hooks;
import sosdet.ai.CmdWriter;
import view.main.VIEW;

/**
 * Yes/no questions the simulation asks: the step opens them with the game's own yes and no actions, and in vanilla a
 * click runs the chosen action at once. In a session the engine keeps the actions, keyed by the step that opened the
 * question and its order in that step, and a click becomes a command that runs the chosen action inside the step. Asked
 * by {@code WArmyState} (declare war and besiege?, a world question) and {@code Invasion} (take the routed invaders
 * prisoner?, a city question).
 *
 * <p>A world question (id 7951) is kept on every PC and shown only on the PC of the human it asks; its answer is a
 * world command, applied in the world part as that human, and another human's answer is skipped (SOS-46). A city
 * question (id 7950) is asked in one PC's city: in own-city mode its answer applies on that PC only. In co-op every PC
 * shows both and the first answer applies; a later one finds the question gone and is skipped everywhere.
 */
public final class StepPrompts {

    static final int ANSWER = 7950;
    static final int WORLD_ANSWER = 7951;

    static final List<Command> ALL = List.of(
            new Command(ANSWER, "stepPromptAnswer", StepPrompts::apply),
            new Command(WORLD_ANSWER, "stepPromptWorldAnswer", StepPrompts::apply));

    private static boolean apply(sosdet.ai.CmdReader r) {
        long key = ((long) r.i() << 32) | (r.i() & 0xFFFFFFFFL);
        boolean yes = r.b();
        Object action = Hooks.stepPromptTake(key, yes);
        if (action == null) {
            return false;
        }
        if (Hooks.stepPromptShown() == key) {
            // Another PC answered: the question closes here too, or this PC would sit under it (SOS-36).
            Hooks.stepPromptHidden(key);
            if (VIEW.inters().yesNo.isActivated()) {
                VIEW.inters().yesNo.deactivate();
            }
        }
        ((ACTION) action).exe();
        return true;
    }

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
        if (key >= 0) { // another PC's human's question shows on that PC only (SOS-46)
            VIEW.inters().yesNo.activate(message, button(key, true), button(key, false), dismissable);
        }
    }

    /** A button of a kept question. */
    private static ACTION button(long key, boolean yes) {
        return () -> answer(key, yes);
    }

    /** This PC's player answers a kept question, outside the step: the answer is a command. */
    public static void answer(long key, boolean yes) {
        Hooks.stepPromptHidden(key); // the game's dialog closed itself on the click
        if (Commands.capturing()) {
            Commands.capture(new CmdWriter(Hooks.stepPromptWorld(key) ? WORLD_ANSWER : ANSWER).i((int) (key >>> 32))
                    .i((int) key).b(yes));
        }
    }
}

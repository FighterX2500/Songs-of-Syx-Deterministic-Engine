package sosdet.game.cmd;

import java.util.List;

import game.GAME;
import game.battle.div.Div;
import sosdet.ai.CmdWriter;

/** The city's army: divisions' size, training and equipment targets, recruitment (ids 4000-4999). */
public final class Military {

    static final int MEN = 4000;

    static final List<Command> ALL = List.of(
            new Command(MEN, "divisionMen", r -> {
                Div d = GAME.ARMIES().division((short) r.i());
                d.info.menSet(r.i());
                return true;
            }));

    private Military() {
    }

    /** Hook in {@code DivInfo.menSet}: a division's target size, set from the army screen or the division editor. */
    public static boolean menSet(Div div, int men) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(MEN).i(div.index()).i(men));
        return true;
    }
}

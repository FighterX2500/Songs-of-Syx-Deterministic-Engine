package sosdet.game.cmd;

import java.util.List;

import settlement.stats.STATS;
import sosdet.ai.CmdWriter;

/** Laws and city-wide policies (ids 1000-1999). */
public final class Laws {

    static final int CURFEW = 1000;

    static final List<Command> ALL = List.of(
            new Command(CURFEW, "curfew", r -> {
                STATS.LAW().getCurfew().setForADay(r.b());
                return true;
            }));

    private Laws() {
    }

    /** Hook in {@code Curfew.setForADay}: the law screen's curfew button. */
    public static boolean curfew(boolean set) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(CURFEW).b(set));
        return true;
    }
}

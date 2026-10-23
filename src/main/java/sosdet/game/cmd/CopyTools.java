package sosdet.game.cmd;

import snake2d.util.color.COLOR;
import view.main.VIEW;

/**
 * The room copier, the mass copier and saved blueprints are not commands yet (ADR 0003, ported after V0.1): in a
 * session they are blocked with a notice, so no PC builds a room the others never see.
 */
public final class CopyTools {

    static final String NOTICE = "SOSDET: the room copier, the mass copier and saved blueprints don't work in a "
            + "multiplayer session yet.";

    private CopyTools() {
    }

    /**
     * Hook in {@code UICopier.activate}, {@code ROOM_COPY.copy} and {@code SavedPrintsPlacer.place}: in a session
     * (true) the tool does not open and a notice says why.
     */
    public static boolean blocked() {
        if (!Commands.capturing()) {
            return false;
        }
        VIEW.inters().fullScreen.activate(NOTICE, COLOR.WHITE100, null);
        return true;
    }

    /** Hook in {@code Copier.isPlacable}, per hovered tile: in a session the copier's refusal, shown at the cursor. */
    public static CharSequence copyRefusal() {
        return Commands.capturing() ? NOTICE : null;
    }
}

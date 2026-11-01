package sosdet;

import javax.swing.JOptionPane;

import game.VERSION;

/**
 * Stops the game at start-up on any build but the one SOSDET's patches were made from (SOS-126). The launcher compares
 * the major version only, so a 71.x update would otherwise run the new game with 71.44's patched classes, single player
 * too. {@code VERSION_STRING} isn't a compile-time constant, so this reads the running game's value.
 */
public final class GameBuild {

    private static final String SUPPORTED = "0.71.44";

    private GameBuild() {
    }

    /** First thing in {@code MainProcess.main}: nothing of the game's has started, so the dialog is Swing's own. */
    public static void check() {
        String problem = problem(VERSION.VERSION_STRING);
        if (problem == null) {
            return;
        }
        JOptionPane.showMessageDialog(null, problem, "SOSDET", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }

    /** What to tell the player, or null on the supported build. */
    static String problem(String build) {
        if (SUPPORTED.equals(build)) {
            return null;
        }
        return "SOSDET works only with Songs of Syx " + SUPPORTED + ", and this game is " + build + ".\n\n"
                + "To play, disable SOSDET and the mods that need it, such as SOS GSG MP, in the game's launcher.\n"
                + "They can be enabled again when SOSDET has an update for this build.";
    }
}

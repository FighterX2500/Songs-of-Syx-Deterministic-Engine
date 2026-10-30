package sosdet.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import game.save.GameLoader;
import init.paths.PATHS;

/**
 * The game's side of a city view (plan 0013, SOS-82): the owner's save, the viewer's own save while it views, and
 * loading either. The files live in the saves folder, under names no player picks.
 */
public final class GameView {

    /** The owner's game, saved at the view's freeze step and sent to the viewer. */
    static final String VIEW_SAVE = "__view";
    /** The viewer's own game at the freeze step, loaded back when the view ends. */
    static final String OWN_SAVE = "__beforeView";
    /** Any other PC's own game, saved and loaded back as a view opens and closes. */
    static final String RELOAD_SAVE = "__viewReload";

    private GameView() {
    }

    /** Saves the running game, between steps, and returns the save's bytes. */
    public static byte[] saveForView() {
        Path file = file(VIEW_SAVE);
        GameResync.save(file);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: the view save " + file + " can't be read", e);
        }
    }

    /** Saves this PC's own game, between steps, to be loaded back when the view ends. */
    public static void saveOwn() {
        GameResync.save(file(OWN_SAVE));
    }

    /** Loads the owner's save, written over this PC's earlier one; the load runs between frames. */
    public static void loadView(byte[] save) {
        Path file = file(VIEW_SAVE);
        try {
            Files.write(file, save);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: the view save " + file + " can't be written", e);
        }
        load(file);
    }

    /** The owner loads the save it just made for the view, as the viewer loads it. */
    public static void loadSaved() {
        load(file(VIEW_SAVE));
    }

    /** Saves this PC's own game, between steps, and loads it back; the load runs between frames. */
    public static void reloadOwn() {
        Path file = file(RELOAD_SAVE);
        GameResync.save(file);
        load(file);
    }

    /** Loads this PC's own game saved at the view's freeze step. */
    public static void loadOwn() {
        load(file(OWN_SAVE));
    }

    /** A local battle's PC at its end: its own game as the battle's generation saved it at the freeze (SOS-93). */
    public static void loadBeforeBattle() {
        load(file("__beforeBattle"));
    }

    /** A file in the saves folder; the game's own lookup wants it to exist already. */
    private static Path file(String name) {
        return PATHS.local().save().get().resolve(name + ".save");
    }

    private static void load(Path file) {
        new GameLoader(file).set();
    }
}

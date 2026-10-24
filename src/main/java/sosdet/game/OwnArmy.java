package sosdet.game;

import game.GAME;
import game.battle.Army;
import game.battle.div.Div;
import settlement.room.military.artillery.ArtilleryInstance;
import sosdet.Hooks;
import sosdet.ai.Commanders;

/**
 * The battle UI's own army on this PC (SOS-15): what vanilla's UI takes to be the player's army. With a commanders
 * table it is the army this PC's player commands, so a PC commanding the enemy side sees and selects that side as its
 * own, and only the divisions it commands. Read by the UI only, never inside the step (a per-PC value).
 */
public final class OwnArmy {

    private OwnArmy() {
    }

    /** The army this PC's player commands; the player's (army 0) without a table. */
    public static Army own() {
        Commanders c = Hooks.commanders();
        return c == null || !c.set() ? GAME.ARMIES().player() : GAME.ARMIES().armies().get(c.ownArmy());
    }

    /** The army it fights. */
    public static Army other() {
        return own().enemy();
    }

    /** {@code a} as this PC sees it: the player's army is its own, the enemy the other. */
    public static Army seen(Army a) {
        return own() == GAME.ARMIES().player() ? a : a.enemy();
    }

    /** Whether this PC's player may select the division: all of them without a table, as in vanilla. */
    public static boolean selectable(Div d) {
        Commanders c = Hooks.commanders();
        return c == null || c.commandsHere(d.index());
    }

    public static boolean selectable(ArtilleryInstance a) {
        Commanders c = Hooks.commanders();
        return c == null || c.commandsArtilleryHere(a.army().index());
    }

    /** The battle's divisions, both armies, in the game's order. */
    public static int divisions() {
        return GAME.ARMIES().divisions().size();
    }

    public static int armies() {
        return GAME.ARMIES().armies().size();
    }
}

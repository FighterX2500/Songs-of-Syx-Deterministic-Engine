package sosdet.game;

import java.lang.reflect.Field;

import game.faction.FACTIONS;
import snake2d.util.rnd.RND;
import snake2d.util.sets.ArrayList;
import snake2d.util.sprite.text.Str;
import world.region.RD;
import world.region.pop.RDRace;

/**
 * The names a world edit gives (SOSGSGMP hot-join, SOS-190): a carve's new NPC state, its capital and its king take
 * vanilla's next names from each race's name lists. Those lists' cursors are per-PC state (vanilla draws the local
 * player's default name from them at start, and skips the local player's name), so an edit every PC runs would name
 * the same state differently on each. Before such an edit every cursor is set from the edit's seeded RND, the same on
 * every PC, and moved past any human's name, which vanilla's skip would otherwise pass on one PC and not another.
 */
public final class GameNames {

    private static final String GAME_VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Field CURSOR = field("i");
    private static final Field ALL = field("all");

    private GameNames() {
    }

    /** Every race's name lists start at a cursor drawn from RND, which the caller has seeded the same on every PC. */
    public static void align() {
        try {
            for (RDRace r : RD.RACES().all) {
                align(r.names.intros);
                align(r.names.fNames);
                align(r.names.rIntro);
                align(r.names.rNames);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot set the race name lists" + GAME_VERSION, e);
        }
    }

    private static void align(RDRace.RDNameList list) throws IllegalAccessException {
        @SuppressWarnings("unchecked") // RDNameList.all is an ArrayList of the names
        ArrayList<String> all = (ArrayList<String>) ALL.get(list);
        int n = all.size();
        if (n == 0) {
            return;
        }
        int at = RND.rInt(n);
        for (int k = 0; k < n && human(all.get(at)); k++) {
            at = (at + 1) % n;
        }
        CURSOR.setInt(list, at);
    }

    private static boolean human(String name) {
        for (int slot : GameHumans.get().slots()) {
            if (Str.isSame(name, FACTIONS.getByIndex(slot).name)) {
                return true;
            }
        }
        return false;
    }

    private static Field field(String name) {
        try {
            Field f = RDRace.RDNameList.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no field RDRace.RDNameList." + name + " in this game build"
                    + GAME_VERSION, e);
        }
    }
}

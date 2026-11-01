package sosdet.game;

import java.lang.reflect.Field;

/**
 * The world's and the city's static state that vanilla never clears between games in one process, reset at every load in a session
 * (SOS-35), so a PC that loads in a fresh process and one that played before both start from the same values. One
 * reflected member each (AGENTS.md "Adapter Boundary"); resolved when a load in a session first needs them.
 *
 * <ul>
 * <li>{@code RDMilitaryGar.WDivsCapitol.supplies} and {@code upI}: the capital garrison's supply sums, which vanilla
 * adds to at every count and never clears, and the step they were last counted at. Dropped (the next count starts
 * from zeros) and back to -1.</li>
 * <li>{@code RDRace.RDRacePopulation.upI} and {@code RoomEmploymentIns.employeesI}/{@code employeesO}: the tick a
 * region's race target and a room's employee list were last cached at (SOS-36). A PC that already ran a tick of its own
 * timeline (a resync load) could find that tick's old cache valid after the load. Back to -1 (and no room).</li>
 * </ul>
 *
 * <p>In a process that hasn't built a game yet (a campaign resumed from the menu, SOS-64), every one of them still holds
 * its start value, which is the value the reset writes, and writing one would run its class's initializer, which needs
 * the game's clock ({@code RDRace.RDRacePopulation} reads {@code TIME.t}). So the reset is skipped until a game exists.
 */
public final class WorldStatics {

    private static final String VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Class<?> CAPITOL = className("world.region.RDMilitaryGar$WDivsCapitol");
    private static final Field SUPPLIES = field(CAPITOL, "supplies");
    private static final Field SUPPLIES_UP = field(CAPITOL, "upI");
    private static final Field RACE_TARGET_UP = field(className("world.region.pop.RDRace$RDRacePopulation"), "upI");
    private static final Class<?> EMPLOYMENT = className("settlement.room.main.employment.RoomEmploymentIns");
    private static final Field EMPLOYEES_UP = field(EMPLOYMENT, "employeesI");
    private static final Field EMPLOYEES_ROOM = field(EMPLOYMENT, "employeesO");
    /** The game's clock: set when the first game is built, never cleared. */
    private static final Field CLOCK = field(className("game.time.TIME"), "t");

    private WorldStatics() {
    }

    /** Game thread, at the start of a load in a session. */
    public static void reset() {
        try {
            if (CLOCK.get(null) == null) {
                return; // no game was built in this process: everything holds its start value
            }
            SUPPLIES.set(null, null);
            SUPPLIES_UP.setInt(null, -1);
            RACE_TARGET_UP.setInt(null, -1);
            EMPLOYEES_UP.setInt(null, -1);
            EMPLOYEES_ROOM.set(null, null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot reset the world's static state" + VERSION, e);
        }
    }

    private static Class<?> className(String name) {
        try {
            return Class.forName(name, false, WorldStatics.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SOSDET: no class " + name + " in this game build" + VERSION, e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no field " + owner.getName() + "." + name + " in this game build" + VERSION, e);
        }
    }
}

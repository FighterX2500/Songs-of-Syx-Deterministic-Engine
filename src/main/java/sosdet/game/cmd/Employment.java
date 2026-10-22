package sosdet.game.cmd;

import java.util.List;

import init.type.WGROUP;
import settlement.main.SETT;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomInstance;
import settlement.room.main.employment.RoomEmployment;
import settlement.room.main.employment.RoomEmploymentSimple;
import settlement.room.main.employment.RoomEquip;
import settlement.room.main.job.ROOM_EMPLOY_AUTO;
import snake2d.util.misc.CLAMP;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import util.data.INT.INTE;

/**
 * Employment: work priorities, targets, auto-employ and staffing (ids 3000-3999).
 *
 * Several hooks sit at the UI call site rather than in the declaring method: {@code RoomEmploymentIns} is menu-unsafe;
 * the work-group priorities, {@code setPrioOnSkill/Fullfillment(WGROUP)} and {@code RoomEquip$Target.set} also run
 * when a game is built or loaded, outside the step, where a declaring-method hook would capture the load's own
 * resets; and one {@code ModuleEmployment} file covers the 19 {@code autoEmploy} overrides.
 */
public final class Employment {

    static final int PRIORITY = 3000;
    static final int GROUP_PRIORITY = 3001;
    static final int TARGETS = 3002;
    static final int PRIO_ON_SKILL = 3003;
    static final int PRIO_ON_FULFILLMENT = 3004;
    static final int WORKERS = 3005;
    static final int AUTO_EMPLOY = 3006;
    static final int EQUIP_TARGET = 3007;

    static final List<Command> ALL = List.of(
            new Command(PRIORITY, "workPriority", r -> {
                RoomEmployment e = employment(r);
                int value = r.i();
                if (e == null) {
                    return false;
                }
                e.priority.set(value);
                return true;
            }),
            new Command(GROUP_PRIORITY, "workGroupPriority", r -> {
                RoomEmployment e = employment(r);
                WGROUP g = group(r);
                int value = r.i();
                if (e == null) {
                    return false;
                }
                e.priorities.set(g, value);
                return true;
            }),
            new Command(TARGETS, "workTargets", r -> {
                SETT.ROOMS().employment.setTargets();
                return true;
            }),
            new Command(PRIO_ON_SKILL, "workPrioOnSkill", r -> {
                RoomEmployment e = employment(r);
                WGROUP g = group(r);
                if (e == null) {
                    return false;
                }
                e.setPrioOnSkill(g);
                return true;
            }),
            new Command(PRIO_ON_FULFILLMENT, "workPrioOnFulfillment", r -> {
                RoomEmployment e = employment(r);
                WGROUP g = group(r);
                if (e == null) {
                    return false;
                }
                e.setPrioOnFullfillment(g);
                return true;
            }),
            new Command(WORKERS, "roomWorkers", r -> {
                RoomInstance room = Args.room(r);
                int value = r.i();
                if (room == null || room.employees() == null) {
                    return false;
                }
                room.employees().neededSet(value);
                return true;
            }),
            new Command(AUTO_EMPLOY, "roomAutoEmploy", r -> {
                RoomInstance room = Args.room(r);
                boolean on = r.b();
                if (room == null || !(room.blueprint() instanceof ROOM_EMPLOY_AUTO)) {
                    return false;
                }
                ((ROOM_EMPLOY_AUTO) room.blueprint()).autoEmploy(room, on);
                return true;
            }),
            new Command(EQUIP_TARGET, "equipTarget", r -> {
                RoomEquip equip = SETT.ROOMS().employment.equip.ALL.get(r.i());
                RoomEmploymentSimple e = Args.blueprint(r).employment();
                int value = r.i();
                if (e == null) {
                    return false;
                }
                equip.target(e).set(value);
                return true;
            }));

    private Employment() {
    }

    /** Hook in {@code RoomEmployment$Priority.set}: a workplace's master priority (room panel, priority table). */
    public static boolean priority(RoomEmployment e, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(employment(new CmdWriter(PRIORITY), e).i(value));
        return true;
    }

    /** Hook at the priority screen's calls of {@code priorities.set}: a work group's priority in one workplace. */
    public static boolean groupPriority(RoomEmployment e, WGROUP g, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(employment(new CmdWriter(GROUP_PRIORITY), e).i(g.index()).i(value));
        return true;
    }

    /** Hook at the priority screen's calls of {@code priorities.inc}: the value {@code INT_OE.inc} would set. */
    public static boolean groupPriorityInc(RoomEmployment e, WGROUP g, int by) {
        return groupPriority(e, g, CLAMP.i(e.priorities.get(g) + by, e.priorities.min(g), e.priorities.max(g)));
    }

    /** Hook in {@code RoomEmployments.setTargets}: the priority screen recomputes the city's work targets. */
    public static boolean targets() {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(TARGETS));
        return true;
    }

    /** Hook at {@code UIWorkPrio}'s call of {@code setPrioOnSkill(WGROUP)}: a priority from the group's skill. */
    public static boolean prioOnSkill(RoomEmployment e, WGROUP g) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(employment(new CmdWriter(PRIO_ON_SKILL), e).i(g.index()));
        return true;
    }

    /** Hook at {@code UIWorkPrio}'s call of {@code setPrioOnFullfillment(WGROUP)}: a priority from what it likes. */
    public static boolean prioOnFulfillment(RoomEmployment e, WGROUP g) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(employment(new CmdWriter(PRIO_ON_FULFILLMENT), e).i(g.index()));
        return true;
    }

    /** Hook at the room panel's calls of {@code RoomEmploymentIns.neededSet}: a room's target number of workers. */
    public static boolean workers(Room room, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(new CmdWriter(WORKERS), room).i(value));
        return true;
    }

    /** Hook at the room panel's calls of {@code ROOM_EMPLOY_AUTO.autoEmploy(Room, boolean)}: auto-employ on or off. */
    public static boolean autoEmploy(Room room, boolean on) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(new CmdWriter(AUTO_EMPLOY), room).b(on));
        return true;
    }

    /** Hook at the equipment screens' calls of {@code RoomEquip$Target.set}: a workplace's tools per worker. */
    public static boolean equipTarget(RoomEquip equip, RoomEmploymentSimple e, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.blueprint(new CmdWriter(EQUIP_TARGET).i(equip.index()), e.blueprint()).i(value));
        return true;
    }

    /** Hook at the equipment screen's calls of {@code Target.inc}: the value {@code INTE.inc} would set. */
    public static boolean equipTargetInc(RoomEquip equip, RoomEmploymentSimple e, int by) {
        RoomEquip.Target t = equip.target(e);
        return equipTarget(equip, e, CLAMP.i(t.get() + by, t.min(), t.max()));
    }

    /**
     * What the equipment screens' allocator widget edits instead of the target itself: it reads the live target, and a
     * change becomes a command. The widget ({@code GAllocator}) is generic, so its own calls are not hooked.
     */
    public static INTE equipTargetInput(RoomEquip equip, RoomEmploymentSimple e) {
        RoomEquip.Target t = equip.target(e);
        return new INTE() {

            @Override
            public int get() {
                return t.get();
            }

            @Override
            public int min() {
                return t.min();
            }

            @Override
            public int max() {
                return t.max();
            }

            @Override
            public void set(int value) {
                if (!equipTarget(equip, e, value)) {
                    t.set(value);
                }
            }
        };
    }

    /** A workplace's employment, by its room blueprint (fixed for the game). */
    private static CmdWriter employment(CmdWriter w, RoomEmploymentSimple e) {
        return Args.blueprint(w, e.blueprint());
    }

    private static RoomEmployment employment(CmdReader r) {
        RoomBlueprint b = Args.blueprint(r);
        return b != null && b.employment() instanceof RoomEmployment ? (RoomEmployment) b.employment() : null;
    }

    /** A work group (a race and a kind of subject), by its index among all groups (fixed for the game). */
    private static WGROUP group(CmdReader r) {
        return WGROUP.all().get(r.i());
    }
}

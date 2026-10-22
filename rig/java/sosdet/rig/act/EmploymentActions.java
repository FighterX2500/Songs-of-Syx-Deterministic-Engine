package sosdet.rig.act;

import init.type.WGROUP;
import settlement.main.SETT;
import settlement.room.main.RoomBlueprintIns;
import settlement.room.main.RoomInstance;
import settlement.room.main.employment.RoomEmployment;
import settlement.room.main.employment.RoomEmploymentSimple;
import settlement.room.main.employment.RoomEquip;
import settlement.room.main.job.ROOM_EMPLOY_AUTO;
import sosdet.Engine;
import sosdet.game.cmd.Employment;

/**
 * The rig's scripted player: employment actions, each the call the game's UI makes (SOS-34). Workplaces are named by
 * their room key (for example FARM_GRAIN), rooms by their key and their index among its rooms, work groups by their
 * index, equipment by its index (acting on the first workplace that uses it).
 */
public final class EmploymentActions {

    private EmploymentActions() {
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        case "empprio": { // empprio <room key> <0-30>: a workplace's master priority (room panel, priority table)
            String key = a[1];
            int v = Integer.parseInt(a[2]);
            Engine.betweenSteps(() -> work(key).priority.set(v));
            return true;
        }
        case "empgroup": { // empgroup <room key> <group> <0-5>: a group card dropped on a priority (priority screen)
            String key = a[1];
            int g = Integer.parseInt(a[2]);
            int v = Integer.parseInt(a[3]);
            Engine.betweenSteps(() -> {
                RoomEmployment e = work(key);
                WGROUP group = WGROUP.all().get(g);
                if (!Employment.groupPriority(e, group, v)) {
                    e.priorities.set(group, v);
                }
            });
            return true;
        }
        case "empgroupinc": { // empgroupinc <room key> <group> <+-1>: a row's or a column's arrow (priority screen)
            String key = a[1];
            int g = Integer.parseInt(a[2]);
            int by = Integer.parseInt(a[3]);
            Engine.betweenSteps(() -> {
                RoomEmployment e = work(key);
                WGROUP group = WGROUP.all().get(g);
                if (!Employment.groupPriorityInc(e, group, by)) {
                    e.priorities.inc(group, by);
                }
            });
            return true;
        }
        case "emptargets": // emptargets: what the priority screen does after a drop
            Engine.betweenSteps(() -> SETT.ROOMS().employment.setTargets());
            return true;
        case "empskill": { // empskill <room key> <group>: "set all priorities based on work skill"
            String key = a[1];
            int g = Integer.parseInt(a[2]);
            Engine.betweenSteps(() -> {
                RoomEmployment e = work(key);
                WGROUP group = WGROUP.all().get(g);
                if (!Employment.prioOnSkill(e, group)) {
                    e.setPrioOnSkill(group);
                }
            });
            return true;
        }
        case "empfulfil": { // empfulfil <room key> <group>: "set all priorities based on fulfillment"
            String key = a[1];
            int g = Integer.parseInt(a[2]);
            Engine.betweenSteps(() -> {
                RoomEmployment e = work(key);
                WGROUP group = WGROUP.all().get(g);
                if (!Employment.prioOnFulfillment(e, group)) {
                    e.setPrioOnFullfillment(group);
                }
            });
            return true;
        }
        case "empworkers": { // empworkers <room key> <room index> <n>: a room's worker slider
            String key = a[1];
            int i = Integer.parseInt(a[2]);
            int n = Integer.parseInt(a[3]);
            Engine.betweenSteps(() -> {
                RoomInstance room = room(key, i);
                if (!Employment.workers(room, n)) {
                    room.employees().neededSet(n);
                }
            });
            return true;
        }
        case "empauto": { // empauto <room key> <room index> 1|0: a room's auto-employ button
            String key = a[1];
            int i = Integer.parseInt(a[2]);
            boolean on = a[3].equals("1");
            Engine.betweenSteps(() -> {
                RoomInstance room = room(key, i);
                if (!Employment.autoEmploy(room, on)) {
                    ((ROOM_EMPLOY_AUTO) room.blueprint()).autoEmploy(room, on);
                }
            });
            return true;
        }
        case "empequip": { // empequip <equipment> <n>: the equipment screen's allocator
            int i = Integer.parseInt(a[1]);
            int n = Integer.parseInt(a[2]);
            Engine.betweenSteps(() -> {
                RoomEquip eq = SETT.ROOMS().employment.equip.ALL.get(i);
                Employment.equipTargetInput(eq, eq.rooms().get(0)).set(n);
            });
            return true;
        }
        case "empequipinc": { // empequipinc <equipment> <+-1>: the equipment screen's category arrows
            int i = Integer.parseInt(a[1]);
            int by = Integer.parseInt(a[2]);
            Engine.betweenSteps(() -> {
                RoomEquip eq = SETT.ROOMS().employment.equip.ALL.get(i);
                RoomEmploymentSimple e = eq.rooms().get(0);
                if (!Employment.equipTargetInc(eq, e, by)) {
                    eq.target(e).inc(by);
                }
            });
            return true;
        }
        default:
            return false;
        }
    }

    private static RoomBlueprintIns<?> blueprint(String key) {
        return (RoomBlueprintIns<?>) SETT.ROOMS().collection.tryGet(key);
    }

    private static RoomEmployment work(String key) {
        return blueprint(key).employmentExtra();
    }

    private static RoomInstance room(String key, int index) {
        return blueprint(key).getInstance(index);
    }
}

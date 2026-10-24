package sosdet.rig.act;

import game.faction.FACTIONS;
import init.race.RACES;
import init.race.Race;
import init.resources.RESOURCE;
import init.tech.TECHS;
import init.type.CRIME_PUNISHMENTS;
import init.type.HCLASS;
import init.type.HCLASSES;
import init.type.HCLASS_RACE;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.STATS;
import settlement.stats.colls.StatsBurial.StatGrave;
import settlement.stats.colls.StatsReligion.StatReligion;
import settlement.stats.muls.StatsMultipliers.StatMultiplier;
import settlement.stats.muls.StatsMultipliers.StatMultiplierAction;
import settlement.stats.service.StatServiceImp;
import settlement.stats.stat.StatDecree;
import sosdet.Engine;
import sosdet.game.cmd.Laws;
import sosdet.rig.Rig;
import util.race.PERMISSION;
import view.main.VIEW;

/**
 * The rig's scripted player: law actions, each the call the game's UI makes (SOS-34). Where the hook sits at the UI's
 * call site, the action is that call site's guarded call. Arguments: a class is citizen, slave, noble or other; a race
 * is its index, or -1 for all races; a decree is its key (PROSECUTION, SLAVES_FREED, HANDOUT, DAY_OFF, OVERTIME).
 */
public final class LawActions {

    private LawActions() {
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        case "curfew": { // curfew 1|0: the law screen's curfew button
            boolean on = a[1].equals("1");
            Rig.later(() -> STATS.LAW().getCurfew().setForADay(on));
            return true;
        }
        case "punish": { // punish <crime> <class> <race> <punishment>: the law screen's punishment buttons
            int crime = Integer.parseInt(a[1]);
            HCLASS cl = cls(a[2]);
            Race race = race(a[3]);
            int p = Integer.parseInt(a[4]);
            Rig.later(() -> STATS.LAW().crimes.get(crime).punishmentSet(cl, race, CRIME_PUNISHMENTS.ALL().get(p)));
            return true;
        }
        case "decree": { // decree <key> <class> <race> <amount>: the decree panel's mark button
            StatMultiplierAction dec = action(a[1]);
            HCLASS cl = cls(a[2]);
            Race race = race(a[3]);
            int am = Integer.parseInt(a[4]);
            Rig.later(() -> {
                if (!Laws.actionMark(dec, cl, race, am)) {
                    dec.mark(cl, race, am);
                }
            });
            return true;
        }
        case "undecree": { // undecree <key> <class> <race>: the decree panel's cancel button
            StatMultiplierAction dec = action(a[1]);
            HCLASS cl = cls(a[2]);
            Race race = race(a[3]);
            Rig.later(() -> {
                if (!Laws.actionUnmark(dec, cl, race)) {
                    dec.unmark(cl, race);
                }
            });
            return true;
        }
        case "decreeauto": { // decreeauto <key> <class> <race> <value>: the decree panel's automatic slider
            StatMultiplierAction dec = action(a[1]);
            HCLASS cl = cls(a[2]);
            Race race = race(a[3]);
            int v = Integer.parseInt(a[4]);
            Rig.later(() -> {
                if (!Laws.actionAuto(dec, cl, race, v)) {
                    dec.auto(cl, race).set(v);
                }
            });
            return true;
        }
        case "decreeperson": { // decreeperson <key> <class> 1|0: the subject panel's decree button, on the first person
            StatMultiplierAction dec = action(a[1]);
            HCLASS cl = cls(a[2]);
            boolean set = a[3].equals("1");
            Rig.later(() -> {
                Humanoid h = person(cl, dec, set);
                if (h != null) {
                    dec.mark(h, set);
                }
            });
            return true;
        }
        case "food": { // food <index> <class> <race>: the access tab's food checkbox (toggles)
            int k = Integer.parseInt(a[1]);
            HCLASS c = cls(a[2]);
            Race race = race(a[3]);
            Rig.later(() -> {
                PERMISSION p = STATS.FOOD().allowed(k);
                if (!Laws.foodAllowed(k, c, race, !p.get(c, race))) {
                    p.toggle(c, race);
                }
            });
            return true;
        }
        case "shrine": // shrine|temple <religion> <class> <race>: the religion tab's checkboxes (toggle)
        case "temple": {
            boolean temple = a[0].equals("temple");
            StatReligion ss = STATS.RELIGION().ALL.get(Integer.parseInt(a[1]));
            HCLASS cl = cls(a[2]);
            Race race = race(a[3]);
            Rig.later(() -> {
                PERMISSION p = temple ? ss.permissionTemple : ss.permissionShrine;
                if (!Laws.religionPermission(ss, temple, cl, race, !p.get(cl, race))) {
                    p.toggle(cl, race);
                }
            });
            return true;
        }
        case "grave": { // grave <n> <class> <race>: the religion tab's grave checkbox (toggles)
            StatGrave ss = STATS.BURIAL().graves().get(Integer.parseInt(a[1]));
            HCLASS cl = cls(a[2]);
            Race race = race(a[3]);
            Rig.later(() -> {
                if (!Laws.gravePermission(ss, cl, race, !ss.grave().permission().get(cl, race))) {
                    ss.grave().permission().toggle(cl, race);
                }
            });
            return true;
        }
        case "service": { // service <n> <class> <race>: the services tab's checkbox (toggles)
            StatServiceImp ss = STATS.SERVICE().ALL.get(Integer.parseInt(a[1]));
            HCLASS_RACE cr = cls(a[2]).get(race(a[3]));
            Rig.later(() -> {
                if (!Laws.servicePermission(ss, cr, !ss.permission().is(cr))) {
                    ss.permission().toggle(cr);
                }
            });
            return true;
        }
        case "home": { // home <n> <class> <race> <target>: the access tab's target for the class's n-th furniture
            int n = Integer.parseInt(a[1]);
            HCLASS cl = cls(a[2]);
            Race race = race(a[3]);
            int t = Integer.parseInt(a[4]);
            Rig.later(() -> {
                RESOURCE res = RACES.res().homeResMax(cl).get(n).resource();
                STATS.HOME().targetSet(t, cl, race, res);
            });
            return true;
        }
        case "civic": { // civic <n> <class> <race> <target>: the access tab's target for the n-th civic equipment
            int n = Integer.parseInt(a[1]);
            HCLASS cl = cls(a[2]);
            Race race = race(a[3]);
            int t = Integer.parseInt(a[4]);
            Rig.later(() -> STATS.EQUIP().civics().get(n).targetSet(t, cl, race));
            return true;
        }
        case "edupolicy": { // edupolicy <class> <race> <policy>: the occupation tab's education policy
            HCLASS cl = cls(a[1]);
            Race race = race(a[2]);
            int p = Integer.parseInt(a[3]);
            Rig.later(() -> STATS.EDUCATION().policySet(cl, race, STATS.EDUCATION().all.get(p)));
            return true;
        }
        case "edulimit": { // edulimit <age 0|1> <class> <race> <limit>: the occupation tab's study limit
            int age = Integer.parseInt(a[1]);
            HCLASS cl = cls(a[2]);
            Race race = race(a[3]);
            int l = Integer.parseInt(a[4]);
            Rig.later(() -> STATS.EDUCATION().allAges.get(age).limitSet(cl, race, l));
            return true;
        }
        case "ration": { // ration food|drink|retire <class> <race> <value>: a stat row's decree slider
            String which = a[1];
            HCLASS cl = cls(a[2]);
            Race race = race(a[3]);
            int v = Integer.parseInt(a[4]);
            Rig.later(() -> decree(which).getI(cl).set(race, v));
            return true;
        }
        case "tech": { // tech <index> <level>: the technology screen's unlock and forget
            int t = Integer.parseInt(a[1]);
            int level = Integer.parseInt(a[2]);
            Rig.later(() -> FACTIONS.player().tech().levelSet(TECHS.ALL().get(t), level));
            return true;
        }
        case "research": { // research open|close: the research window's button, and its close button
            boolean open = a[1].equals("open");
            Rig.later(() -> {
                if (open) {
                    VIEW.UI().tech.activate();
                } else {
                    VIEW.UI().manager.close();
                }
            });
            return true;
        }
        case "techlevel": { // techlevel <index>: a probe note of the technology's level, the step and the research window
            int t = Integer.parseInt(a[1]);
            Rig.later(() -> Rig.note("techlevel " + t + ": level " + FACTIONS.player().tech().level(TECHS.ALL().get(t))
                    + " at step " + Engine.step() + ", research window " + (VIEW.UI().manager.open() ? "open" : "closed")));
            return true;
        }
        case "admit": { // admit <race> <amount>: the population tab's authorize button
            Race race = race(a[1]);
            int am = Integer.parseInt(a[2]);
            Rig.later(() -> {
                if (am > 0 && !Laws.admit(race, am)) {
                    SETT.ENTRY().immi().admit(race, am);
                }
            });
            return true;
        }
        case "admitauto": { // admitauto <race> <value>: the population tab's auto admit limit
            Race race = race(a[1]);
            int v = Integer.parseInt(a[2]);
            Rig.later(() -> {
                if (!Laws.admitAuto(race, v)) {
                    SETT.ENTRY().immi().auto(race).set(v);
                }
            });
            return true;
        }
        case "birthlimit": { // birthlimit <class> <race> <value>: the population tab's breeding limit
            HCLASS_RACE cr = HCLASS_RACE.clP(race(a[2]), cls(a[1]));
            int v = Integer.parseInt(a[3]);
            Rig.later(() -> {
                if (!Laws.birthLimit(cr, v)) {
                    STATS.POP().reproduction.limit.set(cr, v);
                }
            });
            return true;
        }
        case "breeding": { // breeding <class> <race> <value>: the population tab's forced breeding slider
            HCLASS_RACE cr = HCLASS_RACE.clP(race(a[2]), cls(a[1]));
            int v = Integer.parseInt(a[3]);
            Rig.later(() -> {
                if (!Laws.breeding(cr, v)) {
                    STATS.POP().reproduction.settings.set(cr.index, v);
                }
            });
            return true;
        }
        case "favourite": { // favourite: the subject panel's heart, on the first citizen (toggles)
            Rig.later(() -> {
                Humanoid h = person(HCLASSES.CITIZEN(), null, true);
                if (h != null && !Laws.favourite(h, STATS.APPEARANCE().favo.get(h.indu()) == 0)) {
                    STATS.APPEARANCE().favo.set(h.indu(), (STATS.APPEARANCE().favo.get(h.indu()) + 1) & 1);
                }
            });
            return true;
        }
        default:
            return false;
        }
    }

    private static HCLASS cls(String s) {
        switch (s) {
        case "citizen":
            return HCLASSES.CITIZEN();
        case "slave":
            return HCLASSES.SLAVE();
        case "noble":
            return HCLASSES.NOBLE();
        case "other":
            return HCLASSES.OTHER();
        default:
            throw new IllegalArgumentException("SOSDET rig: no class " + s);
        }
    }

    private static Race race(String s) {
        int i = Integer.parseInt(s);
        return i < 0 ? null : RACES.all().get(i);
    }

    private static StatMultiplierAction action(String key) {
        for (StatMultiplier m : STATS.MULTIPLIERS().all()) {
            if (m.key.endsWith(key) && m instanceof StatMultiplierAction) { // the game prefixes the key
                return (StatMultiplierAction) m;
            }
        }
        throw new IllegalArgumentException("SOSDET rig: no decree " + key);
    }

    private static StatDecree decree(String which) {
        switch (which) {
        case "food":
            return STATS.FOOD().FOOD.decree();
        case "drink":
            return STATS.FOOD().DRINK.decree();
        case "retire":
            return STATS.WORK().RET.RETIREMENT_AGE.decree();
        default:
            throw new IllegalArgumentException("SOSDET rig: no ration " + which);
        }
    }

    /**
     * The first person of a class, in entity order, that a decree can mark (set) or has marked (unset); with no decree,
     * the first person of the class.
     */
    private static Humanoid person(HCLASS cl, StatMultiplierAction dec, boolean set) {
        for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
            if (!(e instanceof Humanoid)) {
                continue;
            }
            Humanoid h = (Humanoid) e;
            if (h.indu().clas() != cl) {
                continue;
            }
            if (dec == null || (set ? dec.canBeMarked(h.indu()) : dec.markIs(h))) {
                return h;
            }
        }
        return null;
    }
}

package sosdet.game.cmd;

import java.util.List;

import game.faction.FACTIONS;
import init.race.Race;
import init.resources.RESOURCE;
import init.tech.TECH;
import init.tech.TECHS;
import init.type.CRIME_PUNISHMENTS;
import init.type.CRIME_PUNISHMENTS.PUNISHMENT;
import init.type.HCLASS;
import init.type.HCLASS_RACE;
import settlement.entity.humanoid.Humanoid;
import settlement.entry.Immigration;
import settlement.main.SETT;
import settlement.stats.STATS;
import settlement.stats.colls.StatsBurial.StatGrave;
import settlement.stats.colls.StatsReligion.StatReligion;
import settlement.stats.equip.Equip;
import settlement.stats.equip.EquipCivic;
import settlement.stats.law.StatCrime;
import settlement.stats.muls.StatsMultipliers.StatMultiplier;
import settlement.stats.muls.StatsMultipliers.StatMultiplierAction;
import settlement.stats.service.StatServiceImp;
import settlement.stats.stat.STAT;
import settlement.stats.stat.StatDecree;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;

/** Laws and city-wide policies (ids 1000-1999): the law, standing and decree screens, technology, immigration. */
public final class Laws {

    static final int CURFEW = 1000;
    static final int PUNISHMENT_SET = 1001;
    static final int ACTION_MARK = 1002;
    static final int ACTION_UNMARK = 1003;
    static final int ACTION_AUTO = 1004;
    static final int ACTION_PERSON = 1005;
    static final int FOOD_ALLOWED = 1006;
    static final int RELIGION_PERMISSION = 1007;
    static final int GRAVE_PERMISSION = 1008;
    static final int SERVICE_PERMISSION = 1009;
    static final int HOME_TARGET = 1010;
    static final int CIVIC_TARGET = 1011;
    static final int EDUCATION_POLICY = 1012;
    static final int EDUCATION_LIMIT = 1013;
    static final int STAT_DECREE = 1014;
    static final int TECH_LEVEL = 1015;
    static final int ADMIT = 1016;
    static final int ADMIT_AUTO = 1017;
    static final int BIRTH_LIMIT = 1018;
    static final int BREEDING = 1019;
    static final int FAVOURITE = 1020;

    static final List<Command> ALL = List.of(
            new Command(CURFEW, "curfew", r -> {
                STATS.LAW().getCurfew().setForADay(r.b());
                return true;
            }),
            new Command(PUNISHMENT_SET, "punishmentSet", r -> {
                StatCrime crime = STATS.LAW().crimes.get(r.i());
                HCLASS cl = Args.hclass(r);
                Race race = Args.race(r);
                PUNISHMENT p = CRIME_PUNISHMENTS.ALL().get(r.i());
                crime.punishmentSet(cl, race, p);
                return true;
            }),
            new Command(ACTION_MARK, "actionMark", r -> {
                StatMultiplierAction a = action(r);
                HCLASS cl = Args.hclass(r);
                Race race = Args.race(r);
                a.mark(cl, race, r.i());
                return true;
            }),
            new Command(ACTION_UNMARK, "actionUnmark", r -> {
                StatMultiplierAction a = action(r);
                HCLASS cl = Args.hclass(r);
                a.unmark(cl, Args.race(r));
                return true;
            }),
            new Command(ACTION_AUTO, "actionAuto", r -> {
                StatMultiplierAction a = action(r);
                HCLASS cl = Args.hclass(r);
                Race race = Args.race(r);
                a.auto(cl, race).set(r.i());
                return true;
            }),
            new Command(ACTION_PERSON, "actionPerson", r -> {
                StatMultiplierAction a = action(r);
                Humanoid h = Args.humanoid(r);
                boolean set = r.b();
                if (h == null) {
                    return false;
                }
                a.mark(h, set);
                return true;
            }),
            new Command(FOOD_ALLOWED, "foodAllowed", r -> {
                int food = r.i();
                HCLASS cl = Args.hclass(r);
                Race race = Args.race(r);
                STATS.FOOD().allowed(food).set(cl, race, r.b());
                return true;
            }),
            new Command(RELIGION_PERMISSION, "religionPermission", r -> {
                StatReligion rel = STATS.RELIGION().ALL.get(r.i());
                boolean temple = r.b();
                HCLASS cl = Args.hclass(r);
                Race race = Args.race(r);
                (temple ? rel.permissionTemple : rel.permissionShrine).set(cl, race, r.b());
                return true;
            }),
            new Command(GRAVE_PERMISSION, "gravePermission", r -> {
                STAT s = stat(r);
                HCLASS cl = Args.hclass(r);
                Race race = Args.race(r);
                if (!(s instanceof StatGrave)) {
                    throw new IllegalStateException("SOSDET: stat " + s.index() + " is not a grave");
                }
                ((StatGrave) s).grave().permission().set(cl, race, r.b());
                return true;
            }),
            new Command(SERVICE_PERMISSION, "servicePermission", r -> {
                StatServiceImp s = STATS.SERVICE().ALL.get(r.i());
                HCLASS_RACE cr = clRace(r);
                s.permission().set(cr, r.b());
                return true;
            }),
            new Command(HOME_TARGET, "homeTarget", r -> {
                int target = r.i();
                HCLASS cl = Args.hclass(r);
                Race race = Args.race(r);
                STATS.HOME().targetSet(target, cl, race, Args.resource(r));
                return true;
            }),
            new Command(CIVIC_TARGET, "civicTarget", r -> {
                Equip e = STATS.EQUIP().allE().get(r.i());
                int target = r.i();
                HCLASS cl = Args.hclass(r);
                Race race = Args.race(r);
                if (!(e instanceof EquipCivic)) {
                    throw new IllegalStateException("SOSDET: equipment " + e.index() + " is not civic");
                }
                ((EquipCivic) e).targetSet(target, cl, race);
                return true;
            }),
            new Command(EDUCATION_POLICY, "educationPolicy", r -> {
                HCLASS cl = Args.hclass(r);
                Race race = Args.race(r);
                STATS.EDUCATION().policySet(cl, race, STATS.EDUCATION().all.get(r.i()));
                return true;
            }),
            new Command(EDUCATION_LIMIT, "educationLimit", r -> {
                int age = r.i();
                HCLASS_RACE cr = clRace(r);
                STATS.EDUCATION().allAges.get(age).limitSet(cr, r.i());
                return true;
            }),
            new Command(STAT_DECREE, "statDecree", r -> {
                StatDecree d = stat(r).decree();
                HCLASS cl = Args.hclass(r);
                Race race = Args.race(r);
                d.getI(cl).set(race, r.i());
                return true;
            }),
            new Command(TECH_LEVEL, "techLevel", r -> {
                TECH tech = TECHS.ALL().get(r.i());
                FACTIONS.player().tech().levelSet(tech, r.i());
                return true;
            }),
            new Command(ADMIT, "admit", r -> {
                Race race = Args.race(r);
                int amount = r.i();
                Immigration immi = SETT.ENTRY().immi();
                // The UI offers at most the immigrants waiting at the click; some may have been admitted since.
                if (race != null) {
                    amount = Math.min(amount, immi.wanted(race));
                }
                if (race == null || amount <= 0) {
                    return false;
                }
                immi.admit(race, amount);
                return true;
            }),
            new Command(ADMIT_AUTO, "admitAuto", r -> {
                Race race = Args.race(r);
                int value = r.i();
                if (race == null) {
                    return false;
                }
                SETT.ENTRY().immi().auto(race).set(value);
                return true;
            }),
            new Command(BIRTH_LIMIT, "birthLimit", r -> {
                HCLASS_RACE cr = clRace(r);
                STATS.POP().reproduction.limit.set(cr, r.i());
                return true;
            }),
            new Command(BREEDING, "breeding", r -> {
                HCLASS_RACE cr = clRace(r);
                STATS.POP().reproduction.settings.set(cr.index, r.i());
                return true;
            }),
            new Command(FAVOURITE, "favourite", r -> {
                Humanoid h = Args.humanoid(r);
                boolean on = r.b();
                if (h == null) {
                    return false;
                }
                STATS.APPEARANCE().favo.set(h.indu(), on ? 1 : 0);
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

    /** Hook in {@code StatCrime.punishmentSet}: the law screen's punishment buttons, and the war criminals' one. */
    public static boolean punishmentSet(StatCrime crime, HCLASS cl, Race race, PUNISHMENT p) {
        if (!Commands.capturing()) {
            return false;
        }
        CmdWriter w = new CmdWriter(PUNISHMENT_SET).i(crime.crime.index());
        Commands.capture(Args.race(Args.hclass(w, cl), race).i(p.index()));
        return true;
    }

    /** Call site in the decree panel ({@code DPanel}): mark this many people of a class and race for a decree. */
    public static boolean actionMark(StatMultiplierAction a, HCLASS cl, Race race, int amount) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.hclass(action(new CmdWriter(ACTION_MARK), a), cl), race).i(amount));
        return true;
    }

    /** Call site in the decree panel ({@code DPanel}): cancel a decree for a class and race. */
    public static boolean actionUnmark(StatMultiplierAction a, HCLASS cl, Race race) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.hclass(action(new CmdWriter(ACTION_UNMARK), a), cl), race));
        return true;
    }

    /** Call site in the decree panel ({@code DPanel}): the decree's automatic amount ({@code auto(cl, race).set}). */
    public static boolean actionAuto(StatMultiplierAction a, HCLASS cl, Race race, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.hclass(action(new CmdWriter(ACTION_AUTO), a), cl), race).i(value));
        return true;
    }

    /**
     * Hook in the {@code mark(Humanoid, boolean)} overrides of {@code StatsMultipliers}: one person's decree, from the
     * subject panel and the room employment screen.
     */
    public static boolean actionPerson(StatMultiplierAction a, Humanoid h, boolean set) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.entity(action(new CmdWriter(ACTION_PERSON), a), h).b(set));
        return true;
    }

    /** Call site in the standing screen's access tab ({@code CatAccess}): a food or drink allowed, by its index. */
    public static boolean foodAllowed(int food, HCLASS cl, Race race, boolean allowed) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.hclass(new CmdWriter(FOOD_ALLOWED).i(food), cl), race).b(allowed));
        return true;
    }

    /** Call site in the standing screen's religion tab ({@code CatReligion}): temples or shrines of a religion allowed. */
    public static boolean religionPermission(StatReligion rel, boolean temple, HCLASS cl, Race race, boolean allowed) {
        if (!Commands.capturing()) {
            return false;
        }
        CmdWriter w = new CmdWriter(RELIGION_PERMISSION).i(rel.index()).b(temple);
        Commands.capture(Args.race(Args.hclass(w, cl), race).b(allowed));
        return true;
    }

    /** Call site in the standing screen's religion tab ({@code CatReligion}): a kind of grave allowed. */
    public static boolean gravePermission(StatGrave grave, HCLASS cl, Race race, boolean allowed) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.hclass(new CmdWriter(GRAVE_PERMISSION).i(grave.index()), cl), race).b(allowed));
        return true;
    }

    /** Call site in the standing screen's services tab ({@code CatServices}): a service allowed. */
    public static boolean servicePermission(StatServiceImp s, HCLASS_RACE cr, boolean allowed) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(SERVICE_PERMISSION).i(service(s)).i(cr.index).b(allowed));
        return true;
    }

    /** Hook in {@code StatsHome.targetSet}: the standing screen's furniture targets. */
    public static boolean homeTarget(int target, HCLASS cl, Race race, RESOURCE res) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.resource(Args.race(Args.hclass(new CmdWriter(HOME_TARGET).i(target), cl), race), res));
        return true;
    }

    /** Hook in {@code EquipCivic.targetSet}: the standing screen's clothes and other civic equipment targets. */
    public static boolean civicTarget(Equip e, int target, HCLASS cl, Race race) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.hclass(new CmdWriter(CIVIC_TARGET).i(e.index()).i(target), cl), race));
        return true;
    }

    /** Hook in {@code StatsEducation.policySet(HCLASS, Race, StatEducation)}, which the other overload calls. */
    public static boolean educationPolicy(HCLASS cl, Race race, int policy) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.hclass(new CmdWriter(EDUCATION_POLICY), cl), race).i(policy));
        return true;
    }

    /** Hook in {@code StatsEducation$AgeType.limitSet(HCLASS_RACE, int)}, which the other overload calls. */
    public static boolean educationLimit(int age, HCLASS_RACE cr, int limit) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(EDUCATION_LIMIT).i(age).i(cr.index).i(limit));
        return true;
    }

    /** Hook in {@code StatDecree$2.set}: food and drink rations and the retirement age, by the stat that holds them. */
    public static boolean statDecree(StatDecree d, HCLASS cl, Race race, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.hclass(new CmdWriter(STAT_DECREE).i(decreeStat(d)), cl), race).i(value));
        return true;
    }

    /** Hook in {@code PTech.levelSet}: unlocking and forgetting technology. */
    public static boolean techLevel(TECH tech, int level) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(TECH_LEVEL).i(tech.index()).i(level));
        return true;
    }

    /** Call sites of {@code Immigration.admit} ({@code CatPopulationGrowth}, {@code UIMiniRaces}): admit immigrants. */
    public static boolean admit(Race race, int amount) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(new CmdWriter(ADMIT), race).i(amount));
        return true;
    }

    /** Call site in {@code CatPopulationGrowth}: the automatic admission limit ({@code immi().auto(race).set}). */
    public static boolean admitAuto(Race race, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(new CmdWriter(ADMIT_AUTO), race).i(value));
        return true;
    }

    /** Call site in {@code CatPopulationGrowth}: the birth limit ({@code reproduction.limit.set}). */
    public static boolean birthLimit(HCLASS_RACE cr, int limit) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(BIRTH_LIMIT).i(cr.index).i(limit));
        return true;
    }

    /** Call site in {@code CatPopulationGrowth}: forced breeding ({@code reproduction.settings.set}). */
    public static boolean breeding(HCLASS_RACE cr, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(BREEDING).i(cr.index).i(value));
        return true;
    }

    /** Call site in the subject panel ({@code UISubjectType}): a person marked as favourite. */
    public static boolean favourite(Humanoid h, boolean on) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.entity(new CmdWriter(FAVOURITE), h).b(on));
        return true;
    }

    // ------------------------------------------------------------ arguments only laws use

    /** A decree (an action of {@code STATS.MULTIPLIERS()}), by its index among all multipliers. */
    private static CmdWriter action(CmdWriter w, StatMultiplierAction a) {
        return w.i(a.index());
    }

    private static StatMultiplierAction action(CmdReader r) {
        int i = r.i();
        StatMultiplier m = STATS.MULTIPLIERS().all().get(i);
        if (!(m instanceof StatMultiplierAction)) {
            throw new IllegalStateException("SOSDET: multiplier " + i + " is not a decree: " + m.key);
        }
        return (StatMultiplierAction) m;
    }

    /** A class and race pair, by its index in {@code HCLASS_RACE.ALL()} (which holds the "all" pairs too). */
    private static HCLASS_RACE clRace(CmdReader r) {
        return HCLASS_RACE.ALL().get(r.i());
    }

    /** A stat, by its index in {@code STATS.all()}. */
    private static STAT stat(CmdReader r) {
        return STATS.all().get(r.i());
    }

    /** A service's position in {@code STATS.SERVICE().ALL}: a service has no index of its own. */
    private static int service(StatServiceImp s) {
        for (int i = 0; i < STATS.SERVICE().ALL.size(); i++) {
            if (STATS.SERVICE().ALL.get(i) == s) {
                return i;
            }
        }
        throw new IllegalArgumentException("SOSDET: service " + s.name + " is not in STATS.SERVICE().ALL");
    }

    /** The index of the stat that holds a decree: a decree has no index of its own. */
    private static int decreeStat(StatDecree d) {
        for (int i = 0; i < STATS.all().size(); i++) {
            if (STATS.all().get(i).decree() == d) {
                return i;
            }
        }
        throw new IllegalArgumentException("SOSDET: decree " + d.name + " belongs to no stat");
    }
}

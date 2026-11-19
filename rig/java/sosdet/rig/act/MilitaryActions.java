package sosdet.rig.act;

import game.GAME;
import game.battle.Army;
import game.battle.div.Div;
import game.battle.div.DivInfo;
import game.faction.FACTIONS;
import init.constant.C;
import init.type.CAUSE_LEAVES;
import init.type.HCLASSES;
import init.type.HTYPES;
import init.race.RACES;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.STATS;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.sprite.text.Str;
import sosdet.rig.Rig;
import world.army.AD;

/** The rig's scripted player: military actions, each the call the game's UI makes (SOS-34). */
public final class MilitaryActions {

    private MilitaryActions() {
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        case "men": // men <division index in the player's army> <target>: the army screen's division size
            int div = Integer.parseInt(a[1]);
            int men = Integer.parseInt(a[2]);
            Rig.later(() -> info(div).menSet(men));
            return true;
        case "race": // race <division> <race index>: the division editor's race, or the army screen's "create"
            int raceDiv = Integer.parseInt(a[1]);
            int race = Integer.parseInt(a[2]);
            Rig.later(() -> info(raceDiv).raceSet(RACES.all().get(race)));
            return true;
        case "training": // training <division> <training index> <0..1>: the division editor's training target
            int trainDiv = Integer.parseInt(a[1]);
            int training = Integer.parseInt(a[2]);
            double trainValue = Double.parseDouble(a[3]);
            Rig.later(
                    () -> info(trainDiv).trainingSet(STATS.BATTLE().TRAINING_ALL.get(training), trainValue));
            return true;
        case "equip": // equip <division> <equipment index> <0..1>: the division editor's equipment target
            int equipDiv = Integer.parseInt(a[1]);
            int equip = Integer.parseInt(a[2]);
            double equipValue = Double.parseDouble(a[3]);
            Rig.later(() -> info(equipDiv).equipSet(STATS.EQUIP().BATTLE_ALL().get(equip), equipValue));
            return true;
        case "banner": // banner <division> <banner index>: the division editor's banner
            int bannerDiv = Integer.parseInt(a[1]);
            int banner = Integer.parseInt(a[2]);
            Rig.later(() -> info(bannerDiv).bannerISet(banner));
            return true;
        case "order": // order <place> <place>: the army screen's card at the second place dragged onto the first
            int replaced = Integer.parseInt(a[1]);
            int replacer = Integer.parseInt(a[2]);
            Rig.later(() -> {
                Army army = GAME.ARMIES().player();
                army.setDivAtOrderedIndex(army.ordered().get(replaced), army.ordered().get(replacer));
            });
            return true;
        case "untrained": // untrained 1|0: the army screen's "send out without training" toggle
            boolean untrained = a[1].equals("1");
            Rig.later(() -> SETT.BATTLE().info.sendOutWithoutTraining(untrained));
            return true;
        case "soldierprobe": // soldierprobe spawn N | muster 1|0 | report | clear (SOS-234 spike: SOLDIER people in a city)
            String what = a.length > 1 ? a[1] : "report";
            int arg = a.length > 2 ? Integer.parseInt(a[2]) : 0;
            Rig.later(() -> Rig.note(soldierProbe(what, arg)));
            return true;
        default:
            return false;
        }
    }

    /**
     * Test tooling (SOS-234 spike, ADR 0011 spike 1), one copy only: it changes this copy's city alone. Whether people of
     * vanilla's battle-map kind {@code SOLDIER}, put into a free division of the city's army 0 as 10b would put a
     * stationed ally's soldiers, stay out of the city's population, homes, work and needs. {@code spawn N}: N of them
     * at an entry point, in the first empty division, its target set to N; {@code muster 1|0}: that division's
     * mustering; {@code report}: the counts and each probe soldier's state; {@code clear}: they leave (cause ARMY).
     * Every answer starts with the counts, so a report before the spawn is the baseline.
     */
    private static String soldierProbe(String what, int arg) {
        StringBuilder b = new StringBuilder("soldierprobe ").append(what).append(": ");
        Div div = probeDiv();
        if (what.equals("spawn")) {
            div = null;
            for (Div d : GAME.ARMIES().player().divisions()) {
                if (d.info.men() == 0 && d.menNrOf() == 0 && AD.cityDivs().get(d).men() == 0) {
                    div = d;
                    break;
                }
            }
            if (div == null) {
                return b.append("no empty division").toString();
            }
            int made = 0;
            for (int i = 0; i < arg; i++) {
                COORDINATE c = SETT.ENTRY().points.randomReachable();
                if (c == null) {
                    break;
                }
                Humanoid h = new Humanoid(c.x() * C.TILE_SIZE + C.TILE_SIZEH, c.y() * C.TILE_SIZE + C.TILE_SIZEH,
                        FACTIONS.player().race(), HTYPES.SOLDIER(), null);
                if (!h.isRemoved()) {
                    STATS.NEEDS().clear(h.indu());
                    h.setDivision(div);
                    made++;
                }
            }
            div.info.raceSet(FACTIONS.player().race());
            div.info.menSet(made);
            b.append("made ").append(made).append(" in division ").append(div.index()).append("; ");
        } else if (what.equals("muster") && div != null) {
            div.settings().musteringSet(arg == 1);
        } else if (what.equals("clear")) {
            for (Humanoid h : probeSoldiers()) {
                h.kill(false, CAUSE_LEAVES.ARMY());
            }
            if (div != null) {
                div.info.menSet(0);
            }
        }
        b.append("population ").append(STATS.POP().POP.data().get(null)).append(", citizens ")
                .append(STATS.POP().POP.data(HCLASSES.CITIZEN()).get(null)).append(", SOLDIER ")
                .append(STATS.POP().pop(HTYPES.SOLDIER())).append(", subjects ").append(STATS.POP().pop(HTYPES.SUBJECT()));
        if (div != null) {
            b.append("; division ").append(div.index()).append(" target ").append(div.info.men()).append(" men ")
                    .append(div.menNrOf()).append(" mustering ").append(div.settings().mustering())
                    .append(" recruits ").append(STATS.BATTLE().RECRUIT.inDiv(div));
        }
        int homes = 0;
        int work = 0;
        int n = 0;
        double[] needs = new double[STATS.NEEDS().SNEEDS.size()];
        Str occupation = new Str(64);
        java.util.Map<String, Integer> occupations = new java.util.TreeMap<>();
        for (Humanoid h : probeSoldiers()) {
            n++;
            homes += STATS.HOME().GETTER.has(h) ? 1 : 0;
            work += STATS.WORK().EMPLOYED.get(h) != null ? 1 : 0;
            for (int k = 0; k < needs.length; k++) {
                needs[k] += STATS.NEEDS().SNEEDS.get(k).stat().indu().getD(h.indu());
            }
            occupation.clear();
            h.ai().getOccupation(h, occupation);
            occupations.merge(occupation.toString(), 1, Integer::sum);
        }
        b.append("; probe soldiers ").append(n).append(", with a home ").append(homes).append(", employed ").append(work)
                .append(", needs (mean)");
        for (int k = 0; k < needs.length; k++) {
            b.append(' ').append(STATS.NEEDS().SNEEDS.get(k).need.key).append('=')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", n == 0 ? 0 : needs[k] / n));
        }
        return b.append(", doing ").append(occupations).toString();
    }

    /** The division the probe's soldiers are in: the first army 0 division holding one; null when none. */
    private static Div probeDiv() {
        for (Humanoid h : probeSoldiers()) {
            if (h.division() != null) {
                return h.division();
            }
        }
        return null;
    }

    /** Every SOLDIER person in the city: outside a battle map, only the probe makes them. */
    private static java.util.List<Humanoid> probeSoldiers() {
        java.util.List<Humanoid> out = new java.util.ArrayList<>();
        for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
            if (e instanceof Humanoid && ((Humanoid) e).indu().hType() == HTYPES.SOLDIER() && !e.isRemoved()) {
                out.add((Humanoid) e);
            }
        }
        return out;
    }

    private static DivInfo info(int div) {
        return GAME.ARMIES().player().divisions().get(div).info;
    }
}

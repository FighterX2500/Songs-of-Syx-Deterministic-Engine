package sosdet.game.cmd;

import java.util.List;

import game.GAME;
import game.battle.div.Div;
import init.race.Race;
import settlement.main.SETT;
import settlement.stats.STATS;
import settlement.stats.colls.StatsBattle.StatTraining;
import settlement.stats.equip.EquipBattle;
import sosdet.ai.CmdWriter;

/** The city's army: divisions' size, training and equipment targets, recruitment (ids 4000-4999). */
public final class Military {

    static final int MEN = 4000;
    static final int RACE = 4001;
    static final int TRAINING = 4002;
    static final int EQUIPMENT = 4003;
    static final int BANNER = 4004;
    static final int ORDER = 4005;
    static final int SEND_UNTRAINED = 4006;

    static final List<Command> ALL = List.of(
            new Command(MEN, "divisionMen", r -> {
                Div d = GAME.ARMIES().division((short) r.i());
                d.info.menSet(r.i());
                return true;
            }),
            new Command(RACE, "divisionRace", r -> {
                Div d = Args.div(r);
                Race race = Args.race(r);
                if (race == null) {
                    return false;
                }
                d.info.raceSet(race);
                return true;
            }),
            new Command(TRAINING, "divisionTraining", r -> {
                Div d = Args.div(r);
                StatTraining t = STATS.BATTLE().TRAINING_ALL.get(r.i());
                d.info.trainingSet(t, r.d());
                return true;
            }),
            new Command(EQUIPMENT, "divisionEquipment", r -> {
                EquipBattle e = STATS.EQUIP().BATTLE_ALL().get(r.i());
                Div d = Args.div(r);
                e.targetSet(d, r.i());
                return true;
            }),
            new Command(BANNER, "divisionBanner", r -> {
                Div d = Args.div(r);
                d.info.bannerISet(r.i());
                return true;
            }),
            new Command(ORDER, "divisionOrder", r -> {
                Div replaced = Args.div(r);
                Div replacer = Args.div(r);
                if (replaced == replacer || replaced.army() != replacer.army()) {
                    return false;
                }
                replaced.army().setDivAtOrderedIndex(replaced, replacer);
                return true;
            }),
            new Command(SEND_UNTRAINED, "sendOutUntrained", r -> {
                SETT.BATTLE().info.sendOutWithoutTraining(r.b());
                return true;
            }));

    private Military() {
    }

    /** Hook in {@code DivInfo.menSet}: a division's target size, set from the army screen or the division editor. */
    public static boolean menSet(Div div, int men) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(MEN).i(div.index()).i(men));
        return true;
    }

    /** Hook in {@code DivInfo.raceSet}: the army screen's "create" button and the division editor's race. */
    public static boolean raceSet(Div div, Race race) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.div(new CmdWriter(RACE), div), race));
        return true;
    }

    /** Hook in {@code DivInfo.trainingSet}: the army screen's "create" button and the division editor's training. */
    public static boolean trainingSet(Div div, StatTraining t, double value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.div(new CmdWriter(TRAINING), div).i(t.tIndex).d(value));
        return true;
    }

    /**
     * Hook in {@code EquipBattle.targetSet}: a division's equipment target, from the army screen's "create" button and,
     * through {@code DivInfo.equipSet}, the division editor.
     */
    public static boolean equipmentSet(EquipBattle e, Div div, int target) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.div(new CmdWriter(EQUIPMENT).i(e.indexMilitary()), div).i(target));
        return true;
    }

    /** Hook in {@code DivInfo.bannerISet}: the division editor's banner. */
    public static boolean bannerSet(Div div, int banner) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.div(new CmdWriter(BANNER), div).i(banner));
        return true;
    }

    /** Hook in {@code Army.setDivAtOrderedIndex}: a division card dragged to another place, in the city or a battle. */
    public static boolean orderSet(Div replaced, Div replacer) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.div(Args.div(new CmdWriter(ORDER), replaced), replacer));
        return true;
    }

    /** Hook in {@code ArmyTrainingInfo.sendOutWithoutTraining}: the army screen's "send out untrained" toggle. */
    public static boolean sendOutUntrained(boolean value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(SEND_UNTRAINED).b(value));
        return true;
    }
}

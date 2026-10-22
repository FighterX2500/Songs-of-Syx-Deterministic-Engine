package sosdet.rig.act;

import game.GAME;
import game.battle.Army;
import game.battle.div.DivInfo;
import init.race.RACES;
import settlement.main.SETT;
import settlement.stats.STATS;
import sosdet.rig.Rig;

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
        default:
            return false;
        }
    }

    private static DivInfo info(int div) {
        return GAME.ARMIES().player().divisions().get(div).info;
    }
}

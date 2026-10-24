package sosdet.game.cmd;

import java.util.List;

import game.faction.FACTIONS;
import snake2d.util.color.COLOR;
import snake2d.util.misc.ACTION;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import view.main.VIEW;
import world.army.AD;
import world.battle.spec.WBattleResult;
import world.battle.spec.WBattleSiege;
import world.battle.spec.WBattleSpec;

/**
 * The world-battle prompt's decisions (ids 7900-7999). The world step opens the prompt on every PC alike (the
 * simulation calls {@code UIWBattlePrompt} from {@code WBattles.poll}); a button press becomes a command, and the
 * command closes the prompt and decides, inside the step on every PC. So the prompt being open, which gates the world's
 * battle polling ({@code WBattles.canPoll}), is the same on every PC at every step. In a session the prompt doesn't
 * pause the game, as a pause would hold the step the decision applies at; the world holds instead while it is open
 * ({@code Hooks.worldHeld}), and the city goes on.
 *
 * <p>Engage fights the battle on the battle map on every PC ({@link MapBattles}); in own-city mode it is refused with
 * a notice, as the battle would replace each PC's own city.
 */
public final class WorldBattles {

    static final int DECIDE = 7900;
    static final int RESULT_ACCEPT = 7901;
    static final int SIEGE_RESULT = 7902;

    /** {@link #decide} choices. */
    public static final int AUTO = 1;
    public static final int RETREAT = 2;
    /** The siege prompt's Wait: closes it, the siege goes on. */
    public static final int WAIT = 3;
    /** Engage: the battle is fought on the battle map, on every PC (co-op only; {@link MapBattles}, SOS-32). */
    public static final int ENGAGE = 4;

    /** {@link #siegeResult} choices. */
    public static final int OCCUPY = 1;
    public static final int ABANDON = 2;
    public static final int PUPPET = 3;

    static final String ENGAGE_NOTICE = "SOSDET: fighting a world battle on the battle map doesn't work with own "
            + "cities yet. Choose auto-resolve or retreat.";

    static final List<Command> ALL = List.of(
            new Command(DECIDE, "worldBattleDecide", r -> {
                int choice = r.i();
                Object open = Hooks.worldPrompt();
                if (!(open instanceof WBattleSpec)) {
                    return false;
                }
                WBattleSpec spec = (WBattleSpec) open;
                Hooks.worldPromptClose();
                if (choice == AUTO) {
                    spec.auto();
                } else if (choice == RETREAT) {
                    spec.retreat();
                } else if (choice == ENGAGE) {
                    if (Hooks.ownCity()) {
                        throw new IllegalStateException("SOSDET: Engage in own-city mode");
                    }
                    spec.engage(); // saves the game, generates the battle on the step's stream and opens it (MapBattles)
                } else if (choice != WAIT) {
                    throw new IllegalStateException("SOSDET: world battle choice " + choice);
                }
                return true;
            }),
            new Command(RESULT_ACCEPT, "worldBattleAccept", r -> {
                int[] slaves = ints(r);
                int[] spoils = ints(r);
                double mercy = r.d();
                Object open = Hooks.worldPrompt();
                if (!(open instanceof WBattleResult)) {
                    return false;
                }
                Hooks.worldPromptClose();
                if (slaves != null) {
                    AD.stats().mercy().incD(FACTIONS.player(), mercy);
                }
                ((WBattleResult) open).accept(slaves, spoils);
                return true;
            }),
            new Command(SIEGE_RESULT, "worldSiegeResult", r -> {
                int choice = r.i();
                double devastation = r.d();
                double death = r.d();
                int[] slaves = ints(r);
                int[] spoils = ints(r);
                double mercy = r.d();
                Object open = Hooks.worldPrompt();
                if (!(open instanceof WBattleSiege.Result)) {
                    return false;
                }
                WBattleSiege.Result result = (WBattleSiege.Result) open;
                Hooks.worldPromptClose();
                AD.stats().mercy().incD(FACTIONS.player(), mercy);
                if (choice == OCCUPY) {
                    result.occupy(devastation, death, slaves, spoils);
                } else if (choice == ABANDON) {
                    result.abandon(devastation, death, slaves, spoils);
                } else if (choice == PUPPET) {
                    result.puppet(devastation, death, slaves, spoils);
                } else {
                    throw new IllegalStateException("SOSDET: siege result choice " + choice);
                }
                return true;
            }));

    private WorldBattles() {
    }

    // ------------------------------------------------------------------ hooks (this PC's player, outside the step)

    /**
     * Hook in {@code UIWBattlePrompt}, as the world step opens the prompt: what it decides, and the prompt's own close.
     * Every PC opens it at the same step, so the decision command finds the same one everywhere.
     */
    public static void opened(Object decides, ACTION close) {
        if (Engine.active()) {
            Hooks.worldPromptOpened(decides, close::exe);
        }
    }

    /** Hook in the prompt's auto-resolve, retreat and wait buttons: in a session (true) the choice is a command. */
    public static boolean decide(int choice) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(DECIDE).i(choice));
        return true;
    }

    /**
     * Hook in the prompt's Engage buttons: in a session (true) a command, which fights the battle on every PC; in own-city
     * mode refused with a notice, and the prompt stays open (the battle would replace each PC's own city).
     */
    public static boolean engage() {
        if (!Commands.capturing()) {
            return false;
        }
        if (Hooks.ownCity()) {
            VIEW.inters().fullScreen.activate(ENGAGE_NOTICE, COLOR.WHITE100, null);
            return true;
        }
        Commands.capture(new CmdWriter(DECIDE).i(ENGAGE));
        return true;
    }

    /**
     * Hook in the battle result's Accept button ({@code Res}): the captives and spoils chosen, null after a defeat, and
     * the mercy the captives' choice adds.
     */
    public static boolean accept(int[] slaves, int[] spoils, double mercy) {
        if (!Commands.capturing()) {
            return false;
        }
        CmdWriter w = new CmdWriter(RESULT_ACCEPT);
        ints(w, slaves);
        ints(w, spoils);
        Commands.capture(w.d(mercy));
        return true;
    }

    /** Hook in the captured settlement's Occupy, Abandon and Puppet buttons ({@code Conquer}). */
    public static boolean siegeResult(int choice, double devastation, double death, int[] slaves, int[] spoils,
            double mercy) {
        if (!Commands.capturing()) {
            return false;
        }
        CmdWriter w = new CmdWriter(SIEGE_RESULT).i(choice).d(devastation).d(death);
        ints(w, slaves);
        ints(w, spoils);
        Commands.capture(w.d(mercy));
        return true;
    }

    /** Hook in the prompt's per-frame update: false with a session, so the open prompt doesn't pause the game. */
    public static boolean promptPauses() {
        return !Engine.active();
    }

    // ------------------------------------------------------------------ codecs

    private static void ints(CmdWriter w, int[] a) {
        if (a == null) {
            w.i(-1);
            return;
        }
        w.i(a.length);
        for (int v : a) {
            w.i(v);
        }
    }

    private static int[] ints(CmdReader r) {
        int n = r.i();
        if (n < 0) {
            return null;
        }
        int[] a = new int[n];
        for (int k = 0; k < n; k++) {
            a[k] = r.i();
        }
        return a;
    }
}

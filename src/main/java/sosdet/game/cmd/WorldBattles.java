package sosdet.game.cmd;

import java.util.List;

import game.faction.FACTIONS;
import init.race.RACES;
import init.resources.RESOURCES;
import init.trade.TR;
import snake2d.util.misc.ACTION;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import sosdet.game.BattleArmies;
import world.army.AD;
import world.battle.spec.BATTLE_RESULT;
import world.battle.spec.WBattleResult;
import world.battle.spec.WBattleSiege;
import world.battle.spec.WBattleSpec;
import world.entity.army.WArmy;

/**
 * The world-battle prompt's decisions (ids 7900-7999). The world step opens the prompt on every PC alike (the
 * simulation calls {@code UIWBattlePrompt} from {@code WBattles.poll}); a button press becomes a command, and the
 * command closes the prompt and decides, inside the step on every PC. So the prompt being open, which gates the world's
 * battle polling ({@code WBattles.canPoll}), is the same on every PC at every step. In a session the prompt doesn't
 * pause or hold the game (spec §4): the city and the world go on, and only the battle's armies wait in place while it
 * is open ({@code Hooks.armyHeld}). When nobody answers within 30 s at the agreed speed, every PC gives the default
 * answer at the same step ({@link #timedOut}; spec §16, owner 2026-10-25, SOS-45).
 *
 * <p>Engage fights the battle on the battle map on every PC ({@link MapBattles}); in own-city mode the asked human's
 * PC's battle is loaded on every PC, and each PC returns to its own city after it (plan 0006).
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
    /** Engage: the battle is fought on the battle map, on every PC ({@link MapBattles}, SOS-32; own cities: plan 0006). */
    public static final int ENGAGE = 4;

    /** {@link #siegeResult} choices. */
    public static final int OCCUPY = 1;
    public static final int ABANDON = 2;
    public static final int PUPPET = 3;

    static final List<Command> ALL = List.of(
            new Command(DECIDE, "worldBattleDecide", r -> applyDecide(r.i())),
            new Command(RESULT_ACCEPT, "worldBattleAccept", r -> {
                int[] slaves = ints(r);
                int[] spoils = ints(r);
                return applyAccept(slaves, spoils, r.d());
            }),
            new Command(SIEGE_RESULT, "worldSiegeResult", r -> {
                int choice = r.i();
                double devastation = r.d();
                double death = r.d();
                int[] slaves = ints(r);
                int[] spoils = ints(r);
                return applySiegeResult(choice, devastation, death, slaves, spoils, r.d());
            }));

    private WorldBattles() {
    }

    /** Whether a command's payload is the prompt's Engage, read without applying it (SOS-70). */
    public static boolean isEngage(byte[] payload) {
        if (CmdReader.idOf(payload) != DECIDE) {
            return false;
        }
        return new CmdReader(payload).i() == ENGAGE;
    }

    /** Whether a command's payload storms a human's capital, read without applying it: the save before it (SOS-92). */
    public static boolean isStorm(byte[] payload) {
        if (CmdReader.idOf(payload) != DECIDE || new CmdReader(payload).i() != AUTO) {
            return false;
        }
        Object open = Hooks.worldPrompt();
        return open instanceof WBattleSiege && Sieges.humanCapital(((WBattleSiege) open).besiged)
                && Sieges.stormRefusal(((WBattleSiege) open).besiged) == null;
    }

    // ------------------------------------------------------------------ decisions (inside the step, on every PC)

    /**
     * A battle or siege prompt's choice; false when no such prompt is open (an answer already applied) or it asks
     * another human (SOS-46).
     */
    private static boolean applyDecide(int choice) {
        Object open = Hooks.worldPrompt();
        if (!(open instanceof WBattleSpec) || !Hooks.worldPromptAnswerer()) {
            return false;
        }
        WBattleSpec spec = (WBattleSpec) open;
        if (Hooks.worldPromptBetweenHumans()) { // Fight only; the battle engages once both pressed it (SOS-83)
            if (choice != ENGAGE) {
                return false;
            }
            Hooks.worldPromptFight();
            return true;
        }
        int asked = Hooks.worldPromptHuman();
        int sallyAgainst = Hooks.worldPromptSally();
        if (sallyAgainst >= 0 && choice == AUTO) {
            return false; // a sally against another human is fought or called off (SOS-83)
        }
        if (choice == AUTO && spec instanceof WBattleSiege && Sieges.stormRefusal(((WBattleSiege) spec).besiged) != null) {
            return false; // a human's capital is stormed only after its wait, by the assault (SOS-92)
        }
        Hooks.worldPromptClose();
        if (choice == AUTO && spec instanceof WBattleSiege && Sieges.humanCapital(((WBattleSiege) spec).besiged)) {
            Sieges.storm(asked, (WBattleSiege) spec); // the assault, in lockstep on every PC (SOS-92 B)
            return true;
        }
        if (sallyAgainst >= 0 && choice == ENGAGE) {
            Hooks.ownCityEngage(asked, sallyAgainst, spec::engage); // built by the defender's PC; army 1 the besieger's
        } else if (choice == AUTO) {
            spec.auto();
        } else if (choice == RETREAT) {
            spec.retreat();
        } else if (choice == ENGAGE) {
            if (Hooks.ownCity()) {
                Hooks.ownCityEngage(asked, spec::engage); // the asked human's PC's battle goes to every PC (plan 0006)
            } else {
                spec.engage(); // saves the game, generates the battle on the step's stream and opens it (MapBattles)
            }
        } else if (choice != WAIT) {
            throw new IllegalStateException("SOSDET: world battle choice " + choice);
        }
        return true;
    }

    private static boolean applyAccept(int[] slaves, int[] spoils, double mercy) {
        Object open = Hooks.worldPrompt();
        if (!(open instanceof WBattleResult) || !Hooks.worldPromptAnswerer()) {
            return false;
        }
        Hooks.worldPromptClose();
        if (slaves != null) {
            AD.stats().mercy().incD(FACTIONS.player(), mercy);
        }
        ((WBattleResult) open).accept(slaves, spoils);
        return true;
    }

    private static boolean applySiegeResult(int choice, double devastation, double death, int[] slaves, int[] spoils,
            double mercy) {
        Object open = Hooks.worldPrompt();
        if (!(open instanceof WBattleSiege.Result) || !Hooks.worldPromptAnswerer()) {
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
    }

    /**
     * Nobody answered the open prompt in time (SOS-45): the default answer, at the same step on every PC. A battle is
     * auto-resolved, a siege waits (it goes on), a captured settlement is occupied with nothing extra chosen (vanilla's
     * Occupy with no enslaving, looting or razing), and a battle's result is accepted with no captives or spoils. The
     * result prompt's own defaults would ship every captive and price the mercy by this PC's city, which differs per
     * PC in own-city mode.
     */
    static void timedOut() {
        Object open = Hooks.worldPrompt();
        if (open instanceof WBattleSpec && Hooks.worldPromptSally() >= 0) {
            applyDecide(RETREAT); // a sally against another human: the garrison stays in, and the siege goes on (SOS-83)
        } else if (open instanceof WBattleSpec && Hooks.worldPromptBetweenHumans()) {
            Hooks.worldPromptEngageNext(); // between two humans the battle is always fought (spec §8, SOS-83)
        } else if (open instanceof WBattleSiege) {
            applyDecide(WAIT);
        } else if (open instanceof WBattleSpec) {
            applyDecide(AUTO);
        } else if (open instanceof WBattleResult) {
            boolean won = ((WBattleResult) open).result == BATTLE_RESULT.VICTORY || Hooks.resultForArmy1(true);
            applyAccept(won ? new int[RACES.all().size()] : null, won ? new int[RESOURCES.ALL().size()] : null, 0);
        } else if (open instanceof WBattleSiege.Result) {
            applySiegeResult(OCCUPY, 0.1, 0.1, new int[RACES.all().size()], new int[TR.ALL().size()], 0);
        }
    }

    /**
     * In the world part, at the step set when both humans pressed Fight or the time ran out: the battle between two
     * humans engages, built by the PC of the human asked first, the attacker (SOS-83).
     */
    public static void pvpEngage() {
        WBattleSpec spec = (WBattleSpec) Hooks.worldPrompt();
        int attacker = Hooks.worldPromptHuman();
        int defender = Hooks.worldPromptOther();
        Hooks.worldPromptClose();
        Hooks.ownCityEngage(attacker, defender, spec::engage);
    }

    /**
     * In {@code Resolver.init}, two humans' armies in contact: true when the second side's army is the attacker, the
     * one moving while the first side's stands (owner, 2026-10-27; SOS-83). Otherwise the first side's, the army that
     * reported the contact, is. Read from world state alike on every PC.
     */
    public static boolean secondAttacks(WArmy first, WArmy second) {
        return second != null && second.path().isValid() && (first == null || !first.path().isValid());
    }

    // ------------------------------------------------------------------ hooks (this PC's player, outside the step)

    /**
     * Hook in {@code UIWBattlePrompt}, as the world step opens the prompt: what it decides, and the prompt's own close.
     * Every PC opens it at the same step, so the decision command finds the same one everywhere, and the same armies
     * wait while it is open. True when this PC shows it: only the PC of the human it asks does (SOS-46).
     */
    public static boolean opened(Object decides, ACTION close) {
        if (!Engine.active()) {
            return true;
        }
        return Hooks.worldPromptOpened(decides, close::exe, WorldBattles::timedOut, BattleArmies.of(decides));
    }

    /** Hook in {@code UIWBattlePrompt}'s "a prompt is open" test: the engine's, the same on every PC (SOS-46). */
    public static boolean blocked(boolean dialogOpen) {
        return Hooks.worldPromptBlocked(dialogOpen);
    }

    /** Hook in the prompt's auto-resolve, retreat and wait buttons: in a session (true) the choice is a command. */
    public static boolean decide(int choice) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(DECIDE).i(choice));
        return true;
    }

    /** Hook in the prompt's Engage buttons: in a session (true) a command, which fights the battle on every PC. */
    public static boolean engage() {
        if (!Commands.capturing()) {
            return false;
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

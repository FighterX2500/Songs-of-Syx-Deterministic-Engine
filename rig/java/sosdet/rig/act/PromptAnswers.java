package sosdet.rig.act;

import init.race.RACES;
import init.resources.RESOURCES;
import init.trade.TR;
import game.battle.state.BattleState;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.game.BattleArmies;
import sosdet.game.cmd.MapBattles;
import sosdet.game.cmd.StepPrompts;
import sosdet.game.cmd.WorldBattles;
import view.main.VIEW;
import world.WORLD;
import world.entity.army.WArmy;
import world.entity.army.WArmyConstructor;
import world.battle.spec.BATTLE_RESULT;
import world.battle.spec.WBattleResult;
import world.battle.spec.WBattleSiege;
import world.battle.spec.WBattleSpec;

/**
 * The host's player answering the world-battle prompt in a rig run, where nobody clicks: auto-resolve every battle,
 * take no captives or spoils, occupy a captured settlement, and say yes to the simulation's yes/no questions. Each answer goes through the button's own hook, so it
 * becomes the same command a click makes. One per rig script instance; it answers each prompt once.
 *
 * <p>With {@code engage}, a field battle is fought on the battle map instead (SOS-32): Engage, then Start as soon as it
 * deploys, then Retreat {@code retreatAfter} steps later (0: fight on), and Close on the victory or defeat panel. A siege
 * waits instead of being resolved, so the region's defenders march on the besieging army. With {@code restartAfter}, the
 * battle panel's restart {@code restartAfter} steps after Start, once (SOS-41); the battle deploys again, and Start and
 * the retreat count begin again.
 *
 * <p>With {@code waitOut} (SOS-45), nobody answers a world-battle prompt, so its time runs out and every PC gives the
 * default answer; each prompt's opening and closing is reported, with how many of its armies, and of the others, moved
 * while it was open. The simulation's yes/no questions are still answered.
 */
public final class PromptAnswers {

    private final boolean engage;
    private final long retreatAfter;
    private final long restartAfter;
    /** The battle map's progress: the step Start applied at (-1: not yet), and what has been asked for. */
    private long battleFrom = -1;
    private boolean startAsked;
    private boolean retreatAsked;
    private boolean closeAsked;
    private boolean restartAsked;
    private boolean paused;
    private boolean resumed;
    private double speedBefore;

    public PromptAnswers(boolean engage, long retreatAfter, long restartAfter, boolean waitOut) {
        this.engage = engage;
        this.retreatAfter = retreatAfter;
        this.restartAfter = restartAfter;
        this.waitOut = waitOut;
    }

    private final boolean waitOut;
    /** waitOut: the prompt watched, the step it opened at, its armies, and every army's position then. */
    private Object watched;
    private long openedAt;
    private int[] held = new int[0];
    private final int[] openX = new int[WArmyConstructor.MAX]; // at least the game's armies.max()
    private final int[] openY = new int[WArmyConstructor.MAX]; // at least the game's armies.max()
    /** waitOut: what had moved since the prompt opened, at the last frame it was still open (the release step moves them). */
    private String movedWhileOpen = "";

    /** The prompt last answered: its decision applies some steps later, until then it stays open. */
    private Object answered;
    /** The simulation's yes/no question last answered (Yes), by its key. */
    private long answeredQuestion = -1;

    /** After a frame, outside the step: answers the open prompt if it is new. Returns its name, or null. */
    public String answer() {
        BattleState battle = VIEW.b().state();
        if (battle != null) {
            return battle(battle);
        }
        battleFrom = -1;
        startAsked = retreatAsked = closeAsked = restartAsked = false;
        long question = Hooks.stepPromptOldest();
        if (question >= 0 && question != answeredQuestion) {
            answeredQuestion = question;
            if (Hooks.stepPromptShown() == question && VIEW.inters().yesNo.isActivated()) {
                VIEW.inters().yesNo.deactivate(); // the button hides the dialog as it is clicked; nobody clicks here
            }
            StepPrompts.answer(question, true);
            return "question yes";
        }
        Object open = Hooks.worldPromptHere() ? Hooks.worldPrompt() : null; // another PC's human answers its own (SOS-46)
        if (waitOut) {
            return watch(open);
        }
        if (open == null) {
            answered = null; // the game reuses its battle prompt's object for the next battle
            return null;
        }
        if (open == answered) {
            return null;
        }
        answered = open;
        if (engage && open instanceof WBattleSpec && !(open instanceof WBattleSiege)) {
            WorldBattles.engage();
            return "battle engage";
        }
        if (engage && open instanceof WBattleSiege) {
            WorldBattles.decide(WorldBattles.WAIT); // the siege goes on, and the region's defenders come out to the field
            return "siege wait";
        }
        if (open instanceof WBattleSpec) {
            WorldBattles.decide(WorldBattles.AUTO);
            return open instanceof WBattleSiege ? "siege auto" : "battle auto";
        }
        if (open instanceof WBattleResult) {
            if (((WBattleResult) open).result != BATTLE_RESULT.VICTORY) {
                boolean sent = WorldBattles.accept(null, null, 0); // as the Accept button after a defeat
                return sent ? "defeat accept" : "defeat accept not captured";
            }
            boolean sent = WorldBattles.accept(new int[RACES.all().size()], new int[RESOURCES.ALL().size()], 0);
            return sent ? "victory accept" : "victory accept not captured";
        }
        if (open instanceof WBattleSiege.Result) {
            WorldBattles.siegeResult(WorldBattles.OCCUPY, 0, 0, new int[RACES.all().size()], new int[TR.ALL().size()], 0);
            return "siege occupy";
        }
        throw new IllegalStateException("SOSDET rig: unknown world prompt " + open.getClass().getName());
    }

    /** waitOut: reports a prompt as it opens and as it closes, unanswered. */
    private String watch(Object open) {
        if (open == watched) {
            if (open != null) {
                movedWhileOpen = moved();
            }
            return null;
        }
        String out = watched == null ? null : "closed after " + (Engine.step() - openedAt) + " steps; while it was open "
                + movedWhileOpen;
        watched = open;
        if (open == null) {
            return out;
        }
        openedAt = Engine.step();
        held = BattleArmies.of(open);
        for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
            WArmy a = WORLD.ENTITIES().armies.get(i);
            openX[i] = a != null && a.added() ? a.body().x1() : Integer.MIN_VALUE;
            openY[i] = a != null && a.added() ? a.body().y1() : Integer.MIN_VALUE;
        }
        movedWhileOpen = moved();
        return (out == null ? "" : out + "; ") + "open " + open.getClass().getName() + ", armies held " + held.length;
    }

    /** How many held armies, and how many others, stand elsewhere than when the prompt opened. */
    private String moved() {
        int heldMoved = 0;
        int others = 0;
        int othersMoved = 0;
        for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
            WArmy a = WORLD.ENTITIES().armies.get(i);
            if (a == null || !a.added() || openX[i] == Integer.MIN_VALUE) {
                continue;
            }
            boolean moved = a.body().x1() != openX[i] || a.body().y1() != openY[i];
            boolean isHeld = false;
            for (int h : held) {
                isHeld |= h == i;
            }
            if (isHeld) {
                heldMoved += moved ? 1 : 0;
            } else {
                others++;
                othersMoved += moved ? 1 : 0;
            }
        }
        return "held armies moved " + heldMoved + " of " + held.length + ", other armies moved " + othersMoved + " of "
                + others;
    }

    /**
     * A probe (SOS-83): the battle boosts of army 1's first division with men, beside the same boosts of the NPC army 1
     * would be in vanilla ({@code FACTIONS.otherFaction()}), and of army 0's first division.
     */
    private static String army1Boosts() {
        game.battle.div.Div d1 = firstManned(game.GAME.ARMIES().enemy());
        game.battle.div.Div d0 = firstManned(game.GAME.ARMIES().player());
        StringBuilder b = new StringBuilder("army1Human=").append(Hooks.battleArmy1Human(game.faction.FACTIONS.otherFaction()));
        for (game.boosting.Boostable bo : game.boosting.BOOSTABLES.BATTLE().all()) {
            b.append(' ').append(bo.key).append('=')
                    .append(d1 == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", bo.get(d1))).append('/')
                    .append(String.format(java.util.Locale.ROOT, "%.3f", bo.get(game.faction.FACTIONS.otherFaction()))).append('/')
                    .append(d0 == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", bo.get(d0)));
        }
        return b.toString();
    }

    private static game.battle.div.Div firstManned(game.battle.Army a) {
        for (int i = 0; i < a.divisions().size(); i++) {
            if (a.divisions().get(i).menNrOf() > 0) {
                return a.divisions().get(i);
            }
        }
        return null;
    }

    /** SOSDET_LOCAL_PAUSE=from,to: the fighting human pauses its battle these steps after Start (SOS-93). */
    private String pause() {
        String env = System.getenv("SOSDET_LOCAL_PAUSE");
        if (env == null || resumed) {
            return null;
        }
        String[] at = env.split(",");
        long since = Engine.step() - battleFrom;
        if (!paused && since >= Long.parseLong(at[0])) {
            paused = true;
            speedBefore = game.GAME.SPEED.speedTarget();
            game.GAME.SPEED.speedSet(0);
            return "battle pause, men " + game.GAME.ARMIES().player().men() + "/" + game.GAME.ARMIES().enemy().men();
        }
        if (paused && since >= Long.parseLong(at[1])) {
            resumed = true;
            game.GAME.SPEED.speedSet(speedBefore);
            return "battle resume, men " + game.GAME.ARMIES().player().men() + "/" + game.GAME.ARMIES().enemy().men();
        }
        return null;
    }

    /** On the battle map: Start, then Retreat after the set steps, and Close once the battle is won or lost. */
    private String battle(BattleState battle) {
        sosdet.OwnBattle own = Engine.battle();
        if (own != null && own.awaiting()) {
            return null; // own cities: the battle PC's battle isn't loaded yet; this one is replaced (plan 0006)
        }
        if (battle.deploying()) {
            if (battleFrom >= 0) { // restarted: it deploys again
                battleFrom = -1;
                startAsked = false;
            }
            if (startAsked) {
                return null;
            }
            String wait = System.getenv("SOSDET_LOCAL_START"); // steps a local battle stays in deployment (SOS-109)
            sosdet.LocalBattle fight = wait == null ? null : Engine.localBattle(Hooks.localHuman());
            if (fight != null && Engine.step() < fight.freeze + Long.parseLong(wait)) {
                return null;
            }
            startAsked = true;
            if (!MapBattles.deploy()) {
                battle.deploy();
            }
            String speed = System.getenv("SOSDET_LOCAL_SPEED"); // a local battle: the fighting human's own speed
            if (speed != null && Hooks.localBattleHere()) {
                game.GAME.SPEED.speedSet(Double.parseDouble(speed));
            }
            return "battle start";
        }
        if (battleFrom < 0) {
            battleFrom = Engine.step();
            if (Hooks.battleBetweenHumans()) {
                return "battle probe " + army1Boosts(); // SOS-83: army 1 fights with the other human's values
            }
        }
        if (Hooks.battleConcludeOpen() && !closeAsked) {
            closeAsked = true;
            if (!MapBattles.conclude()) {
                Runnable close = Hooks.battleConcludeTake(); // a local battle: its PC's own, no command (SOS-93)
                if (close != null) {
                    close.run();
                }
            }
            return "battle close";
        }
        String pause = pause();
        if (pause != null) {
            return pause;
        }
        if (restartAfter > 0 && !restartAsked && !closeAsked && Engine.step() - battleFrom >= restartAfter) {
            restartAsked = true;
            battle.reloadBattle(); // as the battle panel's restart, once confirmed: the hook makes it a command
            return "battle restart";
        }
        if (retreatAfter > 0 && !retreatAsked && !closeAsked && Engine.step() - battleFrom >= retreatAfter) {
            retreatAsked = true;
            if (!MapBattles.retreat()) {
                battle.liveRetreat(); // a local battle: its PC's own, no command (SOS-93)
            }
            return "battle retreat, extra rounds " + Engine.localRoundsRun();
        }
        return null;
    }
}

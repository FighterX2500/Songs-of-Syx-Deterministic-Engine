package sosdet.game.cmd;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.player.Player;
import game.time.TIME;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.ai.CmdWriter;
import sosdet.game.GameHumans;
import sosdet.game.siege.SiegeBook;
import sosdet.report.Field;
import world.WORLD;
import world.battle.WBattles;
import world.battle.spec.WBattleSiege;
import world.map.regions.Region;

/**
 * Sieges of a human's capital (mod spec §9, SOS-92), ids 7970-7979. The engine records when each siege began and the
 * city's population then ({@link SiegeBook}): the assault waits a season for every started 1,000 people, up to four.
 * The defender may sally out at any time during the siege ({@link #sally}): the next siege poll asks it, as at the
 * siege's start, once no battle question is open (a besieging human's siege window comes back with each of its army's
 * reports, so the sally waits for the poll rather than being refused). Once the wait has passed, the besieger's Storm
 * starts the assault ({@link #storm}, step B of the mod's plan, sieges.md).
 */
public final class Sieges {

    static final int SALLY = 7970;
    static final int WAIT_PASSED = 7971;

    static final List<Command> ALL = List.of(new Command(SALLY, "siegeSally", r -> applySally()),
            new Command(WAIT_PASSED, "siegeWaitPassed", r -> applyWaitPassed(r.i())));

    /** Patch 15's {@code WBattles.sosdetSally}: the engine compiles against the shipped game, without it. */
    private static final Method SALLY_OUT = sallyOutMethod();

    private Sieges() {
    }

    // ---------------------------------------------------------------- the world part (hooks in PSiege)

    /** Hook in {@code PSiege.besige}, as {@code reg} becomes besieged: a human's capital's siege and its wait begin. */
    public static void began(Region reg) {
        if (!Engine.active() || !humanCapital(reg)) {
            return;
        }
        int slot = reg.faction().index();
        book().begin(slot, (int) TIME.currentSecond(), Math.max(0, Hooks.reportI(slot, Field.POP_TOTAL, 0)));
    }

    /** Hook in {@code PSiege.update}, as {@code reg}'s siege ends (no besieger left): the wait ends with it. */
    public static void ended(Region reg) {
        if (!Engine.active() || !humanCapital(reg)) {
            return;
        }
        book().end(reg.faction().index());
    }

    /** True for a human's capital, which falls only to the assault of mod spec §9. */
    public static boolean humanCapital(Region reg) {
        return reg != null && reg.capitol() && reg.faction() instanceof Player;
    }

    // ---------------------------------------------------------------- the besieger's Storm

    /**
     * Why the besieger can't storm {@code reg} now, as a sentence; null when it can. Anything but another human's capital
     * follows vanilla. Read from world state, so the same on every PC, in the step and in the prompt's UI.
     */
    public static String stormRefusal(Region reg) {
        if (!Engine.active() || !humanCapital(reg)) {
            return null;
        }
        int slot = reg.faction().index();
        if (!book().besieged(slot)) {
            return "The siege of a player's capital must hold before it can be stormed.";
        }
        double left = book().waitLeft(slot, TIME.currentSecond(), TIME.seasons().bitSeconds());
        if (left > 0) {
            int days = (int) Math.ceil(left / TIME.days().bitSeconds());
            int seasons = SiegeBook.waitSeasons(book().population(slot));
            return "The assault can start in " + days + (days == 1 ? " day" : " days") + ": a siege of a city of "
                    + book().population(slot) + " people waits " + seasons + (seasons == 1 ? " season." : " seasons.");
        }
        return Hooks.assaultRefusal();
    }

    /**
     * In the world part, Storm's step, every PC: the besieger asked by the siege prompt assaults the besieged human's
     * capital, with {@link #stormRefusal} null (SOS-92 step B). The prompt is closed already.
     */
    public static void storm(int attacker, WBattleSiege siege) {
        Object[] built = sosdet.game.GameAssault.build(siege);
        Hooks.assaultBegin(attacker, siege.besiged.faction().index(), built[0], (int[]) built[1]);
    }

    // ---------------------------------------------------------------- the defender's sally

    /** Why this PC's player can't sally out of their capital now, as a sentence; null when they can. */
    public static String sallyRefusal() {
        return sallyRefusal(FACTIONS.player());
    }

    /** This PC's player sallies out of their besieged capital: captured, applied at its step. */
    public static void sally() {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: a sally outside a session's play");
        }
        Commands.capture(new CmdWriter(SALLY));
    }

    /**
     * Test tooling, for the rig only (never the mod): the siege of the capital of the human in {@code slot} began long
     * enough ago that its wait has passed, at the command's step on every PC alike.
     */
    public static void waitPassedForTest(int slot) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: a siege's wait outside a session's play");
        }
        Commands.capture(new CmdWriter(WAIT_PASSED).i(slot));
    }

    private static boolean applyWaitPassed(int slot) {
        if (!book().besieged(slot)) {
            return false;
        }
        book().backdate(slot, (int) Math.ceil(SiegeBook.MAX_SEASONS * TIME.seasons().bitSeconds()));
        return true;
    }

    private static boolean applySally() {
        Faction f = FACTIONS.player();
        if (sallyRefusal(f) != null) {
            return false; // it changed since this PC's UI checked it
        }
        try {
            SALLY_OUT.invoke(WORLD.BATTLES(), f.capitolRegion());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot call WBattles.sosdetSally (V71, build 71.44)", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: WBattles.sosdetSally failed", e.getCause());
        }
        return true;
    }

    private static Method sallyOutMethod() {
        try {
            return WBattles.class.getMethod("sosdetSally", Region.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: no method WBattles.sosdetSally in this game build (patch 15; V71, build 71.44)", e);
        }
    }

    private static String sallyRefusal(Faction f) {
        if (!(f instanceof Player) || f.capitolRegion() == null) {
            return "You have no capital.";
        }
        if (!book().besieged(f.index())) {
            return "Your capital isn't besieged.";
        }
        if (Hooks.assaultOf(f.index())) {
            return "The assault on your capital has begun.";
        }
        if (Hooks.worldPrompt() != null && Hooks.worldPromptHuman() == f.index()) {
            return "A battle question for you is open: answer it first.";
        }
        return null;
    }

    /** The game second the siege of the capital of the human in {@code slot} began; -1 when it isn't besieged. */
    public static int besiegedSince(int slot) {
        return book().startSecond(slot);
    }

    /** The game days left before the capital of the human in {@code slot} may be assaulted; -1 when it isn't besieged. */
    public static int assaultInDays(int slot) {
        if (!book().besieged(slot)) {
            return -1;
        }
        double left = book().waitLeft(slot, TIME.currentSecond(), TIME.seasons().bitSeconds());
        return (int) Math.ceil(left / TIME.days().bitSeconds());
    }

    private static SiegeBook book() {
        return GameHumans.get().sieges().book();
    }
}

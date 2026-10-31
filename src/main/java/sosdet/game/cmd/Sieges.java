package sosdet.game.cmd;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.npc.FactionNPC;
import game.faction.player.Player;
import game.time.TIME;
import init.race.RACES;
import init.type.HCLASSES;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.Notice;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import sosdet.game.GameHumans;
import sosdet.game.siege.AssaultTerms;
import sosdet.game.siege.SiegeBook;
import sosdet.report.Field;
import world.WORLD;
import world.army.AD;
import world.battle.WBattles;
import world.battle.spec.WBattleSiege;
import world.entity.army.WArmy;
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
    static final int ASSAULT_END = 7972;
    static final int ASSAULT_TERMS = 7973;
    static final int ARMY_TELEPORT = 7974;
    static final int NPC_BESIEGE = 7975;

    static final List<Command> ALL = List.of(new Command(SALLY, "siegeSally", r -> applySally()),
            new Command(WAIT_PASSED, "siegeWaitPassed", r -> applyWaitPassed(r.i())),
            new Command(ASSAULT_END, "assaultEndForTest", r -> Hooks.assaultForce(r.i())),
            new Command(ASSAULT_TERMS, "assaultTerms", Sieges::applyTermsCommand),
            new Command(ARMY_TELEPORT, "armyTeleportForTest", r -> {
                WArmy a = Args.army(r);
                int tx = r.i();
                int ty = r.i();
                if (a == null) {
                    return false;
                }
                a.teleport(tx, ty);
                return true;
            }),
            new Command(NPC_BESIEGE, "npcBesiegeForTest", r -> applyNpcBesiege(r.i())));

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
        if (!book().besieged(slot)) {
            Hooks.notice(Notice.SIEGE, slot, -1, 0);
        }
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
        int[] armies = (int[]) built[1];
        int[] owners = (int[]) built[2];
        int[] attackers = new int[owners.length + 1];
        attackers[0] = attacker;
        int n = 1;
        for (int owner : owners) {
            boolean known = false;
            for (int i = 0; i < n; i++) {
                known |= attackers[i] == owner;
            }
            if (!known) {
                attackers[n++] = owner;
            }
        }
        attackers = java.util.Arrays.copyOf(attackers, n);
        int[] men = new int[n];
        for (int i = 0; i < n; i++) {
            men[i] = sosdet.game.GameAssault.menOf(attackers[i], armies);
        }
        Hooks.assaultBegin(attacker, siege.besiged.faction().index(), built[0], armies,
                sosdet.game.GameAssault.men(armies), owners, attackers, men);
    }

    /** The furthest an ally's army stands from the capital's centre and still joins an assault, in tiles (owner, 2026-10-31). */
    static final int ALLY_TILES = 3;

    /** Whether {@code a}, on the side of the army {@code stormer} that storms {@code reg}, joins the assault (SOS-92 E). */
    public static boolean joinsAssault(WArmy a, WArmy stormer, Region reg) {
        return a.faction() == stormer.faction()
                || Math.abs(a.ctx() - reg.cx()) <= ALLY_TILES && Math.abs(a.cty() - reg.cy()) <= ALLY_TILES;
    }

    // ---------------------------------------------------------------- after a taken throne (SOS-92 step D)

    /** Why this PC's player can't choose these terms now, as a sentence; null when they can. */
    public static String termsRefusal(int kind, int percent, int[] people) {
        sosdet.Assault a = Engine.assaultTaken();
        if (a == null || a.chooser != FACTIONS.player().index()) {
            return "There is no throne of yours to decide on.";
        }
        return AssaultTerms.refusal(kind, percent, people);
    }

    /** This PC's player, the attacker, chooses what to take: captured, applied at its step on every PC. */
    public static void terms(int kind, int percent, int[] people) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: assault terms outside a session's play");
        }
        CmdWriter w = new CmdWriter(ASSAULT_TERMS).i(FACTIONS.player().index()).i(kind).i(percent).i(people.length);
        for (int n : people) {
            w.i(n);
        }
        Commands.capture(w);
    }

    private static boolean applyTermsCommand(CmdReader r) {
        int attacker = r.i();
        int kind = r.i();
        int percent = r.i();
        int[] people = new int[r.i()];
        for (int i = 0; i < people.length; i++) {
            people[i] = r.i();
        }
        return Hooks.assaultTerms(attacker, kind, percent, people);
    }

    /** The citizens and slaves in the city of the human in {@code slot}, by race, from its report: who can be removed. */
    public static int[] peopleInCity(int slot) {
        int[] n = new int[RACES.all().size()];
        int citizens = HCLASSES.CITIZEN().index() + 1;
        int slaves = HCLASSES.SLAVE().index() + 1;
        for (int r = 0; r < n.length; r++) {
            n[r] = Hooks.reportI(slot, Field.PHYSICAL, citizens, r + 1) + Hooks.reportI(slot, Field.PHYSICAL, slaves, r + 1);
        }
        return n;
    }

    /** In the world part, every PC: {@code people} as far as the city of the human in {@code slot} allows. */
    public static int[] peopleLimited(int slot, int[] people) {
        return AssaultTerms.limited(people, peopleInCity(slot));
    }

    /** In the world part, every PC: the people Eliminate removes from the city of the human in {@code slot}. */
    public static int[] peopleEliminated(int slot, int[] people) {
        return AssaultTerms.eliminated(people, peopleInCity(slot));
    }

    /** In the world part, every PC: the terms reach both players' cities and the world. Returns the money taken. */
    public static int applyTerms(int defender, int chooser, int[] attackers, int[] men, int kind, int percent, int[] people) {
        return HumanDeals.assaultTerms(defender, chooser, attackers, men, kind, percent, people);
    }

    /**
     * Test tooling, for the rig only (never the mod): the running assault's invasion ends with {@code outcome} at the
     * next city tail, on every PC alike.
     */
    public static void endForTest(int outcome) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: an assault's end outside a session's play");
        }
        Commands.capture(new CmdWriter(ASSAULT_END).i(outcome));
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

    /** Test tooling, never a player's: the army stands at this tile, at the command's step on every PC alike. */
    public static void teleportForTest(WArmy a, int tx, int ty) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: an army's teleport outside a session's play");
        }
        Commands.capture(Args.army(new CmdWriter(ARMY_TELEPORT), a).i(tx).i(ty));
    }

    /**
     * Test tooling, never a player's: the strongest NPC army goes to war with the human in {@code slot} and marches on
     * their capital from next to it, at the command's step on every PC alike (SOS-92 F).
     */
    public static void npcBesiegeForTest(int slot) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: an NPC's siege outside a session's play");
        }
        Commands.capture(new CmdWriter(NPC_BESIEGE).i(slot));
    }

    private static boolean applyNpcBesiege(int slot) {
        Faction human = FACTIONS.getByIndex(slot);
        Region capital = human.capitolRegion();
        WArmy strongest = null;
        for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
            WArmy e = WORLD.ENTITIES().armies.tryGet(i);
            if (e != null && e.faction() instanceof FactionNPC && e.faction().capitolRegion() != null
                    && AD.men(null).get(e) > 0
                    && (strongest == null || AD.power().get(e) > AD.power().get(strongest))) {
                strongest = e;
            }
        }
        if (capital == null || strongest == null) {
            return false;
        }
        if (!DIP.WAR().is(strongest.faction(), human)) {
            int before = Hooks.currentHumanSwap(human); // DIP.set reads the player for its war timer
            try {
                DIP.WAR().set(strongest.faction(), human);
            } finally {
                Hooks.currentHumanSwap(before);
            }
        }
        strongest.teleport(capital.cx() + 4, capital.cy() + 4);
        strongest.besiege(capital);
        return true;
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

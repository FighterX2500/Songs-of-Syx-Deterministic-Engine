package sosdet.game.cmd;

import java.util.List;

import game.faction.FACTIONS;
import game.faction.FCredits.CTYPE;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.DipStance;
import game.faction.player.Player;
import game.time.TIME;
import init.trade.TR;
import init.trade.TRADABLE;
import init.trade.TRADE_TYPE;
import sosdet.Hooks;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import sosdet.game.GameHumans;
import sosdet.game.deal.DealBook;
import sosdet.game.deal.DealBook.Outcome;
import sosdet.game.deal.DealBook.Proposal;
import sosdet.game.deal.DealBook.Terms;
import world.WORLD;
import world.entity.caravan.Shipment;

/**
 * Deals between humans (ids 8100-8199, SOS-72, plan 0009): a proposal and its answer, world commands applied in the
 * world part as their issuer on every PC. Vanilla's {@code Deal} always has an NPC side, which prices and judges it; a
 * deal between humans has none, so whatever both agree applies, with vanilla's own calls for each term
 * ({@code DealParty.execute}, {@code DealBools}). No NPC's opinion changes: vanilla's betrayal lives in
 * {@code OpsStance.setNewStance}, which takes NPCs only. War is not offered between humans until they can fight
 * (owner, 2026-10-26).
 */
public final class HumanDeals {

    static final int PROPOSE = 8100;
    static final int ANSWER = 8101;
    /** Cancelling every treaty with another human at once, with no answer (owner, 2026-10-27; SOS-76). */
    static final int CANCEL = 8102;
    /** An unanswered proposal expires as declined (mod spec §6). */
    static final int EXPIRES_DAYS = 30;

    static final List<Command> ALL = List.of(
            new Command(PROPOSE, "humanDealPropose", HumanDeals::applyPropose),
            new Command(ANSWER, "humanDealAnswer", HumanDeals::applyAnswer),
            new Command(CANCEL, "humanDealCancel", HumanDeals::applyCancel));

    private HumanDeals() {
    }

    // ---------------------------------------------------------------- this PC's player (outside the step)

    /** This PC's player proposes {@code terms} to the human in slot {@code to}: captured, applied at its step. */
    public static void propose(int to, Terms terms) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: a deal proposed outside a session's play");
        }
        CmdWriter w = new CmdWriter(PROPOSE).i(to).i(terms.treaty).i(terms.fromCredits).i(terms.toCredits);
        goods(w, terms.fromGoods());
        goods(w, terms.toGoods());
        Commands.capture(w);
    }

    /** This PC's player answers the proposal {@code id} made to them. */
    public static void answer(int id, boolean accept) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: a deal answered outside a session's play");
        }
        Commands.capture(new CmdWriter(ANSWER).i(id).b(accept));
    }

    /** This PC's player cancels every treaty with the human in slot {@code to}: captured, applied at its step. */
    public static void cancel(int to) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: treaties cancelled outside a session's play");
        }
        Commands.capture(new CmdWriter(CANCEL).i(to));
    }

    /** Why this PC's player can't cancel their treaties with the human in slot {@code to} now; null when they can. */
    public static String cancelRefusal(int to) {
        return cancelRefusal(FACTIONS.player(), human(to));
    }

    /** Why this PC's player can't propose {@code terms} to the human in slot {@code to} now; null when they can. */
    public static String proposeRefusal(int to, Terms terms) {
        return refusal(FACTIONS.player(), human(to), terms);
    }

    /** Why this PC's player can't accept the proposal {@code id} now; null when they can. */
    public static String acceptRefusal(int id) {
        Proposal p = book().get(id);
        if (p == null) {
            return "The proposal was answered or has expired.";
        }
        if (p.to() != FACTIONS.player().index()) {
            return "The proposal was made to another player.";
        }
        return refusal(human(p.from()), FACTIONS.player(), p.terms());
    }

    /** The open proposals, oldest first: a live view, read on the game thread. */
    public static List<Proposal> open() {
        return book().open();
    }

    /** The latest answers, oldest first: a live view, read on the game thread. */
    public static List<DealBook.Answer> answers() {
        return book().answers();
    }

    private static void goods(CmdWriter w, int[] pairs) {
        w.i(pairs.length / 2);
        for (int v : pairs) {
            w.i(v);
        }
    }

    // ---------------------------------------------------------------- inside the step, on every PC

    private static boolean applyPropose(CmdReader r) {
        int to = r.i();
        Terms terms;
        try {
            terms = new Terms(r.i(), r.i(), r.i(), goods(r), goods(r));
        } catch (IllegalArgumentException e) {
            return false; // a PC can't send this; skipped everywhere alike
        }
        Faction from = FACTIONS.player();
        if (refusal(from, human(to), terms) != null) {
            return false; // it changed since this PC's UI checked it
        }
        int expires = (int) (TIME.currentSecond() + EXPIRES_DAYS * TIME.secondsPerDay());
        return book().propose(from.index(), to, expires, terms) != null;
    }

    private static int[] goods(CmdReader r) {
        int n = r.i();
        if (n < 0 || n > TR.ALL().size()) {
            throw new IllegalArgumentException("SOSDET: " + n + " goods in a deal");
        }
        int[] pairs = new int[2 * n];
        for (int i = 0; i < pairs.length; i++) {
            pairs[i] = r.i();
        }
        return pairs;
    }

    private static boolean applyAnswer(CmdReader r) {
        int id = r.i();
        boolean accept = r.b();
        Faction me = FACTIONS.player();
        Proposal p = book().get(id);
        if (p == null || p.to() != me.index()) {
            return false; // answered, expired or dropped since; or not theirs to answer
        }
        if (!accept) {
            book().close(id, Outcome.DECLINED);
            return true;
        }
        Faction from = human(p.from());
        if (refusal(from, me, p.terms()) != null) {
            book().close(id, Outcome.REFUSED); // a term no longer holds, such as money spent since
            return true;
        }
        book().close(id, Outcome.ACCEPTED);
        execute(from, me, p.terms());
        return true;
    }

    private static boolean applyCancel(CmdReader r) {
        Faction from = FACTIONS.player();
        Faction to = human(r.i());
        if (cancelRefusal(from, to) != null) {
            return false; // it changed since this PC's UI checked it
        }
        int before = Hooks.currentHumanSwap(from); // DIP.set reads the player for its war timer: the canceller's
        try {
            DIP.NEUTRAL().set(from, to);
        } finally {
            Hooks.currentHumanSwap(before);
        }
        return true;
    }

    /** Start of each world part, before the commands: proposals due expire as declined. */
    public static void expire() {
        book().expire((int) TIME.currentSecond());
    }

    private static void execute(Faction from, Faction to, Terms t) {
        give(from, to, t.fromCredits, t.fromGoods());
        give(to, from, t.toCredits, t.toGoods());
        if (t.treaty != DealBook.NO_TREATY) {
            int before = Hooks.currentHumanSwap(from); // DIP.set reads the player for its war timer: the proposer's
            try {
                stance(t.treaty).set(from, to);
            } finally {
                Hooks.currentHumanSwap(before);
            }
        }
    }

    /** One side's money and goods, as {@code DealParty.execute} sends them, with the giver current for its city. */
    private static void give(Faction f, Faction other, int credits, int[] goods) {
        int before = Hooks.currentHumanSwap(f);
        try {
            if (credits > 0) {
                other.credits().inc(credits, CTYPE.DIPLOMACY);
                f.credits().inc(-credits, CTYPE.DIPLOMACY);
            }
            if (goods.length == 0) {
                return;
            }
            Shipment s = WORLD.ENTITIES().caravans.create(f.capitolRegion().cx(), f.capitolRegion().cy(),
                    other.capitolRegion(), TRADE_TYPE.diplomacy);
            for (int i = 0; i < goods.length; i += 2) {
                TRADABLE res = TR.ALL().get(goods[i]);
                int a = goods[i + 1];
                if (s != null) {
                    s.loadAndReserve(res, a);
                } else {
                    other.buyer(res).addReserveAndDeliver(a, TRADE_TYPE.diplomacy);
                }
                f.seller(res).remove(a, TRADE_TYPE.diplomacy, 0, other);
            }
        } finally {
            Hooks.currentHumanSwap(before);
        }
    }

    // ---------------------------------------------------------------- the rules

    /**
     * Why {@code from} can't propose, or {@code to} can't accept, {@code terms} now; null when it holds. Read inside the
     * step and, for the UI, outside it: each side's money and goods are read as that side, from its city's report in the
     * world part.
     */
    public static String refusal(Faction from, Faction to, Terms terms) {
        if (!(from instanceof Player) || !(to instanceof Player)) {
            return "Deals here are between players.";
        }
        if (from == to) {
            return "A player can't make a deal with themselves.";
        }
        if (Hooks.retired(from.index()) || Hooks.retired(to.index())) {
            return "That player was removed from the game.";
        }
        if (terms.empty()) {
            return "The deal has nothing in it.";
        }
        String why = treatyRefusal(from, to, terms.treaty);
        if (why != null) {
            return why;
        }
        why = sideRefusal(from, terms.fromCredits, terms.fromGoods());
        return why != null ? why : sideRefusal(to, terms.toCredits, terms.toGoods());
    }

    /** Why {@code from} can't cancel every treaty with {@code to} at once; null when they can. */
    static String cancelRefusal(Faction from, Faction to) {
        if (!(from instanceof Player) || !(to instanceof Player)) {
            return "Treaties here are between players.";
        }
        if (from == to) {
            return "A player has no treaties with themselves.";
        }
        if (Hooks.retired(from.index()) || Hooks.retired(to.index())) {
            return "That player was removed from the game.";
        }
        DipStance now = DIP.get(from, to);
        if (now == DIP.WAR()) {
            return "At war: make peace instead.";
        }
        return now == DIP.NEUTRAL() ? "There is no treaty to cancel." : null;
    }

    /** Why the treaty term can't be agreed between {@code a} and {@code b}; null when it can. */
    static String treatyRefusal(Faction a, Faction b, int treaty) {
        if (treaty == DealBook.NO_TREATY) {
            return null;
        }
        DipStance now = DIP.get(a, b);
        if (treaty == DealBook.PEACE) {
            return now == DIP.WAR() ? null : "The two are not at war.";
        }
        if (now == DIP.WAR()) {
            return "At war, only peace can be agreed.";
        }
        if (treaty == DealBook.CANCEL_ALL) {
            return now == DIP.NEUTRAL() ? "There is no treaty to cancel." : null;
        }
        DipStance s = stance(treaty);
        if (s.is(a, b) || s.is(b, a)) {
            return "That treaty is in place already.";
        }
        if (s != DIP.TRADE() && (DIP.overlord(a) != null || DIP.overlord(b) != null)) {
            return "A vassal agrees only trade with others.";
        }
        return null;
    }

    /**
     * In the world part, read as {@code f} from its city's report. Outside the step only this PC's own side can be
     * read: another human's city runs on their PC, so that side is checked when the deal applies.
     */
    private static String sideRefusal(Faction f, int credits, int[] goods) {
        boolean inStep = Hooks.reportRead();
        if (!inStep && f != FACTIONS.player()) {
            return null;
        }
        int before = inStep ? Hooks.currentHumanSwap(f) : -1;
        try {
            if (credits > 0 && credits > f.credits().credits()) {
                return f.name + " doesn't have " + credits + " denari.";
            }
            for (int i = 0; i < goods.length; i += 2) {
                if (goods[i] >= TR.ALL().size()) {
                    return "No such goods.";
                }
                TRADABLE res = TR.ALL().get(goods[i]);
                if (goods[i + 1] > f.res().getAvailable(res)) {
                    return f.name + " doesn't have " + goods[i + 1] + " " + res.name + " to trade.";
                }
            }
            return null;
        } finally {
            if (inStep) {
                Hooks.currentHumanSwap(before);
            }
        }
    }

    private static DipStance stance(int treaty) {
        switch (treaty) {
        case DealBook.PEACE:
        case DealBook.CANCEL_ALL:
            return DIP.NEUTRAL();
        case DealBook.TRADE:
            return DIP.TRADE();
        case DealBook.PACT:
            return DIP.PACT();
        case DealBook.ALLY:
            return DIP.ALLY();
        default:
            throw new IllegalArgumentException("SOSDET: a treaty term of " + treaty);
        }
    }

    private static Faction human(int slot) {
        return slot >= 0 && slot < FACTIONS.MAX() ? FACTIONS.getByIndex(slot) : null;
    }

    private static DealBook book() {
        return GameHumans.get().deals().book();
    }
}

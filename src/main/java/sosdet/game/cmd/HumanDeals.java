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
import sosdet.game.deal.TariffBook;
import snake2d.util.sets.LIST;
import world.WORLD;
import world.entity.caravan.Shipment;
import world.map.pathing.WRegFinder.RegDist;
import world.map.pathing.WRegFinder.Treaty;
import world.map.pathing.WRegSel;
import world.map.regions.Region;
import world.region.RD;

/**
 * Deals between humans (ids 8100-8199, SOS-72, plan 0009): a proposal and its answer, world commands applied in the
 * world part as their issuer on every PC. Vanilla's {@code Deal} always has an NPC side, which prices and judges it; a
 * deal between humans has none, so whatever both agree applies, with vanilla's own calls for each term
 * ({@code DealParty.execute}, {@code DealBools}). No NPC's opinion changes: vanilla's betrayal lives in
 * {@code OpsStance.setNewStance}, which takes NPCs only. SOS-74 adds war between humans: declared at once, with no
 * answer and no NPC's opinion changing; an ally's war never makes two humans enemies, only a declaration does
 * (plan 0004), though a human vassal follows its overlord. SOS-73 adds regions other than capitals, vassalage with a tribute rate set in the deal, and
 * the rules the patches ask about: an overlord stays out of its vassal's wars, and trade crosses a human's land at
 * peace.
 */
public final class HumanDeals {

    static final int PROPOSE = 8100;
    static final int ANSWER = 8101;
    /** Cancelling every treaty with another human at once, with no answer (owner, 2026-10-27; SOS-76). */
    static final int CANCEL = 8102;
    /** A human sets their tariff on another at once, with no answer (owner, 2026-10-27; SOS-77). */
    static final int TARIFF = 8103;
    /** A human declares war on another at once, with no answer (mod spec §6; SOS-74). */
    static final int WAR = 8104;
    /** An unanswered proposal expires as declined (mod spec §6). */
    static final int EXPIRES_DAYS = 30;

    static final List<Command> ALL = List.of(
            new Command(PROPOSE, "humanDealPropose", HumanDeals::applyPropose),
            new Command(ANSWER, "humanDealAnswer", HumanDeals::applyAnswer),
            new Command(CANCEL, "humanDealCancel", HumanDeals::applyCancel),
            new Command(TARIFF, "humanTariff", HumanDeals::applyTariff),
            new Command(WAR, "humanWar", HumanDeals::applyWar));

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
        list(w, terms.fromRegions());
        list(w, terms.toRegions());
        w.i(terms.tributePercent);
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

    /** This PC's player declares war on the human in slot {@code to}: captured, applied at its step. */
    public static void war(int to) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: war declared outside a session's play");
        }
        Commands.capture(new CmdWriter(WAR).i(to));
    }

    /** Why this PC's player can't declare war on the human in slot {@code to} now; null when they can. */
    public static String warRefusal(int to) {
        return warRefusal(FACTIONS.player(), human(to));
    }

    /** This PC's player sets their tariff on the human in slot {@code to}: captured, applied at its step. */
    public static void tariff(int to, int percent) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: a tariff set outside a session's play");
        }
        Commands.capture(new CmdWriter(TARIFF).i(to).i(percent));
    }

    /** Why this PC's player can't set a tariff of {@code percent} on the human in slot {@code to} now; null when they can. */
    public static String tariffRefusal(int to, int percent) {
        return tariffRefusal(FACTIONS.player(), human(to), percent);
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

    /**
     * Why the human in slot {@code giver} can't give {@code region} to the one in slot {@code receiver} along with the
     * regions {@code chosen} already; null when they can. For the UI's region picker.
     */
    public static String regionRefusal(int giver, int receiver, int region, int[] chosen) {
        int[] all = java.util.Arrays.copyOf(chosen, chosen.length + 1);
        all[chosen.length] = region;
        return regionsRefusal(human(giver), human(receiver), all);
    }

    /** {@code vassal}'s yearly tribute in percent to the human in slot {@code overlord}, as the deal set it. */
    public static int tributePercent(int vassal, int overlord) {
        return book().tributePercent(vassal, overlord);
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

    private static void list(CmdWriter w, int[] list) {
        w.i(list.length);
        for (int v : list) {
            w.i(v);
        }
    }

    // ---------------------------------------------------------------- inside the step, on every PC

    private static boolean applyPropose(CmdReader r) {
        int to = r.i();
        Terms terms;
        try {
            terms = new Terms(r.i(), r.i(), r.i(), goods(r), goods(r), regions(r), regions(r), r.i());
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

    private static int[] regions(CmdReader r) {
        int n = r.i();
        if (n < 0 || n > DealBook.MAX_REGIONS) {
            throw new IllegalArgumentException("SOSDET: " + n + " regions in a deal");
        }
        int[] list = new int[n];
        for (int i = 0; i < n; i++) {
            list[i] = r.i();
        }
        return list;
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

    private static boolean applyWar(CmdReader r) {
        Faction from = FACTIONS.player();
        Faction to = human(r.i());
        if (warRefusal(from, to) != null) {
            return false; // it changed since this PC's UI checked it
        }
        declareWar(from, to);
        return true;
    }

    /**
     * In the world part: {@code from} declares war on the human {@code to}, whatever their treaties; a vassalage between
     * them ends ({@code DIP.set}). The war command is the one way war between two humans starts: a besiege of a human's
     * region and a garrison's attack on a human's army need the war first ({@code World}). No NPC's opinion changes
     * (mod spec §6): {@code DIP} directly, not {@code OpsStance.setNewStance}. Their allies join as in vanilla, but
     * never as one human against another ({@link #allyFollows}).
     */
    private static void declareWar(Faction from, Faction to) {
        int before = Hooks.currentHumanSwap(from); // DIP.set reads the player for its war timer: the declarer's
        try {
            DIP.WAR().set(from, to);
        } finally {
            Hooks.currentHumanSwap(before);
        }
    }

    private static boolean applyTariff(CmdReader r) {
        Faction from = FACTIONS.player();
        Faction to = human(r.i());
        int percent = r.i();
        if (tariffRefusal(from, to, percent) != null) {
            return false; // it changed since this PC's UI checked it, such as the trade stance ended
        }
        tariffs().set(from.index(), to.index(), percent);
        return true;
    }

    /**
     * In the world part, {@code TradeSorter}'s purchase by {@code buyer} from {@code seller} at {@code price}: the
     * buyer's tariff of it, which the seller doesn't get and the buyer's treasury does (SOS-77). 0 unless both are
     * humans.
     */
    public static int tariffCut(Faction buyer, Faction seller, int price) {
        if (!(buyer instanceof Player) || !(seller instanceof Player) || buyer == seller) {
            return 0;
        }
        return tariffs().cut(buyer.index(), seller.index(), price, TIME.years().bitsSinceStart());
    }

    /**
     * Start of each world part, before the commands: proposals due expire as declined, and the tribute of a vassalage
     * that ended (cancelled, or ended by vanilla) goes.
     */
    public static void expire() {
        book().expire((int) TIME.currentSecond());
        List<DealBook.Tribute> tributes = book().tributes();
        for (int i = tributes.size() - 1; i >= 0; i--) {
            DealBook.Tribute t = tributes.get(i);
            Faction vassal = human(t.vassal());
            if (vassal == null || DIP.overlord(vassal) != human(t.overlord())) {
                book().endTribute(t.vassal());
            }
        }
    }

    /**
     * In the world part, {@code UpVassal}'s yearly tribute of the human {@code vassal} to {@code overlord}: the deal's
     * rate in percent when the overlord is a human too, else -1 (vanilla's own rule and NPC overlord).
     */
    public static int tributePercent(Faction vassal, Faction overlord) {
        if (!(vassal instanceof Player) || !(overlord instanceof Player)) {
            return -1;
        }
        return book().tributePercent(vassal.index(), overlord.index());
    }

    /**
     * In {@code DIP.ally}: true when {@code f}'s war must not pull in {@code f2}, because {@code f} is a human and
     * {@code f2} its human overlord. The vassal joins its overlord's wars; the overlord does not join a war its vassal
     * starts (mod spec §6).
     */
    public static boolean overlordStaysOut(Faction f, Faction f2) {
        return f instanceof Player && f2 instanceof Player && DIP.overlord(f) == f2;
    }

    /**
     * In {@code DIP.ally}: true when {@code f}'s change of stance with {@code f3} may carry its ally {@code f2} along,
     * into war or out of it. Never between two humans, unless {@code f2} is {@code f}'s vassal: a war between two
     * humans starts only when one declares it (plan 0004, mod spec §6), and a vassal shares its overlord's wars.
     */
    public static boolean allyFollows(Faction f, Faction f2, Faction f3) {
        return !(f2 instanceof Player && f3 instanceof Player) || DIP.overlord(f2) == f;
    }

    /**
     * In a human's trade walk ({@code WRegFinder}): true when the trade of {@code trader} may cross {@code land}'s
     * regions: both are humans, not at war (owner, 2026-10-27; mod spec §7).
     */
    public static boolean tradePasses(Faction trader, Faction land) {
        return trader instanceof Player && land instanceof Player && trader != land && !DIP.WAR().is(trader, land);
    }

    private static void execute(Faction from, Faction to, Terms t) {
        give(from, to, t.fromCredits, t.fromGoods());
        give(to, from, t.toCredits, t.toGoods());
        regions(to, t.fromRegions());
        regions(from, t.toRegions());
        if (t.treaty != DealBook.NO_TREATY) {
            int before = Hooks.currentHumanSwap(from); // DIP.set reads the player for its war timer: the proposer's
            try {
                stance(t.treaty).set(from, to);
            } finally {
                Hooks.currentHumanSwap(before);
            }
        }
        if (t.vassalage()) {
            Faction vassal = t.treaty == DealBook.PROTECTOR ? to : from;
            book().tribute(vassal.index(), (vassal == to ? from : to).index(), t.tributePercent);
        }
    }

    /**
     * The regions go to {@code receiver}, as {@code DealParty.execute} hands them over, with the receiver current: the
     * game's owner-change listeners act for "the player", and in the step that is the receiver on every PC.
     */
    private static void regions(Faction receiver, int[] regions) {
        if (regions.length == 0) {
            return;
        }
        int before = Hooks.currentHumanSwap(receiver);
        try {
            for (int i : regions) {
                RD.setFaction(WORLD.REGIONS().getByIndex(i), receiver, true);
            }
        } finally {
            Hooks.currentHumanSwap(before);
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
        why = regionsRefusal(from, to, terms.fromRegions());
        if (why != null) {
            return why;
        }
        why = regionsRefusal(to, from, terms.toRegions());
        if (why != null) {
            return why;
        }
        why = blockadeRefusal(from, to, terms);
        if (why != null) {
            return why;
        }
        why = sideRefusal(from, terms.fromCredits, terms.fromGoods());
        return why != null ? why : sideRefusal(to, terms.toCredits, terms.toGoods());
    }

    /**
     * Why {@code giver} can't give {@code regions} to {@code receiver}; null when it can. As vanilla's deal
     * ({@code DealRegs}): each is the giver's and not its capital, and each borders the receiver's land, reached from
     * the receiver's capital, or another of these regions that does.
     */
    static String regionsRefusal(Faction giver, Faction receiver, int[] regions) {
        if (regions.length == 0) {
            return null;
        }
        if (regions.length > DealBook.MAX_REGIONS) {
            return "At most " + DealBook.MAX_REGIONS + " regions in one deal.";
        }
        boolean[] chosen = new boolean[WORLD.REGIONS().all().size()];
        for (int i : regions) {
            Region reg = i >= 0 && i < chosen.length ? WORLD.REGIONS().getByIndex(i) : null;
            if (reg == null || !reg.active()) {
                return "No such region.";
            }
            if (reg.faction() != giver) {
                return reg.info.name() + " isn't " + giver.name + "'s.";
            }
            if (reg.capitol()) {
                return "A capital can't be given.";
            }
            if (reg.besieged()) {
                return reg.info.name() + " is under siege.";
            }
            chosen[i] = true;
        }
        Treaty reach = new Treaty() {
            @Override
            public boolean can(Region origin, Region prevReg, Region to, int tx, int ty, double dist) {
                if (to == null) {
                    return true;
                }
                if (prevReg == null) {
                    return false;
                }
                if (prevReg.faction() == receiver || prevReg == to) {
                    return true;
                }
                return to.faction() == giver && chosen[prevReg.index()];
            }
        };
        WRegSel sel = new WRegSel() {
            @Override
            public boolean is(Region t) {
                return chosen[t.index()];
            }
        };
        LIST<RegDist> reached = WORLD.PATH().regFinder.all(receiver.capitolRegion(), reach, sel);
        int n = 0;
        for (int i = 0; i < reached.size(); i++) {
            n += chosen[reached.get(i).reg.index()] ? 1 : 0;
        }
        return n == regions.length ? null : "Each region must border " + receiver.name + "'s land, or another region given with it.";
    }

    /**
     * Why goods in {@code terms} can't go between {@code from} and {@code to}: no goods reach a besieged capital or
     * leave it (mod spec §9, SOS-92). Money still moves. Null when nothing stops them.
     */
    static String blockadeRefusal(Faction from, Faction to, Terms terms) {
        if (terms.fromGoods().length == 0 && terms.toGoods().length == 0) {
            return null;
        }
        Faction besieged = Hooks.humanBlockaded(from) ? from : Hooks.humanBlockaded(to) ? to : null;
        return besieged == null ? null : besieged.name + "'s capital is besieged: no goods can reach it or leave it.";
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

    /** Why {@code from} can't declare war on {@code to}; null when they can. */
    static String warRefusal(Faction from, Faction to) {
        if (!(from instanceof Player) || !(to instanceof Player)) {
            return "War here is between players.";
        }
        if (from == to) {
            return "A player can't declare war on themselves.";
        }
        if (Hooks.retired(from.index()) || Hooks.retired(to.index())) {
            return "That player was removed from the game.";
        }
        return DIP.WAR().is(from, to) ? "Already at war." : null;
    }

    /** Why {@code from} can't set a tariff of {@code percent} on {@code to}; null when they can. */
    static String tariffRefusal(Faction from, Faction to, int percent) {
        if (!(from instanceof Player) || !(to instanceof Player)) {
            return "Tariffs here are between players.";
        }
        if (from == to) {
            return "A player sets no tariff on themselves.";
        }
        if (Hooks.retired(from.index()) || Hooks.retired(to.index())) {
            return "That player was removed from the game.";
        }
        if (!DIP.get(from, to).trades) {
            return "Needs a trade agreement first.";
        }
        return TariffBook.refusal(percent);
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
        case DealBook.PROTECTOR:
            return DIP.OVERLORD(); // DIP.set(instigator, accepter, OVERLORD): the proposer is the overlord
        case DealBook.VASSAL:
            return DIP.VASSAL();
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

    /** What {@code buyer}'s tariff on {@code seller} brought in this year or last; with {@code seller} -1, all this year. */
    public static int tariffRevenue(int buyer, int seller, boolean lastYear) {
        int now = TIME.years().bitsSinceStart();
        return seller < 0 ? tariffs().revenue(buyer, now) : tariffs().revenue(buyer, seller, now, lastYear);
    }

    /** This game's tariffs between humans. */
    public static TariffBook tariffs() {
        return GameHumans.get().tariffs().book();
    }
}

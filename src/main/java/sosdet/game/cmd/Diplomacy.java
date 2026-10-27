package sosdet.game.cmd;

import java.util.List;

import game.faction.FACTIONS;
import game.faction.FCredits.CTYPE;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.deal.Deal;
import game.faction.diplomacy.deal.DealParty;
import game.faction.diplomacy.deal.DealRegs.DealReg;
import game.faction.npc.FactionNPC;
import game.faction.player.Player;
import game.faction.player.emmi.EmiType;
import game.faction.player.emmi.EmiTypeReg;
import game.faction.player.emmi.EmiTypeRoy;
import game.faction.royalty.Royalty;
import game.faction.royalty.opinion.ROPINION;
import init.trade.TR;
import init.trade.TRADABLE;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import world.map.regions.Region;

/**
 * Diplomacy (ids 8000-8999): deals, the NPC factions' requests, and emissaries (stage D, plan 0002).
 *
 * <p>A deal is hooked only where it is committed ({@code Deal.execute}), never in its draft, which the UI rewrites
 * every frame. The command carries every term of the committed deal, as the game's own {@code DealSave} keeps one
 * (faction, toggles, each side's denari, regions and goods), and the step rebuilds it in a fresh deal with
 * {@code DealSave}'s checks; a deal that no longer holds is skipped on every PC.
 */
public final class Diplomacy {

    static final int DEAL_ACCEPT = 8000;
    static final int DEAL_PROPOSAL = 8001;
    static final int REQUEST_EXPIRE = 8002;
    static final int VASSAL_RELEASE = 8003;
    static final int EMISSARIES = 8004;
    static final int TRIBUTE_ACCEPT = 8005;

    /** The only {@code UIDipMessAction} in V71.44: a vassal asking for its freedom. */
    private static final String VASSAL_REQUEST = "game.events.faction.player.WarMessages$VassalRequest";

    static final List<Command> ALL = List.of(
            new Command(DEAL_ACCEPT, "dealAccept", r -> {
                Deal d = deal(r);
                if (d == null || !d.canBeAccepted()) {
                    return false;
                }
                d.execute(true);
                return true;
            }),
            new Command(DEAL_PROPOSAL, "dealProposal", r -> {
                Deal d = deal(r);
                double happiness = r.d();
                if (d == null || !d.npc.npc().request.has()) {
                    return false; // answered or expired since, or the other player accepted it first; the request is the
                                  // issuer's own, one per human (SOS-46 M3)
                }
                FactionNPC f = d.npc.npc();
                d.execute(false);
                f.request.clear();
                ROPINION.GIFTS().makeDeal(f, happiness);
                return true;
            }),
            new Command(REQUEST_EXPIRE, "requestExpire", r -> {
                FactionNPC f = Args.npc(r);
                if (f == null) {
                    return false;
                }
                f.request.expire();
                return true;
            }),
            new Command(VASSAL_RELEASE, "vassalRelease", r -> {
                FactionNPC f = Args.npc(r);
                double happiness = r.d();
                if (f == null || !DIP.VASSAL().is(f)) {
                    return false;
                }
                ROPINION.GIFTS().makeDeal(f, happiness);
                DIP.PACT().set(f); // VassalRequest.accept
                ROPINION.OTHER().liberate(f);
                return true;
            }),
            new Command(EMISSARIES, "emissaries", r -> {
                EmiType<?> type = FACTIONS.player().emissaries.all.get(r.i());
                boolean royalty = r.b();
                if (!royalty) {
                    Region reg = Args.region(r);
                    int value = r.i();
                    if (reg == null || !(type instanceof EmiTypeReg) || reg.faction() instanceof Player) {
                        // no emissary works in a human's region (owner, 2026-10-27; SOS-73)
                        return false;
                    }
                    ((EmiTypeReg) type).set(reg, value);
                    return true;
                }
                FactionNPC f = Args.npc(r);
                int succession = r.i();
                int value = r.i();
                if (f == null || !(type instanceof EmiTypeRoy) || succession < 0
                        || succession >= f.court().all().size()) {
                    return false;
                }
                ((EmiTypeRoy) type).set(f.court().all().get(succession), value);
                return true;
            }),
            new Command(TRIBUTE_ACCEPT, "tributeAccept", r -> {
                int n = r.i();
                FactionNPC[] vassals = new FactionNPC[n];
                int[] credits = new int[n];
                boolean[] taken = new boolean[n];
                for (int i = 0; i < n; i++) {
                    vassals[i] = Args.npc(r);
                    credits[i] = r.i();
                    taken[i] = r.b();
                }
                boolean any = false; // each human's own vassals: UpVassal sends each human its message (SOS-46 M3)
                for (int i = 0; i < n; i++) {
                    FactionNPC f = vassals[i];
                    if (f == null || DIP.overlord(f) != FACTIONS.player()) {
                        continue;
                    }
                    if (taken[i]) {
                        FACTIONS.player().credits().inc(credits[i], CTYPE.TRIBUTE);
                    }
                    ROPINION.OTHER().acceptTribute(f, taken[i]);
                    any = true;
                }
                return any;
            }));

    private Diplomacy() {
    }

    // ---------------------------------------------------------------- capture (hooks)

    /** Call site in the faction screen's deal ({@code UIDiplomacy}): the player's own offer, accepted. */
    public static boolean dealAccept(Deal deal) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(deal(new CmdWriter(DEAL_ACCEPT), deal));
        return true;
    }

    /** Call site in a faction's proposal message ({@code UIDipMessDeal}): the proposal accepted, as last checked. */
    public static boolean dealProposal(Deal deal, double happiness) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(deal(new CmdWriter(DEAL_PROPOSAL), deal).d(happiness));
        return true;
    }

    /** Hook in {@code NPCRequest.expire}: a proposal or request message declined. */
    public static boolean requestExpire(FactionNPC f) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.faction(new CmdWriter(REQUEST_EXPIRE), f));
        return true;
    }

    /** Call site in a request message ({@code UIDipMessAction}): a vassal's freedom request granted. */
    public static boolean vassalRelease(Object message, FactionNPC f, double happiness) {
        if (!Commands.capturing()) {
            return false;
        }
        if (!message.getClass().getName().equals(VASSAL_REQUEST)) {
            throw new IllegalStateException("SOSDET: no command for the request message " + message.getClass().getName());
        }
        Commands.capture(Args.faction(new CmdWriter(VASSAL_RELEASE), f).d(happiness));
        return true;
    }

    /**
     * Hook in the overlord message's Accept ({@code UpVassal.MOverlord}): the vassals (faction index and iteration), the
     * tribute each offers and whether it is taken. In vanilla the click pays at once, outside the step (SOS-46).
     */
    public static boolean tributeAccept(int[] factions, int[] iterations, int[] credits, boolean[] taken) {
        if (!Commands.capturing()) {
            return false;
        }
        CmdWriter w = new CmdWriter(TRIBUTE_ACCEPT).i(factions.length);
        for (int i = 0; i < factions.length; i++) {
            w.i(factions[i]).i(iterations[i]).i(credits[i]).b(taken[i]);
        }
        Commands.capture(w);
        return true;
    }

    /** Hook in {@code EmiType.set}: emissaries placed on a region or a royal (the emissary sliders). */
    public static boolean emissaries(EmiType<?> type, Object target, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        int ti = 0;
        while (FACTIONS.player().emissaries.all.get(ti) != type) {
            ti++;
        }
        CmdWriter w = new CmdWriter(EMISSARIES).i(ti).b(target instanceof Royalty);
        if (target instanceof Royalty) {
            Royalty roy = (Royalty) target;
            Args.faction(w, roy.court.faction).i(roy.successionI());
        } else {
            Args.region(w, (Region) target);
        }
        Commands.capture(w.i(value));
        return true;
    }

    // ---------------------------------------------------------------- the deal's terms

    /** Every term of a committed deal, in {@code DealSave}'s order. */
    private static CmdWriter deal(CmdWriter w, Deal deal) {
        Args.faction(w, deal.npc.npc());
        w.i(deal.bools.all().size());
        for (int i = 0; i < deal.bools.all().size(); i++) {
            w.b(deal.bools.all().get(i).is());
        }
        party(w, deal.player);
        return party(w, deal.npc);
    }

    private static CmdWriter party(CmdWriter w, DealParty p) {
        w.i(p.credits.get());
        int n = 0;
        for (DealReg reg : p.regs.all()) {
            if (reg.is()) {
                n++;
            }
        }
        w.i(n);
        for (DealReg reg : p.regs.all()) {
            if (reg.is()) {
                Args.region(w, reg.reg());
            }
        }
        w.i(TR.ALL().size());
        for (TRADABLE t : TR.ALL()) {
            w.i(p.resources.get(t));
        }
        return w;
    }

    /** The deal {@link #deal(CmdWriter, Deal)} wrote, rebuilt in a fresh deal; null when a term no longer holds. */
    private static Deal deal(CmdReader r) {
        FactionNPC npc = Args.npc(r);
        boolean[] bools = new boolean[r.i()];
        for (int i = 0; i < bools.length; i++) {
            bools[i] = r.b();
        }
        Terms player = new Terms(r);
        Terms other = new Terms(r);
        if (npc == null || FACTIONS.player().capitolRegion() == null) {
            return null; // gone, or (SOS-46) the human has no realm left to deal from
        }
        Deal d = new Deal();
        d.setFactionAndClear(npc);
        if (bools.length != d.bools.all().size()) {
            return null;
        }
        for (int i = 0; i < bools.length; i++) {
            d.bools.all().get(i).set(bools[i]);
        }
        return player.set(d.player) && other.set(d.npc) ? d : null;
    }

    /** One side's terms, read whole before any is checked. */
    private static final class Terms {
        final int credits;
        final Region[] regs;
        final int[] res;

        Terms(CmdReader r) {
            credits = r.i();
            regs = new Region[r.i()];
            for (int i = 0; i < regs.length; i++) {
                regs[i] = Args.region(r);
            }
            res = new int[r.i()];
            for (int i = 0; i < res.length; i++) {
                res[i] = r.i();
            }
        }

        /** {@code DealSave.Party.set}: false when the side can no longer give what it offered. */
        boolean set(DealParty p) {
            p.credits.set(0);
            for (TRADABLE t : TR.ALL()) {
                p.resources.set(t, 0);
            }
            p.regs.clear();
            if (credits > p.credits.max() || res.length != TR.ALL().size()) {
                return false;
            }
            p.credits.set(credits);
            for (Region reg : regs) {
                if (reg == null || !reg.active() || reg.faction() != p.f()) {
                    return false;
                }
                p.regs.add(reg);
            }
            for (int i = 0; i < res.length; i++) {
                TRADABLE t = TR.ALL().get(i);
                if (res[i] > 0 && res[i] > p.resources.max(t)) {
                    return false;
                }
                p.resources.set(t, res[i]);
            }
            return true;
        }
    }
}

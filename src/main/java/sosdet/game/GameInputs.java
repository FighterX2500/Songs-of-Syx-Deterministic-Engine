package sosdet.game;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import game.GAME;
import game.battle.div.Div;
import game.event.actions.EventAction;
import game.event.engine.EContext;
import game.event.engine.Event;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.npc.FactionNPC;
import game.faction.royalty.NPCCourt;
import game.faction.royalty.Royalty;
import game.faction.royalty.opinion.ROPINION;
import settlement.battle.invasion.Invador;
import settlement.stats.Induvidual;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import sosdet.game.cmd.Args;
import sosdet.Hooks;
import sosdet.report.CityReport;
import sosdet.report.EventMarks;
import sosdet.report.Input;
import sosdet.report.InputKind;
import sosdet.report.SentSupplies;
import world.army.AD;
import world.army.ADDiv;
import world.army.ADDivs;
import world.army.ADSupply;
import world.WORLD;
import world.army.WDivStoredAll;
import world.entity.army.WArmy;
import world.map.regions.Region;
import world.region.RD;

/**
 * The city's inputs to the world (ADR 0005 §2): made in the city part from the game's own save data, and applied in
 * the world part when the report carrying them applies, through the same game calls the city part would have made.
 */
public final class GameInputs implements Consumer<CityReport> {

    /** Big enough for a division's people (B4). */
    private final GameCodec codec = new GameCodec(1 << 20);
    private final SentSupplies sent;
    private final EventMarks marks;
    /** {@code WDivStoredAll.add(Induvidual, Div)}: patch 96-cmd-world adds it, so the game jar compiled against lacks it. */
    private final Method addSoldier;
    /** {@code Invador.tell(int, int, int, int)}: patch 14-report-reads adds it. */
    private final Method tell;

    public GameInputs(SentSupplies sent, EventMarks marks) {
        this.sent = sent;
        this.marks = marks;
        try {
            addSoldier = WDivStoredAll.class.getMethod("add", Induvidual.class, Div.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: no WDivStoredAll.add(Induvidual, Div): patch 96-cmd-world is not"
                    + " applied to this game (SOSDET supports Songs of Syx V71, build 71.44)", e);
        }
        try {
            tell = Invador.class.getMethod("tell", int.class, int.class, int.class, int.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: no Invador.tell(int, int, int, int): patch 14-report-reads is not"
                    + " applied to this game (SOSDET supports Songs of Syx V71, build 71.44)", e);
        }
    }

    /** B1: the soldier, as their save data, for their division in the world. */
    public Input soldierJoins(Induvidual i, int div) {
        i.save(codec.hashBegin());
        return new Input(InputKind.SOLDIER_JOINS, div, codec.written());
    }

    /** B2: {@code delta} of a supply for an army, which the city reads as sent until the world applies it. */
    public Input supply(int supply, WArmy a, int delta) {
        sent.sent(supply, a.armyIndex(), a.iteration(), delta, AD.supplies().all.size(),
                world.WORLD.ENTITIES().armies.max());
        ByteBuffer b = ByteBuffer.allocate(12).putInt(a.armyIndex()).putInt(a.iteration()).putInt(delta);
        return new Input(InputKind.SUPPLY, supply, b.array());
    }

    /** B4: an invasion's outcome ({@code Hooks.TELL_*}) for the world's invasion listeners. */
    public Input invasionTell(int kind, int lossed, int kills, int ref) {
        ByteBuffer b = ByteBuffer.allocate(12).putInt(lossed).putInt(kills).putInt(ref);
        return new Input(InputKind.INVASION_TELL, kind, b.array());
    }

    /** B4: a battle the city fought, for the army stats. */
    public Input battleStats(Faction f, boolean victory, int losses, int kills) {
        ByteBuffer b = ByteBuffer.allocate(9).put((byte) (victory ? 1 : 0)).putInt(losses).putInt(kills);
        return new Input(InputKind.BATTLE_STATS, f.index(), b.array());
    }

    /** B4: the invader takes the player as vassal. */
    public Input vassal(Faction f) {
        return new Input(InputKind.VASSAL, f.index(), new byte[0]);
    }

    /**
     * B4: a world division's survivors of a battle in the city. The division is named by its army, the army's
     * iteration, its place in the army and its own index, so one moved or gone by the time the report applies is not
     * mistaken for another.
     */
    public Input divResolve(Object division, Induvidual[] survivors) {
        ADDiv div = (ADDiv) division;
        WArmy a = div.army();
        if (a == null) {
            throw new IllegalArgumentException("SOSDET: a division with no army fought in the city: " + div.index);
        }
        int place = -1;
        ADDivs divs = a.divs();
        for (int k = 0; k < divs.size(); k++) {
            if (divs.get(k) == div) {
                place = k;
            }
        }
        FilePutter p = codec.hashBegin();
        p.i(a.armyIndex());
        p.i(a.iteration());
        p.i(place);
        p.i(div.index);
        p.i(survivors.length);
        for (Induvidual i : survivors) {
            i.save(p);
        }
        return new Input(InputKind.DIV_RESOLVE, 0, codec.written());
    }

    /** B6: a scripted event sets a world mark ({@code Hooks.EVENT_*}), which the city reads as sent until it applies. */
    public Input eventMark(int target, int kind, int index, int value) {
        ByteBuffer b = ByteBuffer.allocate(8).putInt(index).putInt(value);
        return new Input(InputKind.EVENT_MARK, target * EventMarks.KINDS + kind, b.array());
    }

    /**
     * B6: a scripted event's world action, for the world part to run. The action is named by its event and its place
     * among the event's actions; the context carries what the world actions read of it: the counts of what the event
     * selected (the world marks name the targets themselves), its random number, amount and action numbers.
     */
    public Input eventAction(Object action, Object event, Object context) {
        EventAction a = (EventAction) action;
        Event e = (Event) event;
        EContext c = (EContext) context;
        int place = -1;
        for (int k = 0; k < e.actions().size(); k++) {
            if (e.actions().get(k) == a) {
                place = k;
            }
        }
        if (place < 0) {
            throw new IllegalStateException("SOSDET: action " + a.key + " is not one of event " + e.key + "'s");
        }
        FilePutter p = codec.hashBegin();
        p.i(e.allIndex);
        p.chars(e.key);
        p.i(place);
        p.d(c.random);
        p.d(c.actionAmount);
        p.i(c.regs.am);
        p.i(c.faction.am);
        p.i(c.royalty.am);
        int[] ints = c.actionContext == null ? new int[0] : c.actionContext;
        p.i(ints.length);
        for (int v : ints) {
            p.i(v);
        }
        return new Input(InputKind.EVENT_ACTION, 0, codec.written());
    }

    @Override
    public void accept(CityReport r) {
        for (Input in : r.inputs) {
            switch (in.kind) {
            case SOLDIER_JOINS:
                Induvidual i;
                try {
                    i = new Induvidual(codec.reader(in.data()));
                } catch (IOException e) {
                    throw new IllegalStateException("SOSDET: cannot read a soldier joining division " + in.arg, e);
                }
                Div div = GAME.ARMIES().player().divisions().get(in.arg);
                try {
                    addSoldier.invoke(AD.cityDivs(), i, div);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("SOSDET: a soldier could not join division " + in.arg, e);
                }
                break;
            case SUPPLY:
                ByteBuffer b = ByteBuffer.wrap(in.data());
                int index = b.getInt();
                int iteration = b.getInt();
                int delta = b.getInt();
                sent.applied(in.arg, index, iteration, delta);
                WArmy a = Args.army(index, iteration);
                if (a != null) { // an army disbanded since: the goods are lost with it
                    ADSupply s = AD.supplies().all.get(in.arg);
                    s.current().inc(a, delta);
                }
                break;
            case INVASION_TELL:
                ByteBuffer t = ByteBuffer.wrap(in.data());
                int lossed = t.getInt();
                int kills = t.getInt();
                int ref = t.getInt();
                try {
                    tell.invoke(null, in.arg, lossed, kills, ref);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("SOSDET: invasion " + ref + "'s listeners failed", e);
                }
                break;
            case BATTLE_STATS:
                ByteBuffer st = ByteBuffer.wrap(in.data());
                boolean victory = st.get() != 0;
                AD.stats().report(FACTIONS.getByIndex(in.arg), victory, st.getInt(), st.getInt());
                break;
            case VASSAL:
                FactionNPC f = (FactionNPC) FACTIONS.getByIndex(in.arg);
                if (f.isActive()) {
                    ROPINION.STANCE().setNewStance(f, DIP.VASSAL(), false);
                }
                break;
            case DIV_RESOLVE:
                divResolve(in.data());
                break;
            case EVENT_MARK:
                ByteBuffer m = ByteBuffer.wrap(in.data());
                applyEventMark(in.arg / EventMarks.KINDS, in.arg % EventMarks.KINDS, m.getInt(), m.getInt());
                break;
            case EVENT_ACTION:
                applyEventAction(in.data());
                break;
            default:
                throw new IllegalStateException("SOSDET: no applier for input kind " + in.kind);
            }
        }
    }

    private void applyEventMark(int target, int kind, int index, int value) {
        marks.applied(target, kind, index);
        boolean flag = kind == Hooks.EVENT_FLAG;
        if (target == Hooks.EVENT_REGION) {
            Region reg = WORLD.REGIONS().getByIndex(index);
            if (flag) {
                RD.event().ii.set(reg, value);
            } else {
                RD.event().mark.set(reg, value);
            }
        } else if (target == Hooks.EVENT_FACTION) {
            Faction f = FACTIONS.all().get(index);
            if (f == null) {
                return; // gone since
            }
            if (flag) {
                f.eventSet(value == 1);
            } else {
                f.eventMark = (short) value;
            }
        } else {
            Royalty roy = royal(index);
            if (roy == null) {
                return; // their court changed since: the mark is lost
            }
            if (flag) {
                roy.eventSet(value == 1);
            } else {
                roy.eventMark = (short) value;
            }
        }
    }

    /** A royal by the index the event selection gives them ({@code EContext.SelRoy.get}). */
    private static Royalty royal(int index) {
        Faction ff = FACTIONS.all().get(index / NPCCourt.MAX);
        if (!(ff instanceof FactionNPC) || !ff.isActive()) {
            return null;
        }
        FactionNPC f = (FactionNPC) ff;
        int ri = index % NPCCourt.MAX;
        return ri < f.court().all().size() ? f.court().all().get(ri) : null;
    }

    private void applyEventAction(byte[] data) {
        FileGetter g = codec.reader(data);
        try {
            int index = g.i();
            String key = g.chars();
            int place = g.i();
            EContext c = new EContext();
            c.random = g.d();
            c.actionAmount = g.d();
            c.regs.am = g.i();
            c.faction.am = g.i();
            c.royalty.am = g.i();
            int[] ints = new int[g.i()];
            for (int k = 0; k < ints.length; k++) {
                ints[k] = g.i();
            }
            c.actionContext = ints;
            Event e = GAME.EVENT().read(index, key);
            if (e == null || place >= e.actions().size()) {
                throw new IllegalStateException("SOSDET: no event " + key + " (" + index + ") with action " + place);
            }
            e.actions().get(place).exe(e, c);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: cannot read a scripted event's world action", e);
        }
    }

    private void divResolve(byte[] data) {
        FileGetter g = codec.reader(data);
        try {
            WArmy a = Args.army(g.i(), g.i());
            int place = g.i();
            int index = g.i();
            Induvidual[] survivors = new Induvidual[g.i()];
            for (int k = 0; k < survivors.length; k++) {
                survivors[k] = new Induvidual(g);
            }
            if (a == null || place < 0 || place >= a.divs().size() || a.divs().get(place).index != index) {
                return; // the army or the division is gone, or the division moved: the survivors are lost with it
            }
            a.divs().get(place).resolve(survivors);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: cannot read a division's survivors", e);
        }
    }
}

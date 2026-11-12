package sosdet.game;

import java.lang.reflect.Field;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.DipStance;
import game.faction.player.Player;
import init.trade.TR;
import settlement.battle.invasion.Invador;
import settlement.trade.PBuyer;
import settlement.trade.PSeller;
import sosdet.txn.Txn;
import sosdet.txn.TxnKind;
import world.WORLD;
import world.entity.army.WArmy;

/**
 * A transaction for this PC's own city while it views another human's (SOS-97). The city is frozen and the
 * transaction waits for the thaw; by then the PC has loaded its own whole game back, and a game load builds every game
 * object anew (the treasury, the trade buyers and sellers, the factions, the armies). So a held transaction keeps a
 * copy, with stable keys in place of those objects, and finds the loaded game's objects again when it applies. Game
 * data that a load keeps (trade types, credit types, tradables, races, causes) and the world part's own copies (people,
 * an invasion's spec, a garrison division, which reads its division through the game each time) stay as they are.
 */
public final class HeldTxn {

    private static final String VERSION = " (V71)";

    private final Txn t = new Txn();
    /** The key of {@code a}: a tradable's index, an army's index or a faction's index; -1 for none. */
    private int keyA = -1;
    /** The key of {@code c}, a faction's index; -1 for none. */
    private int keyC = -1;
    /** For a war's end: the factions' indices and the stances' keys. */
    private int[] factions;
    private String[] stances;

    private HeldTxn() {
    }

    /** A copy of {@code x}, which the queue clears and reuses after the step. */
    public static HeldTxn of(Txn x) {
        HeldTxn h = new HeldTxn();
        Txn t = h.t;
        t.kind = x.kind;
        t.human = x.human;
        t.i = x.i;
        t.j = x.j;
        t.d = x.d;
        t.a = x.a;
        t.b = x.b;
        t.c = x.c;
        switch (x.kind) {
        case BUYER_RESERVE:
        case BUYER_DELIVER:
            h.keyA = ((PBuyer) x.a).tradable.index();
            h.keyC = faction(x.c);
            t.a = null;
            t.c = null;
            break;
        case SELLER_REMOVE:
            h.keyA = ((PSeller) x.a).type.index();
            h.keyC = faction(x.c);
            t.a = null;
            t.c = null;
            break;
        case CREDITS:
        case CREDITS_TRADE:
            t.a = null; // the human's treasury
            break;
        case ARMY_SUPPLY_DIV:
        case ARMY_SUPPLY_RECRUIT:
            h.keyA = ((WArmy) x.a).armyIndex();
            t.a = null;
            break;
        case WAR_ENDED:
            Object[] w = (Object[]) x.b;
            h.factions = new int[] {faction(w[0]), faction(w[1])};
            h.stances = new String[] {((DipStance) w[2]).key(), ((DipStance) w[3]).key()};
            t.a = null;
            t.b = null;
            break;
        case REL_HUMANOID:
            t.a = null; // a person of the city as it was: no key finds them in the loaded game
            break;
        default:
            break;
        }
        return h;
    }

    /** The human whose city it is for. */
    public int human() {
        return t.human;
    }

    /**
     * Whether a save can hold a transaction of {@code kind} (SOS-193): the kinds that can reach a frozen human's city,
     * whose capital nobody attacks and whose armies stand still: trade, money, a war's end, invasions called off and
     * immigrants. The others carry the world's own objects (people, divisions, an invasion), which no key finds again.
     */
    public static boolean storable(TxnKind kind) {
        switch (kind) {
        case BUYER_RESERVE:
        case BUYER_DELIVER:
        case SELLER_REMOVE:
        case CREDITS:
        case CREDITS_TRADE:
        case WAR_ENDED:
        case INVASION_CANCEL:
        case IMMIGRANTS_HIGHER:
            return true;
        default:
            return false;
        }
    }

    /** Writes a {@link #storable} transaction, by the keys of its game objects. */
    void save(snake2d.util.file.FilePutter file) {
        if (!storable(t.kind)) {
            throw new IllegalStateException("SOSDET: a held transaction of kind " + t.kind + " can't be saved");
        }
        file.i(t.kind.ordinal());
        file.i(t.human);
        file.i(t.i);
        file.i(t.j);
        file.l(Double.doubleToRawLongBits(t.d));
        file.i(keyA);
        file.i(keyC);
        switch (t.kind) {
        case BUYER_RESERVE:
        case BUYER_DELIVER:
        case SELLER_REMOVE:
            file.i(((init.trade.TRADE_TYPE) t.b).index);
            break;
        case CREDITS:
            file.i(((game.faction.FCredits.CTYPE) t.b).ordinal());
            break;
        case CREDITS_TRADE:
            file.i(((game.faction.FCredits.CTYPE) t.b).ordinal());
            file.i(((init.trade.TRADABLE) t.c).index());
            break;
        case WAR_ENDED:
            file.i(factions[0]);
            file.i(factions[1]);
            file.i(stanceIndex(stances[0]));
            file.i(stanceIndex(stances[1]));
            break;
        case IMMIGRANTS_HIGHER:
            file.i(((init.race.Race) t.a).index());
            break;
        default:
            break;
        }
    }

    /** Reads a transaction {@link #save} wrote, with this game's data objects; its others {@link #resolve} finds. */
    static HeldTxn load(snake2d.util.file.FileGetter file) throws java.io.IOException {
        int kind = file.i();
        if (kind < 0 || kind >= TxnKind.values().length || !storable(TxnKind.values()[kind])) {
            throw new java.io.IOException("SOSDET: a held transaction of kind " + kind);
        }
        HeldTxn h = new HeldTxn();
        Txn t = h.t;
        t.kind = TxnKind.values()[kind];
        t.human = file.i();
        t.i = file.i();
        t.j = file.i();
        t.d = Double.longBitsToDouble(file.l());
        h.keyA = file.i();
        h.keyC = file.i();
        switch (t.kind) {
        case BUYER_RESERVE:
        case BUYER_DELIVER:
        case SELLER_REMOVE:
            t.b = init.trade.TRADE_TYPE.all.get(file.i());
            break;
        case CREDITS:
            t.b = game.faction.FCredits.CTYPE.values()[file.i()];
            break;
        case CREDITS_TRADE:
            t.b = game.faction.FCredits.CTYPE.values()[file.i()];
            t.c = TR.ALL().get(file.i());
            break;
        case WAR_ENDED:
            h.factions = new int[] {file.i(), file.i()};
            h.stances = new String[] {stances()[file.i()].key(), stances()[file.i()].key()};
            break;
        case IMMIGRANTS_HIGHER:
            t.a = init.race.RACES.all().get(file.i());
            break;
        default:
            break;
        }
        return h;
    }

    /** The stances a war's end names, in the order a save numbers them (SOS-193). */
    private static DipStance[] stances() {
        return new DipStance[] {DIP.WAR(), DIP.NEUTRAL(), DIP.TRADE(), DIP.PACT(), DIP.ALLY(), DIP.VASSAL(), DIP.OVERLORD()};
    }

    private static int stanceIndex(String key) {
        DipStance[] all = stances();
        for (int i = 0; i < all.length; i++) {
            if (all[i].key().equals(key)) {
                return i;
            }
        }
        throw new IllegalStateException("SOSDET: no diplomatic stance " + key + " in this game build" + VERSION);
    }

    /** The transaction, with the loaded game's objects; null when there is nothing left to apply it to. */
    public Txn resolve() {
        switch (t.kind) {
        case BUYER_RESERVE:
        case BUYER_DELIVER:
            t.a = player().buyer(TR.ALL().get(keyA));
            t.c = keyC < 0 ? null : FACTIONS.getByIndex(keyC);
            return t;
        case SELLER_REMOVE:
            t.a = player().seller(TR.ALL().get(keyA));
            t.c = keyC < 0 ? null : FACTIONS.getByIndex(keyC);
            return t;
        case CREDITS:
        case CREDITS_TRADE:
            t.a = player().credits();
            return t;
        case ARMY_SUPPLY_DIV:
        case ARMY_SUPPLY_RECRUIT:
            t.a = WORLD.ENTITIES().armies.get(keyA);
            return t.a == null ? null : t; // the army is gone since
        case WAR_ENDED:
            t.a = invasionListener();
            t.b = new Object[] {FACTIONS.getByIndex(factions[0]), FACTIONS.getByIndex(factions[1]), stance(stances[0]),
                    stance(stances[1])};
            return t;
        case REL_HUMANOID:
            return null;
        default:
            return t;
        }
    }

    private Player player() {
        return (Player) FACTIONS.getByIndex(t.human);
    }

    private static int faction(Object f) {
        return f == null ? -1 : ((Faction) f).index();
    }

    private static DipStance stance(String key) {
        DipStance[] all = {DIP.WAR(), DIP.NEUTRAL(), DIP.TRADE(), DIP.PACT(), DIP.ALLY(), DIP.VASSAL(), DIP.OVERLORD()};
        for (DipStance s : all) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        throw new IllegalStateException("SOSDET: no diplomatic stance " + key + " in this game build" + VERSION);
    }

    /** The loaded game's {@code Invador} listener, which calls off a faction's invasions when its war ends. */
    private static DIP.DipActivityListener invasionListener() {
        try {
            Field f = DIP.DipActivityListener.class.getDeclaredField("all");
            f.setAccessible(true);
            for (Object o : (snake2d.util.sets.LinkedList<?>) f.get(null)) {
                if (o.getClass().getEnclosingClass() == Invador.class) {
                    return (DIP.DipActivityListener) o;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no field DIP.DipActivityListener.all in this game build" + VERSION, e);
        }
        throw new IllegalStateException("SOSDET: no Invador listener among the game's diplomacy listeners" + VERSION);
    }
}

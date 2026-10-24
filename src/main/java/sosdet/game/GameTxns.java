package sosdet.game;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import game.GAME;
import game.battle.div.Div;
import game.faction.FCredits.CTYPE;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.DipStance;
import game.faction.player.PCredits;
import init.trade.TRADABLE;
import game.faction.FResources.RTYPE;
import init.constant.Config;
import init.race.Race;
import init.resources.RESOURCES;
import init.resources.ResSupply;
import init.trade.TRADE_TYPE;
import init.type.CAUSE_ARRIVES;
import init.type.CAUSE_LEAVE;
import init.type.HTYPES;
import settlement.battle.invasion.InvasionSpec;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.Induvidual;
import settlement.stats.STATS;
import settlement.stats.equip.Equip;
import settlement.trade.PBuyer;
import settlement.trade.PSeller;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.datatypes.Coo;
import snake2d.util.misc.CLAMP;
import sosdet.report.Input;
import sosdet.txn.Txn;
import world.army.AD;
import world.army.WDIV;
import world.entity.army.WArmy;

/**
 * Applies the world part's transactions to the city, in the step's city tail (ADR 0005 §3). Each one makes the game
 * call the world part made, now outside it, so the game's own code changes the city as in vanilla.
 */
public final class GameTxns implements Consumer<Txn> {

    private final GameInputs inputs;
    private final List<Input> cityInputs;
    private final BooleanSupplier follower;

    /**
     * {@code cityInputs}: the next report's, for a soldier the city cannot take in. {@code follower}: whether this PC's
     * world reads another PC's city, so the kinds that are that city's own are not applied here
     * ({@link sosdet.txn.TxnKind#followerApplies}).
     */
    public GameTxns(GameInputs inputs, List<Input> cityInputs, BooleanSupplier follower) {
        this.inputs = inputs;
        this.cityInputs = cityInputs;
        this.follower = follower;
    }

    @Override
    public void accept(Txn t) {
        if (!t.kind.followerApplies() && follower.getAsBoolean()) {
            return;
        }
        switch (t.kind) {
        case BUYER_RESERVE:
            ((PBuyer) t.a).addReserve(t.i, (TRADE_TYPE) t.b, t.j, (Faction) t.c);
            break;
        case BUYER_DELIVER:
            ((PBuyer) t.a).addDeliver(t.i, (TRADE_TYPE) t.b);
            break;
        case SELLER_REMOVE:
            ((PSeller) t.a).remove(t.i, (TRADE_TYPE) t.b, t.j, (Faction) t.c);
            break;
        case CREDITS:
            ((PCredits) t.a).inc(t.d, (CTYPE) t.b);
            break;
        case CREDITS_TRADE:
            ((PCredits) t.a).inc(t.d, (CTYPE) t.b, (TRADABLE) t.c, t.i);
            break;
        case SOLDIER_RETURNS:
            soldierReturns((Induvidual) t.a, t.i, t.j);
            break;
        case RETIREE:
            retiree((Induvidual) t.a);
            break;
        case REL_SOLDIER:
            STATS.REL().setSoldier((Induvidual) t.a, t.i);
            break;
        case REL_DEATH:
            STATS.REL().setDeath((Induvidual) t.a, (CAUSE_LEAVE) t.b);
            break;
        case REL_HUMANOID:
            STATS.REL().setHumanoid((Humanoid) t.a);
            break;
        case ARMY_SUPPLY_DIV:
            Div div = GAME.ARMIES().player().divisions().get(t.i);
            armySupplies((WArmy) t.a, div.info.race(), div.menNrOf(), false);
            break;
        case ARMY_SUPPLY_RECRUIT:
            armySupplies((WArmy) t.a, (Race) t.b, t.i, true);
            break;
        case GARRISON_RESOLVE:
            ((WDIV) t.a).resolve(t.i, t.d);
            break;
        case GARRISON_RESOLVE_PEOPLE:
            ((WDIV) t.a).resolve((Induvidual[]) t.b);
            break;
        case INVADE:
            SETT.INVADOR().invade((InvasionSpec) t.a, null); // the world part told the listeners; this starts it
            break;
        case INVASION_CANCEL:
            SETT.INVADOR().cancel(t.i);
            break;
        case WAR_ENDED:
            Object[] w = (Object[]) t.b;
            ((DIP.DipActivityListener) t.a).change((Faction) w[0], (Faction) w[1], (DipStance) w[2], (DipStance) w[3]);
            break;
        default:
            throw new IllegalStateException("SOSDET: no applier for transaction kind " + t.kind);
        }
    }

    /** {@link sosdet.Hooks#entryLater}. */
    public static COORDINATE entryLater(int seed) {
        return new Coo(seed, 0);
    }

    /**
     * As {@code WDivStoredAll.update} and {@code WDivStored.popSoldier} do in vanilla, from the copy the world part
     * made. With no entry point to take, the soldier goes back to their division in the next report.
     */
    private void soldierReturns(Induvidual t, int div, int entrySeed) {
        COORDINATE c = SETT.ENTRY().points.randomReachable(entrySeed);
        if (c == null) {
            for (int i = 0; i < STATS.EQUIP().allE().size(); i++) {
                STATS.EQUIP().allE().get(i).set(t, 0); // their kit stayed in the army's supplies
            }
            cityInputs.add(inputs.soldierJoins(t, div));
            return;
        }
        Humanoid h = SETT.HUMANOIDS().create(t.race(), c.x(), c.y(), HTYPES.SUBJECT(), CAUSE_ARRIVES.SOLDIER_RETURN());
        if (!h.isRemoved()) {
            STATS.Arrive(h);
            h.indu().copyFrom(t);
            for (int i = 0; i < STATS.EQUIP().allE().size(); i++) {
                Equip e = STATS.EQUIP().allE().get(i);
                e.set(h.indu(), 0);
            }
        }
        Div dd = GAME.ARMIES().player().divisions().get(div);
        if (dd.menNrOf() < Config.battle().MEN_PER_DIVISION) {
            h.setDivision(dd);
        }
    }

    /**
     * As {@code WDivStoredAll.attach} and the recruit command do in vanilla: each supply the men need, as much as the
     * stockpiles can give. The supply counter sends what it gets to the world in the next report.
     */
    private static void armySupplies(WArmy a, Race race, int men, boolean healthOnly) {
        if (!a.added()) {
            return; // the army is gone since the world part asked
        }
        for (ResSupply s : RESOURCES.SUP().ALL) {
            if (healthOnly && s.health <= 0) {
                continue;
            }
            int am = s.amount(race, men);
            if (am <= 0) {
                continue;
            }
            am = CLAMP.i(am, 0, SETT.ROOMS().STOCKPILE.tally().amountReservable.get(s.resource));
            if (am > 0) {
                s.resource.remove(am, RTYPE.ARMY_SUPPLY);
                AD.supplies().get(s).current().inc(a, am);
            }
        }
    }

    /** As {@code WDivStored.age} does in vanilla for a soldier who retires. */
    private static void retiree(Induvidual i) {
        COORDINATE c = SETT.ENTRY().points.randomReachable();
        if (c == null) {
            return;
        }
        Humanoid h = SETT.HUMANOIDS().create(i.race(), c.x(), c.y(), HTYPES.RETIREE(), CAUSE_ARRIVES.SOLDIER_RETURN());
        if (h != null) {
            h.indu().copyFrom(i);
            STATS.REL().setHumanoid(h);
        }
    }
}

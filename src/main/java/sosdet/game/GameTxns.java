package sosdet.game;

import java.util.function.Consumer;

import game.faction.FCredits.CTYPE;
import game.faction.Faction;
import game.faction.player.PCredits;
import init.trade.TRADABLE;
import init.trade.TRADE_TYPE;
import settlement.trade.PBuyer;
import settlement.trade.PSeller;
import sosdet.txn.Txn;

/**
 * Applies the world part's transactions to the city, in the step's city tail (ADR 0005 §3). Each one makes the game
 * call the world part made, now outside it, so the game's own code changes the city as in vanilla.
 */
public final class GameTxns implements Consumer<Txn> {

    @Override
    public void accept(Txn t) {
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
        default:
            throw new IllegalStateException("SOSDET: no applier for transaction kind " + t.kind);
        }
    }
}

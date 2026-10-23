package sosdet.txn;

/** The kinds of transaction, by the scan's codes (docs/plan/notes/city-world-link-scan.md). */
public enum TxnKind {
    /** C1, C3: goods bought for the city, paid now and delivered later ({@code PBuyer.addReserve}). */
    BUYER_RESERVE,
    /** C1: bought goods reach the city ({@code PBuyer.addDeliver}). */
    BUYER_DELIVER,
    /** C2, C3: goods sold from the city, paid now and taken out later ({@code PSeller.remove}). */
    SELLER_REMOVE,
    /** C3: credits in or out ({@code PCredits.inc}). */
    CREDITS,
    /** C3: credits for traded goods, also in the trade history ({@code PCredits.inc} with a tradable). */
    CREDITS_TRADE,
    /** C4: a soldier out of their world division enters the city ({@code WDivStored.popSoldier}). */
    SOLDIER_RETURNS,
    /** C4: a soldier out of their world division retires into the city ({@code WDivStored.age}). */
    RETIREE,
    /** C5: the city's relation table, written by world code ({@code StatsRelations}). */
    REL_SOLDIER,
    REL_DEATH,
    REL_HUMANOID,
    /**
     * C1 two-phase: supplies for an army from the city's stockpiles, as much as the city has; what it took goes back to
     * the world as a supply input (B2). For a city division sent out ({@code WDivStoredAll.attach}).
     */
    ARMY_SUPPLY_DIV,
    /** As {@link #ARMY_SUPPLY_DIV}, for a division recruited in the realm (the recruit command). */
    ARMY_SUPPLY_RECRUIT
}

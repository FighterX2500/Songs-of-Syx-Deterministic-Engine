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
    ARMY_SUPPLY_RECRUIT,
    /** C5: a world battle's survivors and experience for a capitol garrison division ({@code WDivCity.resolve}). */
    GARRISON_RESOLVE,
    /** C5: as {@link #GARRISON_RESOLVE}, with the survivors' people ({@code WDivCity.resolve(Induvidual[])}). */
    GARRISON_RESOLVE_PEOPLE,
    /** C6: an invasion of the city, with the ref the world gave it ({@code Invador.invade}). */
    INVADE,
    /** C6: an invasion called off ({@code Invador.cancel}). */
    INVASION_CANCEL,
    /** C6: a war with the player ended; the city calls off that faction's invasions ({@code Invador}'s listener). */
    WAR_ENDED,
    /** A2 (SOS-46): a haven's race wants more immigrants in the city ({@code Immigration.setHigher}, from the world's havens). */
    IMMIGRANTS_HIGHER;

    /**
     * Whether a follower's city applies it (SOS-38: its world reads another PC's city). Not the ones that carry the
     * source city's own people and divisions: soldiers home from its armies, their relations, supplies its divisions
     * take, its garrison's battles. Trade, credits and invasions reach a follower's city too: there is one player
     * faction in the world (ADR 0005).
     */
    public boolean followerApplies() {
        switch (this) {
        case SOLDIER_RETURNS:
        case RETIREE:
        case REL_SOLDIER:
        case REL_DEATH:
        case REL_HUMANOID:
        case ARMY_SUPPLY_DIV:
        case ARMY_SUPPLY_RECRUIT:
        case GARRISON_RESOLVE:
        case GARRISON_RESOLVE_PEOPLE:
            return false;
        default:
            return true;
        }
    }
}

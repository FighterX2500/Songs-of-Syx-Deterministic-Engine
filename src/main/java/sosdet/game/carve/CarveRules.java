package sosdet.game.carve;

/**
 * What becomes of the land a city-state is carved in (SOSGSGMP docs/plan/hot-join.md decision 3, owner 2026-11-11). The
 * rule looks at the region holding the square's centre tile; any other region the square cuts only loses tiles.
 *
 * <ul>
 * <li>Land nobody owns becomes a new NPC.</li>
 * <li>A region of an NPC realm with {@link #SPLIT_FROM} or more regions breaks away as an NPC of its own, unless it is
 * the realm's capital. A human's NPC vassal follows the same rule.</li>
 * <li>Anything else keeps its owner: a smaller realm, a realm's capital, a human's own region.</li>
 * </ul>
 * A new NPC is neutral to everyone and gets vanilla's start-neighbour protection ({@code FactionNPC.sanctified}). With
 * no free faction slot there is no new NPC, and the players are told why. Plain Java, no game classes.
 */
public final class CarveRules {

    /** The least realm, in regions, that a region breaks away from. */
    public static final int SPLIT_FROM = 4;

    /** Who holds the region the square is carved in. */
    public enum Holder {
        /** No faction. */
        NOBODY,
        /** An NPC, a human's vassal included. */
        NPC,
        /** A human, in a region other than their capital. */
        HUMAN
    }

    /** What the rule does with the region. */
    public enum Outcome {
        /** The region stays with whoever held it. */
        KEEP,
        /** Unowned land: a new NPC holds it. */
        NEW_NPC,
        /** The region leaves its NPC realm and is an NPC of its own. */
        BREAK_AWAY
    }

    private CarveRules() {
    }

    /**
     * The outcome for a region held by {@code holder}, whose realm has {@code realmRegions} regions, {@code capital} if it
     * is the realm's capital, when a faction slot is {@code slotFree}. {@link #wanted} tells whether a missing slot
     * changed it.
     */
    public static Outcome outcome(Holder holder, int realmRegions, boolean capital, boolean slotFree) {
        Outcome w = wanted(holder, realmRegions, capital);
        return w == Outcome.KEEP || slotFree ? w : Outcome.KEEP;
    }

    /** The outcome with a free faction slot. */
    public static Outcome wanted(Holder holder, int realmRegions, boolean capital) {
        switch (holder) {
        case NOBODY:
            return Outcome.NEW_NPC;
        case NPC:
            return realmRegions >= SPLIT_FROM && !capital ? Outcome.BREAK_AWAY : Outcome.KEEP;
        case HUMAN:
            return Outcome.KEEP;
        default:
            throw new IllegalArgumentException("SOSDET: no holder " + holder);
        }
    }
}

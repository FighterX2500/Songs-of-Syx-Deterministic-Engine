package sosdet.report;

/** The kinds of input a city report carries, by the scan's codes (docs/plan/notes/city-world-link-scan.md). */
public enum InputKind {
    /** B1: a soldier leaves the city for its division in the world; the number is the division, the bytes the person. */
    SOLDIER_JOINS,
    /** B2: goods from the city into the army supply; the number is the supply, the bytes the army and the amount. */
    SUPPLY,
    /**
     * B4: an invasion's outcome for the world's listeners; the number is the kind ({@code Hooks.TELL_*}), the bytes
     * the losses, the kills and the invasion's ref.
     */
    INVASION_TELL,
    /** B4: a battle for the army stats; the number is the faction, the bytes victory, losses and kills. */
    BATTLE_STATS,
    /** B4: the invader of a lost invasion takes the player as vassal; the number is the faction. */
    VASSAL,
    /** B4: the survivors of a world division that fought in the city; the bytes name it and hold the people. */
    DIV_RESOLVE
}

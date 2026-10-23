package sosdet.report;

/** The kinds of input a city report carries, by the scan's codes (docs/plan/notes/city-world-link-scan.md). */
public enum InputKind {
    /** B1: a soldier leaves the city for its division in the world; the number is the division, the bytes the person. */
    SOLDIER_JOINS,
    /** B2: goods from the city into the army supply; the number is the supply, the bytes the army and the amount. */
    SUPPLY
}

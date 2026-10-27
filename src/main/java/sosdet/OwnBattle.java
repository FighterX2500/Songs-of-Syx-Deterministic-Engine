package sosdet;

/**
 * A battle on the battle map in own-city mode (plan 0006, SOS-61): from the Engage step until every PC is back in its
 * own city. The battle PC, the asked human's, generates the battle; every PC loads that battle at the Engage step
 * ({@link Engine#battleOut}, {@link Engine#battleIn}) and runs it alike. What the mod reads; the engine's own state.
 */
public final class OwnBattle {

    /** The Engage step: every PC holds here until the battle PC's battle is loaded. */
    public final long step;
    /** The human whose army fights: the one the world-battle prompt asked. */
    public final int human;
    /** That human's PC, which generated the battle and sends it. */
    public final int pc;
    /** In a battle between two humans' armies, the other human, whose PC commands army 1; -1 against NPCs (SOS-83). */
    public final int other;
    /** Between two humans: which side's commanders pressed Start (bit 0 army 0's, bit 1 army 1's), inside the step. */
    int deployReady;
    /** Between two humans: the other human's city report at Engage, the same on every PC; army 1 fights with its values. */
    sosdet.report.CityReport otherReport;
    /** Between two humans: army 1's commanders retreated it, inside the step; army 0 wins (SOS-83). */
    boolean army1Retreat;
    /** The world stream's draw count at Engage, the same on every PC; put back after the generation and the battle. */
    final long worldDraws;
    /** The battle PC's battle save is still to be loaded: no step runs past {@link #step} until it is. */
    volatile boolean awaiting = true;
    /** The battle PC's engine has handed out its battle save. */
    boolean sent;

    OwnBattle(long step, int human, int pc, int other, long worldDraws) {
        this.step = step;
        this.human = human;
        this.pc = pc;
        this.other = other;
        this.worldDraws = worldDraws;
    }

    /** True until every PC has loaded the battle PC's battle. */
    public boolean awaiting() {
        return awaiting;
    }

    @Override
    public String toString() {
        return "battle of human " + human + " (PC " + pc + ")" + (other >= 0 ? " against human " + other : "") + " from step "
                + step + (awaiting ? ", awaiting its save" : "");
    }
}

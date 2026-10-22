package sosdet.game.cmd;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** The room plan stash (ADR 0003) in a session without a running game, as the engine's unit tests run sessions. */
class PlanStashTest {

    @Test
    void aStashWithoutAGameLiftsNothing() {
        PlanStash s = PlanStash.off();
        s.lift();
        assertFalse(s.lifted());
        s.restore();
        s.discard();
        assertFalse(s.lifted());
    }
}

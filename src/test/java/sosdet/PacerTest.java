package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PacerTest {

    private static final long FRAME = Pacer.SECOND / 60;

    /** The slowest peer's step, as its hashes report it, and its real step. */
    private long peerStep;
    private long peerAt;
    /** How many steps late the peer's hashes arrive (the link). */
    private long delay;
    private long now = Pacer.SECOND;
    private long step;

    private Pacer pacer(double wanted) {
        Pacer p = new Pacer(pc -> peerStep);
        p.peers(new int[] {2}, 60);
        p.govern(true);
        p.wanted(wanted, now);
        return p;
    }

    /**
     * Runs the host for that many seconds: it asks for {@code hostRate} steps a second, its peer manages
     * {@code peerRate} (its hash every 30 steps). Returns the last speed change, or 0.
     */
    private double run(Pacer p, double seconds, double hostRate, double peerRate) {
        double change = 0;
        double hostOwed = 0;
        double peerOwed = 0;
        for (long f = 0; f < seconds * 60; f++) {
            now += FRAME;
            long cap = p.frame(now, step, StepClock.NO_LIMIT);
            hostOwed += hostRate / 60;
            while (hostOwed >= 1 && step < cap) {
                step++;
                hostOwed--;
            }
            hostOwed = Math.min(hostOwed, 1);
            peerOwed += peerRate / 60;
            while (peerOwed >= 1 && peerAt < step) {
                peerAt++;
                peerOwed--;
            }
            peerOwed = Math.min(peerOwed, 1);
            peerStep = Math.max(0, peerAt - delay) / 30 * 30;
            double s = p.speed(now);
            if (s > 0) {
                change = s;
            }
        }
        return change;
    }

    @Test
    void theHostRunsAtMostTheLeadPastTheSlowestPeer() {
        Pacer p = pacer(5);
        peerStep = 300;
        assertEquals(360, p.frame(now, 300, StepClock.NO_LIMIT));
        assertEquals(400, p.frame(now + FRAME, 400, StepClock.NO_LIMIT), "never behind its own step");
        Pacer off = new Pacer(pc -> peerStep);
        assertEquals(StepClock.NO_LIMIT, off.frame(now, 300, StepClock.NO_LIMIT), "no peers: no cap");
    }

    @Test
    void aPeerThatCantKeepUpLowersTheSpeedToWhatItHolds() {
        Pacer p = pacer(5); // 80 steps a second at 1/16 s
        double s = run(p, 5, 80, 50);
        // the fastest speed whose steps the peer runs with a tenth to spare: 45 steps a second, 2.8x at 1/16 s
        assertEquals(2.8, s, 0.11);
        assertEquals(2, p.slowPc());
        assertTrue(p.heldFrames() > 0);
    }

    @Test
    void aPeerThatKeepsPaceDoesNotLowerTheSpeed() {
        Pacer p = pacer(2); // 32 steps a second
        delay = 60; // its hashes come two seconds late, so the host is held
        assertEquals(0, run(p, 20, 32, 32));
        assertTrue(p.heldFrames() > 0);
    }

    @Test
    void theStepBandsPickTheCheaperSpeed() {
        Pacer p = pacer(2);
        // 2x runs 32 steps a second, 1.9x 57 (1/30 s): a peer that runs 40 can hold 2x, not 1.9x
        assertEquals(2.0, p.fastestFor(36));
        assertEquals(0.5, p.fastestFor(31), 1e-9, "below 2x the 1/30 s and 1/60 s bands cost more steps");
        assertEquals(Pacer.MIN_SPEED, p.fastestFor(5));
    }

    @Test
    void theSpeedGoesBackUpOnceThePeerKeepsUp() {
        Pacer p = pacer(5);
        double low = run(p, 5, 80, 50);
        assertTrue(low < 5);
        double hostRate = low / StepClock.stepSecondsAt(low);
        double up = run(p, Pacer.CALM_NANOS / (double) Pacer.SECOND + 2, hostRate, 200);
        assertEquals(Math.min(5, Math.round(low * Pacer.RAISE * 10) / 10.0), up, 1e-9, "a quarter faster");
    }

    @Test
    void aTryThatIsHeldAgainDoublesTheWait() {
        Pacer p = pacer(5);
        double low = run(p, 5, 80, 50);
        double rate = low / StepClock.stepSecondsAt(low);
        double up = run(p, 12, rate, 200);
        assertTrue(up > low);
        double down = 0;
        for (int s = 0; s < 60 && down == 0; s++) { // the 60-step lead takes a while to use up
            down = run(p, 1, up / StepClock.stepSecondsAt(up), 50);
        }
        assertEquals(low, down, 0.11, "held again: back down");
        // the next try waits 20 s, not 10
        assertEquals(0, run(p, 15, rate, 200));
        assertTrue(run(p, 8, rate, 200) > low);
    }

    @Test
    void theHostPlayersSpeedIsTheAgreedOneAgain() {
        Pacer p = pacer(5);
        run(p, 5, 80, 50);
        p.wanted(1, now);
        assertEquals(-1, p.slowPc());
        assertEquals(0, run(p, 5, 60, 200), "1x is held: no change");
    }
}

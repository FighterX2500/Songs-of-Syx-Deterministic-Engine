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
    /** The peer's capacity as its hashes report it (SOS-94), -1 unknown. */
    private long peerCapacity = -1;
    /** The time one of the host's steps takes (SOS-94). */
    private long hostStepNanos = 100_000;
    /** The share of the host's frames that wait for another PC's report or log (SOS-90, SOS-94). */
    private double waitShare;
    private long now = Pacer.SECOND;
    private long step;

    private Pacer pacer(double wanted) {
        Pacer p = new Pacer(pc -> peerStep, pc -> peerCapacity);
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
        long ranLastFrame = 0;
        for (long f = 0; f < seconds * 60; f++) {
            now += FRAME;
            boolean waited = (f % 60) < waitShare * 60;
            long cap = p.frame(now, step, StepClock.NO_LIMIT, waited, ranLastFrame * hostStepNanos);
            long before = step;
            hostOwed += hostRate / 60;
            while (hostOwed >= 1 && step < cap && !waited) {
                step++;
                hostOwed--;
            }
            ranLastFrame = step - before;
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

    /** SOS-90: a frame whose steps waited for another PC's report or the host's log is held, on a PC pacing nobody too. */
    @Test
    void aFrameThatWaitedForAnotherPcCountsAsHeld() {
        Pacer p = new Pacer(pc -> -1, pc -> -1);
        long now = 1_000_000_000L;
        assertEquals(StepClock.NO_LIMIT, p.frame(now, 0, StepClock.NO_LIMIT, false, 0));
        p.frame(now + FRAME, 0, StepClock.NO_LIMIT, true, 0);
        p.frame(now + 2 * FRAME, 0, StepClock.NO_LIMIT, true, 0);
        p.frame(now + 3 * FRAME, 5, StepClock.NO_LIMIT, false, 0);
        assertEquals(2, p.heldFrames());
        assertEquals(2.0 * FRAME / Pacer.SECOND, p.heldSeconds(), 1e-9);
        assertEquals(2.0 * FRAME / Pacer.SECOND, p.longestHoldSeconds(), 1e-9);
    }

    @Test
    void theHostRunsAtMostTheLeadPastTheSlowestPeer() {
        Pacer p = pacer(5);
        peerStep = 300;
        assertEquals(360, p.frame(now, 300, StepClock.NO_LIMIT, false, 0));
        assertEquals(400, p.frame(now + FRAME, 400, StepClock.NO_LIMIT, false, 0), "never behind its own step");
        Pacer off = new Pacer(pc -> peerStep, pc -> -1);
        assertEquals(StepClock.NO_LIMIT, off.frame(now, 300, StepClock.NO_LIMIT, false, 0), "no peers: no cap");
    }

    /** SOS-94: a step of 1 ms and 6 ms a frame besides, at no fewer than 30 frames a second for drawing. */
    @Test
    void aCapacityLeavesTimeToDraw() {
        assertEquals(820, Pacer.capacitySample(Pacer.SECOND, 100, 400, 400_000_000L), 1e-6);
        assertEquals(1000, Pacer.capacitySample(Pacer.SECOND, 1, 1000, Pacer.SECOND), 1e-6, "no frame time besides");
    }

    @Test
    void aPeerThatCantKeepUpLowersTheSpeedToWhatItHolds() {
        Pacer p = pacer(5); // 80 steps a second at 1/16 s
        peerCapacity = 50;
        double s = run(p, 5, 80, 50);
        // the fastest speed whose steps the peer runs with a tenth to spare: 45 steps a second, 2.8x at 1/16 s
        assertEquals(2.8, s, 0.11);
        assertEquals(2, p.slowPc());
    }

    /** SOS-94: the playtest of 2026-10-29: both PCs wait for the link now and then, and each could run the speed. */
    @Test
    void waitsForTheLinkAloneDontLowerTheSpeed() {
        Pacer p = pacer(25); // 400 steps a second
        peerCapacity = 800;
        waitShare = 0.25; // a quarter of every second
        assertEquals(0, run(p, 30, 400, 400));
        assertTrue(p.heldFrames() > 0);
    }

    @Test
    void aLinkTooSlowForTheSpeedLowersItOneStepAndNamesTheLink() {
        Pacer p = pacer(25);
        peerCapacity = 800;
        waitShare = 0.5;
        double s = run(p, 2 + Pacer.LINK_SECONDS + 1, 400, 400);
        assertEquals(20, s, 1e-9, "a quarter slower");
        assertEquals(Pacer.LINK, p.slowPc());
    }

    @Test
    void theHostsOwnCapacityCounts() {
        Pacer p = pacer(5); // 80 steps a second
        peerCapacity = 800;
        hostStepNanos = 20_000_000; // 20 ms a step, the frames all steps: 50 steps a second
        assertEquals(2.8, run(p, 6, 80, 80), 0.11, "lowered for the host itself");
        assertEquals(Pacer.HOST_PC, p.slowPc());
    }

    @Test
    void aPausedPcKeepsItsCapacity() {
        Pacer p = pacer(5);
        run(p, 3, 0, 0);
        assertEquals(-1, p.capacity(), "no steps: no measure");
        run(p, 5, 80, 80);
        int c = p.capacity();
        assertTrue(c > 80, "measured while running: " + c);
        run(p, 2, 0, 0); // the window that ran into the pause ends here
        c = p.capacity();
        run(p, 3, 0, 0);
        assertEquals(c, p.capacity(), "a pause leaves it as it was");
    }

    /** L94A: the first seconds after a load, a PC still loading holds the others: neither link nor capacity lowers. */
    @Test
    void aLoadsFirstSecondsDontLowerTheSpeed() {
        Pacer p = pacer(5);
        peerCapacity = -1; // the peer hasn't measured yet
        waitShare = 0.6;
        hostStepNanos = 20_000_000; // the host's first steps run slow, too
        assertEquals(0, run(p, 2.5, 80, 80), "the host's capacity isn't known before three samples");
        hostStepNanos = 100_000;
        assertEquals(0, run(p, 8, 80, 80), "no link step down while a peer's capacity is unknown");
    }

    @Test
    void theStepBandsPickTheCheaperSpeed() {
        Pacer p = pacer(2);
        // 2x runs 32 steps a second, 1.9x 57 (1/30 s): a peer that runs 40 can hold 2x, not 1.9x
        assertEquals(2.0, p.fastestFor(36));
        assertEquals(0.5, p.fastestFor(31), 1e-9, "below 2x the 1/30 s and 1/60 s bands cost more steps");
        assertEquals(Pacer.MIN_SPEED, p.fastestFor(5));
    }

    /** SOS-94: once the slow PC can run it again, the speed goes straight back to the host player's. */
    @Test
    void theSpeedGoesBackUpOnceThePeerKeepsUp() {
        Pacer p = pacer(5);
        peerCapacity = 50;
        double low = run(p, 5, 80, 50);
        assertTrue(low < 5);
        double hostRate = low / StepClock.stepSecondsAt(low);
        peerCapacity = 500;
        double up = run(p, Pacer.CALM_NANOS / (double) Pacer.SECOND + 2, hostRate, 200);
        assertEquals(5, up, 1e-9);
        assertEquals(-1, p.slowPc());
    }

    @Test
    void aRaiseTooMuchForThePcDoublesTheWait() {
        Pacer p = pacer(5);
        peerCapacity = 50;
        double low = run(p, 5, 80, 50);
        double rate = low / StepClock.stepSecondsAt(low);
        peerCapacity = 500;
        double up = run(p, 12, rate, 200);
        assertTrue(up > low);
        peerCapacity = 50;
        assertEquals(low, run(p, 3, up / StepClock.stepSecondsAt(up), 50), 0.11, "back down");
        // the next raise waits 20 s, not 10
        peerCapacity = 500;
        assertEquals(0, run(p, 15, rate, 200));
        assertTrue(run(p, 8, rate, 200) > low);
    }

    @Test
    void aLinkStepDownComesBackAfterTheCalmTime() {
        Pacer p = pacer(25);
        peerCapacity = 800;
        waitShare = 0.5;
        assertEquals(20, run(p, 2 + Pacer.LINK_SECONDS + 1, 400, 400), 1e-9);
        waitShare = 0.02; // a little jitter left
        assertEquals(25, run(p, Pacer.CALM_NANOS / (double) Pacer.SECOND + 2, 320, 320), 1e-9);
    }

    @Test
    void theHostPlayersSpeedIsTheAgreedOneAgain() {
        Pacer p = pacer(5);
        peerCapacity = 50;
        run(p, 5, 80, 50);
        p.wanted(1, now);
        assertEquals(-1, p.slowPc());
        peerCapacity = 500;
        assertEquals(0, run(p, 5, 60, 200), "1x is held: no change");
    }
}

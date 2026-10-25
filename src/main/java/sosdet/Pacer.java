package sosdet;

import java.util.function.IntToLongFunction;

/**
 * The host's pacing and speed governor (mod spec §4: the slowest PC caps the speed; SOS-42). Plain Java: the session
 * feeds it every frame, outside the step.
 *
 * <p>Pacing: the host runs at most {@code lead} steps past where the slowest peer is by now, its last hash's step plus
 * what its measured pace has run since (at most one lead more, so a peer that stopped holds the host within two leads
 * of its last hash). The cap moves on every frame, so the host slows evenly to the slowest peer instead of running to
 * the cap and standing still until the next hash.
 *
 * <p>Governor (only when every PC runs at the agreed speed): once a second it looks at how long the host was held, and
 * how far ahead of the slowest peer it is. Held for over a tenth of that second, with the slowest peer running under
 * {@link #KEEPS_PACE} of the agreed speed's steps a second (a peer that keeps pace was held up by the link, not its
 * speed) and {@link #SETTLE_NANOS} after the last change, the agreed speed becomes the fastest one, up to the host
 * player's, whose steps per second the slowest peer ran with a tenth to spare, if that is fewer steps a second than now.
 * Unheld for {@link #CALM_NANOS} below the host player's speed, its lead over the slowest peer
 * grown by less than half the pacing lead, it tries a quarter faster; a try held again within {@link #MAX_WAIT_NANOS}
 * doubles the wait before the next one, up to that.
 */
final class Pacer {

    static final long SECOND = 1_000_000_000L;
    static final long CALM_NANOS = 10 * SECOND;
    static final long MAX_WAIT_NANOS = 80 * SECOND;
    /** A frame gap longer than this is a pause or a load, not a hold. */
    static final long GAP_NANOS = SECOND / 2;
    static final double HELD_TOO_LONG = 0.1;
    static final double KEEPS_PACE = 0.95;
    /** After a change, the peer's measured pace still shows the old speed for a while. */
    static final long SETTLE_NANOS = 2 * SECOND;
    static final double MARGIN = 0.9;
    static final double RAISE = 1.25;
    static final double MIN_SPEED = 0.25;

    /** A PC's last step its hashes have reached, or -1 before the first. */
    private final IntToLongFunction reached;
    private int[] peers = new int[0];
    private long lead;
    private boolean govern;

    private long cap = StepClock.NO_LIMIT;
    private long lastFrame;
    private int slowestPc = -1;
    /** The slowest peer's measured pace (steps per second, -1 unknown), and its step and time when last measured. */
    private double peerRate = -1;
    private long peerRateStep = -1;
    private long peerRateNanos;

    private long heldFrames;
    private long heldNanos;
    private long heldSince = -1;
    private long longestHold;

    /** The host player's speed and the agreed one the governor chose (0 before the first). */
    private double wanted;
    private double agreed;
    /** The PC that held the speed down, or -1. */
    private int slowPc = -1;
    private long windowStart = -1;
    private long windowHeld;
    private long changedAt;
    /** The host's lead over the slowest peer now and at the last change: a lag that grows is a peer falling behind. */
    private long lag;
    private long lagAtChange;
    private long raisedAt = -1;
    private long wait = CALM_NANOS;

    Pacer(IntToLongFunction reached) {
        this.reached = reached;
    }

    /** Pace by these PCs, at most {@code lead} steps ahead of the slowest; lead 0 is off. */
    void peers(int[] pcs, long lead) {
        if (lead < 0) {
            throw new IllegalArgumentException("SOSDET: pace lead " + lead);
        }
        peers = pcs.clone();
        this.lead = lead;
        cap = StepClock.NO_LIMIT;
    }

    /** PC {@code pc} is gone, its human removed (SOS-66): the pace no longer waits for it. */
    void drop(int pc) {
        int n = 0;
        int[] kept = new int[peers.length];
        for (int p : peers) {
            if (p != pc) {
                kept[n++] = p;
            }
        }
        peers = java.util.Arrays.copyOf(kept, n);
        cap = StepClock.NO_LIMIT;
    }

    void govern(boolean on) {
        govern = on;
    }

    boolean on() {
        return lead > 0 && peers.length > 0;
    }

    /**
     * Every frame: the step the host may run to (NO_LIMIT: no cap). A frame that found the host at the cap, short of
     * {@code limit} (the mod's last step), counts as held.
     */
    long frame(long now, long step, long limit) {
        if (!on()) {
            return StepClock.NO_LIMIT;
        }
        long gap = lastFrame == 0 ? 0 : now - lastFrame;
        lastFrame = now;
        if (gap > GAP_NANOS) {
            heldSince = -1;
            windowStart = -1;
        } else if (cap != StepClock.NO_LIMIT && step >= cap && (limit == StepClock.NO_LIMIT || cap < limit)) {
            heldFrames++;
            heldNanos += gap;
            windowHeld += gap;
            if (heldSince < 0) {
                heldSince = now - gap;
            }
            longestHold = Math.max(longestHold, now - heldSince);
        } else {
            heldSince = -1;
        }
        long slowest = Long.MAX_VALUE;
        for (int pc : peers) {
            long r = reached.applyAsLong(pc);
            if (r < slowest) {
                slowest = r;
                slowestPc = pc;
            }
        }
        trackPeerRate(slowest, now);
        lag = slowest < 0 ? 0 : step - slowest;
        long c = slowest + lead;
        if (peerRate > 0) {
            c += Math.min(lead, (long) (peerRate * (now - peerRateNanos) / 1e9));
        }
        cap = Math.max(step, c);
        return cap;
    }

    /**
     * The slowest peer's steps per second, from the hashes it sends (one every hash interval): a moving average, so one
     * late hash moves it a little. Reset when that peer's step goes back.
     */
    private void trackPeerRate(long slowest, long now) {
        if (slowest < 0 || slowest < peerRateStep) {
            peerRate = -1;
            peerRateStep = slowest;
            peerRateNanos = now;
            return;
        }
        if (slowest == peerRateStep) {
            return;
        }
        if (peerRateStep >= 0) {
            double sample = (slowest - peerRateStep) / ((now - peerRateNanos) / 1e9);
            peerRate = peerRate > 0 ? peerRate * 0.75 + sample * 0.25 : sample;
        }
        peerRateStep = slowest;
        peerRateNanos = now;
    }

    /** The host player set this speed: it is the agreed one, until the governor finds a peer can't hold it. */
    void wanted(double speed, long now) {
        wanted = speed;
        agreed = speed;
        slowPc = -1;
        changedAt = now;
        lagAtChange = lag;
        raisedAt = -1;
        wait = CALM_NANOS;
        windowStart = -1;
    }

    /** After every frame: a new agreed speed, or 0 for none. */
    double speed(long now) {
        if (!govern || !on() || wanted <= 0) {
            return 0;
        }
        if (windowStart < 0) {
            windowStart = now;
            windowHeld = 0;
            return 0;
        }
        long w = now - windowStart;
        if (w < SECOND) {
            return 0;
        }
        double held = (double) windowHeld / w;
        windowStart = now;
        windowHeld = 0;
        if (held > HELD_TOO_LONG && peerRate > 0 && peerRate < KEEPS_PACE * agreed / StepClock.stepSecondsAt(agreed)
                && now - changedAt >= SETTLE_NANOS) {
            double s = fastestFor(peerRate * MARGIN);
            if (s / StepClock.stepSecondsAt(s) >= agreed / StepClock.stepSecondsAt(agreed)) {
                return 0; // held, so only a speed with fewer steps a second (a peer catching up measures fast)
            }
            if (raisedAt >= 0 && now - raisedAt < MAX_WAIT_NANOS) {
                // the last try didn't hold (a peer a little too slow takes a while to use up the lead)
                wait = Math.min(wait * 2, MAX_WAIT_NANOS);
            }
            raisedAt = -1;
            return change(s, slowestPc, now);
        }
        if (held == 0 && agreed < wanted && now - changedAt >= wait && lag - lagAtChange < lead / 2) {
            if (raisedAt >= 0) {
                wait = CALM_NANOS; // the last try held
            }
            raisedAt = now;
            double s = Math.min(wanted, Math.round(agreed * RAISE * 10) / 10.0);
            if (s <= agreed) {
                s = wanted;
            }
            return change(s, s >= wanted ? -1 : slowPc, now);
        }
        return 0;
    }

    private double change(double s, int pc, long now) {
        agreed = s;
        slowPc = pc;
        changedAt = now;
        lagAtChange = lag;
        return s;
    }

    /**
     * The fastest speed, up to the host player's and on a tenth grid, whose steps run at most this many per second; the
     * step length's bands make that not simply the slowest.
     */
    double fastestFor(double stepsPerSecond) {
        if (wanted / StepClock.stepSecondsAt(wanted) <= stepsPerSecond) {
            return wanted;
        }
        for (int t = (int) Math.ceil(wanted * 10) - 1; t >= Math.ceil(MIN_SPEED * 10); t--) {
            double s = t / 10.0;
            if (s / StepClock.stepSecondsAt(s) <= stepsPerSecond) {
                return s;
            }
        }
        return MIN_SPEED;
    }

    double wantedSpeed() {
        return wanted;
    }

    int slowPc() {
        return slowPc;
    }

    void resetCounts() {
        heldFrames = 0;
        heldNanos = 0;
        longestHold = 0;
    }

    long heldFrames() {
        return heldFrames;
    }

    double heldSeconds() {
        return heldNanos / 1e9;
    }

    double longestHoldSeconds() {
        return longestHold / 1e9;
    }
}

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
 * <p>Capacity (every PC, SOS-94): the steps a second this PC could run, measured over about a second of the frames
 * that didn't wait for another PC: the time a step takes, and the time a frame takes besides its steps (drawing), at
 * no fewer than {@link #FPS_FLOOR} frames a second. Each PC sends it with its hashes, so the host knows how fast
 * every PC could go, not only how fast it went: a PC that waited for the link ran slowly but could have run faster.
 *
 * <p>Governor (only when every PC runs at the agreed speed): once a second, {@link #SETTLE_NANOS} after the last
 * change,
 * <ul>
 * <li>the slowest capacity, the host's own included, under {@link #KEEPS_PACE} of the agreed speed's steps a second
 * lowers the agreed speed to the fastest one, up to the host player's, whose steps that capacity runs with a tenth to
 * spare;
 * <li>with every PC able to run the speed, waits for the link over {@link #LINK_HELD} of the time for
 * {@link #LINK_SECONDS} seconds in a row lower it one step (a quarter), the link named as the reason ({@link #LINK});
 * <li>calm ({@link #HELD_CALM}) for a second, {@link #CALM_NANOS} after the last change, below the host player's
 * speed, the lead over the slowest peer grown by less than half the pacing lead, it goes up to the fastest speed every
 * capacity allows, and at least a quarter faster. A capacity drop within {@link #MAX_WAIT_NANOS} of a raise doubles
 * the wait before the next one, up to that; a link step down waits the calm time only.
 * </ul>
 * A capacity counts after {@link #CAPACITY_WARMUP} samples, and the link is judged only once every PC's is known.
 */
final class Pacer {

    static final long SECOND = 1_000_000_000L;
    static final long CALM_NANOS = 10 * SECOND;
    static final long MAX_WAIT_NANOS = 80 * SECOND;
    /** A frame gap longer than this is a pause or a load, not a hold. */
    static final long GAP_NANOS = SECOND / 2;
    static final double KEEPS_PACE = 0.95;
    /** After a change, the peer's measured pace still shows the old speed for a while. */
    static final long SETTLE_NANOS = 2 * SECOND;
    static final double MARGIN = 0.9;
    static final double RAISE = 1.25;
    static final double MIN_SPEED = 0.25;
    /** A link too slow for the speed: held over this share of a second, for this many seconds in a row (SOS-94). */
    static final double LINK_HELD = 1.0 / 3;
    static final int LINK_SECONDS = 5;
    /** A second held at most this share of the time counts as calm, for a raise: a link always has some jitter. */
    static final double HELD_CALM = 0.05;
    /** The reason {@link #slowPc} gives for a speed the link lowered, not a PC. */
    static final int LINK = -2;
    /** The host's own PC number: its own capacity counts with its peers'. */
    static final int HOST_PC = 0;
    /** A capacity leaves this many frames a second for drawing (SOS-94). */
    static final double FPS_FLOOR = 30;
    /** A capacity sample needs this many steps, and this much time not waiting, in its window. */
    static final int CAPACITY_MIN_STEPS = 10;
    static final long CAPACITY_MIN_ACTIVE = SECOND / 5;
    /** A capacity counts after this many samples: the first frames after a load run slow (L94A, SOS-94). */
    static final int CAPACITY_WARMUP = 3;

    /** A PC's last step its hashes have reached, or -1 before the first. */
    private final IntToLongFunction reached;
    /** A peer's capacity from its hashes (steps a second), or -1 before the first (SOS-94). */
    private final IntToLongFunction capacityOf;
    private int[] peers = new int[0];
    /** By PC: a viewer the pace doesn't wait for while it views another city (SOS-82). */
    private final boolean[] away = new boolean[64];
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
    /** Seconds in a row the link held the host too long, with every PC able to run the speed. */
    private int linkSeconds;

    /** This PC's capacity (steps a second, -1 unknown), and the window it is measured over. */
    private double capacity = -1;
    private int capSamples;
    private long capStart = -1;
    private long capLastStep = -1;
    private long capActive;
    private long capFrames;
    private long capSteps;
    private long capStepNanos;

    Pacer(IntToLongFunction reached, IntToLongFunction capacityOf) {
        this.reached = reached;
        this.capacityOf = capacityOf;
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

    /** PC {@code pc} views another city (true) or is back (false): the pace waits for it only when it is back. */
    void away(int pc, boolean on) {
        away[pc] = on;
        cap = StepClock.NO_LIMIT;
    }

    void govern(boolean on) {
        govern = on;
    }

    boolean on() {
        if (lead <= 0) {
            return false;
        }
        for (int p : peers) {
            if (!away[p]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every frame, on every PC: the step the host may run to (NO_LIMIT: no cap). A frame that found the host at the cap,
     * short of {@code limit} (the mod's last step), counts as held, as does one whose steps {@code waited} for another
     * PC's report or the host's log (SOS-90). {@code stepNanos} is the time the frame's steps took, for the capacity.
     */
    long frame(long now, long step, long limit, boolean waited, long stepNanos) {
        long gap = lastFrame == 0 ? 0 : now - lastFrame;
        lastFrame = now;
        boolean atCap = on() && cap != StepClock.NO_LIMIT && step >= cap && (limit == StepClock.NO_LIMIT || cap < limit);
        measure(now, gap, step, atCap || waited, stepNanos);
        if (gap > GAP_NANOS) {
            heldSince = -1;
            windowStart = -1;
        } else if (atCap || waited) {
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
        if (!on()) {
            return StepClock.NO_LIMIT;
        }
        long slowest = Long.MAX_VALUE;
        for (int pc : peers) {
            if (away[pc]) {
                continue;
            }
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
     * This PC's capacity, from the frames that didn't wait: a window of about a second, a moving average over windows.
     * A long gap (a pause, a load) or a step going back (a resync) starts a new window; a window with too few steps
     * (paused, or always waiting) leaves the capacity as it was.
     */
    private void measure(long now, long gap, long step, boolean held, long stepNanos) {
        if (gap > GAP_NANOS || capStart < 0 || step < capLastStep) {
            capStart = now;
            capLastStep = step;
            capActive = capFrames = capSteps = capStepNanos = 0;
            return;
        }
        if (!held) {
            capActive += gap;
            capFrames++;
            capSteps += step - capLastStep;
            capStepNanos += stepNanos;
        }
        capLastStep = step;
        if (now - capStart < SECOND) {
            return;
        }
        if (capSteps >= CAPACITY_MIN_STEPS && capActive >= CAPACITY_MIN_ACTIVE) {
            double sample = capacitySample(capActive, capFrames, capSteps, capStepNanos);
            capacity = capacity < 0 ? sample : capacity * 0.5 + sample * 0.5;
            capSamples++;
        }
        capStart = now;
        capActive = capFrames = capSteps = capStepNanos = 0;
    }

    /**
     * The steps a second a PC could run: each step takes {@code stepNanos / steps}, and each frame besides its steps
     * the rest of the {@code active} time over {@code frames}, at the frame rate it ran or {@link #FPS_FLOOR},
     * whichever is lower.
     */
    static double capacitySample(long active, long frames, long steps, long stepNanos) {
        double perStep = Math.max(1, stepNanos) / 1e9 / steps;
        double perFrame = Math.max(0, active - stepNanos) / 1e9 / Math.max(1, frames);
        double fps = Math.min(FPS_FLOOR, frames / (active / 1e9));
        return Math.max(0, 1 - fps * perFrame) / perStep;
    }

    /** This PC's capacity in steps a second, or -1 before the first measure. */
    int capacity() {
        return capacity < 0 || capSamples < CAPACITY_WARMUP ? -1 : (int) Math.min(Integer.MAX_VALUE, Math.round(capacity));
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
        linkSeconds = 0;
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
        if (now - changedAt < SETTLE_NANOS) {
            linkSeconds = 0;
            return 0;
        }
        double rate = agreed / StepClock.stepSecondsAt(agreed);
        int slowCapPc = slowestCapacityPc();
        double slowCap = slowCapPc == -1 ? -1 : capacityOfPc(slowCapPc);
        if (slowCap >= 0 && slowCap < KEEPS_PACE * rate) {
            linkSeconds = 0;
            double s = fastestFor(slowCap * MARGIN);
            if (s >= agreed) {
                return 0;
            }
            if (raisedAt >= 0 && now - raisedAt < MAX_WAIT_NANOS) {
                wait = Math.min(wait * 2, MAX_WAIT_NANOS); // the last raise was too much for that PC
            }
            raisedAt = -1;
            return change(s, slowCapPc, now);
        }
        // the link is judged once every PC's capacity is known: before that a PC still loading holds the others too
        linkSeconds = held > LINK_HELD && everyCapacityKnown() ? linkSeconds + 1 : 0;
        if (linkSeconds >= LINK_SECONDS) {
            linkSeconds = 0;
            double s = Math.max(MIN_SPEED, Math.round(agreed / RAISE * 10) / 10.0);
            if (s >= agreed) {
                return 0;
            }
            raisedAt = -1;
            wait = CALM_NANOS; // the link, not a PC: come back after the calm time, never later
            return change(s, LINK, now);
        }
        if (held <= HELD_CALM && agreed < wanted && now - changedAt >= wait && lag - lagAtChange < lead / 2) {
            // at least a quarter faster: a PC that draws slowly measures little more than the speed it runs (L94A)
            double s = Math.min(wanted, Math.round(agreed * RAISE * 10) / 10.0);
            if (slowCap >= 0) {
                s = Math.max(s, fastestFor(slowCap * MARGIN));
            }
            if (s <= agreed) {
                return 0;
            }
            if (raisedAt >= 0) {
                wait = CALM_NANOS; // the last raise held
            }
            raisedAt = now;
            return change(s, s >= wanted ? -1 : slowCap >= 0 ? slowCapPc : slowPc, now);
        }
        return 0;
    }

    /** The PC with the lowest known capacity, the host's own among them, or -1 while none is known. */
    private int slowestCapacityPc() {
        double low = capacity();
        int pc = low >= 0 ? HOST_PC : -1;
        for (int p : peers) {
            long c = away[p] ? -1 : capacityOf.applyAsLong(p);
            if (c >= 0 && (pc == -1 || c < low)) {
                pc = p;
                low = c;
            }
        }
        return pc;
    }

    private boolean everyCapacityKnown() {
        if (capacity() < 0) {
            return false;
        }
        for (int p : peers) {
            if (!away[p] && capacityOf.applyAsLong(p) < 0) {
                return false;
            }
        }
        return true;
    }

    private double capacityOfPc(int pc) {
        return pc == HOST_PC ? capacity() : capacityOf.applyAsLong(pc);
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

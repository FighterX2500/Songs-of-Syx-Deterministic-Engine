package sosdet.rig;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * A slow link between the rig's copies (SOS-94): what a copy reads from another copy's files reaches its engine only
 * after a delay, plus a random jitter, in the order it was written, as over a real network. Timing only: the bytes are
 * the same, so the simulation is too. Off (every byte at once) until {@link #set}.
 */
final class Link {

    private long delayNanos;
    private long jitterNanos;
    private final Random random = new Random(94);
    /** By stream: the pieces on their way, each with the time it arrives. */
    private final Map<String, ArrayDeque<Object[]>> inFlight = new HashMap<>();

    void set(long delayMillis, long jitterMillis) {
        if (delayMillis < 0 || jitterMillis < 0) {
            throw new IllegalArgumentException("SOSDET rig: link delay " + delayMillis + " ms, jitter " + jitterMillis + " ms");
        }
        delayNanos = delayMillis * 1_000_000L;
        jitterNanos = jitterMillis * 1_000_000L;
    }

    boolean on() {
        return delayNanos > 0 || jitterNanos > 0;
    }

    /** Puts {@code fresh} on its way in {@code stream}, and returns what of that stream has arrived by {@code now}. */
    byte[] pass(String stream, byte[] fresh, long now) {
        if (!on()) {
            return fresh;
        }
        ArrayDeque<Object[]> q = inFlight.computeIfAbsent(stream, k -> new ArrayDeque<>());
        if (fresh.length > 0) {
            long at = now + delayNanos + (jitterNanos == 0 ? 0 : (long) (random.nextDouble() * jitterNanos));
            Object[] last = q.peekLast();
            if (last != null) {
                at = Math.max(at, (Long) last[0]); // a stream keeps its order, as TCP does
            }
            q.addLast(new Object[] {at, fresh});
        }
        int n = 0;
        for (Object[] p : q) {
            if ((Long) p[0] > now) {
                break;
            }
            n += ((byte[]) p[1]).length;
        }
        byte[] out = new byte[n];
        int at = 0;
        while (!q.isEmpty() && (Long) q.peekFirst()[0] <= now) {
            byte[] b = (byte[]) q.pollFirst()[1];
            System.arraycopy(b, 0, out, at, b.length);
            at += b.length;
        }
        return out;
    }
}

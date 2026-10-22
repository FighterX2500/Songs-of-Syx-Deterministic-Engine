package sosdet;

/**
 * How long each step took, for the timing rule (requirements §4). Preallocated; recording never allocates.
 */
final class StepTimes {

    private static final long BUCKET_NANOS = 100_000; // 0.1 ms
    private static final int BUCKETS = 10_000; // up to 1 s; longer steps go in the last bucket
    private static final long OVER_100_MS = 100_000_000;
    private static final long OVER_1_S = 1_000_000_000;

    private final int[] histogram = new int[BUCKETS + 1];
    private long steps;
    private long totalNanos;
    private long maxNanos;
    private long over100Ms;
    private long over1S;

    void record(long nanos) {
        int bucket = (int) Math.min(BUCKETS, nanos / BUCKET_NANOS);
        histogram[bucket]++;
        steps++;
        totalNanos += nanos;
        maxNanos = Math.max(maxNanos, nanos);
        if (nanos > OVER_100_MS) {
            over100Ms++;
        }
        if (nanos > OVER_1_S) {
            over1S++;
        }
    }

    void reset() {
        java.util.Arrays.fill(histogram, 0);
        steps = 0;
        totalNanos = 0;
        maxNanos = 0;
        over100Ms = 0;
        over1S = 0;
    }

    long steps() {
        return steps;
    }

    double meanMs() {
        return steps == 0 ? 0 : totalNanos / 1e6 / steps;
    }

    double maxMs() {
        return maxNanos / 1e6;
    }

    long over100Ms() {
        return over100Ms;
    }

    long over1S() {
        return over1S;
    }

    /** The step time in ms at or below which this fraction of steps fall (upper edge of its 0.1 ms bucket). */
    double percentileMs(double fraction) {
        if (fraction <= 0 || fraction > 1) {
            throw new IllegalArgumentException("SOSDET: percentile fraction " + fraction);
        }
        if (steps == 0) {
            return 0;
        }
        long needed = (long) Math.ceil(fraction * steps);
        long seen = 0;
        for (int b = 0; b <= BUCKETS; b++) {
            seen += histogram[b];
            if (seen >= needed) {
                return b == BUCKETS ? maxMs() : (b + 1) * BUCKET_NANOS / 1e6;
            }
        }
        return maxMs();
    }
}

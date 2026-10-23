package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PathWorkersTest {

    /** Records which share ran each job; job {@code failing} throws. */
    private static final class Jobs implements PathQueue {
        final int[] share;
        final int[] runs;
        final int failing;
        int queued;

        Jobs(int n, int failing) {
            share = new int[n];
            runs = new int[n];
            this.failing = failing;
            queued = n;
        }

        @Override
        public int queued() {
            return queued;
        }

        @Override
        public void run(int s, int shares) {
            for (int i = s; i < queued; i += shares) {
                share[i] = s;
                runs[i]++;
                if (i == failing) {
                    throw new IllegalArgumentException("job " + i);
                }
            }
        }

        @Override
        public void clear() {
            queued = 0;
        }
    }

    @Test
    void everyJobRunsOnceOnItsFixedShareRoundAfterRound() {
        PathWorkers w = new PathWorkers();
        try {
            for (int round = 0; round < 50; round++) {
                Jobs jobs = new Jobs(1 + round % 11, -1);
                int n = jobs.queued;
                assertEquals(n, w.run(jobs));
                assertEquals(0, jobs.queued);
                for (int i = 0; i < n; i++) {
                    assertEquals(1, jobs.runs[i]);
                    assertEquals(i % PathWorkers.SHARES, jobs.share[i]);
                }
            }
        } finally {
            w.shutdown();
        }
    }

    @Test
    void aJobFailingOnAPathThreadFailsTheStepAndTheNextRoundStillRuns() {
        PathWorkers w = new PathWorkers();
        try {
            Jobs bad = new Jobs(7, 4); // share 1, a path thread
            assertThrows(IllegalStateException.class, () -> w.run(bad));
            assertEquals(0, bad.queued);
            Jobs good = new Jobs(7, -1);
            assertEquals(7, w.run(good));
        } finally {
            w.shutdown();
        }
    }
}

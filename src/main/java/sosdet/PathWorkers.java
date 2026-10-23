package sosdet;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs the path jobs a step queued, at the step's end: share 0 on the simulation thread, the others on path threads
 * (owner's approval, 2026-10-23, SOS-37). Job i always goes to share i % {@link #SHARES}, whatever the PC's core
 * count, so every PC computes the same answers. The simulation thread waits for every share before the hash; the
 * jobs only read the settlement, and each writes only its own path.
 */
final class PathWorkers {

    static final int SHARES = 3;

    private final Object lock = new Object();
    private final AtomicInteger left = new AtomicInteger();
    /** Guarded by {@link #lock}: the queue of the running round, the round number, and the end of the session. */
    private PathQueue queue;
    private long round;
    private boolean stopped;
    private Thread[] threads;
    private volatile Thread waiter;
    private volatile Throwable failure;

    /** Runs every queued job and empties the queue. Returns how many jobs ran. */
    int run(PathQueue q) {
        int n = q.queued();
        if (n == 0) {
            return 0;
        }
        try {
            if (n == 1) {
                q.run(0, SHARES);
                return 1;
            }
            start();
            waiter = Thread.currentThread();
            left.set(SHARES - 1);
            synchronized (lock) {
                queue = q;
                round++;
                lock.notifyAll();
            }
            try {
                q.run(0, SHARES);
            } finally {
                while (left.get() > 0) {
                    LockSupport.park(this);
                }
            }
            Throwable f = failure;
            if (f != null) {
                failure = null;
                throw new IllegalStateException("SOSDET: a path job failed on a path thread", f);
            }
            return n;
        } finally {
            q.clear();
        }
    }

    /** Ends the path threads; called when the session ends. */
    void shutdown() {
        synchronized (lock) {
            stopped = true;
            lock.notifyAll();
        }
    }

    private void start() {
        if (threads != null) {
            return;
        }
        threads = new Thread[SHARES - 1];
        for (int i = 0; i < threads.length; i++) {
            int share = i + 1;
            Thread t = new Thread(() -> work(share), "SOSDET paths #" + share);
            t.setDaemon(true);
            threads[i] = t;
            t.start();
        }
    }

    private void work(int share) {
        long seen = 0;
        while (true) {
            PathQueue q;
            synchronized (lock) {
                while (round == seen && !stopped) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        // nothing interrupts these threads; keep serving rounds, and fail the next one loudly
                        failure = new IllegalStateException("SOSDET: path thread #" + share + " was interrupted", e);
                    }
                }
                if (stopped) {
                    return;
                }
                seen = round;
                q = queue;
            }
            try {
                q.run(share, SHARES);
            } catch (Throwable t) {
                // this thread's top level: the simulation thread rethrows it once every share is done
                failure = t;
            } finally {
                if (left.decrementAndGet() == 0) {
                    LockSupport.unpark(waiter);
                }
            }
        }
    }
}

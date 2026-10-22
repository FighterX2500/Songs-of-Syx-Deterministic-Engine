package sosdet.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class AiLogTest {

    private static final long START = 0x1234ABCDL;

    /** Records what every PC applies, as (step, seq, kind, div, first payload int). */
    private static final class Recorder implements AiApplier {
        final List<String> applied = new ArrayList<>();
        long step;

        @Override
        public void apply(AiEntry e) {
            applied.add(step + ":" + e.seq + ":" + e.kind + ":" + e.div + ":" + e.intAt(0));
        }
    }

    private static void runStep(AiLog log, Recorder r, long step) {
        r.step = step;
        log.stepBegin(step);
    }

    /** The host's general captures from its own thread. */
    private static void captureOnGeneralThread(AiLog host, int kind, int div, int value) throws InterruptedException {
        Thread general = new Thread(() -> {
            host.generalThreadIs(Thread.currentThread());
            assertTrue(host.capturing());
            host.capture(kind, div, AiEntry.ints(value));
        });
        general.start();
        general.join();
    }

    @Test
    void theHostStampsEachChangeThirtyStepsAheadAndAppliesItThen() throws InterruptedException {
        Recorder r = new Recorder();
        AiLog host = AiLog.host(START, r);
        assertFalse(host.capturing(), "only the general's thread captures");
        captureOnGeneralThread(host, AiEntry.GUARD, 7, 1);
        for (long s = 1; s <= 31; s++) {
            runStep(host, r, s);
        }
        assertEquals(List.of("31:0:3:7:1"), r.applied);
        assertEquals(1, host.applied());
        assertEquals(1, host.applied(AiEntry.GUARD));
    }

    @Test
    void aPeerAppliesTheHostsLogAtTheSameSteps() throws InterruptedException {
        Recorder hr = new Recorder();
        AiLog host = AiLog.host(START, hr);
        captureOnGeneralThread(host, AiEntry.RUNNING, 2, 1);
        runStep(host, hr, 1); // stamped 31
        captureOnGeneralThread(host, AiEntry.FORMATION, 3, 2);
        runStep(host, hr, 2); // stamped 32
        byte[] log = host.logOut();
        for (long s = 3; s <= 40; s++) {
            runStep(host, hr, s);
        }

        Recorder pr = new Recorder();
        AiLog peer = AiLog.peer(START, pr);
        peer.logIn(log);
        for (long s = 1; s <= 32; s++) {
            assertTrue(peer.mayRun(s));
            runStep(peer, pr, s);
        }
        assertEquals(hr.applied, pr.applied);
        assertEquals(List.of("31:0:4:2:1", "32:1:6:3:2"), pr.applied);
    }

    @Test
    void aPeerNeverRunsPastTheHostsConfirmedStepPlusTheDelay() {
        Recorder r = new Recorder();
        AiLog host = AiLog.host(START, r);
        AiLog peer = AiLog.peer(START, new Recorder());
        assertTrue(peer.mayRun(AiLog.DELAY_STEPS), "nothing can be stamped before step 31");
        assertFalse(peer.mayRun(AiLog.DELAY_STEPS + 1));
        for (long s = 1; s <= 10; s++) {
            runStep(host, r, s);
        }
        peer.logIn(host.logOut());
        assertTrue(peer.mayRun(10 + AiLog.DELAY_STEPS));
        assertFalse(peer.mayRun(11 + AiLog.DELAY_STEPS));
    }

    @Test
    void theLogArrivesInAnyPieces() throws InterruptedException {
        Recorder hr = new Recorder();
        AiLog host = AiLog.host(START, hr);
        for (int i = 0; i < 5; i++) {
            captureOnGeneralThread(host, AiEntry.GUARD, i, i % 2);
        }
        runStep(host, hr, 1);
        byte[] log = host.logOut();

        Recorder pr = new Recorder();
        AiLog peer = AiLog.peer(START, pr);
        for (int i = 0; i < log.length; i++) {
            peer.logIn(Arrays.copyOfRange(log, i, i + 1)); // one byte at a time
        }
        assertTrue(peer.mayRun(1 + AiLog.DELAY_STEPS));
        for (long s = 1; s <= 31; s++) {
            runStep(peer, pr, s);
        }
        assertEquals(5, pr.applied.size());
        for (long s = 2; s <= 31; s++) {
            runStep(host, hr, s);
        }
        assertEquals(hr.applied, pr.applied);
    }

    @Test
    void aRepeatedEntryChangesNothing() throws InterruptedException {
        Recorder hr = new Recorder();
        AiLog host = AiLog.host(START, hr);
        captureOnGeneralThread(host, AiEntry.GUARD, 1, 1);
        runStep(host, hr, 1);
        byte[] log = host.logOut();

        Recorder pr = new Recorder();
        AiLog peer = AiLog.peer(START, pr);
        peer.logIn(log);
        byte[] again = Arrays.copyOfRange(log, AiLog.HEADER_BYTES, log.length); // the same records once more
        peer.logIn(again);
        for (long s = 1; s <= 31; s++) {
            runStep(peer, pr, s);
        }
        assertEquals(List.of("31:0:3:1:1"), pr.applied);
    }

    @Test
    void aMissingEntryFails() throws InterruptedException {
        Recorder hr = new Recorder();
        AiLog host = AiLog.host(START, hr);
        captureOnGeneralThread(host, AiEntry.GUARD, 1, 1);
        runStep(host, hr, 1);
        byte[] first = host.logOut();
        captureOnGeneralThread(host, AiEntry.GUARD, 2, 0);
        runStep(host, hr, 2);
        byte[] second = host.logOut();

        AiLog peer = AiLog.peer(START, new Recorder());
        byte[] header = Arrays.copyOf(first, AiLog.HEADER_BYTES);
        peer.logIn(header);
        assertThrows(IllegalStateException.class, () -> peer.logIn(second), "entry 0 never arrived");
    }

    @Test
    void aLogFromAnotherStartIsRefused() {
        Recorder r = new Recorder();
        AiLog host = AiLog.host(START, r);
        runStep(host, r, 1);
        AiLog peer = AiLog.peer(START + 1, new Recorder());
        assertThrows(IllegalStateException.class, () -> peer.logIn(host.logOut()));
    }

    @Test
    void anEntryDueEarlierThanItsStepIsAnError() throws InterruptedException {
        Recorder r = new Recorder();
        AiLog host = AiLog.host(START, r);
        captureOnGeneralThread(host, AiEntry.GUARD, 1, 1);
        runStep(host, r, 1); // stamped 31
        assertThrows(IllegalStateException.class, () -> runStep(host, r, 40), "steps 2 to 39 were skipped");
    }

    @Test
    void theLastEntriesAppliedAreKeptForADesyncReport() throws InterruptedException {
        Recorder r = new Recorder();
        AiLog host = AiLog.host(START, r);
        for (int i = 0; i < AiLog.RECENT + 3; i++) {
            captureOnGeneralThread(host, AiEntry.FORMATION, i, 1);
        }
        assertTrue(host.recent().isEmpty());
        for (long s = 1; s <= 31; s++) {
            runStep(host, r, s);
        }
        List<String> recent = host.recent();
        assertEquals(AiLog.RECENT, recent.size());
        assertEquals("step 31, #3, formation, division 3 = 1", recent.get(0));
        assertEquals("step 31, #34, formation, division 34 = 1", recent.get(AiLog.RECENT - 1));
    }

    @Test
    void inStepModeDoesNothing() {
        AiLog log = AiLog.inStep();
        assertEquals(AiMode.IN_STEP, log.mode());
        assertTrue(log.mayRun(1_000_000));
        log.stepBegin(1);
        assertFalse(log.capturing());
        assertThrows(IllegalStateException.class, log::logOut);
        assertThrows(IllegalStateException.class, () -> log.logIn(new byte[0]));
    }

    @Test
    void payloadsRoundTrip() throws InterruptedException {
        Recorder hr = new Recorder();
        AiLog host = AiLog.host(START, hr);
        byte[] order = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        Thread general = new Thread(() -> {
            host.generalThreadIs(Thread.currentThread());
            host.capture(AiEntry.DEST, 12, order);
        });
        general.start();
        general.join();
        runStep(host, hr, 1);
        List<byte[]> got = new ArrayList<>();
        AiLog peer = AiLog.peer(START, e -> got.add(e.payload()));
        peer.logIn(host.logOut());
        for (long s = 1; s <= 31; s++) {
            peer.stepBegin(s);
        }
        assertEquals(1, got.size());
        assertArrayEquals(order, got.get(0));
    }
}

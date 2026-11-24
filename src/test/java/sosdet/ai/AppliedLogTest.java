package sosdet.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sosdet.Applied;

/** SOS-249: the AI log entries applied or skipped, as the logs read them. */
class AppliedLogTest {

    private static final long START = 0x1234ABCDL;

    private static AiEntry entry(int seq, int kind, int div, int pc, byte[] payload) {
        return new AiEntry(seq + 30, seq, kind, div, pc, payload);
    }

    @Test
    void entriesComeBackInOrderAfterTheLastOneReadWithTheCommandsId() {
        AppliedLog l = new AppliedLog();
        List<Applied> out = new ArrayList<>();
        assertEquals(0, l.read(0, out));
        assertEquals(List.of(), out);
        assertEquals(1, l.record(31, entry(1, AiEntry.GUARD, 4, AiEntry.AI, AiEntry.ints(1))));
        assertEquals(2, l.record(31, entry(2, AiEntry.COMMAND, -1, 1, new CmdWriter(7001).i(5).bytes())));
        l.skip(2);
        assertEquals(3, l.record(32, entry(3, AiEntry.SPEED, -1, 0, AiEntry.ints(2000))));
        assertEquals(0, l.read(0, out));
        assertEquals(List.of(new Applied(1, 31, 1, AiEntry.GUARD, AiEntry.AI, 4, -1, false),
                new Applied(2, 31, 2, AiEntry.COMMAND, 1, -1, 7001, true),
                new Applied(3, 32, 3, AiEntry.SPEED, 0, -1, -1, false)), out);
        out.clear();
        assertEquals(0, l.read(2, out));
        assertEquals(1, out.size());
        assertEquals(3, out.get(0).seq());
        assertEquals(3, l.last());
    }

    @Test
    void aReaderThatFellBehindLearnsHowManyItMissedAndAnOverwrittenEntryIsNeverMarked() {
        AppliedLog l = new AppliedLog();
        int n = AppliedLog.CAPACITY + 10;
        for (int i = 1; i <= n; i++) {
            l.record(i, entry(i, AiEntry.TASK, i % 50, AiEntry.AI, AiEntry.ints(0)));
        }
        l.skip(5); // overwritten: its slot holds entry CAPACITY + 5 now
        List<Applied> out = new ArrayList<>();
        assertEquals(10, l.read(0, out), "the first 10 were overwritten");
        assertEquals(AppliedLog.CAPACITY, out.size());
        assertEquals(11, out.get(0).seq());
        assertEquals(n, out.get(out.size() - 1).seq());
        for (Applied a : out) {
            assertFalse(a.skipped());
        }
        out.clear();
        assertEquals(0, l.read(n - 1, out));
        assertEquals(1, out.size());
    }

    /** SOS-249 review: a resync drops the entries past its step, which the PC applies again under new numbers. */
    @Test
    void aResyncDropsTheLaterStepsAndNumbersGoOn() {
        AppliedLog l = new AppliedLog();
        l.record(10, entry(1, AiEntry.GUARD, 0, AiEntry.AI, AiEntry.ints(1)));
        l.record(11, entry(2, AiEntry.GUARD, 1, AiEntry.AI, AiEntry.ints(1)));
        l.record(12, entry(3, AiEntry.GUARD, 2, AiEntry.AI, AiEntry.ints(1)));
        l.cut(11);
        l.skip(3); // dropped: no mark anywhere
        l.skip(-1); // a taped entry's number: nothing
        assertEquals(3, l.last(), "numbers never go back");
        assertEquals(4, l.record(12, entry(3, AiEntry.GUARD, 2, AiEntry.AI, AiEntry.ints(1))));
        List<Applied> out = new ArrayList<>();
        assertEquals(0, l.read(0, out));
        assertEquals(List.of(1L, 2L, 4L), out.stream().map(Applied::seq).toList());
        for (Applied a : out) {
            assertFalse(a.skipped());
        }
        out.clear();
        l.read(3, out); // a reader that had read entry 3 before the resync
        assertEquals(List.of(new Applied(4, 12, 3, AiEntry.GUARD, AiEntry.AI, 2, -1, false)), out);
    }

    /**
     * Records the step and kind of what it applies, as the game's applier would apply it, and the number the log gives
     * the entry applying ({@code Hooks.appliedNow}).
     */
    private static final class Kinds implements AiApplier {
        final List<String> applied = new ArrayList<>();
        final List<Long> numbers = new ArrayList<>();
        AiLog log;

        @Override
        public void apply(AiEntry e) {
            applied.add(e.step + ":" + e.kind);
            if (log != null) {
                numbers.add(log.applyingSeq());
            }
        }
    }

    @Test
    void theHostAndAPeerRecordTheSameEntriesTheSkippedOnesMarked() throws InterruptedException {
        Kinds hostKinds = new Kinds();
        AiLog host = AiLog.host(START, hostKinds);
        hostKinds.log = host;
        AiLog peer = AiLog.peer(START, new Kinds());
        peer.localPc(1);
        // divisions 0 and 1 the host's, 2 the peer's, 3 the general's
        host.captureCommanders(Commanders.payload(new int[] {0, 0, 1, AiEntry.AI}, new int[] {0, 1}));
        host.stepBegin(1); // stamped 31
        host.captureOrder(AiEntry.GUARD, 0, AiEntry.ints(1)); // the host's division
        host.captureOrder(AiEntry.GUARD, 2, AiEntry.ints(1)); // the peer's: skipped
        peer.captureOrder(AiEntry.COMMAND, -1, new CmdWriter(7003).i(2).bytes());
        host.ordersIn(1, peer.ordersOut());
        Thread general = new Thread(() -> {
            host.generalThreadIs(Thread.currentThread());
            host.capture(AiEntry.TASK, 3, AiEntry.ints(5)); // the general's own
        });
        general.start();
        general.join();
        for (long s = 2; s <= 61; s++) {
            host.stepBegin(s); // stamped 32 at step 2
        }
        peer.logIn(host.logOut());
        for (long s = 1; s <= 61; s++) {
            peer.stepBegin(s);
        }
        List<Applied> h = new ArrayList<>();
        List<Applied> p = new ArrayList<>();
        assertEquals(0, host.appliedLog().read(0, h));
        assertEquals(0, peer.appliedLog().read(0, p));
        assertEquals(h, p);
        // the general's batch is stamped first, then the players' orders in the order captured
        assertEquals(List.of(new Applied(1, 31, 0, AiEntry.COMMANDERS, 0, -1, -1, false),
                new Applied(2, 32, 1, AiEntry.TASK, AiEntry.AI, 3, -1, false),
                new Applied(3, 32, 2, AiEntry.GUARD, 0, 0, -1, false),
                new Applied(4, 32, 3, AiEntry.GUARD, 0, 2, -1, true),
                new Applied(5, 32, 4, AiEntry.COMMAND, 1, -1, 7003, false)), h);
        assertEquals(5, host.appliedLog().last());
        assertEquals(List.of(2L, 3L, 5L), hostKinds.numbers, "while each applies, its own number (Hooks.appliedNow)");
        assertEquals(-1, host.applyingSeq(), "none between entries");
        host.applyTaped(AiEntry.order(AiEntry.TASK, 3, AiEntry.AI, AiEntry.ints(6)));
        assertEquals(-1L, hostKinds.numbers.get(3), "a local battle's taped entry has no number in the ring");
    }
}

package sosdet.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Players' commands in the host's log (ADR 0002): their codec, their path from a peer, and issuer-only feedback. */
class CommandsTest {

    private static final long START = 0x5EEDL;

    /** Records each command applied: step, issuing PC, the command's payload, and whether this PC issued it. */
    private static final class Applied implements AiApplier {
        final List<String> lines = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();
        AiLog log;

        @Override
        public void apply(AiEntry e) {
            lines.add(e.step + ":" + e.pc + ":" + CmdReader.idOf(e.payload()) + ":" + log.issuedHere());
            payloads.add(e.payload());
        }
    }

    @Test
    void aCommandsArgumentsReadBackInOrder() {
        byte[] p = new CmdWriter(4000).i(17).b(true).d(0.25).s("Ørn's legion").i(-3).bytes();
        CmdReader r = new CmdReader(p);
        assertEquals(4000, r.id());
        assertEquals(17, r.i());
        assertTrue(r.b());
        assertEquals(0.25, r.d());
        assertEquals("Ørn's legion", r.s());
        assertEquals(-3, r.i());
        r.end();
        assertEquals(4000, CmdReader.idOf(p));
    }

    @Test
    void aCommandReadOnlyInPartFailsLoudly() {
        CmdReader r = new CmdReader(new CmdWriter(1000).b(true).i(5).bytes());
        r.b();
        assertThrows(IllegalStateException.class, r::end, "an applier that reads less than its hook wrote");
        assertThrows(IllegalArgumentException.class, () -> new CmdWriter(70_000));
        assertThrows(IllegalStateException.class, () -> new CmdReader(new byte[] {0, 1, 7}).b(), "not a boolean");
    }

    @Test
    void commandsFromHostAndPeerApplyOnEveryPcAtTheirStepAndOnlyTheIssuerGetsFeedback() {
        Applied hostApplied = new Applied();
        AiLog host = AiLog.host(START, hostApplied);
        hostApplied.log = host;
        Applied peerApplied = new Applied();
        AiLog peer = AiLog.peer(START, peerApplied);
        peerApplied.log = peer;
        peer.localPc(2);

        byte[] curfew = new CmdWriter(1000).b(true).bytes();
        byte[] men = new CmdWriter(4000).i(3).i(40).bytes();
        host.captureOrder(AiEntry.COMMAND, -1, curfew); // the host's own player
        peer.captureOrder(AiEntry.COMMAND, -1, men); // the peer's player
        host.ordersIn(2, peer.ordersOut());
        host.stepBegin(1); // both stamped 31
        peer.logIn(host.logOut());
        for (long s = 2; s <= 31; s++) {
            host.stepBegin(s);
        }
        for (long s = 1; s <= 31; s++) {
            peer.stepBegin(s);
        }

        assertEquals(List.of("31:0:1000:true", "31:2:4000:false"), hostApplied.lines, "the host issued the curfew");
        assertEquals(List.of("31:0:1000:false", "31:2:4000:true"), peerApplied.lines, "the peer issued the size");
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(hostApplied.payloads.get(i), peerApplied.payloads.get(i), "the same bytes everywhere");
        }
        assertArrayEquals(men, peerApplied.payloads.get(1));
        assertEquals(2, host.applied(AiEntry.COMMAND));
        assertFalse(host.issuedHere(), "nothing is being applied now");
        assertEquals(List.of("step 31, #0, command, division -1 1000, PC 0", "step 31, #1, command, division -1 4000, PC 2"),
                host.recentOrders());
    }

    @Test
    void aRepeatOfTheLastCommandIsDroppedUntilItApplies() {
        Applied applied = new Applied();
        AiLog host = AiLog.host(START, applied);
        applied.log = host;
        byte[] drag = new CmdWriter(3005).i(7).i(12).i(5).bytes();
        host.captureOrder(AiEntry.COMMAND, -1, drag);
        host.captureOrder(AiEntry.COMMAND, -1, drag.clone()); // the slider still held: a frame later
        host.stepBegin(1);
        host.captureOrder(AiEntry.COMMAND, -1, drag.clone()); // still held, not applied yet
        assertEquals(2, host.commandRepeatsDropped());
        for (long s = 2; s <= 31; s++) {
            host.stepBegin(s);
        }
        host.captureOrder(AiEntry.COMMAND, -1, drag.clone()); // applied at 31: given again, it counts
        for (long s = 32; s <= 62; s++) {
            host.stepBegin(s);
        }
        assertEquals(List.of("31:0:3005:true", "62:0:3005:true"), applied.lines, "once at 31, once more at 62");
    }

    @Test
    void aPcNumberIsNeverNegative() {
        assertThrows(IllegalArgumentException.class, () -> AiLog.host(START, new Applied()).localPc(-1));
    }
}

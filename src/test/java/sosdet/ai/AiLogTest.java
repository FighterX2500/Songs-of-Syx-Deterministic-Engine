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

    @Test
    void everyEntryKindHasAName() {
        for (int kind = 0; kind < AiEntry.KINDS; kind++) {
            assertFalse(AiEntry.kindName(kind).isEmpty()); // a desync report prints them
        }
    }

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
    void theGeneralsRepeatedChangesToOneSlotBecomeOneEntryPerStep() throws InterruptedException {
        Recorder r = new Recorder();
        AiLog host = AiLog.host(START, r);
        Thread general = new Thread(() -> {
            host.generalThreadIs(Thread.currentThread());
            for (int i = 0; i < 100_000; i++) {
                host.capture(AiEntry.TASK, 121, AiEntry.ints(i)); // the general's loop re-sets the same order
            }
            host.capture(AiEntry.GUARD, 5, AiEntry.ints(1));
        });
        general.start();
        general.join();
        for (long s = 1; s <= 31; s++) {
            runStep(host, r, s);
        }
        assertEquals(List.of("31:0:2:121:99999", "31:1:3:5:1"), r.applied, "the last value of each slot, once");
        assertEquals(99_999, host.coalesced());
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

    /** SOS-90: a longer delay at a high speed moves the peers' horizon; a shorter one never stamps at or behind it. */
    @Test
    void theDelayFollowsTheSpeedAndNeverStampsBehindTheHorizon() throws InterruptedException {
        Recorder hr = new Recorder();
        AiLog host = AiLog.host(START, hr);
        AiLog peer = AiLog.peer(START, new Recorder());
        host.delay(200);
        captureOnGeneralThread(host, AiEntry.GUARD, 7, 1);
        runStep(host, hr, 1);
        peer.logIn(host.logOut());
        assertTrue(peer.mayRun(201));
        assertFalse(peer.mayRun(202));
        host.delay(AiLog.DELAY_STEPS);
        captureOnGeneralThread(host, AiEntry.GUARD, 7, 2);
        runStep(host, hr, 2);
        assertEquals(201, host.horizon(), "the horizon never goes back");
        for (long s = 3; s <= 202; s++) {
            runStep(host, hr, s);
        }
        assertEquals(List.of("201:0:3:7:1", "202:1:3:7:2"), hr.applied, "after the horizon, not at step 32");
        assertEquals(232, host.horizon());
        assertThrows(IllegalArgumentException.class, () -> host.delay(AiLog.DELAY_STEPS - 1));
        assertThrows(IllegalArgumentException.class, () -> host.delay(AiLog.MAX_DELAY_STEPS + 1));
    }

    /** SOS-90: a confirmation of format 7 or older is the step alone: the horizon is the least delay past it. */
    @Test
    void anOlderConfirmationMeansTheLeastDelay() {
        AiLog peer = AiLog.peer(START, new Recorder());
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(AiLog.HEADER_BYTES + 1 + 4 + 8);
        b.putInt(AiLog.MAGIC).putInt(7).putInt(AiLog.DELAY_STEPS).putLong(START);
        b.put(AiLog.CONFIRM).putInt(8).putLong(100);
        peer.logIn(b.array());
        assertEquals(100 + AiLog.DELAY_STEPS, peer.horizon());
        assertTrue(peer.mayRun(130));
        assertFalse(peer.mayRun(131));
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
    void aPeerThatLoadsAResyncAppliesWhatTheHostStillHad() throws InterruptedException {
        Recorder hr = new Recorder();
        AiLog host = AiLog.host(START, hr);
        Recorder pr = new Recorder();
        AiLog peer = AiLog.peer(START, pr);
        captureOnGeneralThread(host, AiEntry.GUARD, 1, 1);
        runStep(host, hr, 1); // stamped 31
        captureOnGeneralThread(host, AiEntry.GUARD, 2, 1);
        runStep(host, hr, 2); // stamped 32
        for (long s = 3; s <= 31; s++) {
            runStep(host, hr, s);
        }
        peer.logIn(host.logOut());
        for (long s = 1; s <= 32; s++) { // the peer runs ahead of the host, applying both
            runStep(peer, pr, s);
        }
        assertEquals(2, peer.applied());

        byte[] resync = host.resyncOut(); // the host saves at step 31: entry 0 applied, entry 1 due at 32
        captureOnGeneralThread(host, AiEntry.GUARD, 3, 1);
        runStep(host, hr, 32); // entry 2, stamped 62, after the save
        peer.logIn(host.logOut());

        peer.resyncIn(resync); // the peer loads the save at step 31
        assertEquals(1, peer.applied(), "the host's count at the save");
        pr.applied.clear();
        hr.applied.clear();
        for (long s = 32; s <= 62; s++) {
            runStep(peer, pr, s);
        }
        for (long s = 33; s <= 62; s++) {
            runStep(host, hr, s);
        }
        assertEquals(List.of("32:1:3:2:1", "62:2:3:3:1"), pr.applied);
        assertEquals(List.of("62:2:3:3:1"), hr.applied);
        host.resyncIn(resync); // the host loads its own resync too: its log stays as it is
        assertEquals(3, host.applied());
        assertThrows(IllegalStateException.class, peer::resyncOut, "a peer never saves");
    }

    /** Records what every PC applies, with who gave it, as (step, seq, kind, div, pc). */
    private static final class ByPc implements AiApplier {
        final List<String> applied = new ArrayList<>();

        @Override
        public void apply(AiEntry e) {
            applied.add(e.step + ":" + e.seq + ":" + e.kind + ":" + e.div + ":" + e.pc);
        }
    }

    @Test
    void thePlayersOrdersGoIntoTheHostsLogAndApplyEverywhereAtTheirStep() {
        ByPc hr = new ByPc();
        AiLog host = AiLog.host(START, hr);
        ByPc pr = new ByPc();
        AiLog peer = AiLog.peer(START, pr);
        assertTrue(host.capturingOrders(), "a player's order on the game's thread");
        assertTrue(peer.capturingOrders());

        host.captureOrder(AiEntry.MUSTER, 4, AiEntry.ints(1)); // the host's own player
        peer.captureOrder(AiEntry.HOLD_FIRE, 7, AiEntry.ints(1)); // the peer's player
        peer.captureOrder(AiEntry.AMMO, 7, AiEntry.ints(2));
        byte[] fromPeer = peer.ordersOut();
        host.ordersIn(2, Arrays.copyOf(fromPeer, 10)); // in any pieces
        host.ordersIn(2, Arrays.copyOfRange(fromPeer, 10, fromPeer.length));
        host.ordersIn(2, Arrays.copyOfRange(fromPeer, Orders.HEADER_BYTES, fromPeer.length)); // sent again: nothing
        runStep(host, new Recorder(), 1); // stamped 31
        peer.logIn(host.logOut());
        for (long s = 2; s <= 31; s++) {
            host.stepBegin(s);
        }
        for (long s = 1; s <= 31; s++) {
            peer.stepBegin(s);
        }
        assertEquals(List.of("31:0:10:4:0", "31:1:8:7:2", "31:2:9:7:2"), hr.applied);
        assertEquals(hr.applied, pr.applied);
        assertEquals(3, host.appliedOrders());
        assertEquals(List.of("step 31, #0, muster, division 4 = 1, PC 0", "step 31, #1, holdFire, division 7 = 1, PC 2",
                "step 31, #2, ammo, division 7 = 2, PC 2"), host.recentOrders());
        assertTrue(host.recent().isEmpty(), "the general's entries are listed apart");
    }

    @Test
    void aWatchingPlayerStillAnswersAWorldQuestion() {
        AiLog peer = AiLog.peer(START, new ByPc());
        peer.watchOnly(true);
        peer.captureOrder(AiEntry.COMMAND, -1, new byte[] {(byte) (AiLog.WORLD_ANSWER >> 8), (byte) AiLog.WORLD_ANSWER, 1});
        assertEquals(0, peer.watchedDropped(), "the question holds the view open until it is answered");
        peer.captureOrder(AiEntry.COMMAND, -1, new byte[] {0, 7, 1});
        assertEquals(1, peer.watchedDropped(), "any other command is dropped");
    }

    @Test
    void aWatchingPlayersOrdersAreDroppedBeforeAnyLog() {
        ByPc hr = new ByPc();
        AiLog host = AiLog.host(START, hr);
        AiLog peer = AiLog.peer(START, new ByPc());
        peer.watchOnly(true);
        peer.captureOrder(AiEntry.HOLD_FIRE, 7, AiEntry.ints(1));
        host.watchOnly(true);
        host.captureOrder(AiEntry.MUSTER, 4, AiEntry.ints(1));
        assertEquals(1, peer.watchedDropped());
        assertEquals(1, host.watchedDropped());
        peer.watchOnly(false); // acting again: its next order is its first, so the host misses none
        peer.captureOrder(AiEntry.AMMO, 7, AiEntry.ints(2));
        host.ordersIn(2, peer.ordersOut());
        runStep(host, new Recorder(), 1);
        for (long s = 2; s <= 31; s++) {
            host.stepBegin(s);
        }
        assertEquals(List.of("31:0:9:7:2"), hr.applied);
    }

    /** Divisions 0 and 1 the host's, 2 the peer's, 3 the general's; army 0's artillery the host's, army 1's the peer's. */
    private static final byte[] TABLE = Commanders.payload(new int[] {0, 0, 1, AiEntry.AI}, new int[] {0, 1});

    @Test
    void anEntryForADivisionItsSourceDoesNotCommandIsSkippedOnEveryPc() throws InterruptedException {
        ByPc hr = new ByPc();
        AiLog host = AiLog.host(START, hr);
        ByPc pr = new ByPc();
        AiLog peer = AiLog.peer(START, pr);
        peer.localPc(1);
        host.captureCommanders(TABLE);
        host.stepBegin(1); // stamped 31
        host.captureOrder(AiEntry.GUARD, 0, AiEntry.ints(1)); // the host's division
        host.captureOrder(AiEntry.GUARD, 2, AiEntry.ints(1)); // the peer's: skipped
        peer.captureOrder(AiEntry.AMMO, 2, AiEntry.ints(2)); // its own
        peer.captureOrder(AiEntry.AMMO, 1, AiEntry.ints(2)); // the host's: skipped
        host.ordersIn(1, peer.ordersOut());
        captureOnGeneralThread(host, AiEntry.TASK, 3, 5); // the general's own
        captureOnGeneralThread(host, AiEntry.RUNNING, 0, 1); // a player's division: skipped
        for (long s = 2; s <= 61; s++) {
            host.stepBegin(s); // stamped 32 at step 2
        }
        peer.logIn(host.logOut());
        for (long s = 1; s <= 61; s++) {
            peer.stepBegin(s);
        }
        assertEquals(List.of("32:1:2:3:-1", "32:3:3:0:0", "32:5:9:2:1"), hr.applied);
        assertEquals(hr.applied, pr.applied);
        assertEquals(3, host.commanders().skipped());
        assertEquals(3, peer.commanders().skipped());
        assertEquals(2, host.appliedOrders(), "skipped orders are not applied ones");
        assertEquals(1, host.commanders().of(2));
        assertEquals(0, host.commanders().ownArmy());
        assertEquals(1, peer.commanders().ownArmy(), "the peer commands division 2, in army 1");
        assertTrue(peer.commanders().commandsHere(2));
        assertFalse(peer.commanders().commandsHere(0));
        assertTrue(peer.commanders().commandsArtilleryHere(1));
        assertEquals(host.commanders().hash(), peer.commanders().hash());
    }

    /** SOS-119: a local battle's tape goes by the table too, and a command on it knows the PC that gave it. */
    @Test
    void aTapedOrderObeysTheLocalTable() {
        int[] from = {-9};
        AiLog[] log = new AiLog[1];
        log[0] = AiLog.host(START, e -> from[0] = log[0].applyingPc());
        log[0].localCommanders(new int[] {0, 1, AiEntry.AI, AiEntry.AI}, new int[] {0, AiEntry.AI});
        log[0].applyTaped(AiEntry.order(AiEntry.GUARD, 0, 1, AiEntry.ints(1)));
        assertEquals(-9, from[0], "not that PC's division");
        assertEquals(1, log[0].commanders().skipped());
        log[0].applyTaped(AiEntry.order(AiEntry.GUARD, 1, 1, AiEntry.ints(1)));
        assertEquals(1, from[0]);
        assertFalse(log[0].commanders().hashed(), "only the battle's PCs hold the table");
    }

    /** An assault with the peer as attacker: divisions 0 and 1 the host's, 2 and 3 the general's until the peer orders them. */
    private static final int[] ASSAULT = {0, 0, Commanders.claimBy(1), Commanders.claimBy(1)};
    private static final int[] ASSAULT_GUNS = {0, Commanders.claimBy(1)};

    @Test
    void anAttackersFirstOrderTakesItsDivisionFromTheGeneralOnEveryPc() throws InterruptedException {
        ByPc hr = new ByPc();
        AiLog host = AiLog.host(START, hr);
        ByPc pr = new ByPc();
        AiLog peer = AiLog.peer(START, pr);
        peer.localPc(1);
        host.assaultCommanders(ASSAULT, ASSAULT_GUNS, false);
        peer.assaultCommanders(ASSAULT, ASSAULT_GUNS, true);
        host.stepBegin(1); // stamped 31
        captureOnGeneralThread(host, AiEntry.TASK, 2, 5); // unclaimed: the general still runs it
        host.stepBegin(2);
        peer.captureOrder(AiEntry.GUARD, 2, AiEntry.ints(1));
        host.ordersIn(1, peer.ordersOut());
        for (long s = 3; s <= 32; s++) {
            host.stepBegin(s);
        }
        captureOnGeneralThread(host, AiEntry.TASK, 2, 5); // claimed by now: skipped
        captureOnGeneralThread(host, AiEntry.TASK, 3, 5); // the other one stays the general's
        for (long s = 33; s <= 64; s++) {
            host.stepBegin(s);
        }
        peer.logIn(host.logOut());
        for (long s = 1; s <= 64; s++) {
            peer.stepBegin(s);
        }
        assertEquals(3, hr.applied.size(), hr.applied.toString());
        assertEquals(hr.applied, pr.applied);
        assertEquals(1, host.commanders().skipped());
        assertEquals(1, peer.commanders().skipped());
        assertEquals(1, host.commanders().of(2));
        assertEquals(Commanders.claimBy(1), host.commanders().of(3));
        assertEquals(host.commanders().hash(), peer.commanders().hash());
    }

    @Test
    void anAttackerCommandsWhoTheyMayClaimAndNoOneElseDoes() {
        AiLog host = AiLog.host(START, new ByPc());
        host.assaultCommanders(ASSAULT, ASSAULT_GUNS, false);
        Commanders c = host.commanders();
        assertTrue(c.allows(1, 2), "the attacker's first order applies");
        assertTrue(c.allows(AiEntry.AI, 2), "the general runs it until then");
        assertFalse(c.allows(0, 2), "the defender never");
        assertFalse(c.allows(1, 0), "the attacker doesn't command the defender's soldiers");
        assertFalse(c.commandsHere(2), "the host is the defender here");
        AiLog peer = AiLog.peer(START, new ByPc());
        peer.localPc(1);
        peer.assaultCommanders(ASSAULT, ASSAULT_GUNS, true);
        assertEquals(1, peer.commanders().ownArmy(), "the attacker's own army is the invaders");
        assertTrue(peer.commanders().commandsHere(2));
        assertTrue(c.allowsArtillery(1, 1));
        c.claimArtillery(1, 1);
        assertFalse(c.allowsArtillery(AiEntry.AI, 1), "the attacker's order took that artillery");
        host.assaultCommanders(null, null, false);
        assertTrue(c.allows(0, 2), "the assault's end clears the table");
    }

    @Test
    void anAttackerWatchingSendsDivisionOrdersOnly() {
        AiLog peer = AiLog.peer(START, new Recorder());
        peer.localPc(1);
        peer.watchOnly(true);
        peer.assaultCommanders(ASSAULT, ASSAULT_GUNS, true);
        peer.captureOrder(AiEntry.GUARD, 2, AiEntry.ints(1));
        peer.captureOrder(AiEntry.SPEED, -1, AiEntry.ints(1));
        peer.captureOrder(AiEntry.COMMAND, -1, AiEntry.ints(1));
        assertEquals(2, peer.watchedDropped(), "time and city commands stay the city's");
        peer.watchOnly(false);
        peer.watchOnly(true);
        peer.captureOrder(AiEntry.GUARD, 3, AiEntry.ints(1));
        assertEquals(3, peer.watchedDropped(), "the next watch isn't the assault's");
    }

    @Test
    void aResyncCarriesTheTableAndAClearedOneRestrictsNothing() {
        AiLog host = AiLog.host(START, new ByPc());
        host.captureCommanders(TABLE);
        for (long s = 1; s <= 31; s++) {
            host.stepBegin(s);
        }
        host.captureOrder(AiEntry.GUARD, 2, AiEntry.ints(1));
        host.stepBegin(32);
        for (long s = 33; s <= 62; s++) {
            host.stepBegin(s);
        }
        AiLog peer = AiLog.peer(START, new ByPc());
        peer.localPc(1);
        peer.resyncIn(host.resyncOut());
        assertEquals(1, peer.commanders().skipped());
        assertEquals(AiEntry.AI, peer.commanders().of(3));
        assertEquals(1, peer.commanders().ownArmy());
        assertEquals(host.commanders().hash(), peer.commanders().hash());

        long unset = new Commanders().hash();
        host.captureCommanders(Commanders.payload(null, null));
        for (long s = 63; s <= 93; s++) {
            host.stepBegin(s);
        }
        assertFalse(host.commanders().set());
        assertEquals(Commanders.UNSET, host.commanders().of(2));
        assertTrue(host.commanders().allows(5, 2));
        assertEquals(unset, host.commanders().hash());
        assertThrows(IllegalArgumentException.class, () -> Commanders.payload(new int[] {0, 1, 2}, new int[] {0, 1}),
                "the divisions split evenly by army");
        assertThrows(IllegalArgumentException.class, () -> Commanders.payload(new int[] {0, -3}, new int[] {0, 0}));
        assertThrows(IllegalStateException.class, () -> peer.captureCommanders(TABLE), "only the host sets them");
    }

    @Test
    void aMissingOrderOrAnotherStartIsRefused() {
        AiLog peer = AiLog.peer(START, new ByPc());
        peer.captureOrder(AiEntry.GUARD, 1, AiEntry.ints(1));
        byte[] first = peer.ordersOut();
        peer.captureOrder(AiEntry.GUARD, 1, AiEntry.ints(0));
        byte[] second = peer.ordersOut();
        AiLog host = AiLog.host(START, new ByPc());
        host.ordersIn(3, Arrays.copyOf(first, Orders.HEADER_BYTES));
        assertThrows(IllegalStateException.class, () -> host.ordersIn(3, second), "order 0 never arrived");
        AiLog other = AiLog.host(START + 1, new ByPc());
        assertThrows(IllegalStateException.class, () -> other.ordersIn(3, first));
        assertThrows(IllegalArgumentException.class, () -> host.ordersIn(0, first), "PC 0 is the host's own player");
        assertThrows(IllegalStateException.class, peer::logOut);
        assertThrows(IllegalStateException.class, () -> AiLog.inStep().captureOrder(AiEntry.GUARD, 1, AiEntry.ints(1)));
        assertFalse(AiLog.inStep().capturingOrders(), "in-step: orders apply at once, as in vanilla");
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

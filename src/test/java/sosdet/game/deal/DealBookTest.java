package sosdet.game.deal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import sosdet.game.deal.DealBook.Outcome;
import sosdet.game.deal.DealBook.Proposal;
import sosdet.game.deal.DealBook.Terms;

/** SOS-72: the deals between humans as world state. */
class DealBookTest {

    private static final Terms TRADE_FOR_GOODS = new Terms(DealBook.TRADE, 100, 0, new int[] {3, 40}, new int[] {7, 5, 2, 1});

    @Test
    void aProposalClosesOnceAndASecondAnswerFindsNothing() {
        DealBook b = new DealBook();
        Proposal p = b.propose(1, 2, 5000, TRADE_FOR_GOODS);
        assertEquals(p, b.get(p.id()));
        assertEquals(p, b.close(p.id(), Outcome.ACCEPTED));
        assertNull(b.close(p.id(), Outcome.ACCEPTED), "a repeated accept changes nothing");
        assertEquals(List.of(), b.open());
        assertEquals(List.of(new DealBook.Answer(p.id(), 1, 2, Outcome.ACCEPTED)), b.answers());
    }

    @Test
    void idsAreNeverReusedAndAHumanReceivesAtMostEightAtOnce() {
        DealBook b = new DealBook();
        int last = 0;
        for (int i = 0; i < DealBook.MAX_OPEN_TO; i++) {
            Proposal p = b.propose(i % 2 == 0 ? 0 : 3, 2, 5000, TRADE_FOR_GOODS);
            assertTrue(p.id() > last);
            last = p.id();
        }
        assertNull(b.propose(0, 2, 5000, TRADE_FOR_GOODS), "slot 2 has 8 open");
        assertTrue(b.propose(2, 0, 5000, TRADE_FOR_GOODS).id() > last, "the other way still opens");
        b.close(last, Outcome.DECLINED);
        assertTrue(b.propose(0, 2, 5000, TRADE_FOR_GOODS).id() > last + 1);
    }

    @Test
    void proposalsExpireAtTheirSecondAndARemovedHumansAreDropped() {
        DealBook b = new DealBook();
        Proposal early = b.propose(0, 1, 100, TRADE_FOR_GOODS);
        Proposal late = b.propose(1, 0, 200, TRADE_FOR_GOODS);
        Proposal other = b.propose(2, 0, 200, TRADE_FOR_GOODS);
        assertEquals(0, b.expire(99));
        assertEquals(1, b.expire(100));
        assertEquals(List.of(late, other), b.open());
        b.dropHuman(1);
        assertEquals(List.of(other), b.open());
        assertEquals(List.of(new DealBook.Answer(early.id(), 0, 1, Outcome.EXPIRED),
                new DealBook.Answer(late.id(), 1, 0, Outcome.DROPPED)), b.answers());
    }

    @Test
    void onlyTheLatestAnswersAreKept() {
        DealBook b = new DealBook();
        for (int i = 0; i < DealBook.ANSWERS_KEPT + 3; i++) {
            b.close(b.propose(0, 1, 100, TRADE_FOR_GOODS).id(), Outcome.DECLINED);
        }
        assertEquals(DealBook.ANSWERS_KEPT, b.answers().size());
        assertEquals(4, b.answers().get(0).id(), "the first three are gone");
    }

    @Test
    void theBookRoundTripsThroughItsInts() throws IOException {
        DealBook b = new DealBook();
        b.propose(0, 1, 100, TRADE_FOR_GOODS);
        b.propose(1, 2, 300, new Terms(DealBook.NO_TREATY, 0, 25, new int[0], new int[0]));
        b.close(b.propose(2, 0, 400, TRADE_FOR_GOODS).id(), Outcome.REFUSED);
        DealBook c = DealBook.fromInts(b.toInts());
        assertEquals(b.open(), c.open());
        assertEquals(b.answers(), c.answers());
        assertArrayEquals(b.toInts(), c.toInts());
        assertEquals(b.hash(7), c.hash(7));
        assertEquals(4, c.propose(0, 2, 500, TRADE_FOR_GOODS).id(), "the next id survives the save");
        assertNotEquals(b.hash(7), c.hash(7));
    }

    @Test
    void aBrokenSaveIsRefused() {
        assertThrows(IOException.class, () -> DealBook.fromInts(new int[] {2, 1, 0, 0}), "another version");
        assertThrows(IOException.class, () -> DealBook.fromInts(new int[] {1, 1, 1}), "cut short");
        assertThrows(IOException.class, () -> DealBook.fromInts(new int[] {1, 1, 0, 0, 9}), "left over");
        // version, next id, 1 open: id, from, to, expires, treaty 9, both monies, no goods either way; no answers
        assertThrows(IOException.class, () -> DealBook.fromInts(new int[] {1, 2, 1, 1, 0, 1, 100, 9, 0, 0, 0, 0, 0}),
                "a treaty term of 9");
    }

    @Test
    void termsRefuseWhatNoDealCanHold() {
        assertThrows(IllegalArgumentException.class, () -> new Terms(6, 0, 0, new int[0], new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, -1, 0, new int[0], new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, 0, 0, new int[] {3}, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, 0, 0, new int[] {3, 0}, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, 0, 0, new int[] {3, 1, 3, 2}, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new DealBook().propose(1, 1, 0, TRADE_FOR_GOODS));
        assertTrue(new Terms(0, 0, 0, new int[0], new int[0]).empty());
    }
}

package sosdet.game.deal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** SOS-72: the deals between humans as world state; SOS-73: regions, vassalage and tributes. */
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
        assertThrows(IOException.class, () -> DealBook.fromInts(new int[] {3, 1, 0, 0, 0}), "another version");
        assertThrows(IOException.class, () -> DealBook.fromInts(new int[] {1, 1, 1}), "cut short");
        assertThrows(IOException.class, () -> DealBook.fromInts(new int[] {1, 1, 0, 0, 9}), "left over");
        // version, next id, 1 open: id, from, to, expires, treaty 9, both monies, no goods either way; no answers
        assertThrows(IOException.class, () -> DealBook.fromInts(new int[] {1, 2, 1, 1, 0, 1, 100, 9, 0, 0, 0, 0, 0}),
                "a treaty term of 9");
    }

    @Test
    void termsRefuseWhatNoDealCanHold() {
        assertThrows(IllegalArgumentException.class, () -> new Terms(8, 0, 0, new int[0], new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, -1, 0, new int[0], new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, 0, 0, new int[] {3}, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, 0, 0, new int[] {3, 0}, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, 0, 0, new int[] {3, 1, 3, 2}, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new DealBook().propose(1, 1, 0, TRADE_FOR_GOODS));
        assertTrue(new Terms(0, 0, 0, new int[0], new int[0]).empty());
        int[] none = new int[0];
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, 0, 0, none, none, new int[] {4, 4}, none, 10),
                "a region twice");
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, 0, 0, none, none, new int[] {4}, new int[] {4}, 10),
                "a region on both sides");
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, 0, 0, none, none, new int[] {-1}, none, 10));
        assertThrows(IllegalArgumentException.class, () -> new Terms(0, 0, 0, none, none, new int[DealBook.MAX_REGIONS + 1], none, 10));
        assertThrows(IllegalArgumentException.class, () -> new Terms(DealBook.VASSAL, 0, 0, none, none, none, none, 101));
        assertTrue(!new Terms(0, 0, 0, none, none, new int[] {4}, none, 10).empty(), "a region is something");
    }

    @Test
    void theTributeRateCountsOnlyForAVassalage() {
        int[] none = new int[0];
        assertEquals(25, new Terms(DealBook.PROTECTOR, 0, 0, none, none, none, none, 25).tributePercent);
        assertEquals(0, new Terms(DealBook.VASSAL, 0, 0, none, none, none, none, 0).tributePercent);
        assertEquals(DealBook.DEFAULT_TRIBUTE, new Terms(DealBook.TRADE, 0, 0, none, none, none, none, 25).tributePercent,
                "no vassalage, no rate: two equal deals compare equal");
    }

    @Test
    void aTributeIsKeptPerVassalUntilItsVassalageEnds() {
        DealBook b = new DealBook();
        assertEquals(DealBook.DEFAULT_TRIBUTE, b.tributePercent(1, 0), "a vassalage the book doesn't know");
        b.tribute(1, 0, 20);
        b.tribute(2, 0, 5);
        assertEquals(20, b.tributePercent(1, 0));
        b.tribute(1, 3, 30);
        assertEquals(List.of(new DealBook.Tribute(2, 0, 5), new DealBook.Tribute(1, 3, 30)), b.tributes(),
                "a vassal has one overlord");
        b.endTribute(1);
        assertEquals(List.of(new DealBook.Tribute(2, 0, 5)), b.tributes());
        b.dropHuman(0);
        assertEquals(List.of(), b.tributes(), "a removed overlord's vassals pay no one");
        assertThrows(IllegalArgumentException.class, () -> b.tribute(1, 1, 10));
    }

    @Test
    void regionsAndTributesRoundTripAndChangeTheHash() throws IOException {
        DealBook b = new DealBook();
        b.propose(0, 1, 100, new Terms(DealBook.NO_TREATY, 0, 0, new int[0], new int[0], new int[] {12, 40}, new int[] {7}, 10));
        b.propose(1, 0, 100, new Terms(DealBook.VASSAL, 0, 0, new int[0], new int[0], new int[0], new int[0], 35));
        long before = b.hash(7);
        b.tribute(2, 0, 15);
        assertNotEquals(before, b.hash(7), "the tributes are in the hash");
        DealBook c = DealBook.fromInts(b.toInts());
        assertEquals(b.open(), c.open());
        assertEquals(b.tributes(), c.tributes());
        assertArrayEquals(b.toInts(), c.toInts());
        assertArrayEquals(new int[] {12, 40}, c.open().get(0).terms().fromRegions());
        assertEquals(35, c.open().get(1).terms().tributePercent);
    }

    @Test
    void aVersionOneSaveStillLoads() throws IOException {
        // version 1, next id 3, 1 open: id 2, from 0, to 1, expires 900, trade, 50 and 0 denari, 1 goods pair, none
        // back; 1 answer: id 1, 1 > 0, declined
        DealBook b = DealBook.fromInts(new int[] {1, 3, 1, 2, 0, 1, 900, 2, 50, 0, 1, 3, 40, 0, 1, 1, 1, 0, 2});
        assertEquals(List.of(new Proposal(2, 0, 1, 900, new Terms(DealBook.TRADE, 50, 0, new int[] {3, 40}, new int[0]))), b.open());
        assertEquals(List.of(new DealBook.Answer(1, 1, 0, Outcome.DECLINED)), b.answers());
        assertEquals(List.of(), b.tributes());
        assertEquals(2, b.toInts()[0], "it saves as version 2");
    }

    @Test
    void stancesNeedReachButPeaceCancelAndGiftsDont() { // SOS-197: as vanilla's deals with an NPC
        assertTrue(DealBook.needsReach(DealBook.TRADE));
        assertTrue(DealBook.needsReach(DealBook.PACT));
        assertTrue(DealBook.needsReach(DealBook.ALLY));
        assertTrue(DealBook.needsReach(DealBook.PROTECTOR));
        assertTrue(DealBook.needsReach(DealBook.VASSAL));
        assertFalse(DealBook.needsReach(DealBook.PEACE));
        assertFalse(DealBook.needsReach(DealBook.CANCEL_ALL));
        assertFalse(DealBook.needsReach(DealBook.NO_TREATY));
    }

    /**
     * SOS-224, the 2026-11-17 playtest: Adam Smasher's Furniture as his report in campaign save 88 had it, after he gave
     * 1000 in deal 66. The second 1000 (deal 67) passed once his city had turned the promise into fetchers.
     */
    @Test
    void goodsOnTheirWayOutAreNotGivenAgain() {
        int held = 1260;
        int exported = 1131;
        // the promise still stands: the stock less it is all he has, as before
        assertEquals(234, DealBook.spare(available(held, 1000), 0, exported));
        // fetchers took the promise over and picked nothing up yet: vanilla's stock shows the 1000 as his again
        assertTrue(1000 <= available(held, 0), "the old check let deal 67 through");
        assertEquals(134, DealBook.spare(available(held, 0), 1000, exported));
        // the fetchers left with the goods: the stock is what stays
        assertEquals(234, DealBook.spare(available(held - 1000, 0), 0, exported - 1000));
        // fetchers that reserved in the stockpiles: the stock already left those out, only the export depot's count
        assertEquals(1134 - 200, DealBook.spare(available(held, 0), 1000, 200));
        assertEquals(0, DealBook.spare(10, 500, 500), "never below 0");
        assertEquals(10, DealBook.spare(10, -1, 500), "a bad count takes nothing");
    }

    /** Vanilla's {@code Player.getAvailable} over the world part's {@code RESOURCE.owned}: 90% of held less promised. */
    private static int available(int held, int promised) {
        return (int) Math.ceil((held - promised) * 0.9);
    }
}

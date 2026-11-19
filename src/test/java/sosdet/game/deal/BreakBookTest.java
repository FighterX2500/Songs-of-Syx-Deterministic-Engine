package sosdet.game.deal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** SOS-227: a war on an NPC that broke its treaties with the human costs no betrayal. */
class BreakBookTest {

    private static final int SLOTS = 64;
    private static final int HUMAN = 0;
    private static final int OTHER_HUMAN = 3;
    private static final int NPC = 12;

    // vanilla's loyalty per stance (DIP): war and neutral 0, trade 0.1, colleagues 0.4, allies 1
    private static final double NEUTRAL = 0;
    private static final double TRADE = 0.1;
    private static final double PACT = 0.4;
    private static final double ALLY = 1.0;

    /** A stance change as the game's diplomacy listener reports it, then {@code OpsStance.setNewStance}'s mark. */
    private static void change(BreakBook b, int human, double was, double to, boolean byHuman) {
        b.changed(human, NPC, BreakBook.lowers(false, was, false, to));
        if (byHuman) {
            b.madeByHuman(human, NPC);
        }
    }

    @Test
    void warIsTheLowestStanceAndLoyaltyOrdersTheRest() {
        assertTrue(BreakBook.lowers(false, NEUTRAL, true, 0), "neutral to war, though both have loyalty 0");
        assertTrue(BreakBook.lowers(false, TRADE, true, 0));
        assertTrue(BreakBook.lowers(false, TRADE, false, NEUTRAL));
        assertTrue(BreakBook.lowers(false, ALLY, false, PACT));
        assertFalse(BreakBook.lowers(true, 0, false, NEUTRAL), "peace raises it");
        assertFalse(BreakBook.lowers(false, NEUTRAL, false, TRADE));
        assertFalse(BreakBook.lowers(false, PACT, false, ALLY));
    }

    @Test
    void theNpcCancelsThenWarCostsNoBetrayal() {
        BreakBook b = new BreakBook(SLOTS);
        change(b, HUMAN, TRADE, NEUTRAL, false);
        assertTrue(b.broke(HUMAN, NPC));
        assertTrue(b.warIsFree(HUMAN, NPC, true));
    }

    @Test
    void theHumanCancelsThenWarCostsBetrayalAsInVanilla() {
        BreakBook b = new BreakBook(SLOTS);
        change(b, HUMAN, TRADE, NEUTRAL, true);
        assertFalse(b.broke(HUMAN, NPC));
        assertFalse(b.warIsFree(HUMAN, NPC, true));
    }

    @Test
    void theHumanBreakingWhatTheNpcLeftIsTheHumansOwnBreak() {
        BreakBook b = new BreakBook(SLOTS);
        change(b, HUMAN, ALLY, PACT, false);
        assertFalse(b.warIsFree(HUMAN, NPC, false), "a war from colleagues breaks a treaty that still stands");
        change(b, HUMAN, PACT, NEUTRAL, true);
        assertFalse(b.warIsFree(HUMAN, NPC, true), "the human cancelled the rest: their own break");
    }

    @Test
    void theNpcCancellingStepByStepToNeutralFreesTheWar() {
        BreakBook b = new BreakBook(SLOTS);
        change(b, HUMAN, PACT, TRADE, false);
        change(b, HUMAN, TRADE, NEUTRAL, false);
        assertTrue(b.warIsFree(HUMAN, NPC, true));
    }

    @Test
    void anyLaterChangeClearsTheBreak() {
        BreakBook b = new BreakBook(SLOTS);
        change(b, HUMAN, TRADE, NEUTRAL, false);
        change(b, HUMAN, NEUTRAL, TRADE, false);
        assertFalse(b.broke(HUMAN, NPC), "a new treaty, the NPC's proposal or the human's");
        change(b, HUMAN, TRADE, NEUTRAL, false);
        change(b, HUMAN, NEUTRAL, TRADE, true);
        assertFalse(b.broke(HUMAN, NPC));
        assertTrue(b.empty());
    }

    @Test
    void eachHumanHasItsOwn() {
        BreakBook b = new BreakBook(SLOTS);
        change(b, HUMAN, TRADE, NEUTRAL, false);
        change(b, OTHER_HUMAN, TRADE, NEUTRAL, true);
        assertTrue(b.warIsFree(HUMAN, NPC, true));
        assertFalse(b.warIsFree(OTHER_HUMAN, NPC, true));
        assertFalse(b.broke(HUMAN, NPC + 1), "another NPC");
    }

    @Test
    void aGoneFactionsPairsGo() {
        BreakBook b = new BreakBook(SLOTS);
        change(b, HUMAN, TRADE, NEUTRAL, false);
        change(b, OTHER_HUMAN, TRADE, NEUTRAL, false);
        b.changed(HUMAN, NPC + 1, true);
        b.forget(NPC);
        assertFalse(b.broke(HUMAN, NPC), "a new NPC in the slot starts with no break");
        assertFalse(b.broke(OTHER_HUMAN, NPC));
        assertTrue(b.broke(HUMAN, NPC + 1));
        b.forget(HUMAN);
        assertTrue(b.empty());
    }

    @Test
    void roundTripsThroughTheSave() throws IOException {
        BreakBook b = new BreakBook(SLOTS);
        b.changed(OTHER_HUMAN, NPC, true);
        b.changed(HUMAN, NPC + 1, true);
        b.changed(HUMAN, NPC, true);
        BreakBook back = BreakBook.fromInts(SLOTS, b.toInts());
        assertArrayEquals(b.toInts(), back.toInts());
        assertTrue(back.broke(HUMAN, NPC));
        assertTrue(back.broke(HUMAN, NPC + 1));
        assertTrue(back.broke(OTHER_HUMAN, NPC));
        assertFalse(back.broke(OTHER_HUMAN, NPC + 1));
        assertEquals(b.hash(7), back.hash(7));
        back.changed(HUMAN, NPC, false);
        assertFalse(BreakBook.fromInts(SLOTS, back.toInts()).broke(HUMAN, NPC));
    }

    @Test
    void anOlderSaveHasNoBreaks() throws IOException {
        // a save from before SOS-227 has no section: the game keeps the book it built, an empty one
        BreakBook b = new BreakBook(SLOTS);
        assertTrue(b.empty());
        assertFalse(b.warIsFree(HUMAN, NPC, true), "vanilla's betrayal until an NPC breaks a stance");
        assertTrue(BreakBook.fromInts(SLOTS, b.toInts()).empty());
    }

    @Test
    void refusesABadSave() {
        assertThrows(IOException.class, () -> BreakBook.fromInts(SLOTS, new int[] {2, 0}), "another version");
        assertThrows(IOException.class, () -> BreakBook.fromInts(SLOTS, new int[] {1, 2, 5}), "cut short");
        assertThrows(IOException.class, () -> BreakBook.fromInts(SLOTS, new int[] {1, 2, 9, 5}), "out of order");
        assertThrows(IOException.class, () -> BreakBook.fromInts(SLOTS, new int[] {1, 1, SLOTS * SLOTS}), "out of range");
        assertThrows(IllegalArgumentException.class, () -> new BreakBook(SLOTS).changed(SLOTS, NPC, true));
    }

    @Test
    void theHashSeesTheBreaks() {
        BreakBook b = new BreakBook(SLOTS);
        long empty = b.hash(7);
        b.changed(HUMAN, NPC, true);
        long one = b.hash(7);
        assertNotEquals(empty, one);
        BreakBook c = new BreakBook(SLOTS);
        c.changed(OTHER_HUMAN, NPC, true);
        assertNotEquals(one, c.hash(7), "another human's break");
    }
}

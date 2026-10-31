package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BattleSpeedVoteTest {

    @Test
    void twoCommandersMustAgree() {
        BattleSpeedVote v = new BattleSpeedVote(2);
        assertEquals(0, v.vote(0, 5));
        assertEquals(5, v.vote(1, 5));
    }

    @Test
    void aTieChangesNothing() {
        BattleSpeedVote v = new BattleSpeedVote(2);
        v.vote(0, 5);
        assertEquals(0, v.vote(1, 25));
        assertEquals(25, v.vote(0, 25), "a commander can change their vote");
    }

    @Test
    void threeCommandersNeedTwo() {
        BattleSpeedVote v = new BattleSpeedVote(3);
        assertEquals(0, v.vote(0, 5));
        assertEquals(0, v.vote(1, 25));
        assertEquals(0, v.vote(2, 1));
        assertEquals(25, v.vote(2, 25));
    }

    @Test
    void aCommanderLeavingStopsCounting() {
        BattleSpeedVote v = new BattleSpeedVote(3);
        v.vote(0, 5);
        v.vote(1, 25);
        assertEquals(0, v.leave(2), "two commanders left, one vote each");
        assertEquals(2, v.commanders());
        assertEquals(5, v.leave(1), "the last one decides alone");
        assertEquals(1, v.commanders());
        assertEquals(0, v.count(25));
    }

    @Test
    void aLoneCommanderDecides() {
        assertEquals(5, new BattleSpeedVote(1).vote(0, 5));
    }
}

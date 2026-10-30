package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The events the step tells the mod (SOS-110): in the order they happened, each handed out once. */
class NoticeTest {

    private Session session;

    @BeforeEach
    void start() {
        Engine.begin();
        session = Engine.session();
    }

    @AfterEach
    void end() {
        Engine.end();
    }

    @Test
    void noticesComeInTheOrderTheyHappenedAndOnlyOnce() {
        session.notice(Notice.WAR, 1, 2, 0);
        session.notice(Notice.TARIFF, 2, 1, 15);

        Notice first = Engine.noticeTake();
        assertEquals(Notice.WAR, first.kind());
        assertEquals(1, first.first());
        assertEquals(2, first.second());
        Notice second = Engine.noticeTake();
        assertEquals(Notice.TARIFF, second.kind());
        assertEquals(15, second.value());
        assertNull(Engine.noticeTake());
    }

    @Test
    void aModThatNeverTakesThemKeepsABoundedQueue() {
        for (int i = 0; i < Session.NOTICES_MAX * 2; i++) {
            session.notice(Notice.BATTLE, i, -1, 0);
        }

        int taken = 0;
        while (Engine.noticeTake() != null) {
            taken++;
        }
        assertEquals(Session.NOTICES_MAX, taken);
    }
}

package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DrawTraceTest {

    private static void drawHere(DrawTrace t) {
        t.draw();
    }

    @Test
    void eachDrawIsKeptWithItsCallSiteForTheLastSteps() {
        DrawTrace t = new DrawTrace(2);
        t.draw(); // before any step: not kept
        t.step(1);
        drawHere(t);
        t.step(2);
        drawHere(t);
        drawHere(t);
        t.step(3); // step 1 drops out
        String dump = t.dump(0, 3);
        assertTrue(dump.startsWith("## step 2, 2 draws\n"), dump);
        assertTrue(dump.contains("## step 3, 0 draws\n"), dump);
        assertTrue(!dump.contains("sosdet."), "the engine's own frames are left out, to show the game's code: " + dump);
        assertEquals(1 + 2 + 1, dump.split("\n").length, "step 2's header and draws, step 3's header; step 1 is gone");
        assertEquals("", t.dump(1, 1), "a step no longer kept dumps nothing");
    }
}

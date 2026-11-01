package sosdet.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlobalEventsTest {

    private static final double KEEP = 1000;

    @Test
    void theFirstRollStartsItAndAnotherCitysRollIsTheSameEvent() {
        GlobalEvents g = new GlobalEvents();
        assertTrue(g.roll(7, 7, 0, 100, KEEP));
        assertFalse(g.roll(7, 7, 2, 105, KEEP));
        assertEquals(1, g.size());
        assertEquals(0, g.get(0).origin);
        assertEquals(100.0, g.get(0).second);
    }

    @Test
    void aStormsTwoVariantsAreOneEvent() {
        GlobalEvents g = new GlobalEvents();
        assertTrue(g.roll(20, 21, 0, 100, KEEP));
        assertFalse(g.roll(21, 20, 1, 101, KEEP));
        assertEquals(1, g.size());
    }

    @Test
    void anEventCanStartAgainAfterItsTime() {
        GlobalEvents g = new GlobalEvents();
        assertTrue(g.roll(7, 7, 0, 100, KEEP));
        assertFalse(g.roll(7, 7, 1, 1100, KEEP));
        assertTrue(g.roll(7, 7, 1, 1101, KEEP));
        assertEquals(1, g.size());
        assertEquals(1, g.get(0).origin);
    }

    @Test
    void differentEventsRunTogether() {
        GlobalEvents g = new GlobalEvents();
        assertTrue(g.roll(7, 7, 0, 100, KEEP));
        assertTrue(g.roll(8, 8, 1, 100, KEEP));
        assertEquals(2, g.size());
    }

    @Test
    void aSavedListHashesAsItWas() {
        GlobalEvents g = new GlobalEvents();
        g.roll(20, 21, 2, 100.5, KEEP);
        GlobalEvents loaded = new GlobalEvents();
        loaded.load(g.get(0).event, g.get(0).twin, g.get(0).origin, g.get(0).second);
        assertEquals(g.hash(1), loaded.hash(1));
        assertNotEquals(new GlobalEvents().hash(1), g.hash(1));
    }

    @Test
    void eachCityEventIsGlobalAndOnlyTheNewsAppliesOnce() {
        assertTrue(GlobalEvents.global("ENVIRO_ECLIPSE"));
        assertFalse(GlobalEvents.news("ENVIRO_ECLIPSE"));
        assertTrue(GlobalEvents.global("REGION_MINING_PEACE"));
        assertTrue(GlobalEvents.news("REGION_MINING_PEACE"));
        assertFalse(GlobalEvents.global("PROD_FISH_GOOD"));
    }

    @Test
    void onlyTheStormHasATwin() {
        assertEquals("ENVIRO_BIG_STORM_V", GlobalEvents.twin("ENVIRO_BIG_STORM"));
        assertEquals("ENVIRO_BIG_STORM", GlobalEvents.twin("ENVIRO_BIG_STORM_V"));
        assertNull(GlobalEvents.twin("ENVIRO_ECLIPSE"));
    }
}

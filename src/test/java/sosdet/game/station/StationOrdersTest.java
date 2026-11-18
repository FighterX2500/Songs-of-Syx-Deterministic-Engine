package sosdet.game.station;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SOS-234: who orders and selects a stationed ally's division, in an invasion and in an assault. */
class StationOrdersTest {

    private static final int HOST = 0, STATIONED = 1, ATTACKER = 2, WATCHER = 3;

    @Test
    void aStationedDivisionIsItsPlayersWhileTheyViewTheCity() {
        for (boolean assault : new boolean[] {false, true}) {
            assertTrue(StationOrders.applies(STATIONED, HOST, STATIONED, true, assault, true), "its player");
            assertFalse(StationOrders.applies(HOST, HOST, STATIONED, true, assault, false), "not the host");
            assertFalse(StationOrders.applies(ATTACKER, HOST, STATIONED, true, assault, true), "not an attacker viewing");
        }
    }

    @Test
    void withoutTheirViewTheHostCommandsIt() {
        for (boolean assault : new boolean[] {false, true}) {
            assertTrue(StationOrders.applies(HOST, HOST, STATIONED, false, assault, false), "the host");
            assertFalse(StationOrders.applies(STATIONED, HOST, STATIONED, false, assault, false), "its player, not viewing");
        }
        assertFalse(StationOrders.applies(ATTACKER, HOST, STATIONED, false, true, true), "an attacker never");
    }

    @Test
    void theCitysOwnDivisionsAreAsBefore() {
        assertTrue(StationOrders.applies(HOST, HOST, -1, false, false, false));
        assertFalse(StationOrders.applies(STATIONED, HOST, -1, false, false, true), "a viewer's order, outside an assault");
        assertTrue(StationOrders.applies(ATTACKER, HOST, -1, false, true, true), "an assault's attacker viewing: the table limits it");
        assertFalse(StationOrders.applies(ATTACKER, HOST, -1, false, true, false), "an attacker whose view ended");
        assertTrue(StationOrders.applies(HOST, HOST, -1, false, true, false), "the defender");
    }

    /**
     * The assault's table gives army 0 to the defender on every PC. A stationed player's order for its own division
     * passes it only where the city runs and names that division; a division the host made after the stationed ones came
     * in isn't in the registry (owner -1), so the stationed player's order for it never passes.
     */
    @Test
    void theTableStaysTheDefendersAndOnlyTheRegistryLetsTheStationedPlayerThrough() {
        assertTrue(StationOrders.passesTable(false, STATIONED, STATIONED, true), "its own, while it views");
        assertFalse(StationOrders.passesTable(false, STATIONED, -1, true), "a division the host made meanwhile");
        assertFalse(StationOrders.passesTable(false, STATIONED, STATIONED, false), "not viewing");
        assertFalse(StationOrders.passesTable(false, ATTACKER, STATIONED, true), "another player's");
        assertTrue(StationOrders.passesTable(true, HOST, -1, false), "what the table allows, as before");
    }

    @Test
    void onlyItsPlayerOrTheHostWithoutThatViewSelectsIt() {
        assertTrue(StationOrders.selectable(STATIONED, STATIONED, true, true));
        assertFalse(StationOrders.selectable(HOST, STATIONED, false, true), "the host while its player views");
        assertTrue(StationOrders.selectable(HOST, STATIONED, false, false), "the host otherwise");
        assertFalse(StationOrders.selectable(WATCHER, STATIONED, true, false), "a third player viewing the city");
    }
}

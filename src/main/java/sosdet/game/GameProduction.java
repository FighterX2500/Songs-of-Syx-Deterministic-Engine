package sosdet.game;

import init.resources.RESOURCES;
import settlement.main.SETT;

/**
 * The goods window's production and consumption rates (SOS-102). Vanilla's {@code RoomProduction} totals each
 * workplace's rate in a scan of the city's people, 200 a tick, that its own UI drives as it reads the totals. In a
 * session the UI only reads (patch 67, {@code outsideStep}), and no step code reads them, so the scan never advanced:
 * the rooms' rates stayed as the load left them, and the Production Rate figures left producers and consumers out. The
 * city part now drives the scan every other step (the totals refresh only when two or more ticks passed), as vanilla's
 * UI would at 1x, on every PC that runs the city alike. Nothing the simulation reads changes.
 */
public final class GameProduction {

    private GameProduction() {
    }

    /** In the city part, every other step. */
    public static void refresh() {
        SETT.ROOMS().PROD.produced(RESOURCES.ALL().get(0));
    }
}

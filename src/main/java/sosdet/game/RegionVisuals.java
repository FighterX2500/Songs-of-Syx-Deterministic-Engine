package sosdet.game;

import init.race.Race;
import world.map.regions.Region;
import world.region.pop.RDRace;
import world.region.pop.RDRaces;

/**
 * Which race a region's map sprites show, worked out as {@code RDRaces.Visuals} caches it but without writing its
 * cache, which lives in the region's saved data (SOS-35). With a session the renderer asks here between steps, so what
 * this PC draws never changes the save. Render path: allocates nothing.
 */
public final class RegionVisuals {

    private static final int VILLAGE_SLOTS = 16;

    private RegionVisuals() {
    }

    /** The race with the most people in the region; the first of them on a tie ({@code Visuals.cRace}). */
    public static Race biggest(RDRaces races, Region reg) {
        RDRace biggest = null;
        int most = -1;
        for (int i = 0; i < races.all.size(); i++) {
            RDRace r = races.all.get(i);
            if (r.pop.get(reg) > most) {
                biggest = r;
                most = r.pop.get(reg);
            }
        }
        return biggest.race;
    }

    /**
     * The race of village sprite {@code slot} (its low four bits): the 16 slots go to the races in turn, each as many as
     * its share of the people, and the slots left over to the biggest race ({@code Visuals.vRace}).
     */
    public static Race village(RDRaces races, Region reg, int slot) {
        int s = slot & (VILLAGE_SLOTS - 1);
        int total = races.population.get(reg);
        if (total > 0) {
            int filled = 0;
            for (int i = 0; i < races.all.size(); i++) {
                RDRace r = races.all.get(i);
                filled += VILLAGE_SLOTS * r.pop.get(reg) / total;
                if (s < filled) {
                    return r.race;
                }
            }
        }
        return biggest(races, reg);
    }
}

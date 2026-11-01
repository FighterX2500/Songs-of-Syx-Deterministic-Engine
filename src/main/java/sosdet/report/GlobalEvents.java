package sosdet.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import sosdet.hash.HashLog;

/**
 * The global events under way (SOS-125, mod spec §5): a scripted event one city rolled, which the world part has put in
 * order for every city. The world part writes this, the same on every PC; a city's part only reads it, so each city
 * starts an event at the same step. A city with an event of its own running starts this one when that ends. While an
 * event is here, any other city's roll of it is that same event.
 */
public final class GlobalEvents {

    private static final String STORM = "ENVIRO_BIG_STORM";
    private static final String STORM_AGAIN = "ENVIRO_BIG_STORM_V";
    /** Each city runs these, and each player answers any choice. */
    private static final Set<String> EVERY_CITY = Set.of("ENVIRO_ECLIPSE", "ENVIRO_COMET_SIGHTED", "PROD_YEAR_CRATOR",
            STORM, STORM_AGAIN, "REGION_FAMINE");
    /** News of the world: the rolling city runs these, so they apply once; the others read the message. */
    private static final Set<String> WORLD_NEWS = Set.of("REGION_WEAPON_PRICE_U", "REGION_GARTHIMI_TROUBLES",
            "REGION_MINING_PEACE", "REGION_DONDORIAN_ECONOMY");

    public static boolean global(String key) {
        return EVERY_CITY.contains(key) || WORLD_NEWS.contains(key);
    }

    public static boolean news(String key) {
        return WORLD_NEWS.contains(key);
    }

    /** The storm's other variant, a city's second storm: one event to the world, each city takes the one its tags allow. */
    public static String twin(String key) {
        if (key.equals(STORM)) {
            return STORM_AGAIN;
        }
        return key.equals(STORM_AGAIN) ? STORM : null;
    }

    /** An event under way: its and its twin's game numbers, the city whose roll started it, the game second it started. */
    public static final class Entry {
        public final int event;
        public final int twin;
        public final int origin;
        public final double second;

        Entry(int event, int twin, int origin, double second) {
            this.event = event;
            this.twin = twin;
            this.origin = origin;
            this.second = second;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * City {@code origin}'s roll of {@code event} at game second {@code second}, with {@code twin} the event's variant
     * ({@code event} itself when it has none). The first roll starts it; later ones, for {@code keep} seconds, are that
     * same event. True when it starts.
     */
    public boolean roll(int event, int twin, int origin, double second, double keep) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (second - entries.get(i).second > keep) {
                entries.remove(i);
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).event == event || entries.get(i).event == twin) {
                return false;
            }
        }
        entries.add(new Entry(event, twin, origin, second));
        return true;
    }

    public int size() {
        return entries.size();
    }

    public Entry get(int i) {
        return entries.get(i);
    }

    public void clear() {
        entries.clear();
    }

    /** Restores an entry from a save, in the saved order. */
    public void load(int event, int twin, int origin, double second) {
        entries.add(new Entry(event, twin, origin, second));
    }

    public long hash(long h) {
        h = HashLog.mix(h, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            h = HashLog.mix(h, e.event);
            h = HashLog.mix(h, e.twin);
            h = HashLog.mix(h, e.origin);
            h = HashLog.mix(h, Double.doubleToLongBits(e.second));
        }
        return h;
    }
}

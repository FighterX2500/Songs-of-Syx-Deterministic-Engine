package sosdet.game;

import game.GAME.GameResource;
import game.events.EVENTS;
import game.faction.FACTIONS;
import game.raiding.RAIDING;
import world.WORLD;

/**
 * Which part of the step each game resource and event runs in (ADR 0005, SOS-38). A step runs the city part, then
 * the world part. The world part is lockstep; the city part is this PC's own city.
 *
 * <ul>
 * <li>World: {@code WORLD}, {@code RAIDING}.</li>
 * <li>Both, each doing its own half: {@code EVENTS} (city events, then world events) and {@code FACTIONS} (the player's
 * own update, then the other factions, trade, emissaries and diplomacy).</li>
 * <li>City: every other resource, a mod's included. Scripted events ({@code EVENT_HANDLER}) are the city's: their
 * world marks and world actions travel in the city's report (B6).</li>
 * </ul>
 */
public final class StepParts {

    public static final int CITY = 0;
    public static final int WORLD_PART = 1;

    private StepParts() {
    }

    /** True when the resource updates in this part. */
    public static boolean updatesIn(GameResource r, int part) {
        if (r instanceof EVENTS || r instanceof FACTIONS) {
            return true;
        }
        return (part == WORLD_PART) == world(r);
    }

    /** True when the resource's once-per-step work runs at the start of this part: the world part for the split ones. */
    public static boolean afterTickIn(GameResource r, int part) {
        boolean world = world(r) || r instanceof EVENTS || r instanceof FACTIONS;
        return (part == WORLD_PART) == world;
    }

    private static boolean world(GameResource r) {
        return r instanceof WORLD || r instanceof RAIDING;
    }

    /** True for the events of the world part, by save key (scan: city-world-link.md); any other event is the city's. */
    public static boolean worldEvent(String key) {
        switch (key) {
        case "FACTION_EXPAND":
        case "FACTION_COLLAPSE":
        case "FACTION_POPUP":
        case "FACTION_WAR":
        case "FACTION_PEACE":
        case "DIPLOMACY":
        case "REBELLION":
            return true;
        default:
            return false;
        }
    }

    /** The player's own faction update is city work; the world part's faction updater skips it. */
    public static boolean worldUpdatesFaction(Object faction) {
        return faction != FACTIONS.player();
    }
}

package sosdet.game.cmd;

import game.GAME;
import game.battle.div.Div;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.npc.FactionNPC;
import init.race.RACES;
import init.race.Race;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import init.type.HCLASS;
import init.type.HCLASSES;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomInstance;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import world.WORLD;
import world.army.ADDiv;
import world.army.WDivMercenary;
import world.army.WDivRegional;
import world.entity.army.WArmy;
import world.map.regions.Region;

/**
 * Game objects as command arguments (ADR 0002): each by a stable index that every PC resolves to the same object at
 * the command's step. A reader returns null when the object is gone; the command is then skipped on every PC alike.
 * Nullable arguments (a race of "all races") are written as -1.
 */
public final class Args {

    private Args() {
    }

    /** A room instance: its index, and its blueprint so that a new room reusing the index is not mistaken for it. */
    public static CmdWriter room(CmdWriter w, Room r) {
        return w.i(r.index()).i(r.blueprint().index());
    }

    /** A room of any kind, construction sites too, as {@link #room(CmdWriter, Room)} wrote it. */
    public static Room.RoomInstanceImp anyRoom(CmdReader r) {
        int index = r.i();
        int blueprint = r.i();
        Room room = index >= 0 && index < SETT.ROOMS().map.max() ? SETT.ROOMS().map.getByIndex(index) : null;
        if (!(room instanceof Room.RoomInstanceImp) || room.blueprint().index() != blueprint) {
            return null;
        }
        return (Room.RoomInstanceImp) room;
    }

    public static RoomInstance room(CmdReader r) {
        int index = r.i();
        int blueprint = r.i();
        Room room = index >= 0 && index < SETT.ROOMS().map.max() ? SETT.ROOMS().map.getByIndex(index) : null;
        if (!(room instanceof RoomInstance) || room.blueprint().index() != blueprint) {
            return null;
        }
        return (RoomInstance) room;
    }

    public static CmdWriter blueprint(CmdWriter w, RoomBlueprint b) {
        return w.i(b == null ? -1 : b.index());
    }

    public static RoomBlueprint blueprint(CmdReader r) {
        int i = r.i();
        return i < 0 ? null : SETT.ROOMS().all().get(i);
    }

    /** A person or other entity, by its id; null when it has died or been removed since. */
    public static CmdWriter entity(CmdWriter w, ENTITY e) {
        return w.i(e.id());
    }

    public static ENTITY entity(CmdReader r) {
        return SETT.ENTITIES().getByID(r.i());
    }

    public static Humanoid humanoid(CmdReader r) {
        ENTITY e = entity(r);
        return e instanceof Humanoid ? (Humanoid) e : null;
    }

    public static CmdWriter race(CmdWriter w, Race race) {
        return w.i(race == null ? -1 : race.index());
    }

    public static Race race(CmdReader r) {
        int i = r.i();
        return i < 0 ? null : RACES.all().get(i);
    }

    public static CmdWriter resource(CmdWriter w, RESOURCE res) {
        return w.i(res == null ? -1 : res.index());
    }

    public static RESOURCE resource(CmdReader r) {
        int i = r.i();
        return i < 0 ? null : RESOURCES.ALL().get(i);
    }

    public static CmdWriter hclass(CmdWriter w, HCLASS c) {
        return w.i(c == null ? -1 : c.index());
    }

    public static HCLASS hclass(CmdReader r) {
        int i = r.i();
        return i < 0 ? null : HCLASSES.ALL().get(i);
    }

    /** A division, by its index among all divisions (fixed for the game). */
    public static CmdWriter div(CmdWriter w, Div d) {
        return w.i(d.index());
    }

    public static Div div(CmdReader r) {
        return GAME.ARMIES().division((short) r.i());
    }

    /**
     * A world army: its slot and the slot's iteration, so that a new army in a freed slot is not mistaken for it.
     * Null (written as -1) for "no army", where the call allows one.
     */
    public static CmdWriter army(CmdWriter w, WArmy a) {
        return a == null ? w.i(-1).i(0) : w.i(a.armyIndex()).i(a.iteration());
    }

    /** The army {@link #army(CmdWriter, WArmy)} wrote; null when it was null, or is gone or disbanded since. */
    public static WArmy army(CmdReader r) {
        int index = r.i();
        int iteration = r.i();
        return army(index, iteration);
    }

    /**
     * The army {@link #army(CmdWriter, WArmy)} wrote, when it is the issuing human's own (inside a world command, the
     * current human); null when it is gone or another faction's: a human orders only its own armies (SOS-46).
     */
    public static WArmy ownArmy(CmdReader r) {
        WArmy a = army(r);
        return a != null && a.faction() == FACTIONS.player() ? a : null;
    }

    /** The army with this index and iteration; null when it is gone or disbanded since. */
    public static WArmy army(int index, int iteration) {
        if (index < 0 || index >= WORLD.ENTITIES().armies.max()) {
            return null;
        }
        WArmy a = WORLD.ENTITIES().armies.get(index);
        return a != null && a.added() && a.iteration() == iteration ? a : null;
    }

    /** A world region, by its index (fixed for the map). */
    public static CmdWriter region(CmdWriter w, Region reg) {
        return w.i(reg.index());
    }

    public static Region region(CmdReader r) {
        int i = r.i();
        return i >= 0 && i < WORLD.REGIONS().all().size() ? WORLD.REGIONS().getByIndex(i) : null;
    }

    /** A faction: its index, and an NPC faction's iteration, so that a faction re-founded in its slot differs. */
    public static CmdWriter faction(CmdWriter w, Faction f) {
        return w.i(f.index()).i(f instanceof FactionNPC ? ((FactionNPC) f).iteration() : 0);
    }

    /** The NPC faction {@link #faction(CmdWriter, Faction)} wrote; null when it is gone, re-founded or the player. */
    public static FactionNPC npc(CmdReader r) {
        int i = r.i();
        int iteration = r.i();
        if (i < 0 || i >= FACTIONS.MAX()) {
            return null; // not a faction slot (-1 for none): refused, as a stale NPC is
        }
        Faction f = FACTIONS.getByIndex(i);
        if (!(f instanceof FactionNPC) || !f.isActive() || ((FactionNPC) f).iteration() != iteration) {
            return null;
        }
        return (FactionNPC) f;
    }

    /**
     * A division of a world army: the army, the division's kind (raised in a region, the city's, mercenary) and its
     * index among its kind. Null when the army is gone or the division has left it.
     */
    public static CmdWriter worldDiv(CmdWriter w, ADDiv d) {
        return army(w, d.army()).i(divKind(d)).i(d.index);
    }

    public static ADDiv worldDiv(CmdReader r) {
        WArmy a = army(r);
        int kind = r.i();
        int index = r.i();
        if (a == null) {
            return null;
        }
        for (int i = 0; i < a.divs().size(); i++) {
            ADDiv d = a.divs().get(i);
            if (d.index == index && divKind(d) == kind) {
                return d;
            }
        }
        return null;
    }

    /** 0 raised in a region, 2 mercenary, 1 the city's own (its class is not public). */
    private static int divKind(ADDiv d) {
        if (d instanceof WDivRegional) {
            return 0;
        }
        return d instanceof WDivMercenary ? 2 : 1;
    }
}

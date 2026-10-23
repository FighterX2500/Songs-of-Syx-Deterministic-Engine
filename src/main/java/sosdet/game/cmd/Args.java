package sosdet.game.cmd;

import game.GAME;
import game.battle.div.Div;
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
}

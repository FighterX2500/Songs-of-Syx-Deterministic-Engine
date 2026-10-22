package sosdet.rig.act;

import java.util.Arrays;

import game.GAME;
import game.battle.DivisionBanners.DivisionBanner;
import game.battle.div.DivInfo;
import game.faction.FACTIONS;
import game.faction.FBanner;
import game.nobility.NobleOffice;
import init.type.HCLASSES;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomBlueprintIns;
import settlement.room.main.RoomInstance;
import settlement.stats.STATS;
import settlement.tilemap.SettMarks.SettMark;
import snake2d.util.color.ColorImp;
import sosdet.Engine;
import sosdet.game.cmd.Names;

/**
 * The rig's scripted player: names, colours, map marks and nobles, each the call the game's UI makes (SOS-34). Where
 * the hook sits at the UI's call site, the action makes the hooked call as that site does. Names are one word.
 */
public final class NameActions {

    private NameActions() {
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        case "divname": // divname <division> <name>: the division editor's accepted name
            int div = Integer.parseInt(a[1]);
            String divName = a[2];
            Engine.betweenSteps(() -> {
                DivInfo info = GAME.ARMIES().player().divisions().get(div).info;
                if (!Names.divisionName(info, divName)) {
                    info.nameE().clear().add(divName);
                }
            });
            return true;
        case "roomname": // roomname <blueprint key> <name>: the room panel's name input, on the first such room
            String key = a[1];
            String roomName = a[2];
            Engine.betweenSteps(() -> {
                RoomInstance room = firstRoom(key);
                if (!Names.roomName(room, roomName)) {
                    room.name().clear().add(roomName);
                }
            });
            return true;
        case "factionname": // factionname <name>: the profile's faction name input
            String factionName = a[1];
            Engine.betweenSteps(() -> {
                if (!Names.factionName(factionName)) {
                    FACTIONS.player().name.clear().add(factionName);
                }
            });
            return true;
        case "rulername": // rulername <name>: the profile's ruler name input
            String rulerName = a[1];
            Engine.betweenSteps(() -> {
                if (!Names.rulerName(rulerName)) {
                    FACTIONS.player().rulerName.clear().add(rulerName);
                }
            });
            return true;
        case "markadd": // markadd <tx> <ty>: the map marks' placer
            int addX = Integer.parseInt(a[1]);
            int addY = Integer.parseInt(a[2]);
            Engine.betweenSteps(() -> {
                SettMark m = Names.markAdd(addX, addY) ? null : SETT.TILE_MAP().marks.make();
                if (m != null) {
                    m.set(addX, addY);
                }
            });
            return true;
        case "markname": // markname <tx> <ty> <name>: the mark panel's name input, on the mark at that tile
            int nameX = Integer.parseInt(a[1]);
            int nameY = Integer.parseInt(a[2]);
            String markName = a[3];
            Engine.betweenSteps(() -> {
                SettMark m = mark(nameX, nameY);
                if (!Names.markName(m, markName)) {
                    m.name.clear().add(markName);
                }
            });
            return true;
        case "markorder": // markorder <tx> <ty> <place>: the mark panel's order
            int orderX = Integer.parseInt(a[1]);
            int orderY = Integer.parseInt(a[2]);
            int place = Integer.parseInt(a[3]);
            Engine.betweenSteps(() -> {
                SettMark m = mark(orderX, orderY);
                if (!Names.markOrder(m, place)) {
                    m.setPosition(place);
                }
            });
            return true;
        case "markremove": // markremove <tx> <ty>: the mark panel's trash button
            int removeX = Integer.parseInt(a[1]);
            int removeY = Integer.parseInt(a[2]);
            Engine.betweenSteps(() -> {
                SettMark m = mark(removeX, removeY);
                if (!Names.markRemove(m)) {
                    m.remove();
                }
            });
            return true;
        case "colour": // colour divbanner <i> <part 0|1> | banner <part 0-3> | mark <tx> <ty>, then <channel 0-2> <value>
            int n = a.length;
            int channel = Integer.parseInt(a[n - 2]);
            int value = Integer.parseInt(a[n - 1]);
            String[] owner = Arrays.copyOfRange(a, 1, n - 2);
            Engine.betweenSteps(() -> {
                ColorImp c = colour(owner);
                if (!Names.colour(c, channel, value)) {
                    if (channel == 0) {
                        c.setRed(value);
                    } else if (channel == 1) {
                        c.setGreen(value);
                    } else {
                        c.setBlue(value);
                    }
                }
            });
            return true;
        case "bannertype": // bannertype <type>: the profile's banner arrows
            int type = Integer.parseInt(a[1]);
            Engine.betweenSteps(() -> FACTIONS.player().banner().bannerTypeSet(type));
            return true;
        case "subjectname": // subjectname <name>: the subject screen's rename, on the first citizen
            String subjectName = a[1];
            Engine.betweenSteps(() -> {
                Humanoid h = citizen(false);
                if (!Names.subjectName(h, subjectName)) {
                    STATS.APPEARANCE().setCustomName(h.indu(), subjectName);
                }
            });
            return true;
        case "noble": // noble: the subject screen's "elevate", on the first citizen of a playable race
            Engine.betweenSteps(() -> {
                Humanoid h = citizen(true);
                if (!Names.nobleSet(h)) {
                    h.nobleSet();
                }
            });
            return true;
        case "nobleoffice": // nobleoffice <noble> <office, -1 for none>: the noble screen's office buttons
            int officeNoble = Integer.parseInt(a[1]);
            int office = Integer.parseInt(a[2]);
            Engine.betweenSteps(() -> GAME.NOBLE().setOffice(GAME.NOBLE().active().get(officeNoble), office(office)));
            return true;
        case "noblerank": // noblerank <noble>: the noble screen's promotion
            int rankNoble = Integer.parseInt(a[1]);
            Engine.betweenSteps(() -> GAME.NOBLE().ranksAllocate(GAME.NOBLE().active().get(rankNoble)));
            return true;
        case "noblereplace": // noblereplace <office> <ranks>: the death message's "replace", on the first citizen
            int replaceOffice = Integer.parseInt(a[1]);
            int ranks = Integer.parseInt(a[2]);
            Engine.betweenSteps(() -> {
                Humanoid h = citizen(true);
                NobleOffice o = office(replaceOffice);
                if (!Names.nobleReplace(h, o, ranks)) {
                    h.nobleSet();
                    GAME.NOBLE().setOffice(h.noble(), o);
                    for (int i = 0; i < ranks; i++) {
                        GAME.NOBLE().ranksAllocate(h.noble());
                    }
                }
            });
            return true;
        default:
            return false;
        }
    }

    private static RoomInstance firstRoom(String key) {
        for (RoomBlueprint b : SETT.ROOMS().all()) {
            if (b.key.equals(key) && b instanceof RoomBlueprintIns && ((RoomBlueprintIns<?>) b).instancesSize() > 0) {
                return ((RoomBlueprintIns<?>) b).getInstance(0);
            }
        }
        throw new IllegalArgumentException("SOSDET rig: no room of blueprint " + key);
    }

    private static SettMark mark(int tx, int ty) {
        for (SettMark m : SETT.TILE_MAP().marks.all) {
            if (m.active && m.tile.x() == tx && m.tile.y() == ty) {
                return m;
            }
        }
        throw new IllegalArgumentException("SOSDET rig: no map mark at " + tx + "," + ty);
    }

    /** The first citizen (in entity order); with {@code forNoble}, one of a playable race who is not a noble. */
    private static Humanoid citizen(boolean forNoble) {
        for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
            if (e instanceof Humanoid) {
                Humanoid h = (Humanoid) e;
                if (h.indu().clas() == HCLASSES.CITIZEN() && (!forNoble || h.race().playable && h.noble() == null)) {
                    return h;
                }
            }
        }
        throw new IllegalArgumentException("SOSDET rig: no citizen");
    }

    private static NobleOffice office(int i) {
        return i < 0 ? null : GAME.NOBLE().OFFICES.get(i);
    }

    private static ColorImp colour(String[] owner) {
        switch (owner[0]) {
        case "divbanner":
            DivisionBanner b = GAME.ARMIES().banners.get(Integer.parseInt(owner[1]));
            return owner[2].equals("0") ? b.col : b.bg;
        case "banner":
            FBanner f = FACTIONS.player().banner();
            int part = Integer.parseInt(owner[1]);
            return part == 0 ? f.colorBG() : part == 1 ? f.colorFG() : part == 2 ? f.colorBorder() : f.colorPole();
        case "mark":
            return mark(Integer.parseInt(owner[1]), Integer.parseInt(owner[2])).color;
        default:
            throw new IllegalArgumentException("SOSDET rig: no colour owner " + owner[0]);
        }
    }
}

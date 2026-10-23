package sosdet.game.cmd;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import game.faction.FACTIONS;
import settlement.job.Job;
import settlement.main.SETT;
import settlement.room.main.ROOMS;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.copy.SavedPrints.SavedPrint;
import settlement.room.main.placement.RoomPlacer;
import settlement.tilemap.terrain.TBuilding;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.RECTANGLE;
import snake2d.util.file.Json;
import snake2d.util.file.JsonE;
import snake2d.util.map.MAP_BOOLEAN;
import snake2d.util.map.MAP_BOOLEANE;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import view.main.VIEW;
import view.sett.ui.room.copy.UICopier;
import view.tool.PlacableFixed;

/**
 * The room copier, saved blueprints and the mass copier (ids 6100-6199, ADR 0003). Each commits at once with one click,
 * so each click becomes one command carrying what it copies; picking the source, rotating and the tool options stay on
 * the issuer's PC. The applier stages the game's own placer with the command's values, runs the click's loop
 * ({@link Tools#placeFixed}), which checks every tile against the step's state first, and puts this PC's own values
 * back.
 *
 * <p>A held button repeats the click every frame, as for the other fixed tools; a repeat finds its tiles taken at the
 * step and is skipped (the mass copier's room copies and construction jobs check their own tiles).
 */
public final class CopyTools {

    static final int ROOM_COPY = 6100;
    static final int PRINT_PLACE = 6101;
    static final int MASS_COPY = 6102;

    static final String NOTICE = "SOSDET: this room can't be copied in a multiplayer session.";

    /** {@code JsonValue.JsonValueJson}, V71.44: public, but nested in a package-private class. */
    private static final String JSON_VALUE_CLASS = "snake2d.util.file.JsonValue$JsonValueJson";

    static final List<Command> ALL = List.of(
            new Command(ROOM_COPY, "roomCopy", CopyTools::applyRoomCopy),
            new Command(PRINT_PLACE, "printPlace", CopyTools::applyPrintPlace),
            new Command(MASS_COPY, "massCopy", CopyTools::applyMassCopy));

    private CopyTools() {
    }

    // ------------------------------------------------------------------ hooks (this PC's player, outside the step)

    /**
     * Hook in the room copier's {@code nextStep.place} at the footprint's first tile, after its {@code update()}: the
     * source room, the blueprint it is copied as (the swap button), the wall material, "include walls", the rotation.
     */
    public static boolean roomCopy(Room source, RoomBlueprintImp blue, TBuilding structure, boolean walls, int rot,
            int x1, int y1) {
        if (!Commands.capturing()) {
            return false;
        }
        if (!(source instanceof Room.RoomInstanceImp)) {
            VIEW.inters().fullScreen.activate(NOTICE, COLOR.WHITE100, null);
            return true;
        }
        Commands.capture(Args.blueprint(Args.room(new CmdWriter(ROOM_COPY), source), blue)
                .i(Tools.buildingIndex(structure)).b(walls).i(rot).i(x1).i(y1));
        return true;
    }

    /**
     * Hook at the top of the saved-print placer's {@code place}, per tile: in a session the first tile captures the
     * print's contents, the blueprint it is placed as, the wall material, "include walls" and the rotation, and every
     * tile returns.
     */
    public static boolean printPlace(SavedPrint print, RoomBlueprintImp blue, boolean walls, int rot, int tx, int ty,
            int rx, int ry) {
        if (!Commands.capturing()) {
            return false;
        }
        if (rx != 0 || ry != 0) {
            return true;
        }
        PrintRecord p = new PrintRecord(print.blue.index(), print.width, print.height,
                (int[]) PlanStores.get(Tools.field(SavedPrint.class, "data"), print));
        // SavedPrintsPlacer.structure(), V71.44, without its write of mud into the room placer
        TBuilding structure = SETT.ROOMS().placement.placer.structure.get();
        if (structure == null) {
            structure = SETT.TERRAIN().BUILDINGS.MUD;
        }
        CmdWriter w = Args.blueprint(new CmdWriter(PRINT_PLACE), blue);
        p.write(w);
        Commands.capture(w.i(Tools.buildingIndex(structure)).b(walls).i(rot).i(tx).i(ty));
        return true;
    }

    /**
     * Hook at the top of the mass copier's {@code Second.place}, per tile: in a session the first tile captures the
     * selected tiles, the destination's centre, the rotation and the job tools' overwrite and road options its
     * construction jobs are placed with, and every tile returns.
     */
    public static boolean massCopy(PlacableFixed second, int tx, int ty, int rx, int ry) {
        if (!Commands.capturing()) {
            return false;
        }
        if (rx != 0 || ry != 0) {
            return true;
        }
        Object dest = PlanStores.get(Tools.field(second.getClass(), "dest"), second);
        MAP_BOOLEAN source = (MAP_BOOLEAN) PlanStores.get(Tools.field(dest.getClass(), "source"), dest);
        RECTANGLE rec = (RECTANGLE) PlanStores.get(Tools.field(source.getClass(), "rec"), source);
        if (rec.width() <= 0 || rec.height() <= 0) {
            return true;
        }
        CmdWriter w = new CmdWriter(MASS_COPY);
        TileArea.write(w, rec.x1(), rec.y1(), rec.width(), rec.height(), source::is);
        // PlacableFixedTool.click, V71.44: the footprint's first tile is the cursor less half the footprint
        Commands.capture(w.i(tx + second.width() / 2).i(ty + second.height() / 2).i(second.rot()).b(Job.overwrite)
                .b(Tools.get(Tools.roadConvert(), SETT.JOBS().roads)));
        return true;
    }

    // ------------------------------------------------------------------ appliers (every PC, inside the step)

    /**
     * The room copier's click, from the copier staged with the issuer's source, blueprint, wall material, walls and
     * rotation. Skipped when the source is gone or can no longer be copied as that blueprint, or the footprint no
     * longer fits ({@code nextStep.placable}: the area and every copied tile's furniture).
     */
    private static boolean applyRoomCopy(CmdReader r) {
        Room.RoomInstanceImp source = Args.anyRoom(r);
        RoomBlueprint b = Args.blueprint(r);
        TBuilding structure = Tools.building(r.i());
        boolean walls = r.b();
        int rot = rotation(r.i());
        int x1 = r.i();
        int y1 = r.i();
        if (source == null || source.constructor() == null || !source.constructor().canBeCopied()
                || !swappable(b, source.constructor().blue())) {
            return false;
        }
        RoomBlueprintImp blue = (RoomBlueprintImp) b;
        if (structure == null && walls && blue.constructor().mustBeIndoors()) {
            return false; // the copier would build walls of no material
        }

        Object copier = PlanStores.get(Tools.field(SETT.ROOMS().copy.getClass(), "copy"), SETT.ROOMS().copy);
        Class<?> c = copier.getClass();
        Field fRoom = Tools.field(c, "room");
        Field fStructure = Tools.field(c, "structure");
        Field fWalls = Tools.field(c, "w");
        Object swap = PlanStores.get(Tools.field(c, "swap"), copier);
        Field fCurrent = Tools.field(swap.getClass(), "current");
        PlacableFixed tool = (PlacableFixed) PlanStores.get(Tools.field(c, "nextStep"), copier);

        Object ownRoom = PlanStores.get(fRoom, copier);
        Object ownStructure = PlanStores.get(fStructure, copier);
        boolean ownWalls = Tools.get(fWalls, copier);
        Object ownCurrent = PlanStores.get(fCurrent, swap);
        int ownRot = tool.rot();
        put(fRoom, copier, source);
        put(fStructure, copier, structure);
        Tools.set(fWalls, copier, walls);
        put(fCurrent, swap, blue);
        tool.rotSet(rot);
        try {
            return Tools.placeFixed(tool, x1, y1);
        } finally {
            put(fRoom, copier, ownRoom);
            put(fStructure, copier, ownStructure);
            Tools.set(fWalls, copier, ownWalls);
            put(fCurrent, swap, ownCurrent);
            tool.rotSet(ownRot);
        }
    }

    /**
     * The saved-print placer's click, from the placer staged with the issuer's print (rebuilt from the command), the
     * blueprint it is placed as, the wall material, walls and rotation. Skipped when the blueprint can't take the print
     * or the footprint no longer fits ({@code placer.placable}: the area and every room tile).
     */
    private static boolean applyPrintPlace(CmdReader r) {
        RoomBlueprint b = Args.blueprint(r);
        PrintRecord p = PrintRecord.read(r);
        TBuilding structure = Tools.building(r.i());
        boolean walls = r.b();
        int rot = rotation(r.i());
        int x1 = r.i();
        int y1 = r.i();
        if (structure == null || p.blueprint < 0 || p.blueprint >= SETT.ROOMS().all().size()) {
            throw new IllegalStateException("SOSDET: printPlace with structure " + structure + ", print blueprint "
                    + p.blueprint);
        }
        RoomBlueprint printBlue = SETT.ROOMS().all().get(p.blueprint);
        if (!(printBlue instanceof RoomBlueprintImp) || !swappable(b, (RoomBlueprintImp) printBlue)
                || !((RoomBlueprintImp) b).constructor().usesArea()) {
            return false;
        }
        RoomBlueprintImp blue = (RoomBlueprintImp) b;
        SavedPrint print = print((RoomBlueprintImp) printBlue, p);

        Object placer = SETT.ROOMS().copy.savedPlacer;
        Class<?> c = placer.getClass();
        Field fPrint = Tools.field(c, "print");
        Field fWalls = Tools.field(c, "w");
        Object swap = PlanStores.get(Tools.field(c, "swap"), placer);
        Field fCurrent = Tools.field(swap.getClass(), "current");
        PlacableFixed tool = (PlacableFixed) PlanStores.get(Tools.field(c, "placer"), placer);
        RoomPlacer rooms = SETT.ROOMS().placement.placer;

        Object ownPrint = PlanStores.get(fPrint, placer);
        boolean ownWalls = Tools.get(fWalls, placer);
        Object ownCurrent = PlanStores.get(fCurrent, swap);
        int ownRot = tool.rot();
        TBuilding ownStructure = rooms.structure.get();
        put(fPrint, placer, print);
        Tools.set(fWalls, placer, walls);
        put(fCurrent, swap, blue);
        tool.rotSet(rot);
        rooms.structure.set(structure); // the print placer reads its wall material from the room placer
        try {
            return Tools.placeFixed(tool, x1, y1);
        } finally {
            put(fPrint, placer, ownPrint);
            Tools.set(fWalls, placer, ownWalls);
            put(fCurrent, swap, ownCurrent);
            tool.rotSet(ownRot);
            if (ownStructure != null) {
                rooms.structure.set(ownStructure);
            }
        }
    }

    /**
     * The mass copier's click, from a selection of its own (not this PC's copier, whose selection may be in use): the
     * issuer's selected tiles, centre and rotation. Every room of the selection is copied if all its tiles fit
     * ({@code CopierMass.copy}), every construction job placed where its own placer allows, with the issuer's overwrite
     * and road options.
     */
    private static boolean applyMassCopy(CmdReader r) {
        TileArea area = TileArea.read(r);
        int cx = r.i();
        int cy = r.i();
        int rot = rotation(r.i());
        boolean overwrite = r.b();
        boolean convert = r.b();
        if (area.count == 0) {
            return false;
        }

        // UICopier's source, dest and second (package-private, V71.44), new for this command
        Class<?> sourceType = Tools.field(UICopier.class, "source").getType();
        Class<?> destType = Tools.field(UICopier.class, "dest").getType();
        Class<?> secondType = Tools.field(UICopier.class, "second").getType();
        MAP_BOOLEANE source = (MAP_BOOLEANE) construct(constructor(sourceType));
        Object dest = construct(constructor(destType, sourceType), source);
        PlacableFixed second = (PlacableFixed) construct(constructor(secondType, destType), dest);
        // the corners first: Source.set grows its rectangle over every tile it is given, true or false
        source.set(area.x1, area.y1, false);
        source.set(area.x1 + area.width - 1, area.y1 + area.height - 1, false);
        for (int y = area.y1; y < area.y1 + area.height; y++) {
            for (int x = area.x1; x < area.x1 + area.width; x++) {
                if (area.is(x, y)) {
                    source.set(x, y, true);
                }
            }
        }
        second.rotSet(rot);
        second.init(cx, cy);

        Field fConvert = Tools.roadConvert();
        boolean ownOverwrite = Job.overwrite;
        boolean ownConvert = Tools.get(fConvert, SETT.JOBS().roads);
        Job.overwrite = overwrite;
        Tools.set(fConvert, SETT.JOBS().roads, convert);
        try {
            return Tools.placeFixed(second, cx - second.width() / 2, cy - second.height() / 2);
        } finally {
            Job.overwrite = ownOverwrite;
            Tools.set(fConvert, SETT.JOBS().roads, ownConvert);
        }
    }

    // ------------------------------------------------------------------ checks and game objects

    /**
     * Whether a source of blueprint {@code from} can be placed as {@code b}: the swap button's choices ({@code BSwap},
     * V71.44): the same kind of room, indoors or not alike, that the player may build.
     */
    private static boolean swappable(RoomBlueprint b, RoomBlueprintImp from) {
        if (!(b instanceof RoomBlueprintImp)) {
            return false;
        }
        RoomBlueprintImp blue = (RoomBlueprintImp) b;
        return blue.constructor() != null && from.constructor() != null && blue.constructor().canBeCopied()
                && blue.getClass() == from.getClass()
                && blue.constructor().mustBeIndoors() == from.constructor().mustBeIndoors()
                && blue.reqs.passes(FACTIONS.player());
    }

    private static int rotation(int rot) {
        if (rot < 0 || rot > 3) {
            throw new IllegalStateException("SOSDET: a copy command with rotation " + rot);
        }
        return rot;
    }

    /**
     * A print from a command's contents, through the game's own reader ({@code SavedPrint(ROOMS, Json)}, as the profile
     * file is read). Its name, check and structure are not read by the placer; the reader keeps no structure anyway.
     */
    private static SavedPrint print(RoomBlueprintImp blue, PrintRecord p) {
        JsonE j = new JsonE();
        j.addString("NAME", "SOSDET");
        j.add("ROOM", blue.key);
        j.add("STRUCTURE", "_");
        j.add("WIDTH", p.width);
        j.add("HEIGHT", p.height);
        j.add("DATA", p.cells());
        Class<?> valueType = jsonValueClass();
        Object value = construct(constructor(valueType, String.class, int.class, String.class, String.class), null, 0,
                "SOSDET print", j.toString());
        Json json = (Json) construct(constructor(Json.class, valueType), value);
        return (SavedPrint) construct(constructor(SavedPrint.class, ROOMS.class, Json.class), SETT.ROOMS(), json);
    }

    private static Class<?> jsonValueClass() {
        try {
            return Class.forName(JSON_VALUE_CLASS, false, Json.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SOSDET: no class " + JSON_VALUE_CLASS + " (game V71.44 expected)", e);
        }
    }

    /** A non-public constructor of the game, V71.44, resolved per use (a copy is a rare UI action). */
    private static Constructor<?> constructor(Class<?> c, Class<?>... params) {
        try {
            Constructor<?> k = c.getDeclaredConstructor(params);
            k.setAccessible(true);
            return k;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: no constructor of " + c.getName() + " (game V71.44 expected)", e);
        }
    }

    private static Object construct(Constructor<?> k, Object... args) {
        try {
            return k.newInstance(args);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot call " + k, e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: " + k + " failed", e.getCause());
        }
    }

    private static void put(Field f, Object o, Object v) {
        try {
            f.set(o, v);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot write " + f, e);
        }
    }
}

package sosdet.game;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import game.GAME;
import game.faction.FACTIONS;
import game.faction.FBanner;
import game.faction.Faction;
import game.faction.npc.FactionNPC;
import game.faction.player.Player;
import init.race.RACES;
import init.race.Race;
import snake2d.util.datatypes.DIR;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.misc.ACTION;
import sosdet.HumanStart;
import world.WORLD;
import world.map.regions.centre.WorldCentrePlacablity;
import world.map.road.WTRAV;

/**
 * World setup with several humans (SOS-55, plan 0005): the confirmed terrain becomes a world with a capital at each
 * human's location, as vanilla's location and finish stages make it for one player ({@code StageCapitol.generate},
 * {@code StageFinish}). Patch 17 gives the generator the other humans' locations; their realms take NPC slots 1 to N-1,
 * and {@link GameHumans#add} turns each into its human. Setup only: never inside a step, never with a session running.
 */
public final class GameWorldSetup {

    private static final String GAME_VERSION = " (SOSDET supports Songs of Syx V71, build 71.44)";
    private static final Field HUMANS_X = field(world.WorldGen.class, "sosdetX");
    private static final Field HUMANS_Y = field(world.WorldGen.class, "sosdetY");
    private static final Method BANNER_SAVE = method(FBanner.class, "save", FilePutter.class);
    private static final Method BANNER_LOAD = method(FBanner.class, "load", FileGetter.class);
    private static final ACTION NO_PRINT = () -> {
    };

    private GameWorldSetup() {
    }

    /**
     * Why a human can't start at {@code tx, ty}: the terrain, as vanilla's location stage checks it, and a land way out
     * of the capital's square ({@link #landExit}); or null.
     */
    public static String terrainProblem(int tx, int ty) {
        CharSequence p = WorldCentrePlacablity.terrain(tx - 1, ty - 1); // vanilla checks the square from (playerX-1, playerY-1)
        if (p != null) {
            return p.toString();
        }
        return landExit(tx, ty) ? null : "No road could leave a city here: it needs land beyond its square.";
    }

    /**
     * Whether the capital whose centre is {@code tx, ty} has a land way out (mod SOS-75): a good land tile on its 3x3
     * square's edge whose neighbour straight out is good land too. Vanilla's road generator ({@code GeneratorRoads})
     * opens only the city map's exits that the world's path network leaves by. Around a capital with only water beyond
     * its square, that network changes with each world built and sometimes leaves none, and the city gets no roads
     * (mod twopc run R75B3, 2026-10-26). Terrain only: the network doesn't exist yet when a location is picked.
     */
    static boolean landExit(int tx, int ty) {
        for (int y = ty - 1; y <= ty + 1; y++) {
            for (int x = tx - 1; x <= tx + 1; x++) {
                if (!WTRAV.isGoodLandTile(x, y)) {
                    continue;
                }
                for (DIR d : DIR.ORTHO) {
                    int ox = x + d.x();
                    int oy = y + d.y();
                    boolean outside = Math.abs(ox - tx) == 2 || Math.abs(oy - ty) == 2;
                    if (outside && WTRAV.isGoodLandTile(ox, oy)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Builds the world around {@code humans} on the confirmed terrain; {@code humans[0]} is slot 0's (this PC's, the
     * host's). The caller then saves, and every PC loads that save before step 0.
     */
    public static void build(HumanStart[] humans) {
        if (!WORLD.GEN().hasGeneratedTerrain) {
            throw new IllegalStateException("SOSDET: the world is built on a confirmed terrain; none is generated");
        }
        if (GameHumans.get().slots().length != 1) {
            throw new IllegalStateException("SOSDET: the world is built once, with no humans but slot 0's (humans "
                    + java.util.Arrays.toString(GameHumans.get().slots()) + ")");
        }
        int n = humans.length;
        Race[] races = new Race[n];
        for (int i = 0; i < n; i++) {
            races[i] = race(humans[i].race);
            String p = terrainProblem(humans[i].tx, humans[i].ty);
            if (p != null) {
                throw new IllegalArgumentException("SOSDET: human " + i + " can't start at " + humans[i].tx + ","
                        + humans[i].ty + ": " + p);
            }
        }
        Player host = FACTIONS.player();
        set(host, humans[0], races[0]);
        int[] xs = new int[n - 1];
        int[] ys = new int[n - 1];
        for (int i = 1; i < n; i++) {
            xs[i - 1] = humans[i].tx;
            ys[i - 1] = humans[i].ty;
        }
        // the game's "other faction" (invaders' banner) can't be a human: it moves past the humans' slots
        FACTIONS.otherFactionSet((FactionNPC) FACTIONS.getByIndex(n));
        try {
            HUMANS_X.set(WORLD.GEN(), xs);
            HUMANS_Y.set(WORLD.GEN(), ys);
            generate(humans[0].tx, humans[0].ty);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot set WorldGen.sosdetX/Y" + GAME_VERSION, e);
        } finally {
            try {
                HUMANS_X.set(WORLD.GEN(), new int[0]);
                HUMANS_Y.set(WORLD.GEN(), new int[0]);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("SOSDET: cannot clear WorldGen.sosdetX/Y" + GAME_VERSION, e);
            }
        }
        for (int slot = 1; slot < n; slot++) {
            Faction f = FACTIONS.getByIndex(slot);
            if (!(f instanceof FactionNPC) || f.capitolRegion() == null
                    || f.capitolRegion() != WORLD.REGIONS().getByIndex(slot)) {
                throw new IllegalStateException("SOSDET: human " + slot + "'s realm is not at its location (slot " + slot
                        + ": " + f + ")");
            }
            Player p = GameHumans.get().add(slot);
            set(p, humans[slot], races[slot]);
            p.credits().set(host.credits().getD()); // every player starts with the same (mod spec §5)
        }
        finish(humans[0].tx, humans[0].ty);
    }

    /** This PC's player's banner in the game's save layout, for the mod to send to the PC that builds the world. */
    public static byte[] bannerBytes() {
        GameCodec codec = new GameCodec();
        FilePutter out = codec.hashBegin();
        invoke(BANNER_SAVE, FACTIONS.player().banner(), out);
        return codec.written();
    }

    /** {@code StageCapitol.clear} and {@code StageCapitol.generate}, without the rendering progress. */
    private static void generate(int tx, int ty) {
        WORLD.BUILDINGS().saver().clear();
        WORLD.ROADS().saver().clear();
        WORLD.PATH().saver().clear();
        WORLD.ENTITIES().saver().clear();
        WORLD.REGIONS().saver().clear();
        WORLD.RD().saver().clear();
        WORLD.GEN().playerX = tx;
        WORLD.GEN().playerY = ty;
        WORLD.OVERLAY().regNames.active.set(false);
        WORLD.BUILDINGS().saver().generate(NO_PRINT);
        WORLD.REGIONS().saver().generate(NO_PRINT);
        WORLD.ROADS().saver().generate(NO_PRINT);
        WORLD.PATH().saver().generate(NO_PRINT);
        WORLD.ENTITIES().saver().generate(NO_PRINT);
        WORLD.RD().saver().generate(NO_PRINT);
        WORLD.MINIMAP().repaint();
        WORLD.initBeforePlay();
        WORLD.OVERLAY().regNames.active.set(true);
    }

    /** {@code StageFinish}'s start: the NPCs primed, the armies, the host's city at its capital. */
    private static void finish(int tx, int ty) {
        GAME.factions().prime();
        WORLD.ARMIES().saver().generate(NO_PRINT);
        WORLD.GEN().isDone = true;
        GAME.s().CreateFromWorldMap(tx - 1, ty - 1, false);
    }

    private static void set(Player p, HumanStart h, Race race) {
        p.setRace(race);
        p.name.clear().add(h.name);
        byte[] banner = h.banner();
        if (banner != null) {
            GameCodec codec = new GameCodec();
            invoke(BANNER_LOAD, p.banner(), codec.reader(padded(banner)));
        }
        if (p.capitolRegion() != null) {
            p.capitolRegion().info.name().clear().add(h.name);
        }
    }

    /** Vanilla's FileGetter.d refuses a buffer's last 8 bytes; zeros after the banner keep its last double readable. */
    private static byte[] padded(byte[] b) {
        return java.util.Arrays.copyOf(b, b.length + 8);
    }

    private static Race race(String key) {
        for (int i = 0; i < RACES.all().size(); i++) {
            if (RACES.all().get(i).key.equals(key)) {
                return RACES.all().get(i);
            }
        }
        throw new IllegalArgumentException("SOSDET: no race " + key);
    }

    private static void invoke(Method m, Object target, Object arg) {
        try {
            m.invoke(target, arg);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot call FBanner." + m.getName() + GAME_VERSION, e);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof IOException) {
                throw new IllegalArgumentException("SOSDET: not a banner", t);
            }
            throw new IllegalStateException("SOSDET: FBanner." + m.getName(), t);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            return owner.getField(name); // patch-added and public (patch 17); the engine compiles against the vanilla jar
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no field " + owner.getSimpleName() + "." + name + " in this game build"
                    + GAME_VERSION, e);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?> param) {
        try {
            Method m = owner.getDeclaredMethod(name, param);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no method " + owner.getSimpleName() + "." + name + GAME_VERSION, e);
        }
    }
}

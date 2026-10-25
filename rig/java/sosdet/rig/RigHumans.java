package sosdet.rig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.npc.FactionNPC;
import snake2d.util.file.FileGetter;
import sosdet.game.GameHash;
import sosdet.game.GameHumans;
import sosdet.game.GameResync;
import sosdet.Engine;
import sosdet.HumanStart;
import world.WORLD;

/**
 * The rig's human factions (SOS-46, plan 0004): {@code makehuman} turns NPCs into humans at setup, {@code humans}
 * reports them, {@code savegame} saves, and {@code comparesaves} compares two saves section by section, which is how
 * a save and load round trip is checked.
 */
final class RigHumans {

    private RigHumans() {
    }

    /**
     * {@code auto N}: the first N active NPCs that are not the "other faction"; otherwise a comma list of slots. The
     * game is saved and loaded before a session, as every PC then loads it.
     */
    static String make(String which) {
        int[] slots;
        if (which.startsWith("auto ")) {
            int n = Integer.parseInt(which.substring(5).trim());
            slots = new int[n];
            int k = 0;
            for (FactionNPC f : FACTIONS.NPCs()) {
                if (k < n && f != FACTIONS.otherFaction()) {
                    slots[k++] = f.index();
                }
            }
            if (k < n) {
                throw new IllegalArgumentException("SOSDET rig: only " + k + " NPCs can become humans");
            }
        } else {
            String[] s = which.split(",");
            slots = new int[s.length];
            for (int i = 0; i < s.length; i++) {
                slots[i] = Integer.parseInt(s[i].trim());
            }
        }
        java.util.Arrays.sort(slots); // humans join in slot order (GameHumans.add)
        for (int slot : slots) {
            GameHumans.get().add(slot);
        }
        return state();
    }

    /**
     * {@code <seed> auto <n>}: a fresh terrain from the seed on this game's map, then a world built around n humans
     * (SOS-55): human 0 at the first free location from the map's centre outwards, the others 7 to 15 tiles from it where
     * the terrain allows, so the 6-15-tile route rule is exercised. Every human takes this game's player's race. Reports the humans, their
     * locations and a path check between every two of them 6 to 15 tiles apart (mod spec §5).
     */
    static String newWorld(String args) {
        String[] a = args.trim().split("\\s+");
        if (a.length != 3 || !a[1].equals("auto")) {
            throw new IllegalArgumentException("SOSDET rig: newworld <seed> auto <n>, not " + args);
        }
        int n = Integer.parseInt(a[2]);
        // the rig builds in the game it loaded, unlike the mod's fresh game: its NPCs and armies go while they still exist
        while (FACTIONS.NPCs().size() > 0) {
            FACTIONS.remove(FACTIONS.NPCs().get(0), false);
        }
        while (FACTIONS.player().armies().all().size() > 0) {
            FACTIONS.player().armies().all().get(0).disband();
        }
        WORLD.GEN().seed = Integer.parseInt(a[0]);
        WORLD.GEN().map = null;
        snake2d.util.misc.ACTION none = () -> {
        };
        WORLD.TERRAIN().saver().generate(none);
        WORLD.LANDMARKS().saver().generate(none);
        WORLD.GEN().hasGeneratedTerrain = true;
        int[] xs = new int[n];
        int[] ys = new int[n];
        int cx = WORLD.TWIDTH() / 2;
        int cy = WORLD.THEIGHT() / 2;
        for (int k = 0; k < n; k++) {
            boolean found = false;
            for (int pass = 0; pass < 2 && !found; pass++) // 7 to 15 tiles from human 0 first, then anywhere
            for (int rad = 0; rad < WORLD.TWIDTH() && !found; rad++) {
                for (int dy = -rad; dy <= rad && !found; dy++) {
                    for (int dx = -rad; dx <= rad && !found; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) != rad) {
                            continue; // the ring at this radius only
                        }
                        int x = (k == 0 ? cx : xs[0]) + dx;
                        int y = (k == 0 ? cy : ys[0]) + dy;
                        double d = Math.hypot(x - xs[0], y - ys[0]);
                        if (k > 0 && pass == 0 && (d < 7 || d > 15)) {
                            continue;
                        }
                        if (Engine.locationProblem(x, y, java.util.Arrays.copyOf(xs, k), java.util.Arrays.copyOf(ys, k)) == null) {
                            xs[k] = x;
                            ys[k] = y;
                            found = true;
                        }
                    }
                }
            }
            if (!found) {
                throw new IllegalStateException("SOSDET rig: no location for human " + k + " near " + xs[0] + "," + ys[0]);
            }
        }
        HumanStart[] starts = new HumanStart[n];
        String race = FACTIONS.player().race().key;
        for (int k = 0; k < n; k++) {
            starts[k] = new HumanStart(race, "Human " + k, null, xs[k], ys[k]);
        }
        Engine.buildWorld(starts);
        StringBuilder b = new StringBuilder(state()).append(",\"locations\":[");
        for (int k = 0; k < n; k++) {
            b.append(k == 0 ? "" : ",").append('[').append(xs[k]).append(',').append(ys[k]).append(']');
        }
        b.append("],\"routes\":[");
        boolean first = true;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = Math.hypot(xs[i] - xs[j], ys[i] - ys[j]);
                if (d < 6 || d > 15) {
                    continue;
                }
                boolean ok = WORLD.PATH().path(xs[i], ys[i], xs[j], ys[j]) != null;
                b.append(first ? "" : ",").append(String.format(Locale.ROOT, "{\"a\":%d,\"b\":%d,\"tiles\":%.1f,\"ok\":%b}",
                        i, j, d, ok));
                first = false;
            }
        }
        return b.append(']').toString();
    }

    /** The engine's world hash and every human faction, as JSON. */
    static String state() {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "\"world\":\"%016x\",\"active\":%d,\"humans\":[",
                GameHash.worldHash(), FACTIONS.active().size()));
        int[] slots = GameHumans.get().slots();
        for (int i = 0; i < slots.length; i++) {
            Faction f = FACTIONS.getByIndex(slots[i]);
            b.append(i == 0 ? "" : ",");
            b.append(String.format(Locale.ROOT, "{\"slot\":%d,\"name\":\"%s\",\"race\":\"%s\",\"capitol\":%d,\"regions\":%d,"
                    + "\"armies\":%d,\"credits\":%.0f,\"active\":%b}", f.index(), clean(f.name.toString()),
                    f.race().key, f.capitolRegion() == null ? -1 : f.capitolRegion().index(), f.realm().regions(),
                    f.armies().all().size(), f.credits().getD(), FACTIONS.active().contains(f)));
        }
        return b.append("]").toString();
    }

    static String save(Path file) {
        String s = state();
        GameResync.save(file);
        return String.format(Locale.ROOT, "\"file\":\"%s\",\"bytes\":%d,%s", clean(file.getFileName().toString()),
                file.toFile().length(), s);
    }

    /** Section by section: the ones that differ, and where each first does. */
    static String compare(Path a, Path b) throws IOException {
        Map<String, byte[]> sa = sections(a);
        Map<String, byte[]> sb = sections(b);
        StringBuilder differ = new StringBuilder();
        int same = 0;
        for (Map.Entry<String, byte[]> e : sa.entrySet()) {
            byte[] x = e.getValue();
            byte[] y = sb.get(e.getKey());
            if (y == null) {
                differ.append(differ.length() == 0 ? "" : ",").append("{\"key\":\"").append(e.getKey()).append("\",\"only\":\"a\"}");
                continue;
            }
            int at = firstDifference(x, y);
            if (at < 0) {
                same++;
                continue;
            }
            differ.append(differ.length() == 0 ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"key\":\"%s\",\"a\":%d,\"b\":%d,\"first\":%d}", e.getKey(), x.length, y.length, at));
        }
        for (String k : sb.keySet()) {
            if (!sa.containsKey(k)) {
                differ.append(differ.length() == 0 ? "" : ",").append("{\"key\":\"").append(k).append("\",\"only\":\"b\"}");
            }
        }
        return String.format(Locale.ROOT, "\"sections\":%d,\"same\":%d,\"differ\":[%s]", sa.size(), same, differ);
    }

    private static int firstDifference(byte[] x, byte[] y) {
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            if (x[i] != y[i]) {
                return i;
            }
        }
        return x.length == y.length ? -1 : n;
    }

    private static String clean(String s) {
        return s.replace("\\", "/").replace("\"", "'");
    }

    /** Every section's bytes by key, read as {@code GameSaver.load} reads the file. */
    private static Map<String, byte[]> sections(Path file) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        FileGetter f = new FileGetter(file, true);
        int spec = f.i();
        f.setPosition(f.getPosition() + spec); // GameSpec
        for (int list = 0; list < 2; list++) { // GameSaver's "before" list, then "all"
            int am = f.i();
            for (int i = 0; i < am; i++) {
                String k = f.chars();
                byte[] bytes = new byte[f.i()];
                f.bs(bytes);
                m.put(k, bytes);
            }
        }
        return m;
    }
}

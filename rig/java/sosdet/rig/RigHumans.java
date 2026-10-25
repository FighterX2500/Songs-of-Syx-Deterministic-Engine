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

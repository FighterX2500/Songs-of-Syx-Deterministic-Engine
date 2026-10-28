package sosdet.rig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import game.GAME;

/**
 * Debugging (SOS-82): every field reachable from the world's roots, by reflection, by path. A dump writes one
 * "path = value" line each, so two dumps of one PC, before and after a load, show the state a load resets. A snapshot
 * keeps the primitive values (and small primitive arrays) before a load, and {@link #restore} writes the floating-point
 * ones under some path prefixes back after it, to find which of them a PC that didn't load still has. The game's rendering and UI
 * packages are left out.
 */
final class StateDump {

    private static final int MAX_ELEMENTS = 4096;
    /** Primitive arrays up to this length are kept whole in a snapshot; longer ones by hash only. */
    private static final int MAX_KEPT = 1 << 16;
    private static final String[] SKIP = {"java.", "javax.", "jdk.", "sun.", "com.sun.", "view.", "init.sprite.",
        "snake2d.util.sprite.", "util.gui.", "util.rendering.", "util.spritecomposer.", "org.lwjgl.", "com.codedisaster."};

    /** Called for every primitive field and primitive array the walk reaches. */
    private interface Visitor {
        void primitive(String path, Object owner, Field f) throws IOException, IllegalAccessException;

        void array(String path, Object array) throws IOException;
    }

    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private final ArrayDeque<Object[]> queue = new ArrayDeque<>();
    private final Visitor visitor;

    private StateDump(Visitor visitor) {
        this.visitor = visitor;
    }

    /** Writes the dump of the world, the factions, the events and the raiders to {@code file}. */
    static void write(Path file) {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new StateDump(new Visitor() {
                @Override
                public void primitive(String path, Object owner, Field f) throws IOException, IllegalAccessException {
                    line(w, path, String.valueOf(f.get(owner)));
                }

                @Override
                public void array(String path, Object array) throws IOException {
                    line(w, path, "len " + Array.getLength(array) + " hash " + primitiveHash(array));
                }
            }).walk();
            boosts(w);
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("SOSDET rig: cannot write the state dump " + file, e);
        }
    }

    /** The primitive values and primitive arrays reachable now, by path; arrays copied, long ones by hash. */
    static Map<String, Object> snapshot() {
        Map<String, Object> m = new HashMap<>(1 << 21);
        try {
            new StateDump(new Visitor() {
                @Override
                public void primitive(String path, Object owner, Field f) throws IllegalAccessException {
                    m.put(path, f.get(owner));
                }

                @Override
                public void array(String path, Object array) {
                    int n = Array.getLength(array);
                    if (n <= MAX_KEPT) {
                        Object copy = Array.newInstance(array.getClass().getComponentType(), n);
                        System.arraycopy(array, 0, copy, 0, n);
                        m.put(path, copy);
                    } else {
                        m.put(path, "hash " + primitiveHash(array));
                    }
                }
            }).walk();
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("SOSDET rig: cannot take the state snapshot", e);
        }
        return m;
    }

    /**
     * Writes back the snapshot's values under any of {@code prefixes} where they differ now, and returns how many it
     * wrote per prefix, with the long arrays that differ and can't be written back.
     */
    static String restore(Map<String, Object> snap, String[] prefixes) {
        Map<String, int[]> counts = new TreeMap<>();
        List<String> notBack = new ArrayList<>();
        try {
            new StateDump(new Visitor() {
                @Override
                public void primitive(String path, Object owner, Field f) throws IllegalAccessException {
                    String p = match(path, prefixes);
                    if (p == null || !snap.containsKey(path) || !floating(f.getType())) {
                        return; // only timers and accumulators: a written-back count or index breaks its structure
                    }
                    Object was = snap.get(path);
                    if (!was.equals(f.get(owner))) {
                        f.set(owner, was);
                        counts.computeIfAbsent(p, k -> new int[1])[0]++;
                    }
                }

                @Override
                public void array(String path, Object array) {
                    String p = match(path, prefixes);
                    if (p == null || !snap.containsKey(path) || !floating(array.getClass().getComponentType())) {
                        return;
                    }
                    Object was = snap.get(path);
                    if (was instanceof String) {
                        if (!was.equals("hash " + primitiveHash(array))) {
                            notBack.add(path);
                        }
                        return;
                    }
                    if (Array.getLength(was) == Array.getLength(array) && primitiveHash(was) != primitiveHash(array)) {
                        System.arraycopy(was, 0, array, 0, Array.getLength(array));
                        counts.computeIfAbsent(p, k -> new int[1])[0]++;
                    }
                }
            }).walk();
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("SOSDET rig: cannot restore the state snapshot", e);
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            b.append(e.getKey()).append(": ").append(e.getValue()[0]).append(" written back\n");
        }
        for (String s : notBack) {
            b.append("long array differs, not written back: ").append(s).append('\n');
        }
        return b.toString();
    }

    /** Every recipe booster's value for every NPC faction: what the NPCs' stockpile rates are made of. */
    private static void boosts(BufferedWriter w) throws IOException {
        for (game.faction.npc.FactionNPC f : game.faction.FACTIONS.NPCs()) {
            for (settlement.recipe.Recipe r : settlement.main.SETT.RECIPES().all()) {
                boosts(w, "BOOST f" + f.index() + " r" + r.index + " out", r.bo, f);
                for (int i = 0; i < r.ins.length; i++) {
                    if (r.ins[i].boost != null) {
                        boosts(w, "BOOST f" + f.index() + " r" + r.index + " in" + i, r.ins[i].boost, f);
                    }
                }
            }
        }
    }

    private static void boosts(BufferedWriter w, String path, game.boosting.Boostable bo,
            game.faction.npc.FactionNPC f) throws IOException {
        for (int i = 0; i < bo.all().size(); i++) {
            game.boosting.Booster b = bo.all().get(i);
            line(w, path + " " + i + " " + b.getClass().getName() + " " + b.info.name, String.valueOf(b.get(f)));
        }
    }

    private static boolean floating(Class<?> t) {
        return t == double.class || t == float.class;
    }

    private static String match(String path, String[] prefixes) {
        for (String p : prefixes) {
            if (path.startsWith(p)) {
                return p;
            }
        }
        return null;
    }

    private void walk() throws IOException, ReflectiveOperationException {
        root("WORLD", staticField(world.WORLD.class, "w"));
        root("RD", staticField(world.region.RD.class, "self"));
        root("FACTIONS", GAME.factions());
        root("EVENTS", GAME.events());
        root("RAIDING", GAME.raiders());
        while (!queue.isEmpty()) {
            Object[] e = queue.poll();
            visit((String) e[0], e[1]);
        }
    }

    private static Object staticField(Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private void root(String path, Object o) {
        if (o != null && seen.put(o, Boolean.TRUE) == null) {
            queue.add(new Object[] {path, o});
        }
    }

    private void visit(String path, Object o) throws IOException, IllegalAccessException {
        Class<?> c = o.getClass();
        if (c.isArray()) {
            int n = Array.getLength(o);
            for (int i = 0; i < n && i < MAX_ELEMENTS; i++) {
                value(path + "[" + i + "]", Array.get(o, i));
            }
            return;
        }
        for (Field f : fields(c)) {
            try {
                f.setAccessible(true);
            } catch (RuntimeException ex) {
                continue; // a field the walk can't reach: left out
            }
            String p = path + "." + f.getName();
            if (f.getType().isPrimitive()) {
                visitor.primitive(p, o, f);
            } else {
                value(p, f.get(o));
            }
        }
    }

    private void value(String path, Object v) throws IOException {
        if (v == null) {
            return;
        }
        Class<?> c = v.getClass();
        if (v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof CharSequence
                || c.isEnum()) {
            return; // boxed values and strings: only the primitive fields are compared
        }
        if (c.isArray() && c.getComponentType().isPrimitive()) {
            visitor.array(path, v);
            return;
        }
        if (!c.isArray() && skipped(c.getName())) {
            return;
        }
        if (seen.put(v, Boolean.TRUE) == null) {
            queue.add(new Object[] {path, v});
        }
    }

    private static int primitiveHash(Object a) {
        if (a instanceof int[]) return Arrays.hashCode((int[]) a);
        if (a instanceof long[]) return Arrays.hashCode((long[]) a);
        if (a instanceof double[]) return Arrays.hashCode((double[]) a);
        if (a instanceof float[]) return Arrays.hashCode((float[]) a);
        if (a instanceof short[]) return Arrays.hashCode((short[]) a);
        if (a instanceof byte[]) return Arrays.hashCode((byte[]) a);
        if (a instanceof boolean[]) return Arrays.hashCode((boolean[]) a);
        return Arrays.hashCode((char[]) a);
    }

    private static boolean skipped(String name) {
        for (String s : SKIP) {
            if (name.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private static List<Field> fields(Class<?> c) {
        List<Field> all = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            List<Field> own = new ArrayList<>();
            for (Field f : k.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    own.add(f);
                }
            }
            all.addAll(0, own);
        }
        return all;
    }

    private static void line(BufferedWriter out, String path, String value) throws IOException {
        out.write(path);
        out.write(" = ");
        out.write(value);
        out.newLine();
    }
}

package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Checks the built mod folder against what the game's launcher and SOS-25 require: the layout, the
 * version in _Info.txt and the jar, and a jar that holds only engine classes and classes compiled
 * from the patch set (nothing else may shadow the game).
 */
class ModFolderTest {

    private static final Path MOD_DIR = Paths.get(property("sosdet.modDir"));
    private static final Path JAR = MOD_DIR.resolve("V71/script/SOSDET.jar");
    private static final String VERSION = property("sosdet.version");

    private static String property(String key) {
        String value = System.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("SOSDET: system property " + key + " not set; run the tests through Gradle");
        }
        return value;
    }

    @Test
    void modFolderHasTheLauncherLayout() {
        assertTrue(Files.isRegularFile(MOD_DIR.resolve("_Info.txt")), "_Info.txt");
        assertTrue(Files.isRegularFile(JAR), "V71/script/SOSDET.jar");
    }

    @Test
    void infoFileCarriesTheBuildVersion() throws IOException {
        String info = new String(Files.readAllBytes(MOD_DIR.resolve("_Info.txt")), StandardCharsets.UTF_8);
        assertTrue(info.contains("VERSION: \"" + VERSION + "\","), info);
        assertTrue(info.contains("NAME: \"SOSDET\","), info);
    }

    @Test
    void engineReportsTheJarVersion() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {JAR.toUri().toURL()}, null)) {
            Method version = loader.loadClass("sosdet.Sosdet").getMethod("version");
            assertEquals(VERSION, version.invoke(null));
        }
    }

    @Test
    void jarHoldsOnlyEngineClassesAndPatchedGameClasses() throws IOException {
        Set<String> patched = patchedGameClasses();
        List<String> stray = new ArrayList<>();
        try (JarFile jar = new JarFile(JAR.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/") || name.startsWith("sosdet/")) {
                    continue;
                }
                if (!patched.contains(name)) {
                    stray.add(name);
                }
            }
        }
        assertEquals(Collections.emptyList(), stray, "classes that are neither engine nor patch set");
    }

    private static Set<String> patchedGameClasses() throws IOException {
        Path dir = Paths.get(property("sosdet.gamePatchClasses"));
        Set<String> names = new TreeSet<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile)
                    .forEach(p -> names.add(dir.relativize(p).toString().replace('\\', '/')));
        }
        return names;
    }
}

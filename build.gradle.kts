// SOSDET build (SOS-26, engine ADR 0001).
//
// Reads the game from SOS_GAME_DIR and never copies game files into the
// repository. Output: build/mod/SOSDET, a Songs of Syx mod folder whose jar
// holds the engine's classes and the game classes compiled from the patch set.
// The launcher puts a mod's script jar ahead of SongsOfSyx.jar, so those
// classes replace the game's own (SOS-25).

import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    java
}

version = "0.1.0"

repositories {
    mavenCentral()
}

// ---------------------------------------------------------------- the game

/** SHA-256 of SongsOfSyx.jar, V71 build 71.44: the one game build SOSDET supports (requirements §2). */
val pinnedGameJarSha256 = "fe0137c05408e5ee9e31f06fc44fe37a5d4372d9c985b280f0852d1347c0224b"

fun gameDir(): File {
    val path = providers.environmentVariable("SOS_GAME_DIR").orNull
        ?: throw GradleException("SOSDET: set SOS_GAME_DIR to the Songs of Syx folder (the one that holds SongsOfSyx.jar)")
    return file(path)
}

fun gameJar(): File = gameDir().resolve("SongsOfSyx.jar")

/** Game packages with source, and the class level the shipped jar was compiled at (ADR 0001). */
val sourcePackages = listOf("snake2d", "integrations", "cutscene", "game", "init", "launcher",
    "menu", "script", "settlement", "util", "view", "world")

fun releaseOf(sourcePath: String): String =
    if (sourcePath.startsWith("snake2d/") || sourcePath.startsWith("src/integrations/")) "8" else "16"

val verifyGameJar by tasks.registering {
    group = "verification"
    description = "Checks that SOS_GAME_DIR holds the pinned game build."
    doLast {
        val jar = gameJar()
        if (!jar.isFile) throw GradleException("SOSDET: no SongsOfSyx.jar in $jar")
        val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
        val sha = digest.joinToString("") { "%02x".format(it) }
        if (sha != pinnedGameJarSha256) {
            throw GradleException("SOSDET: $jar is not Songs of Syx V71 build 71.44 (SHA-256 $sha, expected $pinnedGameJarSha256)")
        }
    }
}

val gameSourcesDir = layout.buildDirectory.dir("game-sources")

val gameSources by tasks.registering(Sync::class) {
    description = "Copies the game's shipped source (unpacked folder, or Steam's sources jar) to build/game-sources."
    dependsOn(verifyGameJar)
    from({
        val folder = gameDir().resolve("info/SongsOfSyx-sources")
        if (folder.isDirectory) folder else zipTree(gameDir().resolve("info/SongsOfSyx-sources.jar"))
    })
    include("**/*.java")
    into(gameSourcesDir)
}

// ---------------------------------------------------------------- ECJ

val ecjTool: Configuration by configurations.creating

dependencies {
    ecjTool("org.eclipse.jdt:ecj:3.40.0")
    compileOnly(files({ gameJar() }))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * Compiles game source with ECJ 3.40.0 and the ADR 0001 flags plus -proc:none, inside the build JVM
 * (a JDK: --release needs its ct.sym). -proc:none matters: without it ECJ's annotation processing
 * pass reorders bridge methods in 26 classes, and the rebuild is no longer byte-identical.
 */
fun ecj(release: String, classpath: List<File>, sources: List<File>, dest: File) {
    if (sources.isEmpty()) return
    dest.mkdirs()
    val args = listOf("--release", release, "-g", "-preserveAllLocals", "-encoding", "UTF-8", "-nowarn", "-proc:none",
        "-cp", classpath.joinToString(File.pathSeparator) { it.absolutePath }, "-d", dest.absolutePath) +
        sources.map { it.absolutePath }
    val log = StringWriter()
    // Platform loader as parent: ECJ needs javax.lang.model (module java.compiler), not Gradle's classes.
    URLClassLoader(ecjTool.files.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
        val compiler = loader.loadClass("org.eclipse.jdt.core.compiler.batch.BatchCompiler")
        val progress = loader.loadClass("org.eclipse.jdt.core.compiler.CompilationProgress")
        val compile = compiler.getMethod("compile", Array<String>::class.java,
            PrintWriter::class.java, PrintWriter::class.java, progress)
        val out = PrintWriter(log)
        val ok = compile.invoke(null, args.toTypedArray(), out, out, null) as Boolean
        out.flush()
        if (!ok) throw GradleException("SOSDET: ECJ failed at --release $release:\n$log")
    }
}

/**
 * The .java files under [root] in the order ECJ must see them: folder by folder, names compared
 * ignoring case (the order Windows lists them). ECJ's output depends on the order of its source
 * files (the order of bridge methods); this order rebuilds the shipped jar byte for byte.
 */
fun javaFilesIn(root: File): List<File> {
    val byFolders = Comparator<List<String>> { a, b ->
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i], ignoreCase = true)
            if (c != 0) return@Comparator c
        }
        a.size - b.size
    }
    return root.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }
        .sortedWith(compareBy(byFolders) { it.relativeTo(root).invariantSeparatorsPath.split('/') })
        .toList()
}

fun classesUnder(dir: File): Map<String, File> =
    if (!dir.isDirectory) emptyMap()
    else dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
        .associateBy { it.relativeTo(dir).invariantSeparatorsPath }

// ---------------------------------------------------------------- the patch set

/** patches/ by default; -Psosdet.patches=<dir> points the build at another set (test fixtures). */
val patchesDir = layout.projectDirectory.dir(providers.gradleProperty("sosdet.patches").orElse("patches"))

fun patchFiles(): List<File> =
    patchesDir.get().asFile.listFiles { f -> f.name.endsWith(".patch") }?.sortedBy { it.name } ?: emptyList()

/** Game files a patch changes, relative to the source root (its "+++ b/..." lines). */
fun touchedFiles(patch: File): List<String> = patch.readLines()
    .filter { it.startsWith("+++ ") }
    .map { line ->
        val path = line.removePrefix("+++ ").substringBefore('\t').trim()
        if (!path.startsWith("b/")) {
            throw GradleException("SOSDET: ${patch.name}: '$path'. Patches change existing game files only, with a/ b/ paths relative to the game source")
        }
        path.removePrefix("b/")
    }

val patchedSourceDir = layout.buildDirectory.dir("patched-src")

/**
 * Copies the game files [patches] touch from the game source to [dest] and applies the patches there.
 * [ownedElsewhere] maps game files another patch set already changes; each game file belongs to one patch.
 */
fun applyPatchSet(patches: List<File>, dest: File, ownedElsewhere: Map<String, String> = emptyMap()) {
    val src = gameSourcesDir.get().asFile
    dest.deleteRecursively()
    dest.mkdirs()
    val owner = sortedMapOf<String, String>()
    owner.putAll(ownedElsewhere)
    for (patch in patches) {
        for (path in touchedFiles(patch)) {
            owner[path]?.let {
                throw GradleException("SOSDET: $path is changed by both $it and ${patch.name}; each game file belongs to exactly one patch")
            }
            owner[path] = patch.name
            val from = src.resolve(path)
            if (!from.isFile) throw GradleException("SOSDET: ${patch.name} changes $path, which is not in the game source")
            from.copyTo(dest.resolve(path))
        }
    }
    for (patch in patches) {
        val process = ProcessBuilder("git", "apply", "--whitespace=nowarn", patch.absolutePath)
            .directory(dest).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException("SOSDET: ${patch.name} no longer applies to the game source:\n$output")
        }
    }
}

val applyPatches by tasks.registering {
    description = "Copies the game files the patch set touches to build/patched-src and applies the patches."
    dependsOn(gameSources)
    inputs.files(fileTree(patchesDir) { include("*.patch") })
    inputs.dir(gameSourcesDir)
    outputs.dir(patchedSourceDir)
    doLast {
        applyPatchSet(patchFiles(), patchedSourceDir.get().asFile)
    }
}

/** Source paths (relative) of the patched game files, by release level. */
fun patchedSources(root: File): Map<String, List<File>> =
    javaFilesIn(root).groupBy { releaseOf(it.relativeTo(root).invariantSeparatorsPath) }

// ---------------------------------------------------------------- engine and patched game classes

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

val gamePatchClassesDir = layout.buildDirectory.dir("classes/game-patches")

val compileGamePatches by tasks.registering {
    description = "Compiles the patched game files with ECJ at the shipped class levels, against the game and the engine."
    dependsOn(applyPatches, tasks.compileJava)
    inputs.dir(patchedSourceDir)
    inputs.files(sourceSets.main.get().output.classesDirs)
    outputs.dir(gamePatchClassesDir)
    doLast {
        val out = gamePatchClassesDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val bySource = patchedSources(patchedSourceDir.get().asFile)
        val engine = sourceSets.main.get().output.classesDirs.files.toList()
        // snake2d and integrations first: patched game code at 16 may call into them.
        ecj("8", engine + gameJar(), bySource["8"].orEmpty(), out)
        ecj("16", listOf(out) + engine + gameJar(), bySource["16"].orEmpty(), out)
    }
}

val verifyUntouched by tasks.registering {
    group = "verification"
    description = "Compiles the patch set's game files unchanged and checks each class is byte-identical to the shipped one."
    dependsOn(applyPatches)
    inputs.dir(patchedSourceDir)
    val marker = layout.buildDirectory.file("verify/untouched.ok")
    outputs.file(marker)
    doLast {
        val src = gameSourcesDir.get().asFile
        val out = layout.buildDirectory.dir("verify/untouched").get().asFile
        out.deleteRecursively()
        val patched = patchedSourceDir.get().asFile
        val bySource = patchedSources(patched).mapValues { (_, files) -> files.map { src.resolve(it.relativeTo(patched)) } }
        ecj("8", listOf(gameJar()), bySource["8"].orEmpty(), out)
        ecj("16", listOf(out, gameJar()), bySource["16"].orEmpty(), out)
        val differ = ZipFile(gameJar()).use { jar ->
            classesUnder(out).filter { (name, file) ->
                val entry = jar.getEntry(name)
                entry == null || !jar.getInputStream(entry).use { it.readBytes() }.contentEquals(file.readBytes())
            }.keys
        }
        if (differ.isNotEmpty()) {
            throw GradleException("SOSDET: unchanged game files do not rebuild the shipped classes (wrong compiler or flags?): $differ")
        }
        marker.get().asFile.writeText("${classesUnder(out).size} classes identical\n")
    }
}

val fullRebuildCheck by tasks.registering {
    group = "verification"
    description = "Rebuilds the whole untouched game source with ECJ and checks it is byte-identical to the shipped jar."
    dependsOn(gameSources)
    inputs.dir(gameSourcesDir)
    val marker = layout.buildDirectory.file("verify/full-rebuild.ok")
    outputs.file(marker)
    doLast {
        val src = gameSourcesDir.get().asFile
        val work = layout.buildDirectory.dir("verify/full-rebuild").get().asFile
        work.deleteRecursively()
        val libs = work.resolve("libs")
        val out = work.resolve("classes")
        // Only the libraries from the shipped jar (LWJGL, steamworks4j, discord-rpc), not its game classes.
        copy {
            from(zipTree(gameJar())) { include("org/**", "com/**", "club/**") }
            into(libs)
        }
        ecj("8", listOf(libs), javaFilesIn(src.resolve("snake2d")), out)
        ecj("8", listOf(libs, out), javaFilesIn(src.resolve("src/integrations")), out)
        ecj("16", listOf(libs, out), sourcePackages.drop(2).flatMap { javaFilesIn(src.resolve(it)) }, out)

        val rebuilt = classesUnder(out)
        val problems = mutableListOf<String>()
        ZipFile(gameJar()).use { jar ->
            val shipped = jar.entries().asSequence().map { it.name }
                .filter { it.endsWith(".class") && it.substringBefore('/') in sourcePackages }.toSet()
            for (name in shipped.sorted()) {
                val file = rebuilt[name]
                when {
                    file == null -> problems += "missing: $name"
                    !jar.getInputStream(jar.getEntry(name)).use { it.readBytes() }.contentEquals(file.readBytes()) -> problems += "differs: $name"
                }
            }
            (rebuilt.keys - shipped).sorted().forEach { problems += "extra: $it" }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("SOSDET: the rebuild of the untouched source doesn't match the shipped jar:\n" + problems.joinToString("\n"))
        }
        marker.get().asFile.writeText("${rebuilt.size} classes byte-identical to the shipped jar\n")
    }
}

// ---------------------------------------------------------------- the mod

tasks.jar {
    description = "The mod's script jar: engine classes plus every class compiled from the patched game files."
    dependsOn(compileGamePatches)
    archiveFileName.set("SOSDET.jar")
    from(gamePatchClassesDir)
    manifest {
        attributes("Implementation-Title" to "SOSDET", "Implementation-Version" to project.version)
    }
    doFirst {
        // Engine code lives in package sosdet; anything else in its output would shadow a game class by accident.
        sourceSets.main.get().output.classesDirs.forEach { dir ->
            classesUnder(dir).keys.filterNot { it.startsWith("sosdet/") }.takeIf { it.isNotEmpty() }?.let {
                throw GradleException("SOSDET: engine classes outside package sosdet: $it")
            }
        }
    }
}

val modInfo by tasks.registering {
    description = "Writes the mod's _Info.txt."
    val file = layout.buildDirectory.file("mod-info/_Info.txt")
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.file(file)
    doLast {
        file.get().asFile.writeText(
            """
            VERSION: "$version",
            NAME: "SOSDET",
            DESC: "Deterministic simulation engine for Songs of Syx V71 (lockstep battles).",
            AUTHOR: "FighterX2500",
            INFO: "https://github.com/FighterX2500/SOSDET",
            """.trimIndent() + "\n")
    }
}

val modDir = layout.buildDirectory.dir("mod/SOSDET")

val assembleMod by tasks.registering(Sync::class) {
    group = "build"
    description = "Assembles the mod folder build/mod/SOSDET (_Info.txt, V71/script/SOSDET.jar)."
    from(modInfo)
    from(tasks.jar) { into("V71/script") }
    into(modDir)
}

tasks.assemble {
    dependsOn(assembleMod)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(assembleMod)
    inputs.dir(modDir)
    systemProperty("sosdet.modDir", modDir.get().asFile.absolutePath)
    systemProperty("sosdet.gamePatchClasses", gamePatchClassesDir.get().asFile.absolutePath)
    systemProperty("sosdet.version", project.version.toString())
}

tasks.check {
    dependsOn(verifyUntouched, fullRebuildCheck)
}

// ---------------------------------------------------------------- the test rig (rig/; never in a release)
//
// A second mod, SOSDET-rig: the harness that drives a game copy (rig/java) and the rig-only patches
// (rig/patches, for example autoload). rig/rig.py runs several copies with both mods and compares them.

val rigPatchesDir = layout.projectDirectory.dir("rig/patches")
val rigPatchedSourceDir = layout.buildDirectory.dir("rig/patched-src")
val rigPatchClassesDir = layout.buildDirectory.dir("rig/classes/game-patches")

val rig: SourceSet = sourceSets.create("rig") {
    java.srcDir("rig/java")
}

dependencies {
    "rigCompileOnly"(files({ gameJar() }))
    "rigCompileOnly"(sourceSets.main.get().output)
}

val applyRigPatches by tasks.registering {
    description = "Applies the rig's patch set to copies of the game files it touches (build/rig/patched-src)."
    dependsOn(gameSources)
    inputs.files(fileTree(rigPatchesDir) { include("*.patch") })
    inputs.files(fileTree(patchesDir) { include("*.patch") })
    inputs.dir(gameSourcesDir)
    outputs.dir(rigPatchedSourceDir)
    doLast {
        val engineOwned = patchFiles().flatMap { p -> touchedFiles(p).map { it to "${p.name} (engine)" } }.toMap()
        val rigPatches = rigPatchesDir.asFile.listFiles { f -> f.name.endsWith(".patch") }?.sortedBy { it.name } ?: emptyList()
        applyPatchSet(rigPatches, rigPatchedSourceDir.get().asFile, engineOwned)
    }
}

val compileRigPatches by tasks.registering {
    description = "Compiles the rig's patched game files with ECJ, like the engine's (they may call the rig's own classes)."
    dependsOn(applyRigPatches, tasks.compileJava, tasks.named("compileRigJava"))
    inputs.dir(rigPatchedSourceDir)
    outputs.dir(rigPatchClassesDir)
    doLast {
        val out = rigPatchClassesDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val bySource = patchedSources(rigPatchedSourceDir.get().asFile)
        val engine = sourceSets.main.get().output.classesDirs.files.toList() + rig.output.classesDirs.files
        ecj("8", engine + gameJar(), bySource["8"].orEmpty(), out)
        ecj("16", listOf(out) + engine + gameJar(), bySource["16"].orEmpty(), out)
    }
}

val rigJar by tasks.registering(Jar::class) {
    description = "The rig mod's script jar: the harness and the rig's patched game classes."
    dependsOn(compileRigPatches)
    archiveFileName.set("SOSDET-rig.jar")
    destinationDirectory.set(layout.buildDirectory.dir("rig/libs"))
    from(rig.output)
    from(rigPatchClassesDir)
}

val rigInfo by tasks.registering {
    val file = layout.buildDirectory.file("rig/mod-info/_Info.txt")
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.file(file)
    doLast {
        file.get().asFile.writeText(
            """
            VERSION: "$version",
            NAME: "SOSDET rig",
            DESC: "Test rig for the SOSDET engine (rig/rig.py). Not for play.",
            AUTHOR: "FighterX2500",
            INFO: "https://github.com/FighterX2500/SOSDET",
            """.trimIndent() + "\n")
    }
}

val assembleRig by tasks.registering(Sync::class) {
    group = "build"
    description = "Assembles the rig mod folder build/rig/mod/SOSDET-rig."
    from(rigInfo)
    from(rigJar) { into("V71/script") }
    into(layout.buildDirectory.dir("rig/mod/SOSDET-rig"))
}

tasks.assemble {
    dependsOn(assembleRig)
}

// ---------------------------------------------------------------- dev install and launch

fun installedModDir(): File {
    val appData = providers.environmentVariable("APPDATA").orNull ?: throw GradleException("SOSDET: APPDATA is not set")
    return file(appData).resolve("songsofsyx/mods/SOSDET")
}

val installMod by tasks.registering(Sync::class) {
    group = "dev"
    description = "Copies the mod to %APPDATA%\\songsofsyx\\mods\\SOSDET. Enable it in the game's launcher."
    from(assembleMod)
    into({ installedModDir() })
}

val uninstallMod by tasks.registering(Delete::class) {
    group = "dev"
    description = "Removes the mod from %APPDATA%\\songsofsyx\\mods\\SOSDET."
    delete({ installedModDir() })
}

val runGame by tasks.registering(Exec::class) {
    group = "dev"
    description = "Starts the game with the mod jar on the class path as the launcher would, without the launcher."
    dependsOn(assembleMod)
    doFirst {
        val game = gameDir()
        val jvmArgs = game.resolve("jvmargs-launcher.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        // The launcher's order (init/Main.java): the game's own script jars, the mod's, then the game.
        // The script scan loads the base jars' classes through this class path, so they must be on it.
        val baseScripts = game.resolve("base/script").listFiles { f -> f.name.endsWith(".jar") }?.sortedBy { it.name } ?: emptyList()
        val modJar = modDir.get().asFile.resolve("V71/script/SOSDET.jar")
        val classPath = (baseScripts + modJar + gameJar()).joinToString(File.pathSeparator) { it.absolutePath }
        workingDir(game)
        executable(game.resolve("jre/bin/java.exe"))
        args(jvmArgs + listOf("-cp", classPath, "init.MainProcess"))
    }
}

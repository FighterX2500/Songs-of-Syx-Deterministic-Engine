# 0001: The engine ships as a Songs of Syx mod, built from a patch set with ECJ

Status: Accepted (owner, 2026-10-22) · Card: [SOS-26](https://fighterx2500.atlassian.net/browse/SOS-26) · Builds on: SOSGSGMP [ADR 0001](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0001-game-compiler-ecj.md) (ECJ), [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md) (the split), [SOS-25](../plan/spikes/distribution.md) (how the engine reaches players)

## Context

The engine changes the game's own code: the fixed step, the random streams, the battle jobs, the AI log (hook-surface B7–B11, B15 and the SOS-23 city fixes). Players need those changed classes, and the developer's condition is that the project stays a **mod inside the game**, never patches `SongsOfSyx.jar`, and isn't sold.

- **SOS-25** showed that the game's launcher puts every enabled mod's `V<n>/script/*.jar` on the game's class path ahead of `SongsOfSyx.jar`. A class in a mod jar therefore replaces the game's class of the same name. This was tested from a Steam launch with patched classes at both class levels.
- **SOS-25** also found that compiling one game file with ECJ against the shipped jar reproduces its shipped classes byte for byte, anonymous and inner classes included.
- **SOS-2** found that the shipped jar was compiled by ECJ, `snake2d` and `integrations` at Java 8 and the rest at Java 16.

## Decision

**A release is a mod folder.**
```
SOSDET/
  _Info.txt
  V71/script/SOSDET.jar
```
The jar holds the engine's own classes (package `sosdet`) and, for every game file the patch set changes, every class compiled from that file. Nothing else: no other game classes, no third-party code. Players install it like any mod and enable it in the game's launcher; disabling or deleting it gives the vanilla game.

**The build** (Gradle, `gradlew.bat build`) reads the game from `SOS_GAME_DIR` and never copies game files into the repository:

1. **Game build check.** `SongsOfSyx.jar` must have the pinned SHA-256 of V71 build 71.44 (requirements §2), or the build stops.
2. **Source.** The shipped source is read from `info/SongsOfSyx-sources/` (unpacked) or `info/SongsOfSyx-sources.jar` (as Steam installs it).
3. **Patch set.** `patches/NN-topic.patch`, unified diffs with 2 lines of context, applied with `git apply` to copies of only the files they touch. Each game file belongs to one patch. A patch that no longer applies fails the build, naming the patch and the line.
4. **Engine code** compiles with `javac --release 21`, the level of the game's bundled JRE. Game code compiled at 8 and 16 can call it: ECJ reads newer class files, and the game runs on Java 21.
5. **Patched game files** compile with **ECJ 3.40.0**, `-g -preserveAllLocals -encoding UTF-8 -proc:none`, at `--release 8` for `snake2d` and `integrations` and `--release 16` for the rest. They compile against `SongsOfSyx.jar` and the engine, in the build's own JDK, because `--release` needs the JDK's `ct.sym`.
6. **Checks** (`gradlew.bat check`):
   - the patch set's files compiled **unchanged** must equal the shipped classes byte for byte, which catches a wrong compiler or flags;
   - the **whole** untouched source rebuilt must equal the shipped jar byte for byte: 9,371 of 9,371 classes;
   - the mod jar may hold only engine classes and classes compiled from the patch set (a test).

**`-proc:none` is required.** Without it, ECJ's annotation processing pass changes the order of compiler-generated bridge methods in 26 classes. Those are the 26 classes SOS-2 could not match. With it, the rebuild is byte-identical. SOSGSGMP ADR 0001 lists the flags without it.

**Downloads.** ECJ and JUnit come from Maven Central at build time; Gradle comes through its wrapper, pinned with its SHA-256. None of them ships in the mod. `gradle.properties` makes Java trust the Windows certificate store, so the build works behind antivirus HTTPS scanning (for example Avast).

**For development:**
- `gradlew.bat installMod` copies the mod into `%APPDATA%\songsofsyx\mods\SOSDET`; it is then enabled in the launcher.
- `gradlew.bat runGame` starts the game without the launcher, with the class path the launcher would build: `base\script\*.jar`, the mod jar, then `SongsOfSyx.jar`.

## Options rejected

- **Patching `SongsOfSyx.jar`** (SOS-25 option B). Breaks the developer's condition; Steam's file check and updates would undo it.
- **Building on each player's PC** (option C). Works: the bundled JRE and ECJ rebuild the classes byte for byte with `-source`/`-target`. But players would need ECJ, the patches applied locally, and a build step before the game starts, which a mod can't run itself.
- **Changing classes at load time with a Java agent** (option D). Needs a JVM argument on every PC and a bytecode library. Not needed, since shipping the patched classes is allowed.
- **Rebuilding and shipping the whole game.** Every file compiles to the shipped bytes on its own, so only the patched files need to ship. A whole game would also no longer be a mod.
- **The allow list of 26 "order-only" classes** from SOS-2. `-proc:none` removes the difference, so the rebuild check can demand byte identity for every class.
- **`javac` for game files.** It names anonymous classes differently and changes constructor signatures (SOSGSGMP ADR 0001).

## Consequences

- **Static initializers run at menu time.** The game's script scan initializes every class in a script jar while it builds the main menu (SOS-25). Engine classes must not touch game state in a static initializer.
- **One game class, one copy.** If another mod, including the multiplayer mod, ships its own version of a game class SOSDET patches, the mod order decides which copy runs. Where the multiplayer mod needs to change a file the engine patches, the class is built once from both patch sets (SOSGSGMP ADR 0004, "Patch order"; distribution ADR SOS-18).
- **Start-up check (SOS-27).** The engine checks the SHA-256 of `SongsOfSyx.jar` at run time too, and refuses to run on another build. The build already guarantees it for the PC that built it.
- **Logging.** Steam starts the game with `javaw`, so stdout is lost. The engine needs its own log file for errors and desync reports (SOS-27, SOS-29).
- **Saves.** A save made with the engine enabled names the mod. It loads without the engine after the game's "saved with other mods" warning, and the other way round.
- **Clashing script classes.** The script scan's check for clashing class names never fires: its exception is swallowed (`ScriptLoad.java:186-190`). The pinned game build keeps it that way; a game version that fixed it would reject the jar.
- **Builds need a JDK 21+.** `--release` and Gradle need a full JDK; players never need one.

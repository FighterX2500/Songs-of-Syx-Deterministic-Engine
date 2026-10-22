# SOSDET

Deterministic simulation engine for Songs of Syx V71 (build 71.44). The same battle save, seed, orders and AI log give a bit-identical simulation on every PC. The SoS GSG MP multiplayer mod ([SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP)) uses it for lockstep battles.

**Status:** the build works; the engine itself is being written (cards SOS-27 to SOS-32). The mod it builds doesn't change the game yet.

- What it must do: [docs/requirements.md](docs/requirements.md)
- Coding rules: [AGENTS.md](AGENTS.md); determinism rules: [.claude/rules/determinism.md](.claude/rules/determinism.md)
- Changes to the game's code: [docs/hooks.md](docs/hooks.md); the patch set: [patches/](patches/README.md)
- How it is built and shipped: [ADR 0001](docs/decisions/0001-build-and-packaging.md)
- Why it is a separate project: [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md) in SOSGSGMP

No game files or game source are ever committed. The build reads the game from `SOS_GAME_DIR`. Where the scenario suite keeps its test saves is settled in SOS-31.

## How it works

SOSDET is an ordinary Songs of Syx mod. The game's launcher puts an enabled mod's `V71/script/*.jar` on the class path ahead of `SongsOfSyx.jar`, so the game classes in SOSDET's jar replace the game's own. The game folder is never changed: untick the mod in the launcher and the game is vanilla again.

The jar holds the engine's code (package `sosdet`) and the game classes compiled from the patch set: the game's own source with small hooks that call into the engine.

## Building

You need:
- Windows;
- a JDK 21 or newer (`JAVA_HOME` set), for example Eclipse Temurin;
- `git` on the `PATH` (the build applies patches with `git apply`);
- Songs of Syx V71, build 71.44. Other builds are refused.

Set `SOS_GAME_DIR` to the game folder, the one that holds `SongsOfSyx.jar`, then build:

```
set SOS_GAME_DIR=C:\Program Files (x86)\Steam\steamapps\common\Songs of Syx
gradlew.bat build
```

The first build downloads Gradle, ECJ 3.40.0 and JUnit. The result is the mod folder `build\mod\SOSDET`.

`gradlew.bat build` also checks the toolchain:
- the patch set's game files, compiled unchanged, must match the shipped classes byte for byte;
- the whole untouched game source, rebuilt, must match the shipped jar byte for byte.

| Command | What it does |
| --- | --- |
| `gradlew.bat assemble` | Builds the mod folder only |
| `gradlew.bat compileJava compileTestJava` | Fast check |
| `gradlew.bat test` | Tests |
| `gradlew.bat check` | Tests and the byte-for-byte checks |
| `gradlew.bat installMod` | Copies the mod to `%APPDATA%\songsofsyx\mods\SOSDET`; enable it in the game's launcher |
| `gradlew.bat uninstallMod` | Removes it again |
| `gradlew.bat runGame` | Starts the game with the mod, without the launcher |

## Layout

| Path | Contents |
| --- | --- |
| `src/main/java/sosdet/` | Engine code, Java 21 |
| `src/test/java/` | Tests (JUnit 5) |
| `patches/` | The patch set: changes to the game's own source |
| `src/test/fixtures/patches/` | Patch sets for testing the build (`-Psosdet.patches=<dir>`) |
| `docs/` | Requirements, decisions, hooks, spike reports |
| `spikes/` | Throwaway experiments; never part of the build |

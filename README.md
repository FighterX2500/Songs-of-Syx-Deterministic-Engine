# Songs of Syx Deterministic Engine (SOSDET)

Deterministic simulation engine for Songs of Syx V71 (build 71.44). The same save, seed, orders, players' commands and AI log give a bit-identical simulation on every PC. The SoS GSG MP multiplayer mod ([SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP)) uses it to run battles, cities and the world map in lockstep. The mod's name in the game's launcher stays SOSDET.

**Status:**
- **Engine core:** fixed step, random streams, and battle jobs in the step (SOS-27).
- **AI general:** runs on the host, with its AI log (SOS-28).
- **State hash:** taken every 30 steps and compared with the other PCs'; the first difference gives a desync report (SOS-29).
- **Resync:** a battle can be saved at a step and reloaded there by every PC to recover from a desync (SOS-30).
- **Players' battle orders and speed changes:** in the host's log, and the step length follows the agreed speed. The owner's 13-minute play session on the Mogus-72 city invasion stayed identical across three copies (SOS-32 M1).
- **City and world lockstep:** the city's and the world map's player actions are commands, and the world runs in the step (SOS-34, SOS-35, [plan 0002](docs/plan/0002-city-and-world-lockstep.md)). The city↔world link comes after V0.1.
- **V0.1:** [docs/release/V0.1.md](docs/release/V0.1.md): what works, what is blocked, and how to start the owner's session. `gradlew.bat releaseZip` packs it into `build\release\SOSDET-0.1.0.zip`.

Until something starts an engine session, the game plays as vanilla.

- What it must do: [docs/requirements.md](docs/requirements.md)
- Coding rules: [AGENTS.md](AGENTS.md); determinism rules: [.claude/rules/determinism.md](.claude/rules/determinism.md)
- Changes to the game's code: [docs/hooks.md](docs/hooks.md); the patch set: [patches/](patches/README.md)
- How it is built and shipped: [ADR 0001](docs/decisions/0001-build-and-packaging.md)
- Why it is a separate project: [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md) in SOSGSGMP
- Code and ideas adapted from other projects: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)

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
| `gradlew.bat releaseZip` | Packs the mod folder and the release notes into `build\release\SOSDET-<version>.zip` |
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
| `rig/` | The test rig: a second mod (SOSDET-rig) and its runner, for determinism runs ([rig/README.md](rig/README.md)); never in a release |
| `spikes/` | Throwaway experiments; never part of the build |

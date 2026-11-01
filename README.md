# Songs of Syx Deterministic Engine (SOSDET)

Deterministic simulation engine for Songs of Syx V71 (build 71.44). The same save, seed, orders, players' commands and AI log give a bit-identical simulation on every PC. The SoS GSG MP multiplayer mod ([SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP)) uses it to run battles, cities and the world map in lockstep. The mod's name in the game's launcher stays SOSDET.

## Status

SOSDET runs one Songs of Syx game in lockstep on several PCs. It has no network code: it hands out bytes and takes them in, and a mod carries them. [SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP) is its first user.

What it does today:

- **Lockstep:** fixed steps, seeded random streams, and path finding on worker threads inside the step. 179 player actions are commands that every PC applies at the same step. The AI general runs on the host and logs what it does.
- **Several humans:** up to 16 share one world, tested with 3. Each PC runs its own human's city. The world reads city reports and changes a city only through transactions applied exactly once.
- **Between humans:** deals, tariffs, vassalage and war.
- **Battles:** humans fight on the battle map, several on one side if they are allies. A battle against NPCs can run on its human's PC alone while the world goes on, and others can watch it.
- **Sieges:** a human's capital can be besieged, sallied from and stormed after a wait. The attacker then chooses sack, vassalage or elimination.
- **Viewing:** a PC can run another human's city in lockstep with its owner's.
- **Desyncs:** a state hash every 30 steps finds the first difference. The host can hold every PC and resync the world or a battle. The engine never resyncs on its own.
- **Pace:** the slowest PC caps the speed. A human who leaves is removed at the same step on every PC.

What it doesn't do yet:

- It runs only on V71, build 71.44. The build checks that; the mod doesn't check at start-up, so a game update can break it.
- The API may still change, and nothing about it is promised.
- The host can't change during a session.
- The scenario suite's recordings need making again. Until then `python rig\suite.py` checks only the own-city world and reports FAIL.

Until something starts an engine session the game simulates as vanilla, with a few exceptions listed in [docs/hooks.md](docs/hooks.md): every game holds 15 spare Players and every save seven extra sections, and captive counts in manual battles differ slightly.

## Documentation

- **Building on the engine:** [docs/api.md](docs/api.md)
- **Start here, especially as an AI agent:** [docs/architecture.md](docs/architecture.md): how a session runs, the code map, every known desync cause and where it is fixed, and the pitfalls
- What it must do: [docs/requirements.md](docs/requirements.md)
- Coding rules: [AGENTS.md](AGENTS.md); determinism rules: [.claude/rules/determinism.md](.claude/rules/determinism.md)
- Changes to the game's code: [docs/hooks.md](docs/hooks.md); the patch set: [patches/](patches/README.md)
- How it is built and shipped: [ADR 0001](docs/decisions/0001-build-and-packaging.md)
- Why it is a separate project: [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md) in SOSGSGMP
- Code and ideas adapted from other projects: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)
- License: MIT for SOSDET's own code ([LICENSE](LICENSE)). The game source lines in `patches/` stay the game developer's, published with their permission; the mod is free and must not be sold.

No game files or game source are ever committed. The build reads the game from `SOS_GAME_DIR`. The scenario suite's test saves stay in your own saves folder ([rig/README.md](rig/README.md)).

## How it works

SOSDET is an ordinary Songs of Syx mod. The game's launcher puts an enabled mod's `V71/script/*.jar` on the class path ahead of `SongsOfSyx.jar`, so the game classes in SOSDET's jar replace the game's own. The game folder is never changed: untick the mod in the launcher and the game is vanilla again.

The jar holds the engine's code (package `sosdet`) and the game classes compiled from the patch set: the game's own source with small hooks that call into the engine.

## Building

You need:
- Windows;
- a JDK 21 or newer (`JAVA_HOME` set), for example Eclipse Temurin;
- `git` on the `PATH` (the build applies patches with `git apply`);
- Songs of Syx V71, build 71.44. The build refuses other builds; the mod itself doesn't check them.

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
| `tools/` | The patch tools |
| `rig/` | The test rig: a second mod (SOSDET-rig) and its runner, for determinism runs ([rig/README.md](rig/README.md)); never in a release |
| `spikes/` | Throwaway experiments; never part of the build |

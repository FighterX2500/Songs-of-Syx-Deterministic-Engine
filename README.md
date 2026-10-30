# Songs of Syx Deterministic Engine (SOSDET)

Deterministic simulation engine for Songs of Syx V71 (build 71.44). The same save, seed, orders, players' commands and AI log give a bit-identical simulation on every PC. The SoS GSG MP multiplayer mod ([SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP)) uses it to run battles, cities and the world map in lockstep. The mod's name in the game's launcher stays SOSDET.

**Status:**
- **Engine core:** fixed step, random streams, and battle jobs in the step (SOS-27).
- **AI general:** runs on the host, with its AI log (SOS-28).
- **State hash:** taken every 30 steps and compared with the other PCs'; the first difference gives a desync report (SOS-29).
- **Resync:** a battle can be saved at a step and reloaded there by every PC to recover from a desync (SOS-30).
- **Players' battle orders and speed changes:** in the host's log, and the step length follows the agreed speed. The owner's 13-minute play session on the Mogus-72 city invasion stayed identical across three copies (SOS-32 M1).
- **City and world lockstep:** the city's and the world map's player actions are commands, and the world runs in the step (SOS-34, SOS-35, [plan 0002](docs/plan/0002-city-and-world-lockstep.md)).
- **City↔world link:** the world never reads a live city. It reads city reports, and changes a city only through transactions applied exactly once, so each PC can run its own city under one lockstep world. A PC whose world splits reloads the host's world and keeps its city, and the slowest PC caps the pace (SOS-38, plan 0002 stage E).
- **Several humans:** up to 16 human factions share one world map, each a player in its own slot. Each PC runs only its own human's city; the world runs each human's part with that human as the player, and keeps NPC relations, event timers and raids per human. Three LLM players stayed identical for an hour on two PCs (SOS-46, [ADR 0004](docs/decisions/0004-several-humans.md), [plan 0004](docs/plan/0004-several-humans.md)). A world resync recovers a desync with several humans (SOS-52).
- **World setup for several humans:** `Engine.buildWorld` builds a new world around every human's picked location; one PC builds it and every PC loads its save (SOS-55, [plan 0005](docs/plan/0005-add-human.md)).
- **Desync hold:** at a desync every PC holds at the same step, and the host chooses a world resync or saving and leaving (SOS-53).
- **Own-city battles:** with own cities, the battle PC generates a world battle and sends its battle save; every PC loads it at the same step and returns to its own city after it (SOS-61, [plan 0006](docs/plan/0006-own-city-battles.md) M1).
- **Removing a human:** a human whose PC is gone is removed at the same step on every PC: its regions go free, its armies disband, and its slot retires (SOS-66, [plan 0007](docs/plan/0007-remove-human.md)).
- **Between humans:** deals (treaties, money, goods, regions), vassalage with a tribute rate, trade at peace, the market with each human's tariffs, and war, all as commands applied at one step (SOS-72 to SOS-74, SOS-77; plans [0009](docs/plan/0009-human-deals.md) to [0012](docs/plan/0012-war-between-humans.md)).
- **Battles between two humans:** both humans are asked, each commands their own army through the commanders table, and the battle runs on every PC (SOS-83, [plan 0012](docs/plan/0012-war-between-humans.md)).
- **City viewing:** a PC runs another human's city in lockstep with its owner's, its own city frozen until the view ends (SOS-82, [plan 0013](docs/plan/0013-city-viewing.md)).
- **Sieges of a human's capital:** the siege, its wait before an assault and the sally at any time (SOS-92 step A, [plan 0014](docs/plan/0014-sieges.md)); the assault itself is in progress.
- **Pace at every speed:** the lockstep windows stay half a second of real time at every speed (SOS-90), and the governor tells a slow PC from a slow link (SOS-94).
- **Scenario suite:** `python rig\suite.py` runs every rig scenario in one command, before each pull request (SOS-31, [rig/README.md](rig/README.md)).
- **V0.1:** [docs/release/V0.1.md](docs/release/V0.1.md): what works, what is blocked, and how to start the owner's session. `gradlew.bat releaseZip` packs it into `build\release\SOSDET-0.1.0.zip`.

Until something starts an engine session, the game plays as vanilla.

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

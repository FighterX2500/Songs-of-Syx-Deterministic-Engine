# Spike: how the engine reaches players (SOS-25)

Date: 2026-10-22 · Card: [SOS-25](https://fighterx2500.atlassian.net/browse/SOS-25) · Epic: SOS-22 · Code: `spikes/distribution/` · Feeds: [SOS-18](https://fighterx2500.atlassian.net/browse/SOS-18) (distribution ADR, in SOSGSGMP)

## Questions

1. Which way of getting the engine's changed game code onto a player's PC works when the game is started from Steam: a patched game jar, a mod or class-path route that puts the engine's classes first, or building the patches on the player's PC?
2. How does each PC prove it runs the same game build and the same engine build?
3. What happens to the player's vanilla game and saves, and how is the engine removed?

**Owner's condition** (2026-10-22, from the game's developer): the project must stay a **mod inside the game**, loaded by the game's own launcher, never a separate game, and must never patch `SongsOfSyx.jar`. Shipping patched game classes inside a mod is fine, as long as it isn't sold. The `info/License.txt` in the game folder is an old license and doesn't reflect this.

## Answer

**An ordinary mod can carry the engine and its patched game classes.** The game's launcher puts every enabled mod's `V71/script/*.jar` on the game process's class path **ahead of** `SongsOfSyx.jar`. The first copy of a class wins, so the mod's patched classes replace the game's own. This was tested from a Steam launch on the Steam install:

- the game's entry class (`init.MainProcess`, `--release 16`) and `snake2d.util.rnd.RND` (`--release 8`) both ran from the mod jar;
- Mogus-72 loaded and played, with the mod on and with it off;
- with the mod disabled, the game was vanilla: the mod jar wasn't on the class path and no marker ran;
- 151 unchanged classes from all 32 game files the spike patches touch, shipped in the mod jar, didn't disturb the game (see "The script scan").

Recommendation for SOS-18: **option A, the engine ships as a mod.** Options B, C and D are rejected below.

1. **Option A works from Steam.** Option C also works, but needs a build step outside the game. Option B breaks the owner's condition.
2. **Same build:** the engine checks the SHA-256 of `SongsOfSyx.jar` against the build it was made for (about 77 ms for 35.6 MB) and reports its own build ID; the mod compares both across PCs.
3. **Vanilla and saves:** disabling the mod gives the vanilla game. Saves load both ways with the game's own "saved with other mods" warning. Removal is deleting the mod folder.

## Setup

- **Steam install:** `C:\Program Files (x86)\Steam\steamapps\common\Songs of Syx` (app 1162750), started with `steam://rungameid/1162750`. Its `SongsOfSyx.jar` is byte-identical to the dev copy (SHA-256 `fe0137c0…`).
- **Test mod `SOSDET-spike`:** `_Info.txt` plus `V71/script/SOSDET-spike.jar`, installed into `%APPDATA%\songsofsyx\mods\` and enabled in `LauncherSettings.txt` (`MODS`). The settings were backed up first and restored at the end.
- **Markers.** Steam starts the launcher with `javaw`, so the game's stdout (all of `LOG.ln`) is lost and the game writes no log file. Each patched class instead writes `%APPDATA%\songsofsyx\sosdet-spike\<class>.txt` with its thread, the class path and the stack that ran it.
- **Builds:** ECJ 3.40.0 with the ADR 0001 flags, one source file at a time, against the shipped `SongsOfSyx.jar`. Before patching, each file is compiled unchanged and compared byte for byte with the shipped classes.

| Script | What it does |
| --- | --- |
| `build_marker.py` | Builds the mod: `MainProcess` and `RND` with a marker each, after the byte-for-byte control |
| `scan_test.py` | Adds unchanged copies of the 32 patched-in-the-spike game files and a probe class |
| `mod_switch.py` | Installs, enables, disables, removes the mod; backs up and restores `LauncherSettings.txt` |
| `option_c.py` | Builds the 32 files on the game's bundled JRE from the Steam install's sources jar, and times it |

## Results

### Launch chain (Steam)

1. Steam runs `SongsofSyx.exe`.
2. That runs `jre\bin\javaw.exe -jar SongsOfSyx.jar launcher` (`init.Main`).
3. `init.Main` starts the launcher window, `java -cp SongsOfSyx.jar launcher.Launcher`, and waits for it.
4. After Play, `init.Main` starts the game: `java <JVM args from LauncherSettings> -cp <script jars>;SongsOfSyx.jar init.MainProcess` (`init/Main.java:78-100`, `snake2d/util/process/Proccesser.java:55-59`).

The script jars are `base\script\*.jar` (the game's two tutorial scripts) followed by every enabled mod's `V<n>\script\*.jar`. With the test mod on:

```
-cp "…\base\script\000_Tutorial.jar;…\base\script\001_Tutorial.jar;
     %APPDATA%\songsofsyx\mods\SOSDET-spike\V71\script\SOSDET-spike.jar;…\SongsOfSyx.jar"
```

`SongsOfSyx.jar` isn't sealed (manifest: `Main-Class: init.Main`, `Class-Path: .`), so classes of one package can come from two jars.

### Option A: a mod jar with patched classes

| Check | Result |
| --- | --- |
| `init.MainProcess` from the mod jar | Ran, on `main`, as the game's first class |
| `snake2d.util.rnd.RND` (`--release 8`) from the mod jar | Ran, at the game's own first use while the menu builds its race data (`menu.RESOURCES` → `INIT` → `RACES` → `RaiderText` → `Inserter` → `RND`) |
| Load and play Mogus-72 with the mod on | Fine; the game warned "saved with other mods" |
| Save with the mod on (`sosdet-modded`), load with it off | Fine; same warning |
| Mod disabled | Vanilla class path, no markers |
| 151 unchanged classes from the 32 engine target files in the jar (plus a probe class), load Mogus-72 | Fine (owner, play-tested); no error logs |

**Building one file at a time is exact.** All 34 files (the 32 below plus `MainProcess` and `RND`), compiled unchanged one by one against the shipped jar, gave classes byte-identical to the shipped ones: 153 classes, every anonymous and inner class included. Patched classes therefore differ from the shipped ones only where a patch changed the source.

### The script scan

The game builds its script list while it constructs the main menu (`menu.ScRandom$Scripts` → `ScriptEngine.getAll` → `ScriptLoad`). For **every class in every script jar** it calls `Class.forName`, which also runs the class's static initializer (`script/ScriptLoad.java:186`). A probe class that nothing references ran its initializer there, on the `updater` thread, before any game existed:

```
sosdet.spike.ScanProbe.<clinit>  ←  Class.forName  ←  ScriptLoad$Init.processJarEntry:186
  ←  ScriptLoad.getAll  ←  ScriptEngine.getAll  ←  menu.ScRandom$Scripts.<init>  ←  menu.Menu.<init>
```

The same call is meant to reject script classes whose names clash with game classes. It throws `Errors.DataError`, but inside a `try` whose `catch (Exception e)` swallows it, so the check never fires. Option A relies on that. The game build is pinned (requirements §2), so the behaviour can't change under us; a later game version that fixes the catch would reject the jar.

With all 32 target files in the jar, the scan initialized `GAME`, `CORE`, `VIEW`, the battle threads, the AI general and its offence steps, path finding, `TWater`, `TForest`, `TileUpdater`, `RANMAP`, `GUTIL`, light and audio at menu time. The game reached the menu, loaded Mogus-72 and played normally.

### Option B: a patched SongsOfSyx.jar

Rejected without a test: it breaks the owner's condition. It would also be undone by Steam's file check and every game update.

### Option C: build on the player's PC

| Check | Result |
| --- | --- |
| Source on a Steam install | `info/SongsOfSyx-sources.jar` (zipped; the dev copy has it unpacked) |
| ECJ with `--release` on the bundled JRE | Fails (`java.lang.Object cannot be resolved`): the JRE has no `lib/ct.sym` |
| ECJ with `-source`/`-target` on the bundled JRE | Works: all 151 classes of the 32 files byte-identical to the shipped ones |
| Time | 0.1 s to unpack, 24 s to compile (one ECJ run per file; one run for all would be faster) |

It works, but:
- we would ship ECJ (3.3 MB, a third-party tool: an ADR);
- the patch set would also have to be applied on the player's PC;
- the build has to run before the game process starts, since a class can't be replaced once the game has loaded it. A mod's own code only runs inside the game, so the first start would build and then need a restart, or players would run a separate tool first.

Option A ships the same classes already built, with none of these steps.

### Option D: change classes as they load (Java agent)

Not tried. It was only needed if option A failed or if shipping game classes weren't allowed; neither is the case. It would need a JVM argument in every player's launcher settings and a bytecode library (an ADR).

### Same build on every PC

- **Game build:** SHA-256 of `SongsOfSyx.jar` takes about 77 ms for 35.6 MB (measured in Python; the JVM's SHA-256 should be close). The engine can check it once, before it does anything, and refuse to run on a build it wasn't made for. Without that check, shadowing classes of another build would break the game in unpredictable ways.
- **Engine build:** a build ID (version plus the SHA-256 of the mod jar) that the API reports. The mod compares both values across PCs in the lobby.
- **The game's own record:** every save's file name carries a hash of the enabled mods' names and version folders (`SaveFile.stamp`). The test save was `sosdet-modded-…-47002c-e39a2c5c-121c.save`, and `e39a2c5c` is `"SOSDET spike" + 71` hashed. The game records which mods were on, not which classes ran.

### Saves and removal

- **Mods differ from the save:** the load screen shows "Game was saved with other mods than those currently enabled." It refuses to load only when the content differs (races, rooms, resources, industries) or a script recorded in the save is missing (`GameSpec.pwarning/pproblem`). A mod that adds no content and no `SCRIPT` class gets the warning only.
- **Removal:** untick the mod in the launcher, or delete its folder. The game folder is never touched.

## Consequences

For the engine (SOS-26, SOS-27):

- **Release = a mod folder.** It holds `_Info.txt` and `V71/script/<engine>.jar`. The jar holds the engine's classes and, for every game file the patch set changes, **every** class compiled from that file. A file's anonymous and inner classes are compiled together with it, so mixing ours and the shipped ones isn't safe.
- **The build compiles only the patched files.** Each file goes through ECJ against the shipped jar (`--release 8` for `snake2d` and `integrations`, 16 for the rest). The untouched-file check (byte-identical to shipped) is cheap and catches a wrong compiler or flags. A full rebuild of the game isn't needed for a release; it stays useful as a check.
- **Static initializers run at menu time.** Every class in the jar is initialized while the main menu is built, before any game exists. Engine classes must not touch game state in a static initializer; their setup waits until the game calls in. Patched game classes keep their vanilla initializers, which survived this (32 files).
- **Build check at start-up.** The engine compares the SHA-256 of `SongsOfSyx.jar` with its pinned build before it does anything, and refuses to run otherwise, saying why.
- **Own log file.** Steam runs the game with `javaw`, so stdout is lost. The engine writes its errors and desync reports to its own file.

For the mod and SOS-18:

- **One game class, one copy.** Only the first copy of a class on the class path counts. Where the engine and the multiplayer mod both change one game file (for example `GAME.java`: A1 and B7), shipping two mods each with its own patched `GAME` can't work: the mod list order would decide which one runs. The class has to be built once from both patch sets and shipped once. Either the multiplayer mod's release includes the engine, or the engine leaves those files to a combined build (ADR 0004 "Patch order").
- **Mod order matters** whenever two enabled mods carry the same class. Compatibility with other mods is out of scope for v1 (spec §2), but a player with another mod that shadows one of our classes would get whichever comes first. The engine's start-up check can detect that a class didn't come from its own jar.
- **Saves** made with the engine name it in the save, and load without it with a warning. That suits a campaign that has to run with the engine anyway.
- **Delivery** of the mod folder (Steam Workshop, a download, a lobby transfer) is SOS-18's to decide; nothing here depends on it.

## Not covered

- Steam Workshop upload and subscription.
- Two mods carrying the same class (the engine plus the multiplayer mod, or a third-party mod).
- A real engine patch run from the mod jar (only markers and unchanged classes ran). The determinism patches themselves ran from a rebuilt jar in SOS-11 and SOS-23.
- Hashing on the JVM (measured in Python).
- A PC other than this one.

## Requirements sections touched

- **§2 (scope):** the pinned build is what makes the swallowed clash check (above) safe to rely on.
- **§5 (verification):** the build check and the per-file control check are new inputs for the engine build (SOS-26).
- No requirement changed.

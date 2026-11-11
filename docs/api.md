# Using the engine from a mod

For a mod that drives SOSDET. To change the engine itself, read [docs/architecture.md](architecture.md).

SOSGSGMP is the one full user. Its calls are in `sosgsgmp.game.EnginePlay` and `WorldSetupAdapter`. In this repository the test rig is the example: `rig/java/sosdet/rig/Rig.java`.

## No stability promise

SOSDET V1 Beta promises no API stability. Methods, formats and behaviour can change in any release, and there is no API version.

The formats that cross the API carry version numbers, and most refuse another version. Beyond that:
- **Game build:** SOSDET stops the game at start-up on any build but 71.44 (`sosdet.GameBuild`).
- **Engine build:** nothing in the engine compares it between PCs. `Sosdet.version()` gives the release number, which doesn't change with each build. SOSGSGMP hashes the jar and compares the hash in its lobby (`menu.MpBuilds`).

If your mod needs to compare builds between PCs, it has to do that itself.

## Compiling against the jar

There is no published artifact. Build SOSDET ([README](../README.md), "Building"), then compile against its mod jar and the game's jar, for both only at compile time:

```kotlin
dependencies {
    compileOnly(files("<game>/SongsOfSyx.jar"))
    compileOnly(files("<SOSDET>/build/mod/SOSDET/V71/script/SOSDET.jar"))
}
```

- Never shade or copy the jar: it holds game classes. Players install SOSDET as its own mod, enabled ahead of yours.
- Your mod is an ordinary script mod (`script.SCRIPT`).
- The engine is compiled with `--release 21`, and the game's bundled JRE is 21.

## What to call

- **`sosdet.Engine` is the API.** It has about 150 static methods, and their Javadoc is the reference. [Requirements §3](requirements.md#3-what-the-mod-can-do-through-the-api) says what each group is for.
- **Types it hands back:** `HumanStart`, `OwnBattle`, `LocalBattle`, `View`, `Assault`, `BattleOutcome`, `Notice`, `DesyncHold`, `Stats`, `Carved`, `sosdet.hash.Desync`.
- **`sosdet.Hooks` is for patches.** It is public because the patched game classes call it. Don't call it from a mod. The same goes for `sosdet.Cosmetic` and `sosdet.game`.

Most `Engine` methods throw `IllegalStateException` with no session. `active()`, `locationProblem`, `buildWorld`, `bannerBytes` and `worldHash` work without one.

## Carved city-states

A city-state can be carved into a running world as a new 3x3 region (SOS-186, SOSGSGMP `docs/plan/hot-join.md`):

- `carveProblem(x, y)` says why a centre can't be used, or null. Any PC, between steps.
- `carve(x, y, name)` carves it on the PC that saves the world: the square, its region info and name, a road and path to the town of each region it touches (no road straight to another human: vanilla's path graph joins a region's town to its borders only, so humans 6 to 15 tiles apart keep SOS-120's trade route), and the split rule for the land it is carved in (unowned land becomes a new NPC; a region of an NPC realm of 4 or more that isn't its capital breaks away; a new NPC is neutral and sanctified). The region has no owner yet. `Carved.noSlot` says a new NPC was wanted but all 64 faction slots are in use.
- `uncarve(region)` undoes one, in any order: only the roads and path steps no other carve uses go.
- The undo log is the save section `SOSDET_CARVES`, loaded with the world and in the world hash.
- After either, every PC loads the world from that PC's save: a world changed in place on one PC never matches one that was loaded (SOS-38, SOS-184).

## Threads

- Call the engine on the game thread only.
- A network thread only decodes bytes and queues them. The game thread feeds them in.
- A script's `update` runs inside the step. Don't call the engine from it, and don't change game state from it.
- Call from `GAME.addAfterUpdate`, which runs after the frame's update, outside the step. Register again after each load: `Engine.afterEachLoad` runs an action then.

## Starting a session

Every PC does the same, in this order. A session holds at step 0 until it is told to run.

1. **Before the load:** `Engine.begin()`, then `aiHost(startId)` or `aiPeer(startId)`, the same `startId` on every PC. Then `localPc(pc)`: 0 on the host, from 1 on a peer. Then `ownCity()` if each PC runs its own city. Then `armLoadSeed(seed)`, the same on every PC, never 0. Optionally `workFolder(dir)`: the engine's working saves (`__battle`, `__beforeBattle`, `__view`, `__beforeView`, `__viewReload`) go there instead of the game's saves folder, where they would show in the player's Load list (SOS-155).
2. **The load:** every PC loads the same save. For a new world, one PC calls `buildWorld`, and every PC loads its save.
3. **After the load, at step 0:** `pcHuman(pc, slot)` for each PC, `seed(seed)`, and `speed(agreed)` if it isn't 1x. A PC whose human isn't slot 0 calls `ownCityAt(x, y)` on a new world.
4. **To start:** `followAgreedSpeed()` on every PC, `pace(peerPcs, lead)` on the host, and `runFreely()` or `runUntil(step)`.

`seed`, `speed`, `aiHost`, `aiPeer`, `ownCity`, `hashEvery`, `ownCityAt` and `removeHumanAtStart` throw after step 0. End with `Engine.end()`: the game is then vanilla again, apart from what [docs/hooks.md](hooks.md) lists.

## What to pump

The engine has no network. Your mod carries every byte. Each frame, after the update:

| Bytes | Out | In |
| --- | --- | --- |
| AI log | host: `aiLogOut()` | every peer: `aiLogIn(bytes)` |
| Orders | peer: `ordersOut()` | host: `ordersIn(pc, bytes)` |
| Hashes | every PC: `hashOut()` | every other PC: `hashIn(pc, bytes)` |
| City reports, own cities | every PC: `reportsOut()` | every other PC: `reportsIn(pc, bytes)` |

Bytes may arrive in any pieces, but deliver them all, in order, for each sender. The engine has no timeouts. A peer holds where the host's log ends, and the world waits for the reports it reads.

A report from a PC whose game data isn't this one's (other mods or game files) is not read: `reportMismatch()` returns it, a `sosdet.report.ReportMismatch` whose `what` names the size that differs (a `Count`, such as `BOOSTS`), with `theirs`, `ours` and the sender's `pc`. The world then waits for that PC's reports, so the mod ends the session with that message (SOS-159).

When they come up:

| What | Out | In |
| --- | --- | --- |
| A battle with own cities | battle PC: `battleOut()` | every PC, that one too, at the Engage step: `battleIn(bytes)` |
| A city view | owner's PC: `viewOut()`, `viewHashOut(viewer)` | viewer's PC: `viewIn(bytes)`, `viewHashIn(bytes)` |
| Watching a local battle | `viewTapeOut(viewer)` | `viewTapeIn(bytes)` |
| A world resync | host: `worldResyncSave(file)` | every PC, the host too: `worldResyncLoad(file, state)` |
| A battle resync | host: `battleResyncSave(file)` | every PC, the host too: `battleResyncLoad(file, state)` |
| A resync with no own cities | host: `resyncSave(file)` | every PC, the host too: `resyncLoad(file, state, afterSet)` |

A resync carries a save file as well as the returned bytes, so send both. The engine never resyncs on its own: `desyncHold()` says when every PC has held, and your mod shows the choice.

## Rules for a mod

- Never change simulation state on one PC. A player's action reaches the game's own UI call, where a hook turns it into a command. The rig's scripted player does that through `Engine.betweenSteps`.
- Never feed a per-PC value into the step.
- Every PC needs the same save, seed, game build, engine build and simulation mods.
- The determinism rules are in [.claude/rules/determinism.md](../.claude/rules/determinism.md).

## Limits today

- V71, build 71.44 only.
- The host can't change during a session: `aiHost` and `aiPeer` throw after step 0.
- `removeHuman` refuses slot 0.
- In `ownCity` mode each city differs by PC, so only the world is compared.
- The scenario suite checks little today: see [rig/README.md](../rig/README.md).

# Hooks into game code

Every change the engine makes to the game's own code, with the reason it exists. The points come from the mod's [hook map](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/plan/hook-surface.md) (hooks B7–B11 and B15; B12–B14 need no game change) and the SOS-23 city fixes. AGENTS.md "Patches to Game Code" says how a hook is written.

Every hook calls `sosdet.Hooks` (or `sosdet.Cosmetic`) and is marked `SOSDET` in the game source. With no engine session, every hook gives the game's own behaviour, except the fixed tables in `50-fixed-tables`, which are filled at class load (see that row).

| Hook | Point | Game file | Calls into the engine | Why no API or reflection route was enough | Card |
| --- | --- | --- | --- | --- | --- |
| `10-fixed-step` | B7 | `game/GAME.java` | `fixedSteps`, `stepsFor`, `stepBegin`, `stepEnd`, `skipPausedFrame`, `autoSaveAllowed`, `worldHeld`, `battleStateInStep` | The frame is cut into sub-steps inside `GAME.update`, a private loop over a private list; nothing outside can change the step length, stop at a step, or hold the world still | SOS-27 |
| `10-fixed-step` | B7 | `view/battle/BattleView.java` | `battleStateInStep` | The battle view updates the battle state once per frame with frame time; it has to update inside the step instead | SOS-27 |
| `20-rnd-streams` | B10 | `snake2d/util/rnd/RND.java` | `random` | `RND` is a static class every system draws from; the stream can only be chosen where the draw happens | SOS-27 |
| `30-battle-jobs` | B8 | `game/battle/thread/BattleThreads.java` | `battleJobsNow`, `runBattleJobs` | The battle jobs are protected methods run by their own wall-clock threads; only the owner can run them inside the step | SOS-27 |
| `30-battle-jobs` | B8, B15 | `game/battle/thread/BattleThread.java` | `threadRunsInStep` | `start()` starts the real thread; nothing outside can stop it starting. On the host the AI general's thread does start (SOS-28) | SOS-27, SOS-28 |
| `30-battle-jobs` | B8 | `snake2d/SlaveThread.java` | `slaveWorking` | The jobs loop on `working()`, and `SlaveThread` is a final class, so nothing can override it; it must be true only while a job runs in the step, or `BattleOrders.init` restarts the real thread (SOS-11) | SOS-27 |
| `35-paths` | B9 | `settlement/path/thread/FinderThread.java` | `pathsNow`, `pathDone` | Paths are queued for a worker thread inside `prep`; the answer would arrive a varying number of frames later | SOS-27 |
| `40-cosmetic` | B10 | `settlement/tilemap/terrain/TForest.java` | `cosmeticBegin`, `cosmeticEnd` | Tree sway draws from `RND` inside the step; only the call site knows the draw is visual | SOS-27 |
| `40-cosmetic` | B10 | `game/time/Light.java` | `cosmeticBegin`, `cosmeticEnd` | Night light flicker draws from `RND` inside the step, and only with the light-cycle setting on | SOS-27 |
| `40-cosmetic` | B10 | `game/audio/Sound.java`, `SoundFile.java`, `SoundRace.java`, `AmbianceChannel.java`, `AmbianceUpdater.java`, `Music.java` | `Cosmetic.rInt`, `rFloat`, `rFloat1` | Sound picks draw from `RND` inside the step, skipped at zero volume and throttled by game speed (SOS-11); only the call site knows they are cosmetic | SOS-27 |
| `50-fixed-tables` | B11 | `util/updating/TileUpdater.java` | `tileOrderRandom` | The tile visiting order is shuffled in a static initializer at class load, before any API call can run. Fixed in every process, with or without a session | SOS-27 |
| `50-fixed-tables` | SOS-23 | `util/data/RANMAP.java`, `util/GUTIL.java` | `randomTableSeed` | Two random tile tables are filled when the game builds its utilities, from the unseeded start-up stream. Fixed seeds in every process, with or without a session; `RANMAP()` itself is unchanged | SOS-27 |
| `60-local-view` | SOS-23 | `settlement/tilemap/terrain/TWater.java` | `simulationValue` | Walkable ice is scaled by this PC's weather-effects setting inside the terrain update | SOS-27 |
| `60-local-view` | SOS-23 | `settlement/entity/humanoid/ai/crime/SerialKiller.java` | `cameraMatters` | The serial killer doesn't strike where this PC's camera looks; the check sits inside the AI plan | SOS-27 |
| `70-load-in-sim` | SOS-23 | `game/save/GameLoader.java` | `loadBegin`, `loadEnd` | Loading draws from `RND` (prisoners' crimes); the draws have to be on the seeded stream, and a load restarts the session at step 0 | SOS-27 |
| `80-gl-jobs` | SOS-23 | `snake2d/CORE.java` | `simulationThreadNow`, `glJobBegin`, `glJobEnd` | Graphics-thread jobs started by the simulation (the speaker cheer table) draw on the graphics thread while the simulation waits; only the job queue knows who handed the job over | SOS-27 |
| `90-ai-host` | B15 | `game/battle/thread/general/Strategos2000.java` | `Hooks.aiGeneralJob` | The general's job runs on its own thread inside the game; only the job itself knows which thread that is | SOS-28 |
| `90-ai-host` | B15 | `game/battle/thread/order/BattleOrder.java`, `BattleOrders.java` | `AiHooks.order` | Every order the general gives goes through `Locked.set`; capturing it needs the division's index, which only `BattleOrders` knows | SOS-28 |
| `90-ai-host` | B15 | `game/battle/thread/general/offence/StepAttackEnemyNear.java`, `StepAttackOthers.java`, `StepLinesMoveTo.java`, `Strategos2000UpdaterOffense.java` | `AiHooks.guard`, `running`, `fireAtWill`, `formation` | The general writes division settings straight into public fields (11 places); a field write can't be intercepted from outside | SOS-28 |
| `90-ai-host` | B15 | `game/battle/thread/general/offence/StepArtilleryBombard.java` | `AiHooks.artilleryTarget` | The general sets artillery targets directly | SOS-28 |
| `90-ai-host` | B15 | `settlement/thing/projectiles/Updater.java` | `Hooks.aiCapturing` | The general's trajectory checks used a static vector shared with the trajectories job inside the step; its thread needs its own (SOS-23 data race) | SOS-28 |

## Rig-only patches

`rig/patches/` holds patches that only the test rig's mod (SOSDET-rig) carries. They never reach a release, and the build refuses one that touches a game file the engine patches.

| Hook | Game file | What it does | Card |
| --- | --- | --- | --- |
| `90-autoload` | `menu/Menu.java` | Loads the save named in `SOSDET_RIG_AUTOLOAD` at start-up, so rig runs need no clicks | SOS-27 |
| `91-frame-timing` | `view/main/VIEW.java` | Times each frame's game update (`sosdet.rig.FrameTiming`), the SOS-23 measure for the timing rule, in engine and vanilla runs alike | SOS-28 |

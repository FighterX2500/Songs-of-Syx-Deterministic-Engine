# Plan 0017: watching another human's local battle

Card: [SOS-109](https://fighterx2500.atlassian.net/browse/SOS-109). Mod spec 0.36 §8 (SOSGSGMP). Builds on plan 0016 (local battles) and plan 0013 (city views).

## What it does

Any other human can join a running local battle, without asking. Their PC loads the battle from the fighter's PC and runs the same battle, with their own city frozen. Leaving ends their view only.

A watch is a city view whose owner runs a local battle (`View.battle`). The view's entry, freeze, holds, envelope, limits, hashes and thaw all stay as they are. What's new:

- **The join** reloads a running battle on two PCs.
- **The tape** carries what the fighter does in each step.
- **A second PC runs a local battle**, from the tape.

## The owner's decisions (2026-10-30)

1. The bug card comes first: SOS-112, a fighter's transactions were lost.
2. If a join in a running battle fails, joining waits for deployment only. It didn't fail.
3. A slow watcher isn't dropped: it runs at its own pace, behind the battle, and catches up when it can.
4. A pause of several seconds on each join is acceptable, for the fighter too.

## Shape

**Rounds stay bound to world steps.** Each world step of the battle holds k rounds, and the watcher runs the same k in the same step number, later. What varies on a slow watcher is how many steps a second it runs, not how many rounds a step holds.

The first idea was to let k vary on the watcher, with the battle counted in rounds instead of steps. That doesn't hold: the battle reads the world's clock, its `GAME.updateI`, `BattleState.update` and the step's seed, paths and jobs, all once a world step. A round on a separate count would see another step's values. A watcher that runs whole steps later sees the same values, as a viewer already does, and needs no new notion of time.

**The tape** (`LocalTape`) holds, per world step: the rounds, 0 when paused, and the orders. The fighter's PC writes it at the start of each step; a watcher takes it at the start of the same step.

- **The fighter's orders now wait for the next step**, with or without a watcher, so a step's orders are on the tape and every local battle takes the same path. It's one step, which nobody can see. Start, Retreat, Close and every division order are entries like those of a battle between humans (`Hooks.orderCapture`), through `GameAi.apply`, so they run inside the step on both PCs. They don't touch the log's `applied` count, which the world hash reads.
- **World commands** from a fighter still go through the log.
- **The fighter never waits for the tape.** The watcher holds at the last step it has (`watchMayRun`). No PC's pace counts a watcher, as for a view.

## The life of a watch

1. **The ask.** `viewBegin` as for a city. `viewRefusal` allows a fighter as owner while its battle runs and has no result. A fighter can't be a viewer, and a second watcher of the same battle is refused (see Gaps).
2. **The freeze step F.** `viewsAt` sets `View.battle` on every PC.
3. **The fighter at F.** `viewOut` saves the game, takes `GameResync.captureLocal()`, and loads its own save with `GameResync.loadLocal`: a new `BattleState` around the old exiter, with the state a save leaves out (throne timer, deployment, the general's timers). Two PCs that load one save run alike; a loaded and an unloaded game don't (plan 0013). A battle already won or lost counts as still running, so the next step opens its panel again on both PCs, as no load keeps it.
4. **The watcher at F.** `viewIn` saves its own game and loads the same bytes, with a stand-in exiter that never runs.
5. **The steps.** `Session.localRuns()` is true on both PCs for every hook that asks "does this PC run a local battle's game": the world part, the world's clock, the general in the step, `battleView`. Only the fighter generates, sends a result, and takes speed and pause from its player.
6. **Hashes.** The view's hash log compares the whole battle: entities, divisions, projectiles, the world, and the battle's draws. Both PCs use the fighter's city seed already, as `cityPc()` is the owner's on a viewer.
7. **Leaving.** As for a city: `viewEnd`, the thaw at T, the watcher loads its own city at T - 1. The fighter reloads only its world, as every PC outside a view does.
8. **The battle's end.** `localResult` ends the watch on every PC at the result's step R, and its hashes stop there, as the fighter loads its city after R. The watcher's steps after R run no rounds; the world part goes on. `localAfter` waits until the watcher has thawed, since vanilla's after-battle reads `FACTIONS.player()`, which on a watcher is still the fighter's until its city is back.

## A slow watcher

- **A peer** runs behind the host's confirmed step, as a viewer does, and catches up at the view's 3.5×. `ViewLimit.battle` replaces a view's two one-minute limits with one: the watch ends 3 minutes behind (`Session.WATCH_BEHIND_SECONDS`), at the world's 1×.
- **The host** is the one PC every other PC follows, so a slow host would hold the fighter. While it watches, `Session.watchLead` moves the log's horizon on at the agreed speed whether or not the host has run those steps (`AiLog.leadTo`). The peers run on; anything the host logs is stamped past the horizon as ever. The host's own lag counts for the same limit, and it catches up the same way.

Known cost: a host far behind that leaves a watch thaws only when it reaches T, and the peers wait for its thaw report there.

## Tests

- **Unit:** `LocalTapeTest`; `LocalBattleTest` (who can watch, the tape's rounds, the result ending a watch, the after-battle waiting, a watcher leaving the fighter's transactions alone, orders on the tape). SOSGSGMP: `ProtocolTest`, `ViewRequestsTest`.
- **Rig:** `--view 999999999:V:O --view-battle AFTER:END`, `SOSDET_LOCAL_START=N` keeps a battle in deployment, `--in-battle "N divorders H"` gives a human's divisions orders.

## Gaps

- **One watcher per battle.** A second watcher would reload the fighter's game while the first doesn't, and a loaded and a reloaded game don't run alike. The mod spec says any other player can join; the second one is refused with a reason for now.
- **The fighter's camera** goes back to the throne on each join, as the load opens a new battle view. Open panels and the selection go too.

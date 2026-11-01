# Release audit, 2026-11-01

An audit of SOSDET at a1786ab and SOSGSGMP at eddbc59, before the Steam Workshop release. Four read-only reviews covered determinism, saves and packaging, and each repository's docs. Findings below were checked against the code on `main`, and the missing-SOSDET case was run in the real game.

**Verdict:** the engine is sound enough for a release to friends, but not yet for strangers. No finding breaks a session that starts correctly. Three things fail badly on a stranger's PC, and two desyncs can appear in long campaigns.

`gradlew.bat compileJava compileTestJava test build` passes on both `main`s: 299 engine tests, 184 mod tests, and the byte-for-byte rebuild check.

## Before a public release

| # | Finding | Where | What goes wrong | Fix |
| --- | --- | --- | --- | --- |
| 1 | No game build check at run time | `build.gradle.kts:46-58` checks at build time only; `ModInfo.bestVersion` compares the major version only | A 71.x game update mixes patched 71.44 classes into the new game, in single player too | Compare `VERSION` to 71.44 at the earliest entry point, and stop with a message |
| 2 | The mod without SOSDET stops the game | `menu.MpDeals$1` fails to load in `ScriptLoad`; run in the game | "Failed loading assets! file corrupt: SOSGSGMP.jar", with no word of SOSDET. The launcher enforces no dependency | Remove the enum switches that need SOSDET at load, and show "SOS GSG MP needs SOSDET" |
| 3 | The lobby doesn't compare builds | `Message.Hello` holds a name and a key; `Sosdet.version()` has no callers | The Workshop updates each mod separately. A different SOSDET passes the lobby, then desyncs or crashes in `Commands`, `CityReport` or `Engine.java:324` | Put the engine, mod and game builds in `Hello` and refuse a mismatch (protocol 21) |
| 4 | `RDDistance.dists` survives a world load | `dists` is cleared only in the constructor | After an NPC capital moves, a local battle or a view gives the full-loading PC fresh distances and the others stale ones. `OpsOther.proximity` then moves hashed NPC opinion. A world resync keeps the stale values, so the desync can return | Clear it when `Hooks.worldLoads()` changes, as `Power` and `RaidingMap` do |
| 5 | `world.battle.Rnd` is reset by full loads only | `WorldStatics.reset` has one caller, in `loadBegin` | The table isn't saved. Full-loading PCs go back to zeros and the others keep it, so `Util.conquer` differs once a kept table wraps, 2024 draws later | Reset it on every PC in `loadWorld` |

Findings 4 and 5 come from reading the code; neither has been reproduced in a run.

## The owner's decisions

- **Debug mode.** Spec §4 says it is off in multiplayer. The engine only ignores the flag in one simulation check, and the developer tools stay open.
- **Lowest speed.** Spec §4 says 0.5×; `Pacer.MIN_SPEED` is 0.25. A test, `PacerTest:172`, follows the code. One of the two must change.
- **A commander who drops out of a battle with allies.** Spec §16 says to settle this before allies ship, and allies have shipped.
- **Each attacker's own bonuses in an assault** have no code and no card.
- **The scenario suite (SOS-107).** Every recording but the own-city world is in an older format, so `suite.py` reports FAIL and checks almost nothing. There is no automatic regression check today.
- **The laptop's login and Radmin address** are in the history of SOSGSGMP's `docs/plan/HANDOFF.md`. The docs PR removes them from the file. Removing them from history means rewriting a public repository.

## Worth fixing

- **Peer data:** `LocalTape`, `ViewCity`, `AiLog` and `CityReport` trust lengths from the network, so a bad length crashes the game instead of dropping that PC. Low risk between friends, and finding 3 removes the likely cause.
- **Captives:** `sosdetCount` in `ResolverPlayer.Manual.afterBattle` isn't gated on a session, so single-player captive counts differ by up to about 9%.
- **A trade round twice:** `TradeManager`'s `sosdetOtherHumans` skips slot 0 rather than the living human, so once slot 0 is eliminated one human trades twice a round. It is deterministic.
- **Packaging:** the mod's folder ships no LICENSE, and both mods still say version 0.1.0.
- **Null crash:** `GameInputs.java:210-212` throws if a data mod removes one storm variant.

## Open cards

- **SOS-95:** a report-section desync 180 steps after a view ends, repaired by resync. Still unexplained. The audit's unproven note on `ownReports` during a view may be related.
- **SOS-85:** a viewer stopped at a view's load after a screenshot.
- **SOS-18:** distribution, which this release finishes. **SOS-19:** the test plan. **SOS-107:** above.
- **SOS-108:** a spike for citizens on worker threads, not needed for a release.

## Plausible, not proven

- `RDDistance`'s per-human slices `rest[k]` go stale the same way, if a world event reads them before `FACTIONS` updates after a load.
- `HeldTxn` resolves an army supply by index, so an index reused during a freeze could hit the wrong army.
- A removed ally's disbanded army stays in a local battle's saved sides. `Side.div` is null-safe; the rest of `afterBattle` wasn't traced.
- `patches/36-path-heap` claims vanilla's order. The tie-break wasn't proved equal, so single-player paths could differ.
- SyxSpeed is credited in `patches/36-path-heap.patch`, but not in THIRD-PARTY-NOTICES. Whether any code was taken, not only the idea, needs confirming.

## Checked and clean

- **Per-PC sources:** clocks are used only for pacing and limits, `Random` has fixed seeds, and no locale reaches the simulation.
- **Iteration order:** hash maps are used for lookups or independent of order.
- **Mutable statics:** only `Engine.session`, `vanillaFrameUpdateNanos` and the reset game statics.
- **Exactly once:** old reports and transactions are dropped, and out-of-order reports are refused.
- **Recent work:** global events, direct routes, market trade at 6 to 15 tiles, sieges, and local battles' guards.
- **Saves:** a version comes first, save and load match, and older versions are handled. No `ObjectOutputStream` anywhere.
- **Off means vanilla:** all 36 patches were scanned and 95 hook sites traced. The exceptions are listed in `docs/hooks.md`.
- **Patch set:** 352 game files, each owned by one patch, all listed in `docs/hooks.md`.
- **Packaging:** the rig stays out, and there are no third-party libraries at run time.
- **Public repositories:** no secrets, keys or save files in tracked files.

## Not covered

`AiLog`, `LocalTape`, `GameResync`, `GameReport`, `WorldBattles`, `ResultShares`, `DealBook`, `TariffBook`, most of `GameHash`, `HashLog` and `Pacer` got no line review for determinism. Patches 10, 12, 16 and 99 weren't read in full. Spectators weren't traced through the game's UI, nor 8 or more humans on their own. No test has run with more than 3 humans.

# Plan 0021: freezing a missing player's human at the start of a resume

Owner's request of 2026-11-16 (SOSGSGMP, resuming a campaign from the lobby). Builds on [plan 0007](0007-remove-human.md) (removing a human, and removing at the start) and [plan 0020](0020-freeze.md) (freezing a human and its rejoin). Status: built, unit-tested; not yet run in the rig or the game.

## The rule

When a saved campaign is resumed and a player is missing, the host can wait, start without them and remove them (`removeHumanAtStart`), or now start without them and keep them **frozen**. A frozen player can come back into the running game later through the existing rejoin (`rejoinOut`, `rejoinLoad`, `thawHuman`).

## The gap

A freeze in play keeps reading the human's last report, which every PC has in its session. After a load, a frozen human's report comes from the world save (`SOSDET_FROZEN`), which held a report only for humans frozen already. A human playing when the save was made had none there: at a normal resume its own PC sends a load report. A human frozen at the start has no PC, so the first world part would throw (`restoreFrozen`: no report in the save).

## What was built

1. **The save keeps every playing human's last report.** `SOSDET_FROZEN` format 2 writes, after the frozen humans and their waiting transactions, the report the world reads now and its ledger for every human that is neither frozen nor retired (`Hooks.frozenReportOut`), or the one the last load had while the session has none. They live apart from the frozen humans' reports (`sosdet.report.LastReports`, read through `GameFrozen.lastReport`), so `GameFrozen.report(slot)` keeps its meaning. Format 1 still loads, with no last reports. They are not in the world hash, and nothing reads them but a freeze with no report in the session: a PC that is present sends its load report as before.
2. **`Engine.freezeHumanAtStart(slot)`**, every PC, at step 0, as `removeHumanAtStart`: the removal flags with the freeze flag (`removing`, cutoff -1, `removeDue`, `freezing`). No PC waits for that human's load report (the cutoff), and `checkPcHumans` asks no PC for it. In the first world part `retireDue` calls `freezeDue`, which now first restores the save's last report and ledger when the session has none; then the freezer does everything a freeze in play does (stances, regions, vassals, armies home, deals, the siege). From then the human is frozen, exactly as after an in-play freeze.
3. **`Engine.freezeAtStartRefusal(slot)`**: a sentence for the player, or null. Refused for slot 0, a slot that isn't a human's, a human frozen or removed already, after step 0, and a save that holds no last report for that human (a save made before this version).

## Determinism

Every PC loads its own copy of the campaign save, so the last reports must be the same bytes on every PC at a save step:
- `Reports.current()` changes only in the world part (`applyDue`), at the step a report applies, the same on every PC; a report taken or received but not yet applied is pending and isn't saved. The local human's report is built on its PC and decoded on the others from the same bytes, and `CityReport` round-trips exactly. Every human's current report is in the state hash's report section.
- The ledger changes only in the world part (transactions through `Hooks.toCity`) and when a report applies.
- A save between a load and the first world part has no session reports; it writes the ones that load had, the same on every PC.

The restored report then joins the state hash's report section on every PC, so a difference would show at the first hash.

## Tests

- Unit: `LastReportsTest` (round trip, none, a removed slot, bad bytes); `EngineTest.aHumanFrozenAtTheStartReadsTheReportTheSaveHolds` (no PC waits, frozen in the first world part, the freezer sees the report, the save's report and ledger read on) and `aFreezeAtTheStartIsRefusedForAnOlderSaveAndAfterTheStart`. `GameFrozen` itself needs the game, so its format 1 load is checked by reading and in the rig.
- Rig: `--freeze-at-start SLOT` (see `rig/README.md`).

## Found on the way

A freeze in play whose human's reports a load in the session cleared (a battle's end, before the freeze's step) also has no report at its step. It threw there before; it now reads the last report of the save that load came from, which every PC wrote as the battle began. That case is by reading only; no run has made it.

# 0022: logging, the applied and army rings, world parts in the hash (SOS-249, SOS-250)

The engine's half of SOSGSGMP batch 15 (`docs/plan/batch-15.md` there). Owner, 2026-11-24: more logging is
necessary; the engine's sub-hashes are in. Branch `batch-15-1124`, worktree `SOSDET-wt\b15-logging`. This PR merges
before the mod's.

## Why

- NPC army decisions (recruit, move, besiege, raid, intercept, stop, disband, teleport) are simulated by every PC
  itself and logged nowhere. They are where world desyncs start, and today one shows only as "world differs".
- The mod can't log the AI entries and commands it applies, by name: only the last 32 reach `desyncReport()`.
- The hash record carries the world section as one value, so the PC that finds a desync can't say which of the 14
  world parts differs; each PC's report describes only its own state.

## E1. The applied ring (SOS-249)

- `sosdet/ai/AppliedLog`, the `TransferLog` pattern: preallocated primitive arrays, 16,384 entries, a sequence number.
  An entry: seq, step, the AI log's seq, kind, PC, division, command id (or -1), skipped.
- Recorded where `AiLog.stepBegin` applies or skips an entry: every kind, the general's and players'.
- API: `Engine.applied(long afterSeq, List<Applied> out)` returns how many were already overwritten;
  `Engine.lastApplied()`; `Engine.commandName(int id)` (from `Commands.name`); `Engine.aiKindName(int kind)`.
  `Applied` is a public record in the API package.
- Every PC records the same entries.

## E2. The army decisions ring (SOS-249)

- `sosdet/game/ArmyOrders`: preallocated, 8,192 entries. An entry: seq, step, army index, faction index (-1 rebels,
  -2 raiders as `invasionNoticeTake` does), kind (CREATE, MOVE, BESIEGE, RAID, INTERCEPT, STOP, DISBAND, TELEPORT),
  target tile x and y, target (region index, army index or -1).
- Filled by hooks at `WArmy`'s order methods (`setDestination`, `besiege`, `raid`, `teleport`, `intercept`, `stop`,
  `disband`) and the army's creation (`WArmyConstructor.create`). SOSDET already patches `WArmy`
  (`15-humans`, `96-cmd-world`): the hooks go in the patch that fits, listed in `docs/hooks.md`.
- Recorded only inside the step's world part (`Hooks` knows it). Humans' armies go through the same methods from
  their commands, so they are recorded too; the faction says whose.
- API: `Engine.armyOrders(long afterSeq, List<ArmyOrder> out)`, `Engine.lastArmyOrder()`; `ArmyOrder` a public
  record with the kind's name.
- Every PC records the same entries.

## E3. World parts in the hash record (SOS-250)

- Each record also carries the 14 world parts' hashes (`GameHash.worldHash(w)`'s `parts`). `HashLog.FORMAT_VERSION`
  goes to 9; `recordBytes` grows by 112 bytes. Records go out every 30 steps.
- `Desync` gets `worldParts()`: the names of the parts that differ, from this PC's and the other PC's records.
  `Desync.report` prints them on the world line.
- Part names: armies, entities, region owners, factions, relations, raiders, events, deals and the rest
  (deals, tariffs, sieges, traded, passed, carves, frozen, stationed), fboosts, NPC stocks, region populations, NPC
  courts, global events, calendar.

## E4. Item hashes in the desync report (SOS-250)

- At each kept hash step, `WorldKept` also keeps a hash per item in the parts where items are many: factions
  (credits, overlord, stances row), regions (owner, people by race), NPC courts, NPC stocks per NPC. Armies already
  keep a row each. Preallocated arrays, sized at the session's start.
- `describeWorld` prints, for each part that differs, one line per item: `<part> <index> <hash>`, in a fixed order,
  so two PCs' reports compare line by line (the mod's `tools/logdiff.py`).
- The relations and NPC stocks lines say whether they are at the step or now, as they do today.

## Rules

- E1 and E2 only record: no draw, no game state written, nothing the hash reads, no allocation in the step
  (determinism rules; AGENTS.md hot paths). Reading (`applied`, `armyOrders`) is between steps and may allocate.
- E3 and E4 add work at the hash step: the timing rule (requirements §4) applies. Compare the hash's mean and max ms
  (`Stats`) before and after in the rig.
- `docs/api.md`: the new calls. `docs/hooks.md`: the new hooks. `docs/architecture.md`: the code map.

## Tests

- Unit: the rings (wrap, lost count, order); the hash record's round trip with the parts; `Desync.worldParts()`
  naming a part changed on one side.
- Rig: one combined smoke run with two copies (`rig.py city --save <Mogus-72> --copies 2`): no desync; both copies'
  `applied` and `armyOrders` equal; then one copy's world changed by a probe, and the report names the part and the
  item. The short suite (`suite.py --short`) only with the owner's yes.

## State on 2026-11-24

- E1 to E4 built on `batch-15-1124`, not pushed: commit `7cade05` (SOS-249, E1 and E2) and the SOS-250 commit after it
  (E3 and E4, this state). `gradlew.bat build` passes; 469 unit tests, no failures (8 new: the two rings, the applied
  ring through a host and a peer, the hash record's parts and `Desync.worldParts()`).
- E1: `sosdet.ai.AppliedLog`; `Engine.applied`, `lastApplied`, `commandName`, `aiKindName`; record `sosdet.Applied`.
  `skipped` marks the commanders table's skips (a stationed player's own order only where its city doesn't run,
  SOS-234) and world commands skipped in the world part; a city command skipped where its city runs is not marked.
- E2: `sosdet.game.ArmyOrders`; `Engine.armyOrders`, `lastArmyOrder`; record `sosdet.ArmyOrder` with enum
  `ArmyOrder.Kind`. Hooks in `96-cmd-world` (`WArmy`, and `WArmyConstructor.add`, new to that patch) call
  `Hooks.armyOrder` after the game's own checks. A raid called off counts as STOP; a stop of an army that stands, and
  the stop a move, siege, raid, intercept or teleport begins with, are not recorded. Raiders have no `WArmy`, so -2
  never shows.
- E3: hash format 9; the parts are the running hashes `worldHash` already kept, so `Desync.worldParts()` gives the
  first differing part and every later one, and the report's world line says "from part X on". Sections and totals are
  unchanged: the two short recordings' headers now say format 9, so `suite.py --short` checks that.
- E4: item lines `factions|regions|courts|stocks <index> <hash>` after the parts; a region's line holds its owner and
  people. The opinions, stocks and inputs lines that follow are headed "now, not at step N".
- Rig probe `rings <step>` for the smoke run. Not run yet: the rig smoke run and the hash timing (`Stats` hash mean
  and max ms) before and after.

## State on 2026-11-25: the test cycle

- **Rig B15S** (`city --save <Mogus-72> --no-invade --copies 2 --speeds 5,7 --humans 2 --steps 9000 --on-desync exit
  --cmds "wait 90;1: rings 3000;2: rings 3000;wait 10;2: wcorrupt"`): the copies identical to the test desync at 8790;
  both reports say "world ... DIFFERS, from part events per human on (8 of 14 parts)", the part `wcorrupt` breaks; the
  1107 item lines the same on both copies. Hash mean 2.2 ms, max 21 to 23 ms (copy 1 and 2), every 30 steps.
- **Rig B15R2** (`--speeds 1,2 --humans 2 --steps 3000`, `wnewarmy 0`, `wmove`, `wstop`, `wraid`, `rings` at 900, 1500
  and 2100 on both copies): identical to step 3000; both copies' rings the same: applied armyCreate, armyMove,
  armyStop, armyRaid, nothing skipped; army orders CREATE, MOVE, RAID (the stop of a standing army is not one). An
  earlier run, B15R, used `wnewarmy` with no army: the step skipped that create on both copies (the human had an
  army), which the applied ring marked as skipped; `8ef416e` makes the probe show skips and `wnewarmy <army>` create.
- **`suite.py --short`:** field-short PASS, ownworld-short PASS, **assault-short FAIL** (differs from the recording at
  step 300, expected `7671342d8e71b28d`, got `22fb40e4bcda1438`). Main (`bad818f`, a worktree made for it) fails the
  same way, with the same hash; this branch's and main's hashes are the same at every one of its 6000 steps. The
  recording was out of date on main before this batch; not re-recorded here.
- Not measured: the hash's time before E3 and E4 against after, in the same run.

## State on 2026-11-25: review fixes

One commit after the test cycle ("SOS-249, SOS-250: review fixes"). `gradlew.bat build` passes; 471 unit tests, no
failures. Not run in the rig since.
- 1, resyncs: every resync load (`Session` resync and battle resync, `Engine.worldResyncLoad`) cuts both rings at its
  step (`Session.ringsCut`): the entries of later steps go, and apply or come again under new numbers. Numbers are never
  given twice, so the mod's reader (`MpEngineRings`) reads only the new ones; a PC that had run past the step has a gap.
  Unit tests for both rings.
- 2, docs: `Engine.applied`, `Applied`, `AppliedLog` and `docs/api.md` say the entries are the same on every PC but
  `skipped` may differ (SOS-234 stationed orders, own-city city commands).
- 3, hooks: MOVE, BESIEGE (the march), RAID on a region and INTERCEPT record once the path search succeeded, in
  `96-cmd-world` regenerated with `tools/mkpatch.py`; BESIEGE at a siege already there records before its battle. The
  game's stop comes first, so an order after a stop of the same army at the same step takes the stop's place in the ring.
- 4, taped entries: `AiLog.applyingSeq()` is set only around `stepBegin`'s apply, -1 otherwise (a local battle's tape
  too); `Hooks.appliedNow` returns it and `AppliedLog.skip` ignores -1. Unit test.
- 5, city commands: a command whose `Commands.apply` returns false in `GameAi.apply` is marked skipped in the ring, as
  world commands are, on the PCs that run it.
- 6, item lines: only for the first part that differs; regions for part 2 or 10, whichever comes first.
- 7, the stop merge is a known limit: in `ArmyOrders`, `ArmyOrder` and `docs/api.md` "Limits today". No code change
  beyond 3.
- 9, `CmdReader.idOf` allocates nothing; `AiEntry.commandId` calls it.
- 10, rig: `wnewarmy <army>` with a bad index notes it and makes no army.

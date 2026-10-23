# Plan 0003: area-room planning in lockstep (SOS-34, part 2)

Status: in progress (2026-10-23). Design: [ADR 0003](../decisions/0003-room-planning.md). Branch: `SOS-34-room-planning` (local, not pushed).

## Done: milestone 1, new area rooms (merged into the branch; rig-tested 2026-10-23, run P34C)

- **Stash:** `PlanStash` lifts the local plan out of the shared tile stores (`RoomsMap.roomI`, `MapRoomData.Data.dataI`, the `MapDataF` bytes and bits) before the frame's first step and restores it in `Hooks.frameUpdated`. It also lifts around `Engine.resyncSave` and discards at `loadBegin`. `PlanStores` reads those arrays by reflection; the buffers are preallocated for `Room.MAX_SIZE`.
- **Record:** `PlanRecord` (game-free, unit-tested) is the whole plan:
  - blueprint, upgrade, structure, auto walls, build-on-walls;
  - the area as a `TileArea`;
  - furniture as (x, y, item) in master-tile scan order;
  - wall bits.
- **Rebuild:** `Plans` rebuilds a record into the placer, either strictly (a command: skipped everywhere if anything fails) or leniently (the local plan after a meeting: dropped tiles and furniture).
- **Command 6007 `roomBuild`:** hooked in `RoomPlacer.create()` (patch `97-room-plans`). A refurnish is not captured yet.
- **Planning side effects moved out of the step's reach** (patch 97, outside the step only): job deletion in `PlacerArea.place`, the maintenance check in `PlacerArea.clear`, and the light-removal bug in `MapDataF.itemClear`.
- **Rig:**
  - actions: `plan`, `plan-items <n> [group]`, `plan-door`, `build`, `plan-cancel`, `build-plan <key> <w> <h> over|apart`;
  - script `rig/cmds/plans.txt`: a farm, a furnished smithy (storage and work benches), a cancel, and the two meeting cases.
- **Rig test (2026-10-23):**
  ```
  python rig\rig.py city --tag P34C --save Mogus-72-19f86f1e048-47002c-0-121c.save --copies 3 --speeds 5,7,7 --steps 20000 --cmds @rig/cmds/plans.txt
  ```
  - **Result:** 3 copies identical at every step to 20,000, no engine desync (1,332 hashes compared). Every copy applied all 6 Builds (the farm, the furnished smithy, both Builds of each meeting case) and skipped none.
  - **Rooms appear:** an applied `roomBuild` has passed the strict rebuild and `createProblem()` and called `create()`; a failure counts as skipped. So 6 applied and 0 skipped means 6 rooms were created at the same step on every copy.
  - **Runs P34A and P34B** found only script faults, all refused by the UI before any command was captured, and stayed identical:
    - `plan-items` placed only the first furniture group (the smithy's storage), so it had no work bench;
    - `_HOME` is not an area room (a house is one item: the tools' `roomItem` command), so that case was dropped;
    - the doorway landed on the furniture's side, so it now scans from the bottom.
  - **Seen, not caused by planning:** one foreign draw on the host copy in P34B. It's a draw by another thread during a step, on the local stream; run X32B (SOS-32) had one too.

- **Owner play test (2026-10-23, run P34P, `--play --no-invade`, about 7.5 minutes):** 3 copies identical to step 38,801, no engine desync (2,587 hashes compared). The host's log held 2 `roomBuild` (one of 521 bytes, furnished), one `planMode`, one `multiPlace` and 12 speed changes, all applied on every copy.

## Next steps, in order

1. **Done, see above.** Owner play test: `--play` on Mogus-72 (or a field-less city save). The owner plans and builds rooms of several kinds, pauses mid-plan, and plans while time runs. Watch for desyncs and for anything the stash misses: a hash split right after planning means a planning write we haven't found.
2. **Done (2026-10-23), milestone 2, refurnish:** see "Milestone 2 plan" below; the owner's answers are built in (trash = 2001 roomRemove with scatter, leave = discard, late failure = vanilla demolish). Command 6008 roomRefurnish.
   - **Rig runs:** Q34E (3 copies, 14,000 steps) identical, 7 commands applied on every copy. Q34F and Q34G found that a fresh construction site's doorway comes back only if vanilla could place its ceiling job at refurnish time; the script places the door again (`rig/cmds/refurnish.txt`, case 6). Q34H, the SOS-34 smoke run (plan 0002, decision 6): 3 copies identical to step 14,000, no engine desync, 8 commands applied on every copy; the menu save held 0 plan tiles and the 226-tile plan came back after it.
3. **After V0.1 (plan 0002, decision 6), milestone 3, copier, mass copier and saved blueprints:** until then all three are refused in a session with a notice (`CopyTools`). Each becomes a command carrying what it copies:
   - the copier: source room, transform, structure, walls;
   - the mass copier: source bitmap, destination, rotation;
   - a saved blueprint: its `SavedPrint` contents, since it lives in the issuer's profile.

   Inventory section 1.15–1.17 (`docs/plan/notes/placement-inventory.md`) has the call sites.
4. **Open items from part 1, to close in this card:**
   - **Done, saves:** the game's own saves in a session (menu, quick save) lift the local plan too (`PlanStash.beforeGameSave`, `afterGameSave`); rig action `game-save`.
   - **Wall material:** `UtilStructure.set` writes a saved game property on each PC separately. Check whether anything in the step reads it; if so, make it a command or keep it out of the save.
   - **Done, number inputs (`GInputInt`):** in a session the box keeps the typed text while it has the keyboard. Checked by reasoning only; the owner's session tries it.
   - **Rig coverage:**
     - hunter, station, transport and canteen room commands need a save that has those rooms;
     - artillery commands 4007 and 4008 need a field battle with artillery (`rig.py field --artillery N` plus a scripted aim);
     - the employment agent's untested keys (FARM_GRAIN, TAVERN_NORMAL) passed, but rooms skipped 12 actions.
   - **Reflection check:** about 30 reflected members in `Rooms` and the `PlanStores` handles are resolved only at run time. Add a build-time test that resolves them against the game jar.
5. **PR** "SOS-34 part 2: area-room planning", then close SOS-34 when the owner says it's merged and the acceptance criteria hold (a 10-minute owner session building and managing a city; round-trip tests; every listed command exercised).

## Milestone 2 plan: refurnish (proposed 2026-10-23, waiting for the owner's approval)

### Today

- **In a session, the refurnish button removes the room.** `UIRoomPlacer.init(RoomInstanceImp)` calls `RoomInstance.remove`, and part 1's hook turns that into command 2001 `roomRemove` (no scatter). The planner doesn't open. 30 steps later the room is gone on every PC, with nothing refunded. The copies stay identical, but the player loses the room.
- **Vanilla (V71.44), for reference:**
  - Starting removes the room (staff, stock, lights, jobs) and turns its tiles into a plan in which every tile is floored and every item built.
  - "Construct!" (`SFrame`) creates the plan at once.
  - Leaving the tool any other way also builds, if the plan has no problem (`Config.deactivateAction`). With a problem it demolishes it, refunding 75 % of the floor and the furniture.
  - The trash button ("remove room") demolishes it the same way.
  - Removing a built item or a floored tile while planning refunds 75 % at once and clears the floor.

### Behaviour after milestone 2 (owner, ADR 0003)

- Starting a refurnish only plans. The room keeps working (staff, stock, production) until the Build lands.
- **Build** is one command. At its step every PC removes the room and builds the new layout as vanilla's refurnish would:
  - what was kept stays built and floored;
  - what was removed is refunded 75 %;
  - what's new must be built.
- **Leaving the tool without Build** discards the plan, and nothing changes (question 2).
- **The trash button:** question 1.

### Design

1. **Start** (UI, outside the step): a hook at the top of `UIRoomPlacer.init(RoomInstanceImp)` calls the new `Refurnish.start(room)`.
   - Vanilla's own refusal check comes first: `canRemoveAndRemoveAction` without scatter, by reflection. A dump or graveyard with bodies still decomposing shows vanilla's notice and refuses.
   - The stash records the room's tiles as the **room layer**, the simulation's values.
   - The plan is then made from the room as vanilla makes it, but without `remove`:
     - the tiles' room data becomes what `remove` leaves (floored, items built);
     - `RoomPlacer.reconstruct` takes the tiles over through the game's temporary area (`TmpArea.set`, then `replaceAndClear`).

     Every write lands in the tile stores the stash already swaps, except the light removal, which is skipped outside the step (patch).
2. **The stash swaps two layers:**
   - **Lift**, before the frame's steps: the plan's tiles are read and cleared as now, then the room layer is written back. The step sees the live room.
   - **Restore**, after the steps: the room's tiles are read again, since the room worked during the steps, and then cleared. Then the plan is written back.
   - **The room is gone after the steps** (a command removed it): the plan is dropped. The room layer stays and the placer is reset; `init(null, 0)` finds no plan tiles, so it writes nothing.
   - **Plan tiles outside the room that another room took** are dropped, as in milestone 1.
   - **A command used the placer in the step** (a meeting): the plan is recorded. After the steps it is rebuilt by starting from the room again and applying the record leniently (the edits in 4).
   - **Saves** (engine and resync) lift as now. The game menu's save is the open item "Saves" (step 4).
   - **Cost:** two passes over at most 2 × 2,048 tiles per frame, with no allocation.
   - **Ending the plan locally** (Build captured, leaving, trash): the plan's tiles are cleared, the room layer is written back and the placer is reset.
3. **Planning side effects skipped outside the step** (patch 97):
   - in `PlacerArea.clear`, the floor removal (line 75);
   - `UtilStats.removeTile` and `removeItem`, which remove the floor and pay the 75 % refund to the ground and the faction's stock;
   - the light removal in `RoomPlacer.reconstruct` (268) and `Instance.set(TmpArea, …)` (71).

   Refurnish sets the wall material from the room's building, a write to the save's properties. That is the open item "Wall material" (step 4), so the two land together.
4. **Build:** "Construct!" calls `create()`, and patch 97's hook takes its refurnish branch.
   - **New command 6008 `roomRefurnish`:** the room (`Args.room`: index and blueprint, as 2001), then the `PlanRecord`.
   - **Locally:** after the capture the plan is ended (2), so `Config.deactivateAction` then finds no plan and can't build a second time.
   - **The applier**, inside the step, on every PC:
     1. The room must still exist with that blueprint, and the plan's tiles outside it must be free and placable. Otherwise the command is skipped.
     2. As vanilla does: `makeState`, upgrade and degrade, then `remove(mX, mY, no scatter, not forced)`, then `reconstruct`. A dump that got bodies in the meantime refuses here, on every PC alike.
     3. The record is applied as edits, through vanilla's calls, so the refunds and floor clears happen here:
        - furniture not in the record is removed, then tiles not in it, each in scan order;
        - then come the new tiles, the new furniture in the record's order, the record's wall bits on every tile, and the upgrade.
     4. `createProblem()`, then `create()`.
   - **Kept** means furniture of the same item at the same spot, or a tile that was in the room. An item removed and put back identically stays built. Vanilla would count it as new; this way is simpler and slightly kinder to the player.
   - **One edit function** serves both the applier and the local rebuild in 2.
   - **The refund carry** (`UtilStats.resRemoved`) is reset by `reconstruct`'s `init`. Locally it's never touched, so it is identical on every PC.
5. **A late failure:** 4.1 rules out what can change between Build and its step. One case remains: a doorway or a piece of furniture whose placement depends on a neighbour that another command changed within those 30 steps. If `createProblem()` fails there, see question 3.

### Game files: all in patch `97-room-plans`, none of them in another patch

- `RoomPlacer.java` (already in 97): `create`'s refurnish branch, and the light removal in `reconstruct`.
- `PlacerArea.java` (already in 97): the floor clear in `clear`.
- `placement/Instance.java`: the light removal in `set(TmpArea, …)`.
- `UtilStats.java`: `removeTile` and `removeItem`.
- `UIRoomPlacer.java`: `init(RoomInstanceImp)` calls `Refurnish.start`.
- `Config.java`: in a session, `deactivateAction` never builds; the trash button follows question 1.
- `SFrame.java` needs no change: its buttons go through `create()` and `Config`. ADR 0003's list of patched files gets corrected.

### Engine code

- **`PlanStash`:** a second `PlanTiles` (the room layer) and the room. `PlanStores` reads and clears any room's tiles.
- **New `Refurnish`** (adapter, `sosdet.game.cmd`): start, the edits, and the applier.
- **New `RefurnishEdits`** (game-free, unit-tested): from the room's record and the plan's record, which furniture and tiles go and which come, in what order. Tests:
  - kept, removed and added furniture and tiles, and their order;
  - a moved item counts as removed plus new;
  - the two-layer swap on fake stores (`PlanStashTest`).
- **Docs:**
  - `hooks.md`: row 97;
  - `commands.md`: the `UIRoomPlacer.init` and `RoomPlacer.create` rows done, and 6008 added;
  - ADR 0003: the patched files.

### Rig

- **Actions:**
  - `refurnish <key>` opens the first room of that type in the save (Mogus-72 has workshops and farms);
  - `plan-drop-item <n>`;
  - `plan-grow <rows>`;
  - `plan-trash`;
  - the existing `plan-items`, `plan-door`, `build` and `plan-cancel`.
- **Script `rig/cmds/refurnish.txt`:**
  1. A workshop: drop one item, add one, grow by a row, Build.
  2. A refurnish left without Build: no command, and the room unchanged.
  3. A refurnish held open about 600 steps while time runs, then Build. The room works under the plan, and identical hashes are the evidence: any leak splits them.
  4. The meeting: a refurnish open while a `roomRemove` of the same room lands. The local plan is dropped.
  5. A farm grown onto new tiles (the "outside tiles" path).
- **Pass:** 3 copies identical, every 6008 applied and none skipped. Then a short owner play session with refurnishing (5–10 minutes).

### Order of work

1. `RefurnishEdits` and its tests.
2. The two-layer stash and its tests.
3. Start and the side-effect patches (local only). Check: a rig run that keeps a refurnish open through the steps, without Build, stays identical.
4. Command 6008, its applier and `Config`.
5. The rig actions and script, then the rig run.
6. Docs, then the owner's play session.

### Questions for the owner

1. **The trash button during a refurnish ("remove room"):**
   - (a) recommended: it removes the room as the room panel's delete button does (command 2001, the same arguments);
   - (b) it just cancels.
2. **Leaving the tool without pressing Construct:** vanilla builds the plan. Recommended, following "cancel does nothing": the plan is discarded and the room stays.
3. **The rare late failure at the Build's step:**
   - (a) recommended: vanilla's outcome for a refurnish that can't be built, which demolishes the room with the 75 % refund, logged;
   - (b) the room is rebuilt as it was, all built and floored, which costs about 40 more lines (deferred refunds and a rebuild of the old layout).

## After SOS-34 (plan 0002 order: A → B → D → C → E)

- **Stage D, world lockstep (new card):**
  - unhold the world;
  - about 8 world patches: Emissaries, RDRaces render write, RDBoostCache, DipWarPlayer and RaidingUtil caches, DealBools developer flag, PTitles profile value, RDLevelsTmp draft, and the DIP.TMP audit;
  - the world-battle prompt as a command (the world holds while it is open: owner);
  - "the player" identical on every PC;
  - the 44 world, diplomacy and event command rows in `docs/commands.md`.
- **Stage C, city play sessions (SOS-32 M2b):** the owner plays with commands and trade until one 30-minute session is identical.
- **Stage E, the city↔world link:** an ADR in SOSGSGMP, then the step split, city→world snapshots and world→city transactions.
- **SOSGSGMP docs** (the mod is in planning):
  - ADR 0005 (world lockstep and lockstep city viewing), with spec §4, §10 and §13 and the architecture's world row;
  - links to the renamed engine repository.

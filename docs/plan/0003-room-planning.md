# Plan 0003: area-room planning in lockstep (SOS-34, part 2)

Status: in progress (2026-10-23). Design: [ADR 0003](../decisions/0003-room-planning.md). Branch: `SOS-34-room-planning` (local, not pushed).

## Done: milestone 1, new area rooms (merged into the branch, not yet rig-tested)

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
  - actions: `plan`, `plan-items`, `plan-door`, `build`, `plan-cancel`, `build-plan <key> <w> <h> over|apart`;
  - script `rig/cmds/plans.txt`: a farm, a furnished smithy, a house, a cancel, and the two meeting cases.

## Next steps, in order

1. **Rig-test milestone 1.** From `C:\ClaudeFolder\SOSDET`:
   ```
   gradlew.bat build
   python rig\rig.py city --tag P34A --save Mogus-72-19f86f1e048-47002c-0-121c.save --copies 3 --speeds 5,7,7 --steps 20000 --cmds @rig/cmds/plans.txt
   ```
   - **Expected:** all copies identical to the end, every Build applied (commands applied equals the builds), and no `cmdskipped` except where the script expects one. The agent's report lists the expected result per script line.
   - **Check that rooms really appear:** compare with a run without `--cmds`, which should end on a different hash, or add a rig check that counts rooms by blueprint.
   - **Likely trouble spots:**
     - `createProblem()` on a rebuilt plan;
     - furniture order and sprite data;
     - the meeting case's lenient rebuild;
     - restore after a tile was taken.
2. **Owner play test:** `--play` on Mogus-72 (or a field-less city save). The owner plans and builds rooms of several kinds, pauses mid-plan, and plans while time runs. Watch for desyncs and for anything the stash misses: a hash split right after planning means a planning write we haven't found.
3. **Milestone 2, refurnish (owner: the room stays until Build; cancel does nothing):**
   - starting a refurnish only plans, without `RoomInstance.remove`;
   - the stash keeps a second layer per tile (the live room's values) and swaps it in around each step;
   - Build is one command that removes the old room and builds the new plan at one step;
   - patch `UIRoomPlacer.init(RoomInstanceImp)`, `Config.deactivateAction` and `SFrame`, and stop the floor-clear and refund side effects of `PlacerArea.clear`/`UtilStats.removeTile` for a refurnish plan outside the step.
4. **Milestone 3, copier, mass copier and saved blueprints:** each becomes a command carrying what it copies:
   - the copier: source room, transform, structure, walls;
   - the mass copier: source bitmap, destination, rotation;
   - a saved blueprint: its `SavedPrint` contents, since it lives in the issuer's profile.

   Inventory section 1.15–1.17 (`docs/plan/notes/placement-inventory.md`) has the call sites.
5. **Open items from part 1, to close in this card:**
   - **Saves:** a save made through the game's menu during a session still contains the local plan. Lift around `GameSaver` too, or route session saves through the engine only.
   - **Wall material:** `UtilStructure.set` writes a saved game property on each PC separately. Check whether anything in the step reads it; if so, make it a command or keep it out of the save.
   - **Number inputs (`GInputInt`):** with the 30-step delay, typing a multi-digit number comes out wrong. Keep the typed text while focused.
   - **Rig coverage:**
     - hunter, station, transport and canteen room commands need a save that has those rooms;
     - artillery commands 4007 and 4008 need a field battle with artillery (`rig.py field --artillery N` plus a scripted aim);
     - the employment agent's untested keys (FARM_GRAIN, TAVERN_NORMAL) passed, but rooms skipped 12 actions.
   - **Reflection check:** about 30 reflected members in `Rooms` and the `PlanStores` handles are resolved only at run time. Add a build-time test that resolves them against the game jar.
6. **PR** "SOS-34 part 2: area-room planning", then close SOS-34 when the owner says it's merged and the acceptance criteria hold (a 10-minute owner session building and managing a city; round-trip tests; every listed command exercised).

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

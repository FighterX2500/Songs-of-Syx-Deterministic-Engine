# Room placement inventory (V71.44) — what planning writes, who reads it, how to rebuild

All paths are relative to `<game>/info/SongsOfSyx-sources/`. Line numbers are from the shipped V71 source.
"Saved" means the structure is written by the game save.

## 0. The pieces

- `PLACEMENT` owns one `Instance` and one `RoomPlacer` (settlement/room/main/placement/PLACEMENT.java:30-36). `ROOMS` creates it once (settlement/room/main/ROOMS.java:133).
- The plan's `Instance` is a real `Room` registered as a **singleton** in `RoomsMap.rooms[]` (`super(m, p, true)`, Instance.java:171 -> Room.java:34 -> RoomsMap.create singleton branch RoomsMap.java:139-148). Its blueprint is a hidden `RoomBlueprint("_PLACEMENT")` (PLACEMENT.java:38) that sits in `RoomBlueprint.ALL` and so is updated every tick (ROOMS.java:740-742 -> PLACEMENT.java:43-45 -> RoomPlacer.update RoomPlacer.java:491-495, which only resets `renderExpense`).
- `Instance.is(tile)` is `ROOMS().map.indexGetter.get(tile) == roomI` (Instance.java:337-339). The plan's tile membership therefore lives **only** in the shared `RoomsMap.roomI[]` array; `Instance` keeps just bounds/area/mCoo/unroofed/upgrade/blue (Instance.java:43-49).
- `RoomsMap.set` (used by `setIndex`) wipes fData and pData of the tile and throws if the tile already has a room (RoomsMap.java:178-184); `RoomsMap.clear` wipes fData/pData too (RoomsMap.java:186-192). Neither updates PATH/ENV (only `RoomsMap.init`, RoomsMap.java:219-229, does, and placement never calls it).
- Shared tile stores the planner writes:
  - `RoomsMap.roomI[]` (RoomsMap.java:26), saved whole (RoomsMap.java:44).
  - `ROOMS().data` = `pData` int per tile (ROOMS.java:124-125, MapRoomData.java:23), saved (ROOMS.java:605). Bit layout (ConstructionData.java:10-44): bit0 dFloored, bit1 dConstructed, bit2 dBroken, bit3 dExpensive, bits4-11 dWall (planning only; the same bits are dResourceNeeded[0..1] once under construction).
  - `ROOMS().fData` item/tile/spriteData/spriteData2 bytes + master/candle bits (MapDataF.java:38-43), saved (MapDataF.java:52-60).

## 1. Every planning action and its writes outside the placer's private fields

Notation: [S] = state is saved in the game save; [R] = render/UI-only cache.

### 1.1 `RoomPlacer.init(blue, upgrade)` (RoomPlacer.java:208-226)
- For each current plan tile: `placerArea.clear(x,y)` (RoomPlacer.java:213-217) -> see 1.3 (shrink): FLOOR clear + refund if dFloored, MAINTENANCE isser side effect, fData itemClear, history, `Instance.clear`.
- `instance.clearRegardless()`, `instance.init(b)` -> `setBlueprint` clears again any remaining tiles (Instance.java:134-143, 145-147).
- Private: resources.clear, history.clear, oldDegrade, state.
- Called at: room tool start (UIRoomPlacer.java:44, 86, 229), tool exit (Config.java:142), `removeArea` (RoomPlacer.java:467), `_PLACEMENT.clear()` (PLACEMENT.java:67-69), `load()` (RoomPlacer.java:245).

### 1.2 Area expand: `PlacerArea.place(tx,ty)` (PlacerArea.java:238-259) and `finishPlacing` (262-282)
- `SETT.JOBS().clearer.set(tx,ty)` (PlacerArea.java:244) -> `PlacerDelete.place` (settlement/job/PlacerDelete.java:28-38): `JOBS().state.clear` -> StateManager.clear (settlement/job/StateManager.java:177-180) deletes the job marker [S], and clears the `TGrowable.job` flag on growables [S]. **Deletes jobs on every tile the drag touches, even if later undone.**
- `door.undo.place` on the tile and its 4 neighbours (PlacerArea.java:245-249) -> `removeWithoutHistory` edits `dWall` bits of plan tiles only (PlacerDoor.java:155-183) [S via pData]; + history.
- `instance.set(tx,ty)` (Instance.java:53-60): `setSoft` -> `setIndex` -> RoomsMap.set: roomI[t]=placement roomI, fData.clean(t), pData[t]=0 (RoomsMap.java:178-184) [S]; `ROOMS().data.set(...,0)` [S]; `TILE_MAP().miniCUpdate` [R]. Reads TERRAIN roof for `unroofed`.
- `ROOMS().data.set(instance, tx,ty, 0)` again (PlacerArea.java:254) [S].
- GUTIL.coos scratch list (PlacerArea.java:252-253, 264-278) — transient.
- `finishPlacing` -> `validateItems` around each new tile: `fData.itemClear` then `fData.itemSet` (re-place) or history+refund (PlacerArea.java:111-158). Re-place keeps `dConstructed=1` on constructed items (PlacerArea.java:148-155) [S]. itemSet draws RND (see 1.4).
- No PATH availability/ENV update is triggered.

### 1.3 Area shrink / undo tool: `PlacerArea.undo.place` -> `PlacerArea.clear(tx,ty)` (PlacerArea.java:34-38, 72-104)
- If `dFloored` (only true for a refurnish plan): `resources.removeTile` (UtilStats.java:52-63): `FLOOR().clearer.clear` (also PlacerArea.java:75) -> removes floor type [S], updates neighbour floors and **PATH().availability.updateAvailability** (Floors.java:849-873); then 75 % area-cost refund with fractional carry `resRemoved[]`: `THINGS().resources.create(tx,ty,...)` [S, scattered resources] and `GAME.player().res().inc(..., CONSTRUCTION, n)` [S, faction stats].
- `SETT.MAINTENANCE().isser.is(tx,ty)` (PlacerArea.java:79) is **not read-only**: if the `pisser` bit is set and no MType validates, it clears the bit (MAINTENANCE.java:192-203; `pisser` saved MAINTENANCE.java:140). MFloor.validate fails on room tiles (MFloor.java:69), so this clears floor-maintenance marks on plan tiles [S].
- Item on the tile: `fData.itemClear` (MapDataF.java:196-225) [S]; **bug**: `SETT.LIGHTS().remove(x, y)` uses item-relative x,y (MapDataF.java:217), i.e. removes point lights at world tiles (0..w-1, 0..h-1) [S, POINTLIGHTS saved POINTLIGHTS.java:59-66]; history; if constructed: `resources.removeItem` refund (UtilStats.java:65-74) -> THINGS + player res [S].
- `instance.clear` (Instance.java:108-132): fData.itemClear, data=0, `clearIndex` -> RoomsMap.clear (fData.clean, pData=0, roomI=0) [S], miniCUpdate [R], and **again** `FLOOR().clearer.clear(i)` if dFloored (Instance.java:119-120). Note this test is buggy: it passes the tile's data *value* (already zeroed at line 116) as a tile index, so it actually tests dFloored of tile 0 (map corner); in practice it almost never fires. Same pattern in TmpArea.java:155.
- `undo.finishPlacing` -> validateItems (PlacerArea.java:53-61).

### 1.4 Area item place: `PlacerItemArea.place` (PlacerItemArea.java:145-163)
- `fData.itemSet(tx,ty, group.item(size,rot), instance)` (MapDataF.java:152-194): writes itemI/tileI/master/spriteData/spriteData2 of the item tiles [S]. **Draws `RND.rInt()`** (MapDataF.java:153) and the sprite's `getData`/`getData2`, several of which draw RND too (RoomSpriteBoxN.java:64, RoomSpriteCombo.java:97). RND is the single global `snake2d.util.rnd.RND` (RND.java:10-25).
- Sprite data depends on neighbours already placed (joins), so it depends on placement order.
- `PlacerItemSingle.set(...)` (called by `RoomPlacer.item`, RoomPlacer.java:307-314) also stores per-blueprint size memory `sizes[]` (PlacerItemSingle.java:82-99) — UI state.
- Render path: `PlacerItemSingle.renderPlaceHolder` calls `sprite().getData(...)` every frame (PlacerItemSingle.java:201, 211) -> RND draws during **render**.

### 1.5 Area item remove: `PlacerItemArea.removeItem` (PlacerItemArea.java:60-99); `RoomPlacer.removeAllItems` (RoomPlacer.java:445-457)
- `dConstructed=0` on item tiles [S], `fData.itemClear` (+ the LIGHTS bug) [S], history, refund via `resources.removeItem` if constructed [S: THINGS, player res].

### 1.6 Rotation / size
- Only private tool state (`PlacableFixed` rot/size; `sizes[]`). No shared writes until place.

### 1.7 Single-item rooms (`usesArea()==false`): `PlacerItemSingle.place` (PlacerItemSingle.java:106-183)
- This is an immediate **commit**, not a plan. With autoWalls+indoors: temporarily fills `embryo.instance` (`instance.clear(blueprint)` + `instance.set` per tile, PlacerItemSingle.java:116-121) -> `door.build(s)` (wall/opening jobs) -> `instance.clear(blueprint)`; then `openingBuild` around reachable tiles (126-140); then `TmpArea` + `fData.itemSet` + `construction.createClean` (161-183). Note `instance.clear(blueprint)` wipes whatever plan the placer held.
- Undo of this tool demolishes a real room: `r.remove(tx,ty,true,...)` (PlacerItemSingle.java:53-57).

### 1.8 Doors / walls
- `PlacerDoor.placer.place` -> `placeWithoutHistory` (PlacerDoor.java:79-83, 127-153): sets `dWall` bits on the plan tiles adjacent to the edge tile [S via pData].
- `PlacerDoor.undo.place` -> `removeWithoutHistory` (PlacerDoor.java:50-53, 155-183): clears those bits [S].
- Edge/opening reads: `UtilWallPlacability.wallCanBe/wallShouldBuild/openingIsReal` (UtilWallPlacability.java:20-110) read TERRAIN, ROOMS map, JOBS getter (`get`, 112-116) and call job placers' `isPlacable` with the static `Job.overwrite` toggled (118-124).
- `AutoWalls` is a private flag, default on (RoomPlacer.java:180-197). `door.init` (PlacerDoor.java:193-205) auto-opens edges that are real openings (refurnish only, RoomPlacer.java:281-283).
- Walls are only written to the world in `create()` (see 1.13) and in 1.7 / copier / prints.

### 1.9 Structure material: `UtilStructure.set` (UtilStructure.java:75-79)
- Writes `PROP.game("ROOM_CONSTRUCTION")` -> `charsSet("STRUCTURE", key)` -> PROP `gameMap` (UtilStructure.java:17, 78; PROP.java:142-144), which **is written into the game save** (PROP.java:96-103). Read back on load (PLACEMENT.java:62 -> UtilStructure.java:46-52).
- Also set by `ToolDefault.java:319`, `Shared.java:38`, `SMaterial.java:122`, `SavedPrintsPlacer.java:125, 141`, and by `reconstruct` (RoomPlacer.java:271-272, 285-286).

### 1.10 Upgrade: `RoomPlacer.setUpgrade` (RoomPlacer.java:203-206) -> `Instance.upgradeSet` (Instance.java:87-93) private; `resources.updatee`. No shared writes.

### 1.11 `removeArea` (RoomPlacer.java:459-471) and `popHistory` (RoomPlacer.java:434-443 -> UtilHistory.java:121-163)
- removeArea: `instance.clear(blue)` -> `setBlueprint` -> `Instance.clear` per tile (1.3 minus refunds), then `init` (1.1).
- popHistory per entry: `fData.itemSet` (RND, 1.4) or `itemClear`; `instance.clear` / `instance.set` (1.2/1.3 minus jobs/refunds); `door.removeWithoutHistory/placeWithoutHistory` (dWall). Undoing a shrink does not restore dFloored or take back a refund. History grouping uses `GAME.updateI()` (UtilHistory.java:38-43).

### 1.12 Stats / costs (UI reads that write)
- `UtilExtraCost.cache` -> `support(a)` writes `dExpensive` on every plan tile (UtilExtraCost.java:133-145) [S via pData]; uses GUTIL.marker scratch. Triggered by UI (`SStats`, `SFrame.java:108`, `resNeeded`).
- `UtilStats.update` read-only on shared state; cached by `GAME.updateI()` (UtilStats.java:81-85). Same cache pattern: `UtilExtraCost.cache` (UtilExtraCost.java:44-52), `PlacerDoor.update` (PlacerDoor.java:207-252, toggles static `Job.overwrite`).

### 1.13 `create()` (RoomPlacer.java:327-351)
- `createProblem()` must be null (throws otherwise).
- Indoors + autoWalls: `door.build(structure)` (PlacerDoor.java:391-422) -> per edge tile `UtilWallPlacability.openingBuild/wallBuild` (UtilWallPlacability.java:126-165): `JOBS().clearer.set` (job delete) or job `placer().place` for caveFill / tunnel / build_structure wall/ceiling [S, jobs]; job placement reads `JOBS().planMode` (settlement/job/Placer.java:73, 87).
- `ConstructionInit(upgrade, constructor, structure, oldDegrade, state)`; `TmpArea.set(instance,...)` swaps roomI to the TmpArea instance without cleaning fData/pData (TmpArea.java:101-123, RoomsMap.replace 194-198).
- `createWithConstructionData` keeps data bits 0-3 (floored/constructed/broken/expensive) and zeroes the rest (incl. dWall) (CONSTRUCTION.java:86-95) -> `pcreate`: rooms with 0 resources and no walls/floor go straight to `blueprint.create` (CONSTRUCTION.java:97-135); others -> `ConstructionBlueprint.create` -> `new ConstructionInstance` (ConstructionBlueprint.java:87-92; ConstructionInstance.java:95-182): recomputes support/foundation (writes dExpensive again, ConstructionInstance.java:97), `JOBS().clearer.set` on every tile (123), candles via `GUTIL.coos().shuffle` (RND, ArrayCooShort.java:124-127), `active` depends on `JOBS().planMode` (145), resource piles with `RND.oneIn` (320, 346), `jobClear` (224), `putFloor` on floored tiles (227-229), `ROOMS().map.init(this)` -> PATH availability + ENV changed + service update (173; RoomsMap.java:219-229), stats.
- `instance.clearRegardless()` (private).
- The UI then calls `deactivate` -> `init(null,0)` on an already-empty plan (SFrame.java:81-86, Config.java:139-144).

### 1.14 Refurnish start: `UIRoomPlacer.init(RoomInstanceImp)` (view/sett/ui/room/construction/UIRoomPlacer.java:95-114)
**The real room is destroyed when the player merely opens refurnish.**
- `ins.makeState(...)`, upgrade, degrade captured (98-101).
- `ins.remove(mX,mY,false,this,false)` (102) -> `RoomInstance.remove` (settlement/room/main/RoomInstance.java:254-295): `stats.finished().remove`, `deactivate()`, employees `dispose()`, room `dispose()` (room-type specific: storage, workers, etc.), per tile `LIGHTS().remove`, `JOBS().clearer.set`, data=0 then `dConstructed=1` on item tiles and `dFloored=1` on all tiles, `blueprintI().removeInstance`, `map.delete` -> rooms[roomI]=null, PATH availability/ENV/service updates over the area (RoomsMap.java:200-229). All [S].
- `placer.reconstruct(a, up, deg, state, b)` (RoomPlacer.java:248-289): `init`, `doBeforePlanning` per tile (spirit dump writes `fData.spriteData=0`, settlement/room/spirit/dump/Constructor.java:143-146), `LIGHTS().remove` per tile (RoomPlacer.java:268) [S], structure from the building terrain -> PROP write (1.9), `instance.set(area, ret)` -> LIGHTS remove again, `area.replaceAndClear(this)` moves roomI from TmpArea to the placement Instance keeping fData and data (Instance.java:62-80, TmpArea.java:137-146), `door.init` auto-openings (dWall). `unroofed` is not recomputed here.
- Exit: `Config.deactivateAction` (Config.java:139-144): if refurnishing and `build` and no problem -> `create()`; then `init(null,0)`. If the player cancels (SFrame.java:58 sets build=false) or the plan has a problem, `init(null,0)` clears every tile with refunds: 75 % of the floor and of each constructed item goes to THINGS + player resources (1.3, 1.5). So "cancel refurnish" = demolish with partial refund in vanilla.
- Refurnish cannot use popHistory beyond the start, removeAllItems or removeArea (Config.java:113-120).

### 1.15 Copier (single room): `settlement/room/main/copy/Copier.java`
- No placement Instance. Tool state only: source `ROOMA room`, `structure`, walls flag `w`, rotation, `BSwap` blueprint (Copier.java:62-65, 214-226).
- `nextStep.place` at rx=ry=0 is an immediate commit (Copier.java:280-397): `wallBuild/openingBuild` around the copy (job placement/delete) [S]; `TmpArea`; `fData.itemSet` per source master item, rotated (RND) [S]; `ConstructionInit(source upgrade, swap furnisher, structure, 0, source.makeState(...))`; `createClean` (data=0) -> construction [S].

### 1.16 Mass copier: `view/sett/ui/room/copy/*` + `CopierMass`
- UI state: `Source` bitmap, `Dest` transform, `RoomChecker.placableBits` (view/sett/ui/room/copy/RoomChecker.java:15; Source.java:11).
- `Second.place` per destination tile (view/sett/ui/room/copy/Second.java:48-63): copies jobs (`j.placer().place`) [S] and calls `RoomChecker.place` -> `CopierMass.copy(rx,ry,destCX,destCY,rot)` at each source room's master tile (RoomChecker.java:74-127): validates every tile (CopierMass.java:87-103), `TmpArea`, `fData.itemSet` (RND), `ConstructionInit(room,...)` (copies upgrade, degrade, state, structure via `findStructure`), `createClean` (CopierMass.java:105-157). Immediate commit.

### 1.17 Saved prints: `SavedPrints` / `SavedPrintsPlacer`
- Prints live in the **profile** file `SavedPrints71` on each PC (SavedPrints.java:34, 44, 77-80), not in the game save. Content: blue key, structure, CHECK string, width, height, `int[] data` (bit0 room, bit1 wall, bit2 roof/opening, bit3 solid, bits4+ item index+1 at master tiles) (SavedPrints.java:131-274).
- `SavedPrintsPlacer.placer.place(tx,ty,rrx,rry)` (SavedPrintsPlacer.java:179-243) runs per tile: wall/opening job per non-room print tile (190-196) [S]; at rrx=rry=0 builds the room: `TmpArea`, `fData.itemSet` per item (RND), `ConstructionInit(0, furnisher, structure(), 0, RoomState.DUMMY)`, `createClean` [S]. Structure comes from the placer's `UtilStructure` (138-145, may write PROP). Immediate commit.
- Bug: `placable` reads world `fData` at print-local coordinates (SavedPrintsPlacer.java:254).

## 2. Simulation reads that see the plan

### 2.1 Does the simulation treat the plan specially?
- Only one sim-side special case: environment emitters skip plan tiles: `getRadius` returns 0 if `placement.embryo.is` (settlement/environment/SettEnvMap.java:400, 411).
- Everything else that tests `embryo`/`placement` is render or placability UI: Addable.java:179, 207; Snow.java:113 (inside `render`, Snow.java:74); farm Constructor.java:197, orchard 428, woodcutter 456, pasture ConstructorOutdoor.java:180, fish Constructor.java:387 (all `renderBelow`/`render`); pasture ConstructorOutdoor.java:89 and stockade Constructor.java:128 (`isPlacable`); FurnisherItemTools.java:52 (placability helper).
- Elsewhere the plan looks like an ordinary room whose `blueprint()` is `_PLACEMENT` with default behaviour (RoomBlueprint.java:71-99: not dirty, strength 400*TILE_SIZE, tile LOS, no environment registration, no employment), `getAvailability` null (Instance.java:262-265), `degrader` null (300-302), `constructor()` = the planned furnisher (267-272), not a `RoomInstance` (so `map.instance.get` null, the round-robin `Updater` skips it, Updater.java:45-49).

### 2.2 Reads that change behaviour on plan tiles (sim code, run inside the settlement update)
`ROOMS().map.is/get/blueprint/blueprintImp/rooma` (true / non-null on plan tiles):
- Humanoid.java:232-235: `RND.oneIn(18) && !map.is(tile)` then two more `RND.rInt0` draws and a floor/dirt write — **RNG stream depends on the plan**.
- AIModule_Idle.java:190 (bench access stat), Activity.java:224, M_PlanSpectator.java:240, ROOM_ACTIVITY.java:105 (`isSpot` rejects plan tiles), StatsNeeds.java:183-184 (dirtiness), StatsEnv.java:247 (road preference), StatsAccess.java:94-95 (access checks skipped when `!registersEnvironment`), AIUtilMoveH.java:24-50 (`unfuck` moves a humanoid off a planned item using `fData.item`), AISUB_walkTo.java:154.
- Path finders: SFINDERS.java:69 (resource dump), SFinderRequest.java:124, SFinderMisc.java:150 (free-tile finders).
- Jobs: Job.java:145 (`problem` -> ROOM_BLOCK; job placement and validity), JobBuildFillCave.java:67, JobBuildRoad.java:218/250, JobBuildStructure.java:120, PlacerActivate.java:56, PlacerRemoveAll.java:49 (+ `map.get(...).remove` on a plan tile returns null from Instance.remove, Instance.java:342-345 -> NPE at PlacerRemoveAll.java:50), PlacerDelete.java:77.
- Maintenance: MFloor.java:29 and 69 (no floor degrade / no maintenance on plan tiles); MRoom.java:38 (degrader null -> invalid).
- Environment: SettEnvMap.java:149 (URBAN base value 1.0 on plan tiles), 390 (floor env value suppressed).
- Terrain/floor: TGrowth.java:287-294 (daily tile update: on a plan tile only grass via planned furnisher's `growsGrass`, the non-room branch is skipped), TBuilding.java:650 and 833 (floor not cleared when a structure is removed on a plan tile), Floors.java:230, 253, 282, 661, 672, 827, 836 (floor matching), TerrainDiagonal.java:30-99, TWater.java:601/605 (ice), Minables.java:109, TFence.java:179-183.
- Animals: AnimalSpawnSpot.java:165 (spawn spot blocked), animal State.java:68, Animal.java:236-268/460.
- Isolation/other rooms: RoomIsolation.java:126 (`blueprintImp.get` returns the **planned** blueprint, e.g. HOME), ROOM_POOL.java:188, WSprite.java:235, SettEnvMonument.java:213 (`blueprintImp` = planned type), PlanBuryCorpse.java:206/259.
- Battle: game/battle/TargetMap.java:47-157 (plan tile has `_PLACEMENT.strength`).
- Placement checks used by in-step commands: `PLACEMENT.placable` (PLACEMENT.java:95-98), `UtilWallPlacability.*` (map.is, lines 24, 70, 129, 148), `RoomsMap.set` throws on an occupied tile (RoomsMap.java:179-180).
- `fData.item/tile/availability/blocking` on plan tiles return planned furniture (MapDataF.java:115-132, 404-421, 542-580) — read by AIUtilMoveH above and any finder that inspects room furniture by tile.
- PATH availability: **not** affected. The cached map is only recomputed by `updateAvailability`, and for the plan `ROOMS().getAvailability` returns null so the tile falls through to FLOOR/terrain (AvailabilityMap.java:73-89, ROOMS.java:754-758). Plan tiles stay walkable with their floor cost.
- `ROOMS().data` bits on plan tiles are read only by room/construction code acting on its own tiles; but they leak into a room later created there via `createWithConstructionData` (CONSTRUCTION.java:86-95) if not zeroed.

## 3. Save/load
- The plan's `Instance` object is never serialized (RoomsMap saver skips singletons, RoomsMap.java:47-55; `_PLACEMENT.save` writes nothing, PLACEMENT.java:54-57; `Instance.readResolve` is a leftover, Instance.java:274-281).
- But its **tiles are saved**: roomI (RoomsMap.java:44), pData incl. dWall/dFloored/dConstructed/dExpensive (ROOMS.java:605), fData items (ROOMS.java:606). The structure choice is saved via PROP (1.9).
- On load, singleton slots survive (RoomsMap.java:73-75), so plan tiles map back to the Instance; `_PLACEMENT.load` -> `placer.load()` walks every tile and `placerArea.clear`s plan tiles (RoomPlacer.java:228-246), i.e. FLOOR clear + refund if dFloored, MAINTENANCE side effect, itemClear (+LIGHTS bug), then `init(null,0)`; then `structure.read()`. A refurnish plan saved mid-way therefore refunds on load; in a fresh process `blue` is null and `UtilStats.removeTile` would NPE at UtilStats.java:54.
- Consequence for lockstep: any save/hash/resync taken while a plan is present contains per-PC data, and loading it runs side effects.

## 4. State needed to rebuild a plan exactly on another PC

Minimal command payload:
1. Blueprint key (`RoomBlueprintImp.key`), upgrade.
2. Area tiles (list or bitmap relative to a bounding box; max 2048 tiles, MAX_DIM 55, Room.java:25-26, PlacerArea.java:209-224).
3. Items: for each, the constructor-global item index (`FurnisherItem.index()`, used by history UtilHistory.java:71-73 and `constructor().item(i)`) or (group index, size, rotation), plus x1,y1 (upper-left; `itemSet` takes x1,y1). Keep a fixed order (sprite data depends on order and draws RND).
4. dWall mask per plan tile (8 bits) or the list of opening edge tiles; autoWalls flag; structure key (TBuilding); `buildOnWalls` (placability only).
5. `JOBS().planMode` of the issuing player (read in create and job placement; a per-PC UI flag, JOBS.java:51-64).
6. Refurnish: source room (mX,mY + blueprint key, plus an identity check), the edits as final state; per-tile dFloored and per-item dConstructed come from the source room in-step. `oldDegrade` and `RoomState` are captured in-step from the source room (UIRoomPlacer.java:99-101).
7. Copier: source room mX,mY, swap blueprint, rotation, dest tile, structure, walls flag, planMode. Mass copier: source rect, dest transform/rot, and per-tile jobs. Saved print: full print contents (blue key, width, height, data[], structure) + rotation, dest, walls flag, swap blueprint — the print is profile data and differs per PC.

Rebuild order with the existing API (all on the game thread inside the step):
1. `placer.init(blue, upgrade)` (public).
2. `placer.structure.set(b)`, `placer.autoWalls.set(on)`, `placer.buildOnWalls.set(x)`.
3. Tiles: `PlacerArea.finishChecking` (resets GUTIL.coos, PlacerArea.java:233-235), then `placer.area().place(tx,ty,null,null)` per tile, then `finishPlacing(area)`; or, via a new hook in the package, `instance.set(tx,ty)` per tile (no job clearing).
4. Items: `PlacableFixed p = placer.item(group)`, `p.sizeSet`, `p.rotSet`, `p.place(x1,y1,0,0)` -> itemSet (RND). Or `fData.itemSet(x1,y1,constructor.item(i), instance)` via hook.
5. Doors: `placer.placerDoor.place(tx,ty,null,null)` per opening, or write dWall masks via hook.
6. Refurnish: `ins.remove(...)` + `placer.reconstruct(a, up, deg, state, b)` first, then remove dropped tiles (`area().getUndo().place`) and dropped items (`placerAreaItem.removeItem`) in a fixed order (refunds happen here, deterministic), then add new tiles/items/doors.
7. Force cache refresh: `resources.updatee()` (UtilStats.java:76-79); `UtilExtraCost.tick` and `PlacerDoor.tick` have no public reset — they are keyed on `GAME.updateI()` and the UI on the issuing PC may already have filled them this tick with the local plan.
8. `if (placer.createProblem() == null) placer.create(); else reject deterministically.`
9. Leave the placer in `init(null,0)` state.

RND draws: planning `itemSet` (MapDataF.java:153) and sprite getData (RoomSpriteBoxN.java:64, RoomSpriteCombo.java:97, others), render-time getData (PlacerItemSingle.java:201, 211); in create: `GUTIL.coos().shuffle` (ConstructionInstance.java:164; ArrayCooShort.java:124-127), `RND.oneIn` (ConstructionInstance.java:320, 346), plus room-type `create` for no-resource rooms. Profile/UI reads: `UtilStructure` default and PROP gameMap (UtilStructure.java:17-23, 46-52), SavedPrints profile file, `PlacerItemSingle.sizes[]`, `showOverlay/showFoundation`, `JOBS().planMode`, static `Job.overwrite` (toggled and restored, UtilWallPlacability.java:118-124, PlacerDoor.java:218-250).

## 5. Designs

### (a) STASH
Keep the vanilla planner writing into the shared maps, but lift the local plan out before every simulation step (and before every save, state hash and resync load) and put it back after.

What to stash per plan tile: roomI (RoomsMap.java:26), pData int (MapRoomData.java:23), fData itemI/tileI/spriteData/spriteData2 bytes and master/candle bits (MapDataF.java:38-43). Lift = write raw "sim" values (roomI 0, data 0, fData 0); restore = write plan values back. Use raw array access, not `RoomsMap.set/clear` (they throw on mismatch and wipe pData/fData, RoomsMap.java:178-192). Refurnish needs a two-layer entry: the tile's sim value (real room's roomI/data/fData) and the plan value, because locally the plan overlays a live room.

Also stash the **placer object state** (Instance bounds/area/mCoo/unroofed/upgrade/blue; UtilHistory; UtilStats/UtilExtraCost/PlacerDoor caches; structure; autoWalls; buildOnWalls; oldDegrade/state), because the commit command (and single-item rooms, which call `instance.clear(blueprint)`, PlacerItemSingle.java:116) uses the same singleton placer in-step on every PC, including PCs whose player is planning something else. Cleaner alternative: a second "commit" PLACEMENT created at the **end** of the ROOMS constructor so its singleton index comes after every existing singleton (RoomSingleton.java:45-46; RoomsMap.java:139-148) and old saves keep their indices.

Cost per step: plan ≤ 2048 tiles (Room.java:25) -> two passes over ≤ 2048 tiles copying ~10 bytes each; negligible.

Conflicts: after the step, a stashed tile whose sim roomI is now non-zero (another command or the player's own Build created a room/construction there), or that is no longer placable, is dropped from the plan, with any item overlapping it; recompute bounds/area. For refurnish, if the source room no longer exists or changed identity, abort the local plan. The player's own Build naturally removes the plan this way when its command lands.

Side effects that stashing cannot undo and must be patched out of local planning (moved to the commit or made local):
- Job deletion on drag (PlacerArea.java:244). Free to drop: `ConstructionInstance` clears jobs on every final tile anyway (ConstructionInstance.java:123, 224).
- MAINTENANCE isser bit clear (PlacerArea.java:79).
- Refurnish: real-room removal (UIRoomPlacer.java:102), LIGHTS removal (RoomPlacer.java:268, Instance.java:71), FLOOR clears (PlacerArea.java:75, UtilStats.java:53) and refunds to THINGS/player res (UtilStats.java:52-74). The commit performs removal, edits and refunds in-step; "cancel refurnish" becomes a no-op (a behaviour change from vanilla's demolish-with-refund, Config.java:139-144).
- PROP write of the structure (UtilStructure.java:78).
- RND draws in planning `itemSet` and sprite `getData`, including render-time ones: run planning and render under a local RNG.
- LIGHTS bug in `itemClear` (MapDataF.java:217): it fires in-step too (create/remove paths), so it is deterministic there, but it must not fire during local planning.

Risks: one missed side effect breaks sync; refurnish overlay adds complexity; caches keyed on `GAME.updateI()` must be reset at commit; an in-step `createProblem` failure on a refurnish must leave the original room intact, so validate before calling `remove`, or rebuild the original.

### (b) SHADOW
Redirect the planner's writes to private arrays read only by UI/renderer. The planner code and every furnisher it calls read the shared world through `ROOMS().map`/`fData`/`ConstructionData` static maps: `Instance.is` (Instance.java:337-339), `MapDataF.itemSet/itemClear/item.get` (MapDataF.java:115-225), `ConstructionData.Map` (ConstructionData.java:48-78), `PlacerDoor` edge/opening maps (PlacerDoor.java:443-487), `UtilPlacability` (all `SETT.ROOMS().fData.*`), `UtilStats`, `UtilExtraCost`, the renderers (RoomPlacer.java:92-178, Instance.java:180-260, ROOMS.render 681-735), each room type's `FurnisherItemTile.isPlacable` and sprite `getData`/`joins` (which read neighbours through world fData), FurnisherItemTools.java:52, pasture/stockade `isPlacable`. A shadow world would need an alternate `ROOMS().map/fData/data` view for all of that code, across dozens of room types. Refurnish is cleaner (the live room is never touched), but the patch surface is very large and any missed read silently shows wrong placability.

### Recommendation: STASH
Keep vanilla planning code intact and add one choke point around the step, the save, the hash and resync, plus targeted patches for the non-tile side effects. Game files a patch would touch:
- settlement/room/main/RoomsMap.java (raw roomI get/set for stash), MapDataF.java (raw byte/bit access; local RNG in `itemSet` when the room is the plan Instance), MapRoomData.java (raw set exists, package-private, line 60).
- settlement/room/main/placement/RoomPlacer.java (state snapshot/restore, `rebuild(plan)` commit entry, cache reset), Instance.java (raw lift/restore, conflict drop), PlacerArea.java (lines 74-79, 244), PlacerItemArea.java (97), UtilStats.java (52-74), UtilStructure.java (78), UtilExtraCost.java / PlacerDoor.java (cache reset).
- settlement/room/main/ROOMS.java (commit placer created last, if chosen) and the step/save hook site (engine's step boundary).
- view/sett/ui/room/construction/UIRoomPlacer.java (refurnish start without `remove`), Config.java (139-144), SFrame.java (58, 78-86) -> send command.
- PlacerItemSingle.java (106-183 single-item rooms -> command), settlement/room/main/copy/Copier.java (280-397), CopierMass.java (77-158), SavedPrintsPlacer.java (179-243), view/sett/ui/room/copy/Second.java (48-63), RoomChecker.java (74-127) -> commands.
- Sprite classes that draw RND in `getData` (RoomSpriteBoxN.java:64, RoomSpriteCombo.java:97, and others), unless planning and render already run under a separate RNG.

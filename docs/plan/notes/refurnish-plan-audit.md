# Refurnish plans: the crash audit (SOS-181)

Read-only audit made after the 2026-11-09 playtest crash (Adam Smasher refurnished a mason's workshop under construction: `ClassCastException` in `JobRoom.getState`, from `Refurnish.plan` → `RoomPlacer.reconstruct` → `PlacerDoor.init` → placability → JOBS getter). Owner: check every other place that can crash the same way. Game V71.44; paths are relative to `<game>/info/SongsOfSyx-sources/`.

## What a refurnish plan leaves on the map

Between steps (render, hover, UI, the placer's own calls), a refurnish plan ([Refurnish](../../../src/main/java/sosdet/game/cmd/Refurnish.java), [PlanStash](../../../src/main/java/sosdet/game/cmd/PlanStash.java)) puts the placer's `placement.Instance` on the room's tiles, with the plan's room data and furniture. Everything else stays: the room or site object, its index, workers and storages, the site's jobs on the JOBS map, the maintenance bits, things on the tiles. Inside the step the room's own tiles are back, so step code is safe.

The plan's room:
- `blueprint()` is the placement factory (`PLACEMENT.java:32`), never the planned room's; but `constructor()` (and so `RoomsMap.blueprintImp`) is the planned room's furnisher (`Instance.java:268`);
- is a `RoomInstanceImp` but no `RoomInstance` (`map.instance` gives null); `icon()`, `name()`, `degrader()` and `remove()` give null.

## Findings

| Place | What fails | Reached outside the step? | Outcome |
| --- | --- | --- | --- |
| `JobRoom.getState`, `get`, `becomesSolid*`, `jobResourcesNeeded` | cast to `ROOM_JOBBER` | Yes: render, and the placer's checks while the plan is made | Batch 4 routed them through `Refurnish.jobRoom`; the stash knew the site only after the plan was made, and making a walled room's plan places its doorways, whose check reads the site's jobs. **Fixed (SOS-181):** the stash knows the site first |
| `MAINTENANCE.isser`, `resource.get` | a read clears the saved bit when no room validates it (the plan's room has no degrader) | Yes: the minimap's maintenance overlay over a plan; the default tool's terrain hover over a tile whose room is gone (vanilla) | A desync source, no crash. **Fixed (SOS-181):** outside the step they leave the bit |
| `UIRoom.hover` (via `ToolDefault`) | throws on the plan's null icon | Only with the default tool active while a plan is down | Not reachable: closing the room tool ends a refurnish plan (`Refurnish.leave`); rig action `plan-switch-tool` checks it |
| `Deleter.place` | NPE on the plan's null `remove()` | Only with the delete tool active while a plan is down | Not reachable, as above |
| `ToolDefault` Ctrl-click | `RoomPlacer.init` clears the plan outside the step | Only with the default tool active | Not reachable, as above |
| `RoomResStorage.render` (pasture, orchard), hunter sprite | read the plan's room data as an amount | Render | Wrong pictures only (phantom piles, gore count); left |
| Room panel, `CONSTRUCTION.hoverButt`, `ModuleIndustry` | the room's own `is()` is false under the plan | UI | Zeros in the panel; left |
| `workshop/Job.java:21`, `refiner/Job.java:23` | cast to `ROOM_PRODUCER_INSTANCE` | No: reached through `RoomResStorage.get(…, ins)`, which needs `ins.is` | Safe |
| `ROOM_HOME` getters, `RoomBlueprintIns.get`, `CONSTRUCTION.state`, `ConstructionBlueprint`, `ConstructionInit`, `ROOM_JOBBER.get`, throne, logistics placers, storage and admin sprites, animal `State` | casts | Guarded by `blueprint() == X` or `instanceof` | Safe |
| `UIHomeAssign.java:154`, `UIRoomPlacer.java:119` | casts to `ROOMA`, `RoomInstanceImp` | Always hold | Safe |

Checks that compare `blueprintImp` and then cast would fail too, since `blueprintImp` gives the planned blueprint; none was found that casts (`PlanBuryCorpse` is step code; `SettEnvMonument`, `RoomIsolation`, `ROOM_POOL`, `WSprite` only compare or check again).

## Verification

`rig/cmds/refurnish-walled-site.txt`: on main, case 1 logs the `ClassCastException` as a `cmdskipped` event; with the fix it runs clean, `plan-maint` keeps the bits, `plan-switch-tool` finds no plan tile left, and every copy stays identical. `refurnish.txt` and `refurnish-site.txt` still pass.

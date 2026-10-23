# 0003: A player's room plan is lifted out of the simulation around every step; Build is one command

Status: Accepted (owner, 2026-10-23) · Card: [SOS-34](https://fighterx2500.atlassian.net/browse/SOS-34) · Builds on: [ADR 0002](0002-player-commands.md), [plan 0002](../plan/0002-city-and-world-lockstep.md) decision 5

## Context

The owner decided (plan 0002, decision 5) that a player plans an area room on their own PC, hidden from the simulation, which also works while paused, and that Build sends the whole plan as one command. In V71.44 the plan is not local. The research behind this ADR (scratchpad `placement-inventory.md`, SOS-34) found:
- **Where the plan lives:** it is a real room, the singleton `placement.Instance` with blueprint `_PLACEMENT`. Its tiles live only in the shared tile stores, all three saved: `RoomsMap.roomI[]`, `ROOMS().data` (an int per tile: floored, constructed, broken, expensive, and the planned wall bits) and `ROOMS().fData` (the furniture bytes).
- **Reads:** the simulation reads those stores everywhere, for example people's movement and its random draws, job placement, free-tile finders, grass, isolation and needs.
- **Side effects:** planning also deletes jobs where the player drags, clears a maintenance bit, writes the structure choice into the save's properties, draws random numbers when furniture is placed, and removes point lights through a game bug. Starting a refurnish destroys the real room at once, and cancelling it demolishes the room for a 75 % refund.

## Decision

**Stash (owner, 2026-10-23).** Vanilla planning code keeps writing the plan into the shared stores, where the UI and renderer see it.
- **Lift:** before the first step of every frame, and around every save, state hash and resync, the engine lifts the plan out. It records, for each plan tile, the room index, the room data and the furniture bytes, and writes back what the simulation would hold without a plan.
- **Restore:** after the frame's steps, it puts them back.
- **Placer state:** the planner's own fields (blueprint, bounds, upgrade, history, structure, walls) are not touched by a lift, so a plan survives any number of steps unchanged.
- **Cost:** two passes over at most 2,048 tiles per frame.

**Build is one command.** The planner's `create()` captures a plan record and does not create: blueprint, upgrade, structure, auto-walls, the area's tiles, every furniture item in placement order, and the wall bits. The UI then clears the plan as it does after a vanilla build. Every PC applies the record inside the step:
1. initialise the planner;
2. place the area;
3. place the furniture in the recorded order (its random draws come from the step's stream);
4. set the walls;
5. reset the planner's tick-keyed caches;
6. check `createProblem()`, skipping the command on every PC alike if it fails;
7. call `create()`.

**A plan meets a commit.** When a command applies a plan inside a step while this PC has its own plan lifted, the engine first records the local plan. Then it applies the command, and after the step it rebuilds the local plan from the record instead of restoring the raw tiles. Tiles a new room now occupies are dropped, together with the furniture on them. The local undo history is lost in that case only.

**Side effects that a lift cannot undo leave planning** (in a session, outside the step):
- Job deletion on drag is skipped. `create()` and construction delete the jobs on the final tiles anyway.
- The maintenance bit is not cleared by a planning read.
- The light-removing bug in `itemClear` does not fire.
- Random draws already go to the non-simulation stream outside the step (`20-rnd-streams`).

**Refurnish keeps the room (owner, 2026-10-23).**
- Starting a refurnish plans only: the real room keeps working until Build.
- Locally, the plan is drawn over the room. Around each step, the room's own tile values go back in place of the plan's (a second layer per tile).
- Build is one command: it removes the old room, then builds the new plan, at the same step on every PC.
- Cancelling does nothing.

**Commits without a plan.** The copier, the mass copier and saved blueprints already commit at once; each becomes a command carrying what it copies. A saved blueprint lives in the player's profile, so its contents travel in the command.

## Options rejected

- **Every planning step as a command:** the owner's choice of local planning. Commands take 0.5 s, nothing runs while paused, and every PC would carry a draft.
- **A shadow layer the UI alone reads:** the planner and every room type's placement and sprite code read the shared stores directly, so dozens of room types would need changes.
- **A second planner just for commits:** a new singleton room changes the room indices and old saves. Rebuilding the local plan in the rare case of a meeting is simpler.

## Consequences

- **Rebuild fidelity:** a plan record must rebuild exactly. Sprite data depends on the order furniture was placed and on random draws, so the order is part of the record and the draws happen inside the step on every PC alike.
- **Refurnish:** it no longer removes the room when it starts, and cancelling no longer demolishes it. The owner chose this behaviour change.
- **Saves:** a game saved through the UI during a session would contain the plan. Saves in a session go through the engine's save barrier, which lifts the plan first.
- **Patched files:** the side-effect hooks touch `PlacerArea`, `MAINTENANCE` (or its caller), `MapDataF`, `RoomPlacer.create` and the refurnish UI (`UIRoomPlacer`, `Config`, `SFrame`). The copier, mass copier and prints add their own call sites.
  - *As built (SOS-34, patch 97-room-plans):* `MapDataF`, `Instance`, `PlacerArea`, `RoomPlacer`, `UtilStats`, `UIRoomPlacer`, `Config`; the number box `GInputInt`; and, until they are ported after V0.1, the copier, mass copier and saved blueprints are blocked in a session at `Copier`, `ROOM_COPY`, `SavedPrintsPlacer` and `UICopier`. The stash itself reads the tile stores by reflection, in the adapter.

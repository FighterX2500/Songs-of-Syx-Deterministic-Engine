# Plan 0005: humans placed at world setup (addHuman)

Card: [SOS-55](https://fighterx2500.atlassian.net/browse/SOS-55) · Date: 2026-10-25 · Status: merged (PR #35); approved by the owner 2026-10-25 (one PC builds, every PC loads; small NPC states around every human) · Mod spec: §5 Factions and starting locations · [ADR 0004](../decisions/0004-several-humans.md)

Game paths are relative to `<game>/info/SongsOfSyx-sources/`. Nothing here has been run yet.

## 1. Goal

The mod's setup (SOSGSGMP SOS-56) hands the engine the confirmed terrain and the lobby's humans, each with a race, a faction name, a banner and a picked world location. The engine builds the world around them: a capital at each location, then NPC countries and routes as vanilla makes them. Every PC then plays that world, each as its own human.

Today a human is made only by the rig (`GameHumans.add`): an active NPC with a capital becomes a human, then the game is saved and loaded. ADR 0004 says setup should also turn a free location into a human, before the session. That is this card.

## 2. How vanilla builds the world around one player

- The location stage (`view/world/generator/StageCapitol.java`) stores the picked tile in `WORLD.GEN().playerX/playerY` and runs `StageCapitol.generate()`: buildings, regions, roads, paths, entities, then region data (`RD`).
- **Regions** (`world/map/regions/Gen.java`): `GenPlayer.gen()` reserves a square of `WCentre.TILE_DIM` tiles at the picked tile for one region, `WREGIONS.player`. `GenAssign` then grows every region, and the player's region gets the player's name.
- **Region data** (`world/region/Gen.java`): `generatePlayer` gives the player's region to `FACTIONS.player()` as its capital. It keeps one neighbour free (`handsOff`) and puts a few small NPC states around it (`sanctified`). Then `generateKingdoms` fills about three quarters of the remaining regions with NPC realms, through `FACTIONS.activateNext`.
- Everything assumes one player region: `WREGIONS.player`, and the `FACTIONS.player()` lines in `GenInit`, `GenName` and `RD`.

## 3. Design

**API, outside the session, on the PC that builds the world:**

```java
Engine.locationProblem(int tx, int ty, int[] otherTx, int[] otherTy) // null, or why not: terrain, or within 6 tiles of another human
Engine.buildWorld(HumanStart[] humans)  // after the terrain is confirmed; humans[0] is the host
```

A `HumanStart` holds the race key, faction name, banner (vanilla's banner data) and location tile. `buildWorld`:

1. Sets slot 0's `Player` (the host) from `humans[0]`, as the vanilla stages do.
2. **Regions (patch):** `GenPlayer` reserves one square per human, at each location: the host's in `WREGIONS.player` as now, the others in the next free regions. `GenAssign` and the rest run unchanged.
3. **Region data (patch):** `world/region/Gen.generatePlayer` runs once per human: slot 0's as now. For each other human, its region becomes an NPC realm through `FACTIONS.activateNext`, with the same free neighbour and small NPC states as vanilla gives the player. Then `generateKingdoms` runs as before.
   - The humans' realms are made first, so they take NPC slots 1 to N−1 in order. That is the order `GameHumans.add` needs ("humans join in slot order").
4. **Humans:** `GameHumans.add(slot)` turns each of those realms into a human, the tested SOS-46 path. Then race, name and banner are set from the `HumanStart`, not copied from the NPC.
5. **Rest of setup:** `ROADS`, `PATH`, `ENTITIES` and `WORLD.initBeforePlay()`, as `StageCapitol.generate()` runs them.
6. The world is saved. Every PC, the builder included, loads that save before step 0, as the rig does after `makehuman` today.

**One PC builds; every PC loads its save (recommended).** World generation draws from the game's global random stream and from per-frame print callbacks. Making it identical on every PC would mean auditing all of world generation for determinism. Building once and sending the save (about 3 MB for Mogus-72) avoids that. The mod sends the save over its network, as it will send resync saves. The map step's terrain hash stays a check before Confirm.

**Routes (spec §5: a route between any two humans 6–15 tiles apart).** `GenPlayer.fixWays` → `WORLD.TERRAIN().secretFixWays()` already makes the terrain traversable. The plan is a rig check after `buildWorld`: a path exists, by land or water, between every pair of humans 6–15 tiles apart. If the check fails on test maps, the fix is a road between them; that is decided then. It never failed: `GeneratorValidator` joins every legal city site. The market's walk was the gap, and SOS-120 closed it: two humans 6 to 15 tiles apart with a trade stance are each other's partners along that path.

**The 6-tile rule** is checked twice:
- by `locationProblem`, which the mod's picker calls on every PC;
- by `buildWorld`, which refuses a list that breaks it.

## 4. Patches

To `world/map/regions/GenPlayer.java`, `world/map/regions/Gen.java` or `GenInit.java` and `GenName.java` (each human's region name), and `world/region/Gen.java`. All are setup code, never run inside a step. Each gets a `docs/hooks.md` row: the generator has no API for a second player region, and reflection can't add one.

## 5. Milestones

| | What | Verified by |
|---|---|---|
| M1 | `HumanStart`, `locationProblem` (terrain, 6-tile rule, at most 16 humans) | Unit tests for the rules |
| M2 | Patches for N human regions; `buildWorld` with `GameHumans.add` | Build; the rig command below |
| M3 | Rig `newworld <size> <seed> <x,y;x,y;...>`: copy 1 builds a world with 3 humans on a fresh map, saves, and every copy loads it. A short smoke run of 3 copies, 3 humans. The route check. | Smoke run identical; route check passes; a save → load round trip keeps each human's slot and capital |
| M4 | `docs/hooks.md`, README, requirements §3 | |

A one-human world built through the vanilla stages must not change: its world hash at step 0 is compared before and after.

## 6. Open questions for the owner

1. **One PC builds, every PC loads the save** (recommended), or every PC builds the same world?
2. **Small NPC states around each human:** vanilla puts a few around the player. Every human gets them too; with 16 humans on a small map, that leaves fewer kingdoms.

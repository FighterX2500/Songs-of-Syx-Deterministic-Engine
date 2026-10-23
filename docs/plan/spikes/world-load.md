# Spike: loading only the world into a running game (SOS-38)

Date: 2026-10-23 · Card: [SOS-38](https://fighterx2500.atlassian.net/browse/SOS-38) · Epic: SOS-22 · Code: the rig's `worldat` command (`rig/java/sosdet/rig/Rig.java`) and `rig.py --world-at` · Feeds: stage E of [plan 0002](../0002-city-and-world-lockstep.md) and [ADR 0005](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0005-world-lockstep-and-city-world-link.md)

## Question

ADR 0005 resyncs the world without touching the city: a PC whose world differs loads the host's world, and its city keeps running. Can the game load only the world's part of a save into a running game? How long does it take, how big is it, and does the loaded world then run like the host's?

## Answer

**Yes, and every PC reloads.**
- A world-only load from a vanilla save into a running game takes about **0.25 s**.
- The part of the save it needs is **about 0.6 MB compressed**.
- **When every PC loads the same world at the same step, the host included,** the copies stay identical at every step, every engine hash section included (W38C).
- **A PC that keeps its running world drifts from one that reloaded:** an in-place load is not transparent (W38A, W38B). This is the same rule the full resync follows.

## How a save is laid out

`GameSaver` writes `GameSpec`, then two lists of named sections ("before", then "all"). Each section has its key and its length in bytes, and the loader skips a key it doesn't know. So one section can be loaded alone, without touching the rest of the save.

Mogus-72's sections, uncompressed, with the compressed size of the big ones:

| Section | Bytes | Compressed | Holds |
|---|---|---|---|
| `SETT` | 22,987,244 | 2,758,346 | the city |
| `FACTIONS` | 3,030,143 | 364,199 | every faction, the player's included; trade; diplomacy |
| `BATTLE_THREADS` | 2,523,293 | 7,503 | battle jobs |
| `WORLD` | 1,456,695 | 225,032 | the world map's resources (regions, armies, entities, paths, battles, log) |
| `ARMIES` | 671,884 | 10,277 | battle armies |
| `RAIDING` | 465,975 | 34,723 | raiders |
| `MESSAGES` | 210,931 | 36,886 | messages |
| `TOURISTS` | 49,076 | 6,239 | tourists |
| `TEMP_EVENTS` | 17,660 | 897 | |
| `EVENTS` | 2,147 | 548 | |
| 30 small "before" sections | about 125 KB | | game data layouts (`RDR`, `RDF`, `ADARMY`, `ADFACTION`, `STATS` and so on) |

The spike loads `WORLD`, `FACTIONS` and `RAIDING`: about 4.9 MB uncompressed and 0.62 MB compressed, against 3.4 MB compressed for the whole save.

## The runs

Mogus-72, 3 copies at speeds 5, 7 and 7, no invasion. At step 3,000 copy 1 saved the whole game through the game's own save pipeline (`GameResync.save`) and ran on. The loading copies held at step 3,000 until the save was written, loaded the three sections from it into their running game (the same reading loop as `GameSaver.load`, through `Savable.load`), and ran on.

| Run | Who loads | Result |
|---|---|---|
| W38A, 9,000 steps | copies 2 and 3 | The state stayed identical to step 9,000: every per-step city hash, every engine section but one, the end hashes. The engine's **clock** section differed from step 3,210: different draw counts. |
| W38B, 3,600 steps, draw trace from 3,000 | copies 2 and 3 | The same split. At step 3,183 copy 1 creates an NPC-to-NPC trade caravan (`TradeManager.ship` → `Shipments.create` → `WPATHING.rnd`, one draw) and copies 2 and 3 don't. The world hash leaves caravans out, so only the clock caught it. |
| **W38C**, 6,000 steps | **every copy, copy 1 too, right after its save** | **Identical at every step to 6,000, no engine desync (400 hashes compared).** |

Load times: 246–351 ms per copy. The parts: `WORLD` 79–162 ms, `RAIDING` 57–77 ms, `FACTIONS` 7–14 ms. Saving the whole game took 1.1–1.6 s.

## Why one load isn't transparent

The loaded state is the host's, but not every value the running game keeps is in the save. The likely one here: an NPC-to-NPC shipment only becomes a caravan while the world has fewer than 200 fast entities (`TradeManager.ship`, `WORLD.ENTITIES().allFast().size() < 200`); otherwise the goods are delivered directly. A world that keeps running and one that was just loaded can count a different number of entities. This is not confirmed: W38C makes it unnecessary to chase.

## What this means for stage E

- **World resync:** every PC, the host included, loads the host's world sections at the same step. This is the same rule as the full resync (owner, 2026-10-22).
- **The world's sections aren't purely the world's:**
  - `FACTIONS` also holds the player faction's own data (tech, titles, credits, trade settings), which ADR 0005 gives to the city. Loading it would overwrite this PC's city values with the host's.
  - `RAIDING`'s load can cancel an invasion in the city (`RaidingCurrent.clear` → `SETT.INVADOR().cancel`).
  - `WORLD`'s load re-inits the city's world area (`initCity`).

  Milestone 2 has to split the player's city-owned part out of the world load, or reapply it from the PC's own city after the load.
- **Not tested here:** a world load into a PC whose city differs from the host's. In this spike every copy ran the same city. That needs city reports (milestone 2), and it is the follower test in SOS-38's final smoke run.
- **The world hash leaves out caravans** (and other world entities besides armies). In W38A only the clock caught the difference. Milestone 2 should add world entities to the hash's world section.

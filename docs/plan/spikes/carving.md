# Spike: carving a city-state into a running world (SOS-184)

Date: 2026-11-12 · Card: [SOS-184](https://fighterx2500.atlassian.net/browse/SOS-184) · Epic: SOS-22 · Code: `sosdet.game.GameCarve`, the rig's `carveat`, `worldresyncnext`, `wresyncsavegame` commands and `wregion`, `whumanregions`, `wlayout`, `wcarvespots`, `wcarveprobe`, `wcarved` probes, `rig.py --carve`, `--world-resync2`, `--uncarve`, `--resync-savegame` · Feeds: [SOSGSGMP docs/plan/hot-join.md](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/plan/hot-join.md) sections 3 to 6, cards SOS-186 to SOS-190

## Question

Can the host carve a new 3x3 capital region into a living world (Mogus-72), link it by road and path to the town of each region it touches, save it, have every PC load it, and later un-carve it, with every PC's state identical?

## Answer

**Yes.** A carve takes about **25 ms** on the host, outside the step and before a world resync save. Every PC loads the carved world with the existing world resync (the load is 100–160 ms). The copies then run identically.

- The new region gets its region info: climate, moisture and terrain shares, from patch 17's `RegionInfo.init` change (SOS-185).
- It is linked by road and path to the town of each touching region, and an army walks to its centre from each.
- An un-carve puts back the region map, roads and path bits exactly: the layout digest equals the one before the carve, on every PC.

## The runs

Mogus-72, 3 copies, each its own human (`--humans 3`), speeds 5, 7 and 8, no invasion. Copy 1 carves just before its world resync save; every copy, copy 1 too, loads the world from it.

| Run | Carve | Result |
|---|---|---|
| CV1 | inside region 22 at 128,2, step 3,000; un-carve at 9,000 | Identical from 3,000 to 9,000. Copy 1's un-carve brought back the digest from before the carve. The difference at 9,013 was the rig's: copies 2 and 3 weren't held at the second resync step, so they never loaded it. Fixed (`limit` to the next resync step after the first load). |
| **CV2** | **on the border of regions 109 and 14 at 232,2, step 2,000; un-carve at 8,000** | **Identical at every step to 11,000, no engine desync.** Cut from region 109, linked to both towns: 20 road tiles, 25 path tiles changed. An army walks to the new centre in 20 tiles from region 14's town and 11 from region 109's, on every copy. After the un-carve the digest is the one from before the carve, on every copy. |
| CV3 | border, step 2,000; every copy saves its game right after the load | Identical to 5,000. |
| CV4 | CV3's three saves, resumed in fresh processes | Identical to each other to 3,000. Compared with CV3 at the same game time: every hash differs from the first step (see below). |
| CV5, CV6 | the same as CV3 and CV4 with **no carve** | The same split: a fresh process differs from the running copies from the first step. |
| CV7 | border, step 2,000; 34,000 steps (about 1.7 game days after the carve; a day is 18,432 steps) | Identical at every step to 34,000. |
| CM1–CM4 (SOS-186) | the split rule's cases, carved at 2,000, un-carved at 8,000: a big realm's region (breaks away), unowned land (new NPC), a human's region, a big realm's capital region | Identical to 11,000 each. The carve book reaches every copy with the world; each copy's un-carve brings back the layout from before. |
| CS1 (SOS-187) | setup by carving: a new world around human 0, humans 1 and 2 carved | Identical to 3,000; the save and load keep both humans and their carved capitals. |

## Fresh process against running copies

The card asked whether a fresh process and the running copies agree after a carve, to find caches that a load doesn't reset. CV5 and CV6 answer it differently: they show that **a save resumed in a fresh process never matches copies that keep running, with or without a carve.** The world-load spike found the same (SOS-38, W38A/B): an in-place load is not transparent, and every PC must load. A carve therefore needs nothing more than the existing rule.

For hot-join this means the newcomer can't join by resuming a save while the others run on. Every PC, the newcomer included, loads the same world at the same step (SOS-189: the world resync that grows by one human). The one cache found in reading the code, `RDProspects.average`, is reset by `GameCarve.afterLoad` after every world load, on every PC.

## How a carve works

`GameCarve.carve(cx, cy)`, outside the step:
1. **Placement** (`problem`):
   - the terrain check of a human's location;
   - the 5x5 area in bounds, with no big water and no road or path tile;
   - the 3x3 square inside regions;
   - at least 5 tiles from every active region centre and 6 from every human capital;
   - no world entity on the square;
   - the regions it cuts not besieged and not a player's capital;
   - no region split in two by the cut;
   - a free region index;
   - a route to each touching region's town (a dry run).
2. **The square:** its 9 tiles go to a free region index, and `RegionInfo.init` fills the info. Every cut region's info is computed again.
3. **The RD row:** `RD.RAN().randomize`, then 3 runs of `RD.UPDATER().BUILD` and each `RDInit.upers` entry's `init`, as vanilla's region generation does.
4. **Links:** to each region whose land touches the ring around the square, a cheapest route over the square's region and the target's (paths, then roads, then open land). `WTRAV.makeRoad` lays the road, and the path bits are added both ways along it. Vanilla's last generation pass makes the path graph dense inside the route's bounding box.
5. **The undo record:** the old region of each tile, every road and path bit the links changed, and the regions cut and linked.

`uncarve` reverses it:
1. it removes the owner (an NPC that held only this region is removed);
2. it puts back the path bits and roads and the tiles' old regions;
3. it clears the region's info and computes the cut regions' info again.

At load (`Comps.generate`) the game rebuilds the path graph from the path bits and deletes unreached path tiles, so the bits are the whole state.

## What SOS-186 needs

- **The region's name:** a carved region has an empty name; it takes the city-state's name.
- **Picking a spot:** the rig's `inside` and `border` take the first spot in row order, so both landed on the map's top edge (y = 2). The setup and hot-join screens let the player pick. A default pick should prefer spots away from the edge, near a town.
- **The undo record in the save:** the spike keeps it in memory only. The `SOSDET_CARVES` section has to hold it, versioned, with a round-trip test.
- **The owner rules (hot-join.md decision 3):**
  - an NPC realm of 4 or more regions has the cut region break away as a neutral, sanctified NPC, never the realm's capital;
  - unowned land becomes a new NPC;
  - with no free faction slot, there is no split, and the chat says why.
- **Direct links between humans** 6 to 15 tiles apart (hot-join.md decision 4). Tried in SOS-187's setup run (CS1) and dropped: vanilla's path graph joins a region's town to its borders only, so a road through a third region is never walked (two humans 14.6 tiles apart walked 253 tiles beside it). SOS-120's trade route between humans 6 to 15 tiles apart covers carved humans as it covers a classic setup.
- **Regions cut by more than one carve,** and an un-carve after the cut region changed owner: the restore must not undo later changes.

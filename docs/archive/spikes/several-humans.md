# Spike: several human factions as Players in their own slots (SOS-46)

Date: 2026-10-25 · Card: [SOS-46](https://fighterx2500.atlassian.net/browse/SOS-46) · Epic: SOS-22 · Code: throwaway, not committed (`SpikeHumans` in the rig, the rig's `spike*` commands, a rig patch guarding the `FOW` cast, and a runner script) · Feeds: milestone 1 of [plan 0004](../0004-several-humans.md) and ADR 0004

## Question

Plan 0004 makes each human faction a `Player` in its own slot, the same on every PC (option 1). Can the game hold several `Player` objects, one per human slot, and does such a game save and load to the same state? What breaks first when it runs?

## Answer

**The game holds them, and they save and load to the same state, but `Player` must be made safe to build more than once.**
- **Round trip: yes.** Mogus-72 with two NPC slots turned into humans: the save made right after the load differs from the save that was loaded only in the sections that vanilla's own round trip changes (`SETT`, `BATTLE_THREADS`, `COUNTS`). `FACTIONS` is byte-identical. The world hash and both humans' state are identical before the save and after the load.
- **A `Player` assumes it is the only one, in four places:**
  1. it can be built only while the game is created;
  2. the texture cache must have been made with the same number of Players;
  3. it registers game-wide values and locks under fixed keys;
  4. it registers boosters that read its own state for every `Player`.

  The spike works around all four. Milestone 1 needs them in the engine, as patches.
- **What breaks first are the casts expected:**
  - in the first frame, `(FactionNPC)` casts on a region's or a capitol's owner;
  - the first is in the fog of war (`FOW.update`, local view);
  - after that one is guarded, the next is `RDDistance.init`, reached from the player's own trade update.

  These are the 54 casts and 33 `instanceof` branches that milestone 2 turns into `isHuman(f)`. The spike stops there.

## What a `Player` assumes

| # | Where | What happens with a second `Player` | The spike's workaround | Milestone 1 |
|---|---|---|---|---|
| 1 | `PTitles.IconMaker` composes the title icons into the texture atlas | built in a running game: `IOException` from the sprite composer | spare Players built at game creation (`initBeforeGameInited`); a spare takes its slot's index (`Faction.index`, by reflection) when it becomes a human | the spares are built in `FACTIONS`'s constructor, a patch |
| 2 | The texture cache (`cache/data/gameTextureData.cachedata`) replays the composer's calls in order | a cache made without the spares can't be read: `IOException`. The game would rebuild the cache, but the script engine wraps the exception that asks for it | every game in the copy builds the spares, and the copy's cache is deleted so the first game rebuilds it | built in `FACTIONS`'s constructor, the `IOException` reaches the game's `Initer`, which rebuilds the cache once |
| 3 | `PCredits`, `PBonusSetting`, `PLevels`, `PTitles` push values and locks into `GVALUES` under fixed keys | `RuntimeException: Another value has the same key: CREDITS_YEARLY_TURNOVER` | the 4 categories' value and lock maps are set aside while a spare is built and put back after | a patch to these parts: only the first `Player` registers |
| 4 | `BoostCompound` (tech, titles, levels) connects a booster per boostable whose value reads *its own* `Player` for every `Player` it is asked about; `PCredits.VV` answers only for `FACTIONS.player()` | a second `Player`'s tech would boost the first | the spare's connecters are dropped from `BOOSTING`'s list, so only the first `Player`'s boosters exist | a patch: each value and booster reads the `Player` it is asked about (in own-city mode, that human's report) |

Smaller things seen:
- `Player`'s parts also add debug-panel buttons by name, which replace each other harmlessly.
- `PlayerColors.saver` is static, so every human slot saves the same colours. Harmless for the round trip, but per-human banner colours need a per-slot saver.

## The runs

One copy on this PC, with no engine session. Each load saves the game again and compares the two saves section by section right after the load, before any frame. Frames may change state: at speed 0 they still changed `FACTIONS` and `GAME` in the earlier runs.

**Baseline, no humans:** Mogus-72 saved, loaded, saved, loaded, saved.
- The first round trip changes `SETT` (from byte 18,582,890), `BATTLE_THREADS` and `COUNTS`.
- The second changes only `SETT` and `BATTLE_THREADS`.
- The world hash is `f65fb5325783b230` throughout.

So vanilla's own save is not byte-stable: the test is "nothing else differs".

**Humans:** the first two active NPCs not the "other faction" became humans.

| Slot | Name | Capitol region | Regions | Armies | Credits |
|---|---|---|---|---|---|
| 1 | Buksu | 82 | 1 | 1 | 537,077 |
| 2 | Wyninia | 214 | 1 | 1 | 158,129 |

- The save names the human slots in a section of the "before" list, `SOSDET_SPIKE_HUMANS` with a version, which loads ahead of `FACTIONS`. Its load puts a spare `Player` into each named slot, and `FACTIONS` then loads each slot's bytes in `Player`'s layout.
- **Round trip (run 7):** the world hash is `6e11413de92d2f68` before the save and after the load, and the humans' state is identical. Only `SETT`, `BATTLE_THREADS` and `COUNTS` differ, as in the baseline. `FACTIONS` (3,030,143 bytes) is identical.
- **First frame:**
  - run 6: `ClassCastException` at `world.map.fow.FOW:85` (`DIP.get((FactionNPC) from.faction())`);
  - run 7, with that cast guarded by a throwaway rig patch: `ClassCastException` at `world.region.RDDistance:228` (`borders.add((FactionNPC) d.reg.faction())`), reached from `Player.updateSpecial` → `PTrade.update` → `NPCRes.priceBuyP` → `RDDistance.distance`.

## Also found

- **`FACTIONS.active()` leaves the humans out.** It adds slot 0's `Player` and then the NPCs from `npcs`, so after the swap the active count drops from 44 to 42. The engine's world hash loops over `active()`, so it misses the humans' credits and wars. Milestone 1's `FACTIONS` patch puts every human in `active()`.
- **Activity listeners and the trade manager keep the old NPC objects.** In a running game, the spike tells the `FactionActivityListener`s to drop the NPC. Other holders may keep it, `TradeManager` among them. Turning an NPC into a human is setup, before the session, so milestone 1's `addHuman` does it in a fresh game, the way a load does.
- **`FACTIONS.load` casts `otherFaction` to `FactionNPC`.** A human may not be the "other faction" (the spike skips it).
- **Getting the error text.** The game's crash window holds the error, and its error file is written only once the window closes. The spike's runner reads the error from the window's process. The rig needs the same for unattended runs.

## What this means for plan 0004

- Option 1 stands. Plan 0004 asked whether (a) or (b) holds the Players. Both need several `Player` objects, so the open question was really whether `Player` allows that. It does, with the four patches above.
- **Choice (a): spare Players swapped into the slots the save names.**
  - It keeps every NPC in its slot, so faction-indexed arrays in old saves keep their meaning.
  - The spares come from `FACTIONS`'s constructor: a fixed count, the same on every PC and in every game, so the texture cache always matches.
- Milestone 1 gains:
  - the `Player` patch: register once, read the faction asked;
  - spares built in `FACTIONS`'s constructor;
  - `active()` with every human;
  - the save section that names the humans, loaded ahead of `FACTIONS`.
- The casts are milestone 2's work as planned. Of the two seen, one is in the step (`RDDistance`) and one is outside it (`FOW`, local view, G8).

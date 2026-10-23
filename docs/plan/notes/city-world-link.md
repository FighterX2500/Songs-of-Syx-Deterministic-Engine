# The city↔world link: what crosses, and how stage E builds it (SOS-38)

Date: 2026-10-23 · Card: [SOS-38](https://fighterx2500.atlassian.net/browse/SOS-38) milestone 1 · Decision: [ADR 0005](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0005-world-lockstep-and-city-world-link.md) · Inventory: [city-world-link-scan.md](city-world-link-scan.md) · Spike: [world-load.md](../spikes/world-load.md)

Milestone 1 makes no game changes. It answers three questions: what the world reads from the city and writes into it, how `EVENTS` splits, and whether the world can be loaded alone. Milestones 2 to 4 build on the answers.

## What crosses the boundary

The full list, with every site, is in the scan. In short:

| Direction | Kinds | Step sites | Travels as |
|---|---|---|---|
| A. World reads the city | 10 (+1 that needs nothing) | about 140 | report fields |
| B. City writes into the world | 9 | about 40 | report inputs, stamped (B6 is the exception) |
| C. World writes into the city | 9, of which 6 matter | about 49 (11 are UI commands) | transactions |
| D. Objects that are both | 12 | | split by owner |

- **The report:** about 3–4 KB, or up to about 8 KB with per-division detail for 120 divisions. That matches ADR 0005's "a few KB".
- **What fills it:**
  - stock and trade settings per tradable (A1, A2);
  - credits (A3);
  - population by race and class (A4);
  - capitol garrison, power and invasion state (A5);
  - city division settings (A6);
  - boosts (A7);
  - data-driven values (A8);
  - laws per race (A9);
  - entry state (A10);
  - inputs: soldier joins (B1), supply deposits (B2), capitol population (B3), invasion results (B4), mercy (B5).

## The step split

`GAME.update` runs its resources in construction order:

`ScriptEngine, TIME, Intervals, TmpBoosting, SETT, Armies, BattleThreads, GCOUNTS, WORLD, RAIDING, EVENTS, TOURISM, NOBLES, FACTIONS, EVENT_HANDLER`

City work also runs after `WORLD`: `TOURISM`, `NOBLES`, the city events, `Player.updateSpecial` inside `FACTIONS.update` and `EVENT_HANDLER`. So ADR 0005's "city part first, then the world part, in vanilla's order" can't hold as written. The split order:

1. **Shared, before both parts:** `ScriptEngine`, `TIME`, `Intervals`, `TmpBoosting`. These run on every PC and are compared, as in V0.1.
2. **City part (local):** `SETT`, `GCOUNTS`, the 9 city events, `TOURISM`, `NOBLES`, the city half of `Player.updateSpecial` (tech, titles, levels, price history), the player's inflation (`FCredits.update` for the player, today in `Faction.update`), and the city half of `EVENT_HANDLER`.
3. **World part (lockstep, reseeded from (seed, step)):** `WORLD`, `RAIDING`, the 7 world events, `FACTIONS` without the player's city logic, emissaries (today in the city's `updateSpecial`, but they are world decisions), and the world actions of scripted events.

Each part keeps vanilla's order among its own resources. In co-op mode every PC runs the same split, so co-op still compares equal. It is no longer step-for-step vanilla's order, but this is invisible in play.

Still open for milestone 2: `Armies` and `BattleThreads`. A field battle runs in lockstep (ADR 0003) and pauses the world. An invasion of the own city is a city battle. In milestone 2 they stay where they are, and the choice is made with C6.

## The EVENTS split

`EVENTS` holds 16 `EventResource`s in one list. Each one saves under its own key (`SuperSaver`), so the save already splits by key.

| Part | Events (save key) | Crosses the boundary |
|---|---|---|
| City (9) | `CITIZEN`, `SLAVES`, `DISEASE`, `DISEASE_MILD`, `KILLER`, `TEMP`, `ADVICE`, `ACCIDENTS`, `ENGINE` | only `SLAVES`: a slave uprising takes 75 % of the treasury (`EventUprising:215`), a city write to the player's credits |
| World (7) | `FACTION_EXPAND`, `FACTION_COLLAPSE`, `FACTION_POPUP`, `FACTION_WAR`, `FACTION_PEACE`, `DIPLOMACY`, `REBELLION` | their `FACTIONS.player()` reads are of world state: realm, regions, armies. `REBELLION` reads the capitol garrison (A5) |

The split is clean:
- each part runs its own events, in the existing relative order;
- a world resync loads the 7 world keys.

`EVENT_HANDLER` (scripted events, `game/event/**`) is a separate resource, and it is mixed. It checks city conditions, runs world actions (boosts, opinions, region population, raids) and sets marks that are saved in the world. The plan:
- a stamped input says an event fired or a choice was made;
- the world actions and the marks run in the world part;
- city actions run in the city part. This is B6.

## Corrections to ADR 0005 and plan 0002

These are facts the scan found. The decision doesn't change.

1. **Step order** (above): it is "city part, then world part, each in vanilla's order". Today city and world work interleave, so the split reorders it.
2. **Army supply mostly flows the other way.** Supply depots and soldiers joining push goods into `AD.supplies()`: a city→world deposit, which travels in the report (B2). The world takes supply from the city in only two places, both player commands: sending a division out (`WDivStoredAll.attach`) and `ArmyRecruit`. These are the two-phase supply withdrawals.
3. **The wall count is render-only** (`RDBuildings.levelWall`, read only by `CSprite`). It leaves the report. Another player's PC still needs it to draw that capitol, which is the mod's display data and not world state.
4. **"7 policies":** the world reads 3 values per race: prosecution, war standing and the tourism permit (A9).

## How milestones 2–4 handle the hard parts

These are the scan's five hardest problems, with the proposed answer to each.

1. **Boosts and data-driven values (A7, A8).**
   - Don't list boostables key by key. Hook the one chokepoint instead, `BValue.vGet(Player)`: every route from a region, a division or `clP` to the player ends there.
   - At report time, every `BValue`'s player value goes into the report. In the world part, `vGet(Player)` returns the reported value.
   - The same goes for `GVALUES.FACTION`: snapshot every registered value at report time.
   - Milestone 2 measures both counts. If they are too big, the snapshot is narrowed to the keys the world part reads, which is found by recording reads during a co-op run.
2. **The capitol garrison and invasions (A5, B4, C5, C6).**
   - The world sees the capitol garrison through the report: aggregate power and men, plus per-division detail for world battles.
   - The world's effects on it become transactions: casualties per division, and "invade with spec S".
   - The invasion ID is the transaction ID, allocated by the world, not by the city. The outcome comes back in the report.
   - The heavy one: an `InvasionSpec` holds per-person data, but every PC computes it, so it never crosses the network.
3. **The treasury and trade counters (A2, A3, C1–C3).**
   - Deposits take one phase: goods and credits in.
   - Withdrawals take two: exports, purchases, upkeep, tribute, raider demands and sending a division out. The world asks; the city's next report returns what was taken.
   - The world keeps a ledger of open requests, so a report up to 30 steps old can't be spent twice.
   - This changes vanilla in one visible way: the world no longer sees money or goods move in the same tick.
4. **Soldiers crossing (B1, C4).**
   - A soldier who joins an army travels in the report with their full data.
   - A soldier who returns, or a retiree, is a transaction with their full data.
   - The entry-point pick and its random draws move into the city part.
   - Stored soldiers keep their references in the city's relation table. Changes to them from the world arrive as transactions.
5. **The step split itself** (above), plus the hidden writes. The credit changes inside the `MVassal` and `MessDefeated` constructors become C3 transactions. City listeners fired by world changes (`Invador` on peace, `Creator` on an owner change, emissaries on a capture) become transactions, or move into the world part.

## The world-only resync (from the spike)

- **What works:** loading `WORLD`, `FACTIONS` and `RAIDING` into a running game takes about 0.25 s, and the data is about 0.6 MB compressed.
  - When every PC reloads at the same step, all copies stay identical (W38C).
  - A PC that doesn't reload drifts, so every PC reloads, the host included.
- **What milestone 4 must keep from the own city:**
  - the player's city-owned data inside `FACTIONS` (tech, titles, credits, trade settings);
  - an invasion in progress (`RaidingCurrent.clear` cancels it);
  - the city's world area (`initCity`).
- **What milestone 2 must add:** world entities (caravans) in the world hash's world section. In the spike, only the clock section caught an extra caravan.

## Estimates

In working time (a day ≈ 6–8 h). The earlier "about 3 days for all of stage E" came before the scan, and was too low.

| Milestone | Work | Days |
|---|---|---|
| 2. Step split and reports | Reorder (1); report codec and the plain chokepoints A1–A4, A6, A9, A10, B3, B5, B7 (1.5); the `BValue` and `GVALUES` snapshot (1); capitol garrison and invasion power from the report (0.5–1); world entities in the hash and a co-op check (0.5) | 4.5–5 |
| 3. Transactions | Queue, IDs and save (0.5); deposits C1, C3 (1); two-phase withdrawals and the ledger (1.5); soldiers both ways (1); invasions and garrison casualties C5, C6, B4 (1.5–2); scripted events B6 (0.5–1) | 6–7 |
| 4. API, follower mode, world-only resync | API and follower rig mode (1); resync that keeps the city (0.5); the 30-minute smoke run and docs (1) | 2.5 |
| **Total** | | **13–15** |

**A cut that saves about 3 days.** Two items could become a follow-up card, while co-op mode keeps both working:
- invasions of the player's capitol, and world battles on the capitol region (C5, C6, B4);
- scripted events (B6).

In the own-city mode, the engine would refuse those for now, as V0.1 refuses Engage. The 30-minute follower smoke run uses `--no-invade` and doesn't need them. That leaves about 10–12 days.

## Not verified

- The byte size of a soldier's data (`Induvidual`) and of an `InvasionSpec`.
- Which battle paths reach `WDivCity.resolve` rather than `invadeCapitol`.
- Whether `Attack.afterExit` runs inside the step.
- How many `BValue`s and `GVALUES.FACTION` values exist: milestone 2 measures this.
- Game scripts under `<game>/base/script`.
- A world load into a PC whose city differs from the host's: this is milestone 4's follower test.

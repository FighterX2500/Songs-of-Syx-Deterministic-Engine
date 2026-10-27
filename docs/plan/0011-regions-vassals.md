# Plan 0011: regions, vassalage and trade at peace between humans (SOS-73)

Card: [SOS-73](https://fighterx2500.atlassian.net/browse/SOS-73). The mod's plan is SOSGSGMP `docs/plan/diplomacy-2.md`, approved by the owner on 2026-10-27; this file records the engine's half as built. Part 1 is [plan 0009](0009-human-deals.md).

## 1. Goal

- A deal between humans can give regions other than capitals, either way.
- A deal can make one human the other's vassal, with a yearly tribute rate set in the deal (10% by default).
- A human's trade crosses the land of any human it isn't at war with, as armies already do.
- No emissary works in a human's region.

## 2. Design, as built

- **Terms (`DealBook.Terms`):**
  - New fields `fromRegions`, `toRegions` (region indices, at most 16 a side, no region twice or on both sides) and `tributePercent` (0 to 100, kept only for a vassalage, so two equal deals compare equal).
  - New treaties `PROTECTOR` (the proposer becomes the overlord) and `VASSAL` (the proposer becomes the vassal).
  - The 5-argument constructor stays, with no regions and the default rate.
- **The book (`DealBook`):**
  - It holds the tributes: (vassal, overlord, percent), one per vassal.
  - **Save format, version 2:** the regions and the rate follow each proposal's goods, and the tributes follow the answers. Version 1 still loads, with no regions and no tributes. The hash mixes the whole book, so the tributes are in hash part 7.
- **The command 8100 `humanDealPropose`:**
  - The payload appends both region lists and the rate. Commands live only within a session, so the payload has no second version.
- **The rules (`HumanDeals`):**
  - **Regions (`regionsRefusal`):** each region is the giver's, active, not a capital and not besieged. Each borders the receiver's land, reached from the receiver's capital, or another region given in the same deal; this is vanilla's `DealRegs` walk, through `regFinder`. The rules are checked again at the accept, and `Engine.dealRegionRefusal` serves the mod's picker.
  - **Vassalage:** the existing rules hold: "already in place", and a vassal agrees only trade with others.
- **Apply:**
  - Regions go by `RD.setFaction` with the receiver current, so the owner-change listeners, which act for "the player", act for the receiver on every PC. The listeners were audited in the patched source: SOS-46 already runs them per human, and the rest only mark a cache dirty on every PC alike.
  - The stance is `DIP.OVERLORD()`/`DIP.VASSAL().set(proposer, receiver)`, with the proposer current, as for any treaty. The book then records the tribute.
  - `expire()` (start of each world part) drops the tribute of a vassalage that ended: Cancel all (8102) ends it at once, as the right to rebel (owner, 2026-10-27).
- **Hooks:**
  - `UpVassal`: a human vassal of a human overlord pays the deal's rate of `FACTIONS.WORTH().faction()` once a year, under vanilla's own clamp to the treasury, and the overlord is credited (`CTYPE.TRIBUTE`). An NPC overlord keeps vanilla's rule.
  - `DIP.ally`: a human overlord stays out of its human vassal's wars; the vassal still joins the overlord's.
  - `WRegFinder.FACTION_REACHABLE_HUMAN_TRADE`, used by `RDDistance.tradePartners` for humans.
  - `Emissaries`: the region gate clears any human's region. The command 8004 refuses a human's region as a target.
- **Engine API:** `dealRegionRefusal(giver, receiver, region, chosen)`, `tributePercent(vassal, overlord)`.
- **Rig:** `dealregions <slot> give|ask <n>`, `dealvassal <slot> protector|vassal <percent>`. The `deals` probe also notes the human's region count and its overlord with the rate.

## 3. Checks

- **Unit:** `DealBookTest`: region terms, rate rules, tributes per vassal, the version-2 round trip with the hash, and a version-1 save loading.
- **Rig, 3 humans, Mogus-72:** results below.
  - **D73A, pass** (9,000 steps; humans 0, 2 and 24; `--make-humans 2,24`):
    - region deal: slot 0 gave slot 2 region 100 (`dealregions 2 give 1`) and slot 2 accepted; slot 0 went from 2 regions to 1 and slot 2 from 1 to 2;
    - vassalage: slot 2 proposed to become slot 0's vassal at 15% (`dealvassal 0 vassal 15`) and slot 0 accepted; the stances were OVERLORD and VASSAL, and the book held 15%;
    - Cancel All by the vassal (`dealcancel 0`) left both NEUTRAL, and the tribute entry was gone.

    Every copy identical to step 9,000, 600 hashes compared on each, 0 missed.
  - **D73B, pass** (6,000 steps): trade treaties 0–24 and 0–2; each then lists slot 0 as a trade partner (24 at distance 53, 2 at 46). Every copy identical.
- **Not covered by a run:**
  - **A yearly tribute payment:** a game year is far longer than a smoke run, so the `UpVassal` hook is checked by reasoning only.
  - **Trade crossing a third human's land:** these humans' capitals reach each other directly.
  - **A human overlord staying out of its vassal's war.**
  - **The passage walk:** vanilla code, unchanged; spec §6.

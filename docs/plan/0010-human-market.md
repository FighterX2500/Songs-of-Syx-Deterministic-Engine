# Plan 0010: the market and tariffs between humans (SOS-77)

Card: [SOS-77](https://fighterx2500.atlassian.net/browse/SOS-77). The mod's plan is SOSGSGMP `docs/plan/goods-tariffs.md`, approved by the owner on 2026-10-27; this file records the engine's half as built.

## 1. Goal

- Two humans with a trade stance (trade partners, colleagues, allies) trade in the shared market, as a human and an NPC do (mod spec section 7).
- Each human sets their own tariff on what another human sells them: 0% to 80% in steps of 5%, 20% when nobody set it, whatever the stance.
- The buyer's tariff is cut from what the seller gets and paid to the buyer's treasury.

## 2. Design, as built

- **The market (patch 15, `TradeSorter`):**
  - Before SOS-77, `buy` skipped every human seller and the player's auction gave no bid for a human buyer. Now `buy` skips only the human whose round it is (`FACTIONS.player()`), so a human buys from another human with a trade stance as from an NPC. The auction still bids only to NPCs, so one deal can't happen in both rounds.
  - The seller's calls (`removeMax`, `removePrice`, `remove`) run as the seller: `sosdetMax`, `sosdetPrice` and `sosdetSell` wrap them in `Hooks.currentHumanSwap`, so they read the seller's report and go through the seller's ledger (ADR 0005). For an NPC seller the swap does nothing.
  - The price is the seller's export price cap plus the toll (path distance between the capitals), as vanilla prices a human's goods.
  - **Who can trade:** as vanilla's player, a human's partners are the capitals it reaches (`RD.DIST().tradePartners`: across its own land, a neighbour's, or a land it has transit through; never across unclaimed land) that it has a trade stance with. Two humans with unclaimed land between them don't trade, unless they are 6 to 15 tiles apart (SOS-120): then the market walks the way caravans do, across any land.
  - A short delivery settles as an NPC sale does: what was sold is the seller's promised export, a debt the export depot pays when it has stock.
- **The tariff:**
  - `TariffBook` (plain Java): the rate per ordered pair (setter, other), and the revenue this year and last. World state: saved by `GameTariffs` (key `SOSDET_TARIFFS`, version 1, after the deals) and mixed into hash part 7, "deals and tariffs between humans". An empty book saves and hashes as nothing, so a world without tariffs hashes as before.
  - At a sale between two humans, `Hooks.tariffCut(buyer, seller, price)` takes the buyer's rate of the price. The seller is paid the price less the cut, and the cut goes into the buyer's treasury as `CTYPE.TRADE`. The buyer pays the full price, toll included, and the seller is paid it, as vanilla pays a seller; with the cut going back to the buyer, the trade makes or loses no money.
  - The world command 8103 `humanTariff` applies at once with no answer. It is refused outside 0–80% or off the 5% steps, against oneself or a retired human, and without a trade stance. A human's removal drops their tariffs both ways.
- **Engine API:** `tariffRefusal(slot, percent)`, `setTariff(slot, percent)`, `tariff(setter, other)`, `tariffRevenue(buyer, seller, lastYear)` (seller -1 for the total), and `reportedPrice(slot, tradable, sell)` for the mod's Price column.
- **Rig:** the action `tariff <slot> <percent>`; the probe `wtradeinfo` (goods for sale, goods wanted, goods owned); `deals` shows each pair's tariffs and revenue.

## 3. Checks

- **Unit:** `TariffBookTest`: the default, the steps, the cut and its revenue, the year roll, the round trip, a removed human.
- **Rig, 3 humans, Mogus-72:**
  - C77A (6,000 steps): a trade stance; every copy identical.
  - C77B (12,000 steps): copy 3 sets a 50% tariff on copy 2; every copy sees 50 at once and stays identical. No trade yet: the run covers a third of a game day (1,152 s), and trade rounds come once a day.
  - C77D to C77F: no trade at all, not even with NPCs. The rig's resource arguments took only numbers (fixed: keys too), and the probe then showed why: the NPCs the rig turns into humans by default reach no one across unclaimed land, so they have no trade partners. New rig option `--make-humans A,B` picks NPCs that border the player.
  - **C77K, pass** (45,000 steps, about 1.5 game days; humans 0, 2 and 24; `--make-humans 2,24`): 0 and 2 sign a trade treaty, copy 2 exports furniture at a price cap of 30 (new rig action `export-price`), and copy 1 imports it. Slot 0's tariff revenue from slot 2 grew 600, 2,280, 3,060 at 20%; after `tariff 2 50` every copy showed 50 at once, and the revenue reached 5,655 while the seller's daily trade income fell from 7,593 to 6,657. Every copy identical to step 45,000, 3,000 hashes compared, 0 missed.

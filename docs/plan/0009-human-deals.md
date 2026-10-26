# Plan 0009: deals between humans (SOS-72, milestone 1)

Card: [SOS-72](https://fighterx2500.atlassian.net/browse/SOS-72) · Date: 2026-10-26 · Status: approved (owner, 2026-10-26, as the mod's `docs/plan/diplomacy.md`), in progress · Mod spec 0.18 §6 Diplomacy, §7 Trade (direct contracts) · Builds on ADR 0002 (commands), ADR 0004 (several humans), ADR 0005 (city and world parts)

Game paths are relative to `<game>/info/SongsOfSyx-sources/`.

## 1. Goal

One human proposes a deal to another, and the other accepts or declines. An accepted deal applies at one step on every PC. A proposal left unanswered for 30 in-game days expires as declined.

Milestone 1's terms:
- one treaty: peace, trade, pact or ally, or cancel all (back to neutral);
- each side's money;
- each side's goods.

War, regions and vassalage are SOS-73 and SOS-74. War is not offered against a human until battles between humans exist (owner, 2026-10-26).

## 2. Design

- **`DealBook`** (plain Java, `sosdet.game.deal`): the open proposals and the latest answers, as world state.
  - A proposal holds: id, from slot, to slot, the game second it expires at, the treaty term, each side's money, and each side's goods as (tradable index, amount) pairs.
  - An answer holds: id, from, to, and the outcome (accepted, declined, expired or refused), kept for the UIs to tell the proposer.
  - It saves to and loads from ints, and mixes into a hash.
  - Unit-tested: save round trip, a repeated take, expiry, and a removed human's proposals being dropped.
- **`GameDeals`** (`Savable`, key `SOSDET_DEALS`), made by `GameHumans` after the other sections:
  - it writes the book with a version first and an end marker (vanilla's reader refuses a file's last 8 bytes, see `GameEvents`);
  - a save without the section gives an empty book;
  - its hash is a new part of the world hash.
- **Commands** (`cmd.HumanDeals`, ids 8100 to 8199, inside diplomacy's world-command range): `PROPOSE` (8100) and `ANSWER` (8101).
  - **Propose** applies in the world part with the proposer as the current human. It is refused when:
    - the other side is not a human, is the proposer, or is retired;
    - the treaty term is not offered (`refusal`);
    - the other side already has 8 open proposals.
  - **Answer:** only the receiver's own answer counts. An accept rechecks every term, then applies:
    - the treaty: `DipStance.set(from, to)`, with no NPC opinion change, since vanilla's betrayal is in `OpsStance.setNewStance`, which takes NPCs only;
    - the money: `credits().inc(±x, CTYPE.DIPLOMACY)`;
    - the goods: `seller(t).remove(...)` and a `TRADE_TYPE.diplomacy` caravan to the receiver's capital, as `DealParty.execute` does.

    Each side applies with its giver as the current human, so its transactions reach its own city.
  - **Expiry** runs at the start of each world part, before the commands.
  - **A removed human** (SOS-66): their open proposals are dropped at the removal.
- **Engine API for the mod:**
  - `Engine.dealPropose(...)` and `Engine.dealAnswer(id, accept)` capture the commands;
  - `Engine.dealRefusal(...)` lets the UI grey out a term before proposing;
  - `Engine.deals(slot)` gives this human's open proposals, in and out, and the latest answers, as immutable values.

## 3. Checks

- **Unit:** the `DealBook` tests, and the commands' refusal logic where it is plain Java.
- **Rig:** `--humans 3`:
  - copy 2 proposes trade and money to copy 3, and copy 3 accepts;
  - a second proposal is left to expire;
  - the treaty, the money and the caravan are the same on every copy, and the copies stay identical;
  - new rig actions `dealpropose` and `dealanswer`.
- `gradlew.bat build`, then the mod's UI and its two-PC run (the mod's plan).

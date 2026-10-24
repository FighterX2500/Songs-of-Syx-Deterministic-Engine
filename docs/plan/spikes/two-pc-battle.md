# Spike: a battle on two PCs, several commanders per side (SOS-15)

Date: 2026-10-24 · Card: [SOS-15](https://fighterx2500.atlassian.net/browse/SOS-15) · Epic: SOS-22 · Code: `Engine.watchOnly`, `Engine.commanders`, `sosdet.ai.Commanders`, `sosdet.game.OwnArmy`, patches `94-own-army` and `95-player-orders`, the rig's `--watch` and `--commanders` · Feeds: the mod's SP4 ([single-player-assumptions.md](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/plan/single-player-assumptions.md) §2, hook B6)

## Question

Can the owner play a field battle in lockstep on two PCs (this PC and the laptop, over Radmin), first with the laptop only watching, then with orders from both PCs, and then with each PC commanding its own divisions: one per side, or two on one side against the AI?

## Answer

**Yes, in all three stages.** Every session stayed identical at every step on both PCs, and no engine hash was missed. The details are in [stage-e-smoke-tests.md](../notes/stage-e-smoke-tests.md), under SOS-15.

| Run | Who commands what | Result |
|---|---|---|
| V0, V1 | the laptop only watches | identical to 3,000 and 14,242 |
| V2 | both PCs order the same army | identical to 15,871; 141 orders |
| S3 | table "0 1", nobody playing | identical to 3,000; the general's changes for army 1 all skipped |
| V3 | this PC army 0, the laptop the enemy | identical to 15,219; 89 orders; 2,530 general changes skipped |
| V4 | army 0 split in half between the PCs, the enemy the AI | identical to 8,097; 76 orders; none skipped |

The owner, after V3 and V4: "All fine", "Its also all fine".

## How it works

The work is split as the owner chose (2026-10-24): the engine owns a generic commanders table and enforces it; the mod decides who commands what.

- **The table** (`Engine.commanders(byDivision, artilleryByArmy)`): for every battle division of both armies, a PC or `Engine.AI`; for each army's artillery, one of the same. The host sets it, and it applies at the step the host stamps (30 steps ahead), on every PC. It can change mid-battle. It is in the resync state, and in the state hash once set, so a run without a table hashes as before.
- **Enforcement, inside the step:** an entry from a source that doesn't command its division or artillery is skipped on every PC and counted (`Engine.ordersNotCommanded`). This covers the general's changes, players' orders, deployment placement and artillery orders. The general itself is unchanged: it still plans for every division of its army, and its entries for human divisions are skipped.
- **The UI, outside the step:** "the player's army" in the battle UI becomes this PC's own army, the one holding the first division it commands (`OwnArmy`). Its player can select only the divisions and artillery it commands. Display and input only; the step never reads it.

## What stays for the mod (SP4)

- **Who commands what.** The mod builds the table from its campaign: which player's army fights, the allies' divisions and who replaces a player who drops out (set the table again with their divisions to `AI`, or to another PC).
- **Deployment.** Where each PC's divisions start, and each PC's deployment zone. Placement now follows the table, but the zones are still the game's two sides.
- **The battle's result and victory** are still army 0's: `UIBattleResult` and the world battle's outcome take army 0 as the player's. With the enemy side human, the mod decides what each PC sees and what the result means for its campaign.
- **The side's faction (hook B6):** each army still has one faction (`FACTIONS.player()` or `FACTIONS.otherFaction()`), which drives bonuses and race. Allies of different factions on one side need B6.
- **Retreat and Restart** are commands any PC's player can give, and they end or restart the battle for everyone. The mod decides who may.
- **The speed vote:** the host's player sets the speed; the governor only lowers it to what the slowest PC holds (V4: 5x became 2.7x to 0.4x on the laptop).
- **Small UI gaps:** the battle panel is built before the table arrives, so a PC commanding the enemy side first sees army 0's unit cards and opens its own with the army button. Both armies' cards stay built; only the selection is restricted.
- **The general's work for human divisions** is spent and skipped. Cheap in these runs; if it matters in big battles, the general could be told which divisions to leave alone.

## Gaps in this spike

- One battle shape: the rig's field battle, 2,000 men per side, no artillery. Artillery commanders are tested in unit tests only.
- Two PCs only; three or more PCs are not tested. A table cleared mid-battle is covered by a unit test, not a session.
- No resync during a session with a table; the round trip is a unit test.

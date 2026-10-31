# Plan 0018: several humans in one battle

Mod spec 0.39 §8 (SOSGSGMP), its ADR 0009 and plan `battles-allies.md`. Builds on plan 0006 (own-city battles), SOS-83 (two humans), plan 0016 (local battles) and plan 0017 (watching).

## What changes

A battle has held two humans: `OwnBattle.human` and `other`. Vanilla's two armies stay, and each division gets an owner. Everything that today asks "army 0 or army 1?" asks "whose division?".

## Milestones

### M1: men at the start, and either winner

- `OwnBattle` keeps each division's men at the start, read from the loaded battle, the same on every PC.
- `ResolverPlayer.Manual.afterBattle` counts `losses` from them, not from `savedPlayer.div(i).men()`, which reads a different city on each PC after the load back.
- `WorldBattles.accept` stops carrying the defeat's counts: S6T's fix goes.
- `Hooks.resultForArmy1` already gives army 1's human the panel when they win, but with Accept only: they get the same choice of slaves and spoils as army 0.
- **Test:** a unit test for the count. Rig: a field battle won by each army, a beaten sally. Copies identical.

### M2: the speed vote

- `MapBattles`: a VOTE command: human, speed. Applied in the step, so every PC counts it alike.
- The rule in a plain class, with no game imports: `BattleSpeedVote`. Unit tests cover 2 and 3 commanders, ties, and a commander leaving.
- A local battle joined by an ally (M8) uses the same class.
- `Session`: the speed before a battle is kept, and restored at the back-in-own-city step.

### M3: owners

- `Side` keeps which `SideUnit` each division came from (`ui`). A new `BattleOwners`: for each division, a human slot or AI, the same on every PC, from the sides at Engage.
- `commandBattle` builds the table from owners, not from `i / (divisions / armies)`. `OwnArmy` already reads that table.
- `OwnBattle.humans`: every human in the battle, with their side. `other` goes.
- **Test:** rig, three humans: orders only reach the owner's divisions.

### M4: who joins

- `Util`'s gather keeps vanilla's 4 tiles and its allied-and-at-war test.
- For a human ally, the prompt asks them too. `Session.worldPromptOther` becomes a list, and Fight's bookkeeping (`pvpEngageAt`) waits for all answers or the 30 seconds. Join defaults to Stay out, and Fight stays each main human's default.
- An ally in a battle (`locals[human] != null`, or `battle`) isn't asked.
- The armies that stayed out are left out of the sides on every PC alike.
- **The subtle part:** `Hooks.battleAhead` predicts the battle's step for the save before it, as with two answers (SOS-70).

### M5: each human's bonuses

- `army1Report`, `battleArmy1Human`, `otherReport`: a report per human in the battle, kept at Engage, as a battle load clears the reports.
- A soldier's and a division's `faction()` read the owner when the division has a human owner. The patches at `Army.faction()`, `Div.faction()` and the soldier's type are the ones SOS-83 touched for army 1.
- **The risk of the whole plan.** Vanilla gives a soldier's side, not its owner, and any boost read outside those patches would use the side's faction. Audit every `faction()` use in the battle part before the first run.
- Names, banners and formation follow the owner too (SOS-83 left these open).
- **Test:** rig: two allied humans with different technology. Each soldier's boost matches their report on every copy.
- Then check assaults: `Session.assaultCommanders` has owners already, and their allied attackers may read the stormer's bonuses.

### M6: slots and deployment

- Slots split evenly in `Side`, unused ones passed on, the same on every PC.
- Allied armies 20 tiles apart. Each human's deployment area holds only their divisions, and Start waits for every commander (`deployReady` becomes a set).

### M7: separate retreats and shared results

- `MapBattles.RETREAT` takes the human, and the owner alone can send it.
- A retreating army leaves at that step on every PC. Its soldiers go, and its survivors are counted with the new loss: `ceil(enemyMen * 0.4)`, times its men over the side's men still fighting, halved while an ally stays. Melee divisions lose 75%, as in `BattleStateResult`.
- The battle ends when one side has no one left. `battleArmy1Retreats` becomes "the last army of that side left".
- A rule class with no game imports for the loss, with unit tests.
- Results: captives and spoils split by men brought, with the leftover to the most men, in a plain class with unit tests. Each winner's prompt in turn: prompts are one at a time, so a second winner's waits for the first's answer or 30 seconds.
- `transactions`: each human's survivors and losses reach their own city once.
- **Test:** rig: one retreats and the other wins; both retreat; shares add up to the whole; a resync after a retreat.

### M8: an ally in a local battle

- `LocalBattle.human` becomes humans: every PC freezes both, and both armies wait.
- The ally's PC opens as a watcher (`View.battle`, the tape). The ally's orders go to the fighter's PC and onto the tape, with the owners' check on the way in.
- `AiLog.watchOnly` lets the ally's orders through, as `commanding` does for an assault's attacker.
- A slow ally runs behind and its orders apply late, as they're stamped on the fighter's PC. The 3-minute limit stays until the owner decides (spec §16).
- The result applies once. `resultStep`, thaw and the world's 1× run until both thaw.
- **Test:** two PCs: both fight, one retreats, the result applies once on every PC, both cities are back.

## Order

M1 and M2 stand alone. M3 to M7 go together, and M8 comes last. Determinism: everything here runs in the battle step or feeds the hash, so `.claude/rules/determinism.md` applies, and no per-PC value enters the step.

## Results

All on the three-copy rig, each to step 26000 with every copy identical and no desync.

- **S8D, S8F, S8G (a battle between players):** an ally joins by answer and stays out by answer; each human's bonuses are their own, the same on every copy; the groups deploy apart; an ally's order moves only their divisions; the ally's retreat leaves the lead fighting; the shares came to 7 captives and 296 spoils, and 1 captive and 87 spoils, on a 71 to 22 men side.
- **S9N, S9P (an ally in an NPC battle):** the ally's view starts by itself, the fighter's game and the ally's stayed equal over 479 view hashes, the ally's order moved their men from a mean x of 418 to 305, both retreats went through, and the result reached every PC.

Three bugs came out of these runs:

- A local battle's commanders table sits on two PCs only, and it was in the clock hash and resync state. It's now outside both (`Commanders.setLocal`, `hashed`).
- A local battle's tape applied orders past the log's checks, so a command didn't know its PC and a division took any PC's order. `AiLog.applyTaped` applies the same rules.
- A save holds the game's update counter as it was, so a city loaded back after a battle ran behind the PCs that loaded nothing. `Hooks.updateLoaded` keeps the counter on the step, and `EventDiplomacy.sosdetPhase` is now hashed.
- The war timer desync of S9E, S9J and S9Q to S9T. `DipWarPlayer.init` fills its list in two loops over `FACTIONS.NPCs()`, so an NPC is in it twice, as in vanilla. `ArrayList` has one shared iterator, so a nested loop over that list ends the outer one early. A PC that just loaded has cold caches, and something inside `validEnemy` then runs such a loop: the first loop stops, every NPC is in the list once, and `attack` is half the other PCs'. War timers move once a day, about 25,000 steps here, so it showed long after the load. `init` now loops by index. A viewer's load back could set it off too.

The rig's relay sent every file as append-only, so a marker rewritten longer (`6873`, then `11418`) arrived as `68738`. Markers and saves now go whole when they change.

Not tested by a run:

- A leaving army's survivors in a close fight: in every run the enemy outnumbered it about 5 to 1, and the 40% penalty killed all of it, as the rule says. `BattleRulesTest` covers the numbers.
- A resync inside a battle with allies.
- A win over an NPC with an ally in a fought battle: an NPC weaker than the side withdraws, so a real fight means the side is the weaker one.

## Open

The mod's spec §16: a commander who drops out with allies still in the battle. Today's Continue without them ends the battle with no result.

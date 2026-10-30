# Plan 0014: sieges of a human's capital (SOS-92)

Card: [SOS-92](https://fighterx2500.atlassian.net/browse/SOS-92). Mod spec §9 and ADR 0006 (SOSGSGMP). The whole plan, engine and mod, is SOSGSGMP `docs/plan/sieges.md`, approved by the owner on 2026-10-29. This file records the engine's side of each step as it is built.

Status: step A merged (PR #60, 2026-10-30); steps B and C in part are in PR #63 (open).

## Step A: the siege and its wait

**Besieging a human's capital.** At war, `ARMY_BESIEGE` accepts another human's capital (`World.otherHumanRefused` keeps only the peace check). The capital still never falls to `Util.conquer` (patch 15): only to the assault, step B.

**The wait** ([SiegeBook](../../src/main/java/sosdet/game/siege/SiegeBook.java)):
- When `PSiege.besige` first marks a human's capital besieged, the engine records the game second and the city's population, read from its report (`Sieges.began`). When `PSiege.update` clears the mark, the record goes (`Sieges.ended`). A new siege waits from its own start.
- The assault is allowed after one season per started 1,000 people, from 1 to 4 (mod spec §9), counted from the population at the start (owner, 2026-10-29).
- The book is world state:
  - it is saved with the world (`GameSieges`, section `SOSDET_SIEGES`);
  - a world load carries it (`GameWorldLoad.KEYS`);
  - it is in the world hash (part 7, with the deals and tariffs).
  - A save without the section gives an empty book, and an empty book hashes as nothing, so a game without a besieged human hashes as before.

**Storm.** The besieger's siege prompt (`BattleSiege`) greys out auto-resolve on another human's capital, with the days left of the wait. Once the wait has passed it stays greyed out with "The assault on a player's capital isn't built yet." The decision command refuses it too (`WorldBattles.applyDecide`). `ResolverPlayer.besige` no longer opens the capture panel for a human's capital with no defenders.

**The sally at any time.**
- Command 7970 (`Sieges`, `Engine.sallyOut`): at its step, the next siege poll asks the defender as at the siege's start (`WBattles.sosdetSally`, patch 15).
  - Against a human besieger, that is SOS-83's Fight or Fall Back.
  - Against an NPC, it is vanilla's sally.
- Refused while a battle question is open, or when the capital isn't besieged.

**The blockade.** A besieged human's capital buys nothing and sells nothing in the market, with NPCs or other humans (`TradeSorter.buy`, `Hooks.humanBlockaded`). Vanilla's own blockade (`SENTRY.isClosed`) works in the city, which in own-city mode runs only on its owner's PC. A deal between humans with goods to or from a besieged capital is refused; money still moves (`HumanDeals.blockadeRefusal`).

**For the mod:** `Engine.besiegedSince`, `assaultInDays`, `sallyRefusal`, `sallyOut`.

**Rig:** the probe `wsiege` and the actions `sally` and `sallyother` (rig README).

### Results

Rig runs on Mogus-72 with three humans (`--humans 3 --make-humans 2,24`, copy 3 on the laptop), slot 24 declaring war and besieging slot 0's capital (`3: dealwar 0;3: wbesiegehuman 0 0 capitol`). Each run was identical on every copy, with no engine desync.

- **S92A to S92D** (18,000 to 30,000 steps): the siege begins with its record (3,064 to 3,066 people, 4 seasons, "the assault can start in 16 days"), Storm is refused with the days left, and a goods deal with the besieged human is refused. A sally while the defender's own question is open is refused; with no question open it is asked at once (S92C, step 16,261).
- **S92E** (24,000 steps, `--unanswered`, `sallyother`): the defender's first question ran out at step 10,765 and the besieger's siege window opened; the sally made during that window was taken, not refused, and asked the defender as soon as the window closed (step 13,166).

Not covered by a run: the wait running out (Storm then stays refused until step B), and the mod's popup and Sally out button, which need a campaign with soldiers (the owner's playtest).

## Step B: design (2026-10-30, decided as recommended while the owner was away)

The mod plan says every PC loads the defender's city "with `loadView`'s path". The audit below settles how, and what is reused.

### The vehicle: a view of the defender's city by every other PC, with the world held

- **Not the field-battle path.** `battleIn` and `MapBattles.loadBattle` reload a battle through vanilla's battle state (`VIEW.b().state().reloadBattle`). An assault runs in the settlement, with no `BattleState`.
- **The view path fits.** `GameView.saveForView` and `ViewCity.cityState` already carry an owner's whole city with the engine state a save leaves out. A viewer loads it, runs it in lockstep with the owner's PC, and hashes it (`viewHashes`). At the thaw, the viewer loads its own game back and puts the world it ran into it (`viewBackStart`, `viewBackWorld`, and SOS-97's `HeldTxn`).
- **Engage at Storm's step S.** Every human except the defender gets a view of the defender's city, scheduled by the engine itself inside the step: not through the host's log, and with no Allow (the attacker and every other player watch the assault). The views freeze at the next report step, as any view does; the attackers' armies leave the world there.
- **The world is held.** From the freeze until the assault ends, the world part doesn't run on any PC (new: `Session.assault`, checked where `stepPart` runs the world part). The defender's city runs on every PC. Every other city is frozen, as a viewer's is. This matches mod spec §4 ("the world pauses") without a battle-mode load.

### The invasion

- At the first step after every PC has loaded (the view's RUNNING on every viewer, as the host's log confirms), every PC starts the same invasion in the defender's city, in the city tail: `SETT.INVADOR().invade(spec, null)`.
- The `InvasionSpec` is built from lockstep world data at S:
  - the besieging armies' divisions (`WDIV.generate`, as `ResolverPlayer.invadeCapitol` does), with artillery and supplies;
  - the world position, which sets the entry edge;
  - `fi` = the attacker's faction index.
- It is kept in the assault record, so a PC that loads late builds nothing itself.
- The attacker's boosts come from its city report, as army 1's do in SOS-83 (`BValue`).
- The state starts at BOMBARD (decision 2). Vanilla's `Prompt` (the WARNING phase's Attack first) is not sent.
- **Audit of `Invasion.update` for lockstep:**
  - The `RND` draws are on the city's stream, which a view seeds as the owner's.
  - `TIME`, `STATS` and `SETT` are city state, the same on every PC.
  - `VIEW.s().getWindow().centererTile.set` moves this PC's camera only (cosmetic); a patch skips it on PCs that don't command.
  - `MessageText.send` goes through `Hooks.messageHere`.
  - `FACTIONS.otherFactionSet` is game state, the same on every PC.
  - The prisoners prompt (`VIEW.inters().yesNo`) must become a defender's command at a step (step C).
  - `STANDINGS.emergency` is city state.
  - The audit found no reads of per-PC state other than the camera.

### Commanders

- The commanders table (`Commanders`, `captureCommanders`) maps divisions to PCs, and the settlement has the same `GAME.ARMIES()`: army 0 is the city's, army 1 the invaders'.
- The host sends the table as the invasion starts:
  - army 0 to the defender's PC;
  - army 1 to the attacker's PC;
  - the rest to the host's AI.
- Military orders go as the battle's already do.
- City commands other than military orders, from any PC, are refused while the assault runs (with a reason).

### The end

- The assault ends when `Invador.invading()` turns false at a step (a win, a loss, or the 5-day retreat), the same on every PC. The record keeps the outcome: the enemy's and the city's losses, and who won.
- Every view of it ends there (`View.GAME`) and thaws as views do. The world resumes from the freeze step.
- The defender's PC keeps the city as it is: every PC ran the same city.
- The outcome applies to the world at the thaw step: the survivors, the siege record and the defeat. That is step C.

### Step B's tests

- **Unit tests:** the assault record's save and load round trip; the refusals (a city command during the assault; Storm before the wait ends).
- **Rig:** Mogus-72 with 2 humans, slot 1 besieging slot 0, Storm after the wait, using a rig shortcut that sets the siege's start back. Every copy's city stays identical (the view hash) to the assault's end.

### Step B's results (2026-10-30)

- **With local battles (SOS-93, owner 2026-10-30):** Storm is refused while any player is in a local battle, from its Engage until its human has thawed. Every other player must view the assault, and a player in a local battle can't, so the view would fail and call the assault off. Unit test only: no rig run has had a Storm and a local battle together.
- Rig S92M and S92N (Mogus-72, slot 2 storms slot 0, 2 copies, 5x): Storm at about step 16,600, the view of slot 0 by slot 2 loaded in 7.5 s, the invasion ran on both copies, the defender held at 28,882 and 32,884, the view ended "The assault is over.", and afterwards the attacker's army was gone and the siege record cleared on both copies. No desync.
- S92J found a step A bug: `CommandParts.world` stopped at 7949, so the sally (7970) applied on its sender's PC only in own-city mode. The siege commands are world commands now.
- Not built from the list above: the save round trip (saves are refused during an assault instead, so the assault is never saved), and the refusal of the defender's city commands during the assault (they apply on every PC that runs the city, as in any view, so they don't desync).

## Step D: the attacker's terms (2026-10-30, decided as recommended while the owner was away)

After a taken throne the world keeps the assault as `Session.taken` until the attacker chooses, in the world part, by `ASSAULT_TERMS` (7973):

- **Sack** (`AssaultTerms.SACK`, 0% to 80%): that share of the defender's money and of each good, read from its city's report, goes to the attacker through `HumanDeals.execute` (the money at once, the goods by caravan from the defender's capital), and the war ends (`CANCEL_ALL`, neutral).
- **Vassalage** (0% to 100%): the defender becomes the attacker's vassal with that tribute (SOS-73's terms), which also ends the war.
- **No choice in two minutes of real time** (240 half-second windows at the agreed speed): Sack at 50%.
- The siege ends as the war does: `PSiege` drops a besieger that is no longer an enemy, which clears the siege record.
- No saves while a choice is open (mod).

Not built: Eliminate and the people's fates (Moved, Killed, Freed, mercy), which need a `REMOVE_PEOPLE` transaction and spec §11's elimination.

Rig: `wassaultend held|taken|retreat` ends the running invasion at the next city tail on every copy (test tooling, `ASSAULT_END` 7972); `wterms sack|vassalage <percent> [moved:killed:freed]` chooses.

### Step D's results (2026-10-30)

- **S92Q and S92S** (a forced retreat, then a second Storm, a forced taken, and `wterms sack 60`):
  - the retreat started a new 16-day wait, and the army stayed besieging;
  - the second view ran (it found a rig bug: the viewer kept the first view's hash offset, now reset at each view);
  - the sack moved 1,350,756 (60%) from slot 0 to slot 2, set the two neutral, and cleared the siege;
  - no desync.
  - The defender's treasury then fell near zero: its import orders bought back the goods the sack took (962,228 of TRADE out the same day). That is the game's trade, not the sack.
- **S92R** (one assault, a forced taken, sack 60%): exactly 60% left the defender (DIPLOMACY out 1,354,957).
- **S92P** (a forced taken, `wterms vassalage 25`): slot 0 became "vassal of 2 at 25%" (VASSAL and OVERLORD), the war ended, the siege cleared and the army went to Fortifying. No desync.
- **Not run:** the 2-minute default (Sack 50%) when the attacker doesn't choose.

## The removed people (SOS-92 step D, part 1, 2026-10-30)

A sack now also removes people. Eliminate is still to come; the spectator it needs is SOS-111.

- **The choice:** `ASSAULT_TERMS` carries a count per race and fate (`AssaultTerms`' flat array). Refusals check only its shape: the population moves between the choice and its step, so `AssaultTerms.limited` caps it at the step, from the defender's report, the same on every PC.
- **The limit:** 80% of everyone who can be removed, in all and not per race, so one race can go whole. Who can be removed: the citizens and slaves physically in the city (`Field.PHYSICAL`). Nobles stay, and people away in armies aren't counted.
- **The default:** no one is removed. A timed-out attacker gets half the money and goods, and no one's fate or the attacker's mercy is decided for them.
- **`REMOVE_PEOPLE`** (a new `TxnKind`): the world part queues the counts for the defender's city, whose tail removes the first citizens and slaves of each race in entity order, so that a PC viewing the city removes the same people. Its transaction ID makes it apply once; a repeated command finds no taken throne and changes nothing. A frozen city holds it for its thaw (`HeldTxn`).
- **Causes:** moved `SOLD`, killed `SLAYED` (with corpses, capped by the game), freed `EMMIGRATED`.
- **Moved:** they travel in a spoils caravan as the race's `TR.SLAVES` tradable and arrive through `PBuyerSlave` as prisoners; the attacker's prison law then decides. Where no caravan can go they arrive directly, like goods.
- **Mercy:** freed +n, moved -n/2, killed -n, over 1 + the attacker's population from its report, on `AD.stats().mercy()`.
- **A fix to Sack:** it walked `TR.ALL`, which holds a slave tradable per race, so a sack silently took that share of the defender's slaves as goods, with no mercy cost. It walks `TR.RES` now: slaves are people, and go only by the attacker's choice.
- **The mod:** `MpAssaultTerms` has a row per race with a slider per fate, and the notices and the playtest log give the counts.

Proof:

- **Rig S92T** (Mogus-72, slot 2 storms slot 0, 2 copies, 5x, a forced taken, `wterms sack 60 300:200:100`): the defender's city went from 4,126 people to 3,526, exactly the 600 chosen, and the terms note gave 300 moved, 200 killed, 100 freed and 1,354,884 credits. The 300 prisoners were delivered at step 28,214 (`DELIVERED 47 300`, a slave tradable), six steps of the caravan's goods. No desync through step 34,201, no crash file.
- **Prisoners and mercy:** 6 prisoners were left in the attacker's city at step 34,201: its prison law had processed the rest. Each one's punishment writes the attacker's mercy in its own city (`StatPunishment.report`), so its PC reached 1.0 while the host's copy stayed at -0.079. That isn't a desync: the world reads a human's mercy from that human's report (`Field.MERCY`), and the copies on other PCs aren't read or hashed. The rig didn't note the attacker's mercy before those punishments, so the fates' own change was seen only on the host's copy.
- **The window:** a two-game GUI preview on one PC (`twopc --here --gui-preview`, TERMS3, PASS, 0 hashes differ) showed the terms window with a real city's limit, and the notice with the counts. Moving the sliders by hand isn't tested.

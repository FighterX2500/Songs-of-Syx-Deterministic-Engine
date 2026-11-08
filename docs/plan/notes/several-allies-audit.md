# Audit: several allied humans in one NPC (local) battle

Read-only audit, 2026-11-08. Engine at SOSDET `691a793` (line numbers are HEAD's; the b10 worktree has unrelated uncommitted SOS-162 edits that shift Session.java by +2 lines after line 83 and Hooks.java by +3 after line 263). Mod at SOSGSGMP `822f164`.

**Summary**
1. Three hard limits stop a second ally today: `alliesJoined(human, 1)` (Session.java:353), `allyWatches` starting a view for `commanders[leads]` only (Session.java:420), and `Engine.viewOut` calling `tape.watch` for the first view that freezes at a step only (Engine.java:644). The third is already a bug for two watchers who freeze at the same step.
2. A deployment bug shows up only with 3 or more groups on a side: `groupOffset` plus patch 99-allies' `width < 220-|offset|` gives a negative row width to the outer groups once each human brings about 16 divisions (3 groups) or 10 divisions (4 groups). Those divisions are never placed, so they probably count as lost.
3. Desync risk: the local commanders table is set only on commanders' PCs, but the tape carries orders from before the fighter's filter. A watcher who isn't a commander applies orders the fighter skipped. More allies means more such orders.
4. Most of the rest already works for N: roster arrays, slots, owners, per-owner bonuses, retreat and leaving loss, result shares, the speed vote, freeze and thaw through views, results through the log, and the mod's per-viewer save, tape and hash relay.
5. Rule gaps that 1 ally has too and N makes worse: Start comes from the fighter alone (spec: every commander, or 2 min); allies on the losing side get no losses window; an ally who closes, falls behind or is removed leaves orphaned divisions (spec §16 open). The spec and AT line 400 ("two players' PCs only") need the owner's approval to change.

## Method

I read the engine docs (CLAUDE.md, AGENTS.md, determinism rules, architecture, plans 0016 to 0018) and the code on the path from prompt to result: `Session` (local battles, views, roster, vote, retreat), `LocalBattle`, `BattleRoster`, `BattleAllies`, `BattleRules`, `ResultShares`, `BattleSpeedVote`, `PromptTimer`, `LocalTape`, `LocalGeneral`, `AiLog`, `Commanders`, `Engine.viewOut`/`viewIn`/`viewTapeOut`, `Hooks` (prompt, engage, result, deployment, bonuses), `WorldBattles`, `MapBattles`, patches 15 (Side, ResolverPlayer, Util), 99-allies (BattleStateGenArmy) and 99-map-battles. I also read vanilla `BattleStateGenArmy` and `Util.fill`, and in the mod `MpAllyJoin`, `MpPlay`, `MpViews`, `MpLoading`, `MpBattleOutcome` and `EnginePlay`. Nothing was run. Every finding comes from reading the code.

## A. What blocks 2 or more allies (must change)

### A1. Joining is capped at one ally
- **Where:** Session.java:352-353, `localEngage`: `int[] allies = alliesJoined(human, 1); // one ally: the battle runs on two PCs only`.
- **What breaks:** the second and later "Join" answers are dropped. They are told `ALLY_OUT`, and their armies leave the battle through `BattleAllies.inBattle`.
- **Change:** call `alliesJoined(human)` (no cap). Flip the test `LocalBattleTest.anNpcBattleTakesOneAlly` (src/test/java/sosdet/LocalBattleTest.java:291) to expect every ally who joined, in answer order.

### A2. Only the first ally's PC follows the battle
- **Where:** Session.java:416-428, `allyWatches`: `int ally = b.roster.commanders[b.roster.leads];`.
- **What breaks:** allies 2 and later get no view. Their PCs never load the battle and their cities never freeze. The fighter's PC still has their divisions on the field, deployed and owned by their PC (`localCommanders`), but nobody can give them orders. The ally's own city runs on, and its report and transactions are not held.
- **Change:** loop `for (int c = b.roster.leads; c < b.roster.commanders.length; c++)` with the same body. Every ally's view then freezes at the same step, `nextReportStep(F + 1)`. That relies on A3.
- **Also, while there:** skip an ally that is `retired` or `removing` (see B1). Also skip a commander that is `gone` before its view starts: it left the field, so freezing its city serves no purpose.

### A3. Several views freezing at one step: only the first gets the tape
- **Where:** Engine.java:627-650, `viewOut`. It marks every view of the owner that freezes at this step as `sent` (lines 635-639), but calls `s.tape.watch(v.viewer)` only for the first `v` (line 644).
- **What breaks:** `LocalTape.out[slot]` stays null for the other viewers, so `viewTapeOut` returns empty bytes for them (LocalTape.java:116-124). Their PC loads the battle, then holds at `watchMayRun`, because the tape never reaches F+1. The likely end is the 3-minute watch limit (`ViewLimit.battle`), with reason FELL_BEHIND, and `allyWatches` does not restart that view. With A2 fixed, every ally after the first would hit this.
- **Already broken today:** two plain watchers whose `viewBegin` entries land in the same 30-step window freeze at the same step and hit the same bug. The current test of two watchers (spec AT line 412) joins them one after the other.
- **Change:** in the `o` loop, `if (o.battle) s.tape.watch(o.viewer);` (or loop over every view sharing `v.freeze`), and drop the single `s.tape.watch(v.viewer)`. Add a unit test: two views of one battle that freeze at one step both get non-empty `viewTapeOut` after the next `record`.
- **Already works:** the mod side. `MpViews.owner` (mod/src/main/java/menu/MpViews.java:166-195) sends the one save to every viewer with `freeze == step` or `rejoinAt == step`, and the tape and the view hashes to each viewer.

### A4. Deployment of 3 or more groups on one side: outer groups get no room
- **Where:** BattleAllies.java:226-244, `groupOffset`, and patch `99-allies.patch` line 36 (`BattleStateGenArmy.genArmy`): `for (int width = 0; width < 220-Math.abs(sosdetOff); width++)`. Vanilla places a group within ±220 tiles of the line's middle. The patch moves each group by `sosdetOff` and shrinks the half-width to `220 - |offset|`.
- **The arithmetic:** `half = min(13 * divisions, 390) / 2`, with a 20-tile gap between groups.
  - 3 groups of k divisions each: the outer offset is 20 + 13k, which leaves a half-width of 200 - 13k. That is negative from k = 16.
  - 4 groups: the outer offset is 30 + 19.5k, which goes past 220 from k = 10.
  - DIVISIONS_PER_ARMY is 120 in V71's data, so "fighter + 2 allies with 20 divisions each" is a normal case. The outer offsets are then ±280, so the outer two groups' loop never runs.
- **What breaks:** `divPlace` never runs for those divisions. They spawn no soldiers, and `menSet` is never called. At the result, `BattleStateResult` counts their survivors as 0 and the after-battle subtracts all their men at Engage. These are probably silent total losses. This needs a rig run to confirm. It is no desync, because every PC running the battle loads the fighter's save.
  - With 2 groups the outer offset is 10 + 6.5k, which is safe up to 120 divisions. That is why S8/S9 never saw this.
- **Change:**
  - Scale the gap and the per-group width so that the side's whole width stays within vanilla's ±220: `half[i] = min(half[i], (440 - GAP*(groups-1)) / groups / 2)`.
  - Or pass each group its own half-width, `half[g]`, as the row bound instead of `220 - |off|`. Let a group grow in depth when it has to; `depth < 256` allows about 19 rows.
  - Or place groups behind one another once `groups > 2`.
  - Add a unit test of `groupOffset` / row width for 3 and 4 groups at 1, 20 and 40 divisions each: every group's half-width > 0, and the union stays within ±220.

### A5. The local commanders table lives only on commanders' PCs, but the tape carries unfiltered orders
- **Where:**
  - Session.java:431-441, `localCommanders`: set at the freeze only where `r.commander(localHuman) >= 0`.
  - Session.java:718-728, `localOrders`: the fighter calls `tape.record(step, ...)` with every queued order before `applyTaped`, and `applyTaped` (AiLog.java:703-714) skips an order the table refuses.
  - `Commanders.allows` returns true when no table is set (Commanders.java:145-148).
- **What breaks:** a plain watcher (a human who chose Watch battle) has no table. It applies every taped order, including ones the fighter's PC skipped (a general's or another PC's order for a division it doesn't hold). The fighter's copy and the watcher's then differ: a view-hash desync and a reload for every viewer. This is latent with 1 ally. With N allies there are more PCs giving orders, so more chances.
- **Change (either one):**
  - Write to the tape only the orders the fighter applied. Move `record` after the apply loop and pass the accepted list. The tape then means "what ran".
  - Or set the table on every PC that runs the battle: in `Engine.viewIn` / `viewRejoin` for a battle view whose roster `allies()`, call `ai().localCommanders(...)` with the same `byDivision`, and clear it at the thaw.

  The first is simpler and also makes `Commanders.skip` counts equal everywhere.

## B. Desync and crash risks to fix or check with N allies

### B1. A joined ally that is retired, removing or out: `allyWatches` churns
- **Where:** Session.java:416-428 together with `viewRefusal` (Session.java:1636-1638: `retired(viewer) ... removing[viewer]` gives "That city can't be viewed.") and `viewsAt` (Session.java:1568-1573: cancelled with `why = GAME`).
- **What breaks:** `allyWatches` recreates any view whose `why == GAME`, so a removed ally gets a new View every report step. Each one is cancelled at its freeze, for the whole battle. The world state stays equal on every PC, so this is no desync. But on every PC the mod's `MpLoading` opens a new "X joins Y's battle" window every 30 steps (MpLoading.java:93-97), and the views table churns. `alliesJoined` (Session.java:2263-2276) also doesn't re-check `retired`/`removing`/`localBusy` at Engage.
- **Change:** in `allyWatches`, skip `retired(ally) || removing[ally] || b.roster.gone[c]`. In `alliesJoined`, drop a human that is `retired`/`removing` now and give it `ALLY_OUT`.

### B2. Several views freezing at one step: needless world reloads, and a viewer reloading for another viewer
- **Where:** Session.java:1001-1018, `viewWorldReloads`. `rejoins` (line 1007) only spares a PC whose own view rejoins at `v.freeze`. A PC whose own view of the same owner freezes at the same step (ally B, for ally A's view) still has `atFreeze` true and reloads its own world. Every PC outside the battle also reloads once per view (`v.reloaded` is per view), so N allies cost N world reloads of about 230 ms each, all at one step.
- **What breaks:** cost on every PC. It may also be a hazard: if the mod's `viewIn` ran before this `stepBegin`, ally B reloads a world into the battle game it just loaded. Assaults with 3 or more humans hit the same path and passed the rig, so this is probably safe, but nothing guards it.
- **Change:**
  - In `viewWorldReloads`, skip when `mine != null && mine.owner == v.owner && (mine.freeze == v.freeze || mine.rejoinAt == v.freeze)`.
  - Reload at most once per step: keep `lastReloadStep`.
  - Skip while `viewLoading`.

### B3. `localVote` reads `roster.gone`, which only the battle's PCs set
- **Where:** Session.java:2588-2603. `gone[c]` is set by `armyLeaves` (Session.java:2226-2245) inside the Retreat command, which runs only on the PCs that run the battle (fighter, allies, watchers, from the tape). Every other PC sets it only at the result (`localGone`, Session.java:560-568).
- **What breaks:** after a retreat, a vote from the commander who left changes `b.paused` and `speedVote` on the outside PCs only. Nothing outside reads them today: only the fighter's `speedSeen` uses `votedSpeed`/`paused`. So this is no desync, but it's one record that differs per PC. With 3 or more commanders, more retreats happen before the end.
- **Change:** have `localVote` change `b` only where `localRuns()` and the battle is this one. Or document that `votedSpeed`, `paused` and `gone` are battle-PC-only state until the result.

### B4. The fighter hashes the whole battle once per viewer
- **Where:** Session.java:1708-1719, `viewHashes`: one `h.take(..., source())` per frozen view, every 30 steps. With 3 allies and 2 watchers, the fighter's PC hashes all entities, divisions and projectiles 5 times per hash step, inside the step.
- **Change:** take the state hash once per step for the city this PC runs, and mix in each viewer's draw base for its clock part. Measure under the timing rule (requirements §4). This matters most on the fighter, whose PC sets the battle's rounds.

### B5. The deferred Engage runs with `currentHuman = living`
- **Where:** Hooks.java:856 calls `WorldBattles.pvpEngage()` (WorldBattles.java:210-216) in the world part after `s.currentHuman = s.living` (Hooks.java:855). Engage is deferred whenever the fighter pressed Engage before every ally answered (WorldBattles.java:116-117). The immediate Engage runs under the issuer's human.
- **Risk:** low. `Manual`'s constructor reads only the copied sides, and the generation runs later on the fighter's PC. With N allies the deferred path is the normal one, so check that nothing on the Engage path reads `FACTIONS.player()`. Simplest: `currentHumanSwap(Hooks.worldPromptHuman())` around `pvpEngage`.

### B6. Retreat with N allies: correct, one note
- Session.java:2206-2245 and BattleRoster.java:21: `leaving` is a single int, but `retreatSide` sets it and `armyLeaves` consumes it inside the same Retreat command (MapBattles RETREAT). Two retreats in one step therefore run one after the other, correctly. The last commander of the side to retreat ends the battle through vanilla's `liveRetreat`.
- An ally whose army left keeps their view, and their city stays frozen, until the battle ends. With N allies, more cities stay frozen longer. Consider ending a commander's view when they leave (spec decision).

## C. Rule compliance (owner's list) against today's code

| Rule | Status | Where |
|---|---|---|
| Asked if within 4 tiles, allied with the fighter, at war with the NPC | Generalises: vanilla's `Util.fill` (allies of the side's first faction and enemies of the other) gives `sosdetHumans`, de-duplicated per human; `BattleAllies.asked` keeps the fighter's side, skipping `localBusy` and `retired` | BattleAllies.java:31-45, patch 15 Resolver |
| Join / Stay out, 30 s → stay out | Generalises. The 30 s run from the prompt's opening (`promptTimer`), shared with the fighter's own answer. If the fighter presses Engage at 25 s, the allies have 5 s left. Spec's wording allows this; say it in the mod's text or start a per-ally timer at Engage. | Hooks.java:2499-2538, WorldBattles.java:186-189 |
| Battle on every joined PC, cities frozen | Blocked by A1 to A3; then each ally freezes through its own view (`frozen` reads `views[slot]`) | Session.java:818-823 |
| Fighter leads, others follow as watchers | Generalises once A2/A3 are fixed: `LocalTape` has one stream per viewer slot | LocalTape.java:42, 87-93 |
| Each player's orders go to the leader | Generalises: an ally's PC wraps orders in LOCAL_ORDER (Hooks.java:2179-2181), watch-only lets it through (AiLog.java:187-188), and the fighter's PC tapes it with `pcOf(currentHuman)` (Session.java:2606-2611) | |
| Each commands their own army | Generalises: `byDivision[i] = pcOf(owners[i])` (Session.java:438), `Commanders.allows`; but see A5 | |
| Speed vote, majority of commanders | Generalises: `BattleSpeedVote` counts commanders still in, so 2 of 3 settles and a 1-1-1 split never does | BattleSpeedVote.java, Session.java:2588-2603 |
| Pause | Any commander pauses, and any commander's non-zero press unpauses, even someone else's pause. With 3 or more, one player can override another's pause. Rule check for the owner. | Session.java:2594 |
| Retreat | Generalises (B6) | |
| Deployment slots split evenly | Generalises: `BattleRules.slots` round-robins over N humans, with the NPCs last | BattleRules.java:258-275, patch 15 `Side.sosdetShare` |
| Deployment placement | **Broken for 3 or more groups (A4)** | |
| Start | Fighter only (`deployReady`, Session.java:2184-2186). Spec §8 for battles between players: every commander, or 2 minutes. Pre-existing divergence. Change: `deployReady` as a set over `roster.commanders`, with `deployTimer` for local battles too. | |
| Result: shares by men brought, remainder to the most | Generalises: `BattleAllies.shares` builds `ResultShares` over `stillIn(side)`, `BattleRules.share` gives each item's remainder to the one with the most men, and each winner's prompt follows in turn with its own 30 s | BattleAllies.java:144-165, ResultShares.java, patch 15 `Res.accept` |
| Losing players get a losses window | **Missing for NPC battles.** `BattleOutcome` / `MpBattleOutcome` exist for battles between players only, and on a defeat only the fighter sees vanilla's prompt. Pre-existing. | Hooks.java:2445-2456, mod MpBattleOutcome.java |
| Transactions back to each city once | Generalises: survivors resolve in each WDIV in the world part on every PC (patch 15 `Res.accept`); each winner's share ships to their own capital (`Util.sosdetShip` with `sosdetTo`); an ally's held transactions wait in `heldTxns` until the view thaws | |

## D. The mod (SOSGSGMP)

- **`MpAllyJoin` and `MpPlay.allyPrompt`** (menu/MpAllyJoin.java, MpPlay.java:131-141): each PC shows its own, so this generalises. The text doesn't name whose battle it is. Suggest adding the fighter's name (`Hooks.worldPromptHuman()`).
- **`MpViews.owner`** (MpViews.java:166-195): the save fan-out, the per-viewer tape and the per-viewer hashes all generalise. This is the mod half of A3, and it's already right.
- **`MpLoading.freezesAhead`** (MpLoading.java:93-97): `isNew(VIEW_START, v.freeze)` keys on the step, so when N allies freeze at one step only the first ally is named ("A joins F's battle"). Change: join the names of every view with that freeze, or for allies say "Allies join F's battle".
- **`MpViews.bar`**: an ally's bar says "Watching F's battle" with a Close button. Closing ends the view with `CLOSED`, which `allyWatches` never restarts, and the ally's divisions stay on the field with no commander (spec §16 open). Suggest a "You command your army" line for allies, as for the assault's attacker, and a confirm on Close.
- **`MpPlay.battles`** (MpPlay.java:250-262): the chat names only fighters (`localBattle(slot)`); allies in the battle aren't mentioned and get the "Watch battle to join it" hint. Minor.
- **Losses window:** as in C. The mod would need a `BattleOutcome`-like notice for local battles: the losers on the human side, or the allies after a defeat.
- **`MpViews` owner messages** ("X joined to watch your battle"): say "joined your battle" for an ally (`engine.localBattleOf(owner).roster` isn't public, so the API would need `LocalBattle.commanders()`).

## E. What already generalises correctly

- `BattleRoster`: commanders, sides, reports, gone, owners and left are arrays sized by the commanders. `commander`, `ally`, `onSide`, `stillIn`, `goneHumans` and `allyStays` all loop.
- `BattleAllies.owners`, `inBattle`, `leave` (the loss is the army's share of the side's men, halved), `survivors`, `shares`, `groups` and `group`. `groupOffset` is fine for 2 groups (A4).
- `BattleRules.slots`, `leavingLoss`, `divisionLoss`, `share`, and `ResultShares` with any number of winners.
- `BattleSpeedVote` with any number of commanders and leavers.
- Bonuses per owner: `Hooks.allyOf`, `boostFaction`, `reportOwner` (every non-fighter owner reads its report), and `roster().reports[commander(owner)]` (Hooks.java:1359).
- Freezing: each ally through its own view (`frozen`, `ownTxns` holds its transactions, `pacer.away` per viewer PC).
- Thawing: `localResult` ends every view with `v.battle && v.owner == human` (Session.java:550-555). `localAfter` waits for every such view to thaw (`watched`). `localBacks` clears the table on every commander's PC.
- The result goes out once from the fighter and carries `goneHumans()`; `localGone` makes `gone` equal everywhere before the after-battle.
- Ally orders, votes and Retreat go through the log (LOCAL_ORDER and LOCAL_VOTE get past watch-only), and the tape keeps each order's PC.
- `localBusy` holds the prompts of every commander of a running battle, so an ally in a battle isn't asked again.
- Hashes: `Commanders.setLocal` keeps the table out of the clock hash and the resync. Views hash per viewer slot.
- Armies wait: `armyHeld` reads every local battle's `armies`, all allies' included.
- Battles between players (`OwnBattle`) already take every ally (`alliesJoined(human)`, Hooks.java:3014).

## F. Tests to change or add

- **Change:** `LocalBattleTest.anNpcBattleTakesOneAlly`, to "takes every ally who joined, in answer order". The `withAlly` helper keeps one ally; add a `withAllies(s, int...)` helper.
- **Add, unit:**
  - two allies' views both start at one freeze step (`allyWatches`);
  - `viewOut` watches the tape for every battle view freezing at its step (A3);
  - a removed ally's view isn't recreated (B1);
  - the speed vote in a local battle with 3 commanders (2 of 3 settles; one left, 1 of 2 doesn't);
  - two of three retreat and the third fights on; the third retreating ends the battle;
  - shares among 3 winners add up to the whole;
  - group offsets and row widths for 3 and 4 groups (A4);
  - a watcher that isn't a commander applies the same taped orders as the fighter (A5).
- **Rig:** the S9N line with 3 humans, two allies joining; both ally PCs' view hashes equal the fighter's to the end; one ally retreats; the result applies once on every PC; both ally cities are back with their held transactions. Run once with 20 divisions per human to confirm A4. Then a two-PC GUI run.

## G. Spec

Spec §8 ("If they join, only the two players' PCs run the battle") and AT line 400 ("An ally who joins an NPC battle runs it on the two players' PCs only") name two PCs. The owner's request changes that rule. Per CLAUDE.md, update the spec in the same PR, with the owner's approval recorded in its change log. Spec §16's open item (a commander who drops out, a slow ally dropped at 3 minutes) matters more with N allies: every dropped ally leaves divisions on the field with no commander.

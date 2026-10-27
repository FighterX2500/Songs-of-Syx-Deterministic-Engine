# Plan 0012: viewing another human's city (SOS-82)

Card: [SOS-82](https://fighterx2500.atlassian.net/browse/SOS-82). Mod spec §10 and ADR 0006 (SOSGSGMP). The mod's half is SOSGSGMP `docs/plan/city-viewing.md`. Approved by the owner on 2026-10-27, with option A for the host's own view.

## 1. Goal

A human (the viewer) watches another human's city (the owner's) live. The viewer's PC unloads its own city, loads the owner's and runs it in step with the owner until the view closes. Then the viewer's own city loads back and resumes where it stopped. Nothing else changes for any other PC, and no PC waits for the viewer, except when the viewer is the host (option A below).

## 2. Design

**The view entry.** The host logs a VIEW entry (`Engine.viewBegin(viewer, owner)`), a new AI log kind (log format 7). Every PC applies it at its stamped step S and computes the freeze step F, the first multiple of 30 (`Reports.EVERY`) after S. So every PC knows F before it runs it. `Engine.viewEnd(viewer)` logs the matching end entry, whose thaw step T is the first multiple of 30 after its step.

**At F, on every PC (start of the step):**
- The view is cancelled on every PC alike if a battle runs, a world prompt is open or a world question is kept: their actions are game objects the viewer's load would lose. The mod reads why (`Engine.view()`).
- Otherwise the viewer's human is **frozen**. Its report taken at F is not taken, so its last report is the one taken at F − 30, which applies at F as usual. From then on every PC's world reads that report, and nobody waits for the viewer's reports. Transactions for the frozen human are kept on the viewer's PC until T.
- The host's pacing leaves the viewer's PC out.

**The owner,** after step F: holds before F + 1 until the mod takes `Engine.viewOut()`. That call saves the game (`GameResync.save`) and returns the save with the engine's state of the owner's city that the save leaves out (§3). Then the owner loads that same save itself as a view load (option E, owner, 2026-10-27), keeping the session as the viewer does, and runs on. A game that loads a save doesn't run its city exactly like the game that saved it: in rig run V82D the citizens' AI split two steps after the load. Two PCs that load one save do run alike. The cost is a pause of a few seconds for the owner as the view opens, which the owner accepts by allowing the view.

**The viewer,** after step F: holds before F + 1. `Engine.viewIn(bytes)` saves its own game (`__beforeView`) and keeps its own city's engine state, then loads the owner's save as a *view load*:
- It keeps the session: the step, the log, the hashes, every human's reports and ledgers, the feeds. The viewer's world at F is the same as every PC's, so nothing of the world needs to come from the owner.
- It takes the owner's city state from the bytes.
- From then on the city is the owner's. The city stream is seeded with the owner's PC, the owner's city commands apply here, the owner's transactions apply to the city, and `FACTIONS.player()` outside the world part is the owner's human. The viewer's own human stays its local human for everything else (its world commands, its reports, `remote`).
- No report is taken on the viewer while it views.
- It runs faster (3.5× its speed) while it is more than 60 steps behind the host's confirmed step, so a 5-second gap closes in about 2 seconds.

**Option A, the host views.** The host holds at F like any viewer, and every PC waits for the host's log, so every PC holds until the host's view is loaded and runs on. No catch-up is needed. Moving the host's role to another PC during a view (option C) is for later.

**The view hash.** From F, the owner and the viewer each keep a second hash log with the city's sections (`HashLog` not world-only) and a clock of the step and the city's draws since F. The owner's goes out (`Engine.viewHashOut()`), and the viewer compares it with its own (`Engine.viewHashIn`). A difference is `Engine.viewDesync()`.

**At T** (M2), on the viewer, between T − 1 and T: it saves the world (`GameWorldLoad.save`), loads `__beforeView`, then loads that world into it keeping its own city (as a world resync does), puts back its own city's engine state and applies the transactions kept since F. From T every PC unfreezes the human: the reports taken from T on apply, and the world reads the frozen report until the report taken at T applies.

## 3. The owner's city state the save leaves out

Sent with the save in `viewOut`:

- `playerUpdateAcc` (the city part's player update timer; a load sets it to 0);
- the city's inputs not yet reported (`cityInputs`), the supplies sent (`SentSupplies`) and the event marks (`EventMarks`);
- the city's draws so far, so both hash logs count from F alike.

The world's state no save holds, put back after every view load and after the load back at T on both PCs (`GameWorldLoad.unsaved`):

- every world entity's move remainder (`WPath.movement`): without it the world split at once (rig run V82B);
- the sizes of the world entity pools (the caravans', the armies', the camps' `free` stacks): a loaded game reuses a pooled caravan where the running game makes a new one, whose path draws its direction, so the world split about 130 steps after the load (V82C, V82E).

- the faction war event's timer, which its load sets to 16 seconds whatever was saved (V82I);
- each NPC court's day counter (`NPCCourt.addT`): a day ages the royals and may draw a successor (V82X);
- the slave recipes' NPC production boosts (`FBoost.mul`): a new game draws them, and unlike the other recipes' they aren't in `Recipes.fboosts`, so no save holds them. A whole game's load drew new ones, and every NPC's slave prices and credits split about 690 steps later (V82J to V82AA; found by dumping every recipe booster per NPC, V82AA).

Every raid map (`RaidingMap.AUp`, `dirty`, one per human) is set as a new game's after every view load, so each works its army power and regions out afresh on every PC alike.

Every other PC reloads its own world too (`Session.viewWorldReloads`, owner, 2026-10-27): after step F, and before step T where the viewer loads its own city back (the owner too then), each saves its world and loads it back keeping its city, as a world resync does, with the unsaved state above put back. A world load's resets then happen on every PC alike. A whole game's load, as the owner and the viewer do, resets more than the world's alone; the list above carries that too, so the other PCs load only their world (owner, 2026-10-28). Rig run V82AB (3 copies, 3 humans, a view from 2400 to 2900): all copies identical to step 3600. The world reloads took 440 ms and 489 ms on the PC outside the view and 269 ms on the owner at the thaw, three copies sharing one PC; the viewer's load of the owner's save took 8.4 s.

The state dump that found these (`StateDump`, the rig's `--dump-at` and `--dump-reload`) is debugging only.

The city's own unsaved state shows in the view hash.

## 4. Milestones

- **M1, the spike:** the view entry, the freeze, `viewOut`/`viewIn`, the view load, the view hash, catching up. The rig runs `--humans 2` (and 3) with `--view STEP:VIEWER:OWNER`. Success: the viewer's city hash matches the owner's at every check for a few thousand steps, and the world hash still matches on every copy.
- **M2:** the thaw, the limits (not caught up 1 minute after the load, falling behind for 1 minute), a battle or a prompt for the viewer ends the view, the viewer's room plans across the view.
- **M3 and M4** are the mod's: the request and the owner's consent, the screens, the network, two PCs.

## 5. Known gaps (to check in M1)

- A city question open on the owner's PC at F (`cmd.StepPrompts`, its actions are game objects): the viewer's copy has none, and the owner's answer is skipped there. The view hash shows it.
- The game's caches that a load starts fresh. A load-only difference shows in the view hash; one in the world part shows in the viewer's world hash.

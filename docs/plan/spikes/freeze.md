# Spike: a frozen human's city back into a newer world (SOS-192)

SOSGSGMP plan `docs/plan/freeze.md` (owner approved 2026-11-12) freezes a human whose player leaves, and brings the city back when they rejoin. The spike answers two questions:

- whether a city left behind for game days can come back into a newer world on every PC alike;
- what a freeze after a crash puts at stake.

## 1. The swap already exists: a view's thaw

A viewer's own city is frozen while it views another human's city. At the thaw, its PC loads its own game back from the freeze step (`Session.viewBackStart`: `GameView.loadOwn`). Then it loads the world it ran meanwhile into that game, keeping its city (`viewBackWorld`: `GameWorldLoad.load` with the move remainders, then `applyHeldTxns`). A rejoin is the same move: load the city from its freeze save, then put the current world into it.

**FZ1** (rig, Mogus-72, 3 copies, 3 humans, speed 10):
- copy 3's human views copy 2's city from step 1,110 to 40,110, so its own city is frozen for 39,000 steps (2.1 game days);
- at the thaw, its PC loads its game from step 1,110 and the world of step 40,110.

Result: **all copies identical to step 46,000**, with 3,066 hashes compared and none missed. The city came back on the newer world and ran with every copy.

## 2. What is still different at a rejoin

- **The process is new.** A view's thaw happens in a running game. A rejoin follows hot-join's restart: every PC loads a campaign save.
  - The PCs that stayed load their own copy of save M, whole.
  - The returning PC loads its freeze save (its city and an old world), then the world of M into it.
  - Those two paths must give one world. The view's own rule covers it: at a thaw, the other PCs reload their world too, so every PC's world comes from a world load. At a rejoin, every PC can do the same: after loading its own save M, it loads M's world once more through `GameWorldLoad.load`.
  - The engine card's rig test checks this (FZ2).
- **Held transfers live on one PC today.** A view's held transfers are kept by the viewer's PC alone (`HeldTxn`). A frozen human has no PC, so every PC must keep its transfers in the world save, as a list per frozen slot, applied by the returning PC after its world load.
- **Frozen in every world.** A view freezes the viewer on every PC from its freeze step: its reports aren't read, and the world sees the last one. A freeze does the same with no thaw step until a rejoin.

## 3. A freeze after a crash: what is at stake

The returning city comes back from its last campaign save. The world went on from there until the crash. From the link scan (`notes/city-world-link-scan.md`):

- **World to city** (scan C) goes only through transfers (`TxnKind`). Today a PC that doesn't run that city drops them, but every PC can keep, per human, the transfers since that human's last campaign save. A crash-freeze then turns that list into the frozen slot's held list, and the returning city replays everything the world sent it: deliveries, payments, invasions, soldiers returning. **Nothing sent to the city is lost.**
- **City to world** (scan B):
  - B3 population, B5 mercy and B7 treasury are levels. The world reads the returning city's next report, so they go back to the save's values with the city.
  - **B1, soldiers who joined a world division, and B2, goods put into army supply, after the save stay in the world and also in the returning city: they exist twice.**
  - B4 invasion results and B6 events in that span happened in the world, and the city replays the matching transfers.
- **How much:** at most what the city sent to its armies since its last campaign save. That is bounded by the autosave interval (vanilla's setting) and by the save before each battle.

**Choice (taken under the owner's delegation, recorded for the report):**
- Keep the transfers since each human's last save on every PC, so the returning city loses nothing it was sent.
- Accept the B1/B2 duplicates.
- The host's "Freeze them" button says how old that player's last save is, so the host can pick Return to the last save instead.

Rejected:
- Undoing B1/B2 in the world: it would need a log of every soldier and supply item and an undo for each.
- Allowing "Freeze them" only after a recent save: a crash rarely waits for one.

## 4. For the engine card (SOS-193)

- `freezeHuman(slot)`: a step-world entry, like `retire`, but it keeps the capital and marks the slot frozen. Every PC stops reading its reports and keeps its transfers in the world save.
- Each human's transfers since its last campaign save are kept on every PC, in the world save, and cleared at each campaign save. At a crash-freeze they become the held list.
- `thawHuman(slot)` at the restart: the returning PC loads its freeze save, then the world (FZ2 checks the other PCs' world reload), then applies the held list.
- Frozen armies: vanilla's army update skips them (to find: `WArmy` update, upkeep, supply).
- Global events (mod spec §5) skip a frozen city.

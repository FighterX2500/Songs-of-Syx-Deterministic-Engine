# 0020: a frozen human (SOS-193)

SOSGSGMP `docs/plan/freeze.md` (owner decisions 2026-11-12); spike `spikes/freeze.md`. A human whose player leaves keeps their capital, frozen, until they rejoin.

## Steps

Each step ends with a rig run.

1. **Core: the freeze entry and the frozen slot.**
   - `Engine.freezeHuman(slot)` (host, between steps) works as `removeHuman`: a `REMOVE` entry whose payload has a fourth int, 1 for a freeze, with the same cutoff at the last report every PC reads. The slot's PC leaves the pacer.
   - At the due step (`retireDue`) every PC calls `GameHumans.freeze(slot)` instead of `retire`, and the slot is `frozenHuman`. That is saved in `SOSDET_HUMANS`, with a new format version.
   - `Session.frozen(slot)` covers it: no PC waits for its reports, and the world keeps what its last report applied.
   - Transfers to a frozen human are held by every PC in the world save (`HeldTxn` gets a codec).
   - **Rig FZ2:** copy 3 drops; the host freezes its human; the two others run on identical, through a save and load.
2. **The freeze's effects** (owner's rules), the same on every PC in the world part:
   - every stance with the slot becomes neutral;
   - its vassals go free;
   - every region but the capital becomes free land;
   - its open deals and the siege of its capital end;
   - its armies go to the capital and are frozen (vanilla's army update skips them).
   - **Rig:** FZ2 with a war and a vassal before the freeze.
3. **Protection.** No war declaration on a frozen human (player commands and NPC AI), no siege or assault of its capital, no raid or invasion of it, and no deal proposed to it.
   - **Rig:** an NPC or a human tries each, and each is refused.
4. **Thaw at a rejoin** (hot-join's restart, before step 0):
   - Every PC loads its own campaign save M, then loads M's world again through `GameWorldLoad.load`, as the view's thaw has every PC do.
   - The returning PC loads its freeze save, then M's world into it, keeping its city, then applies the held transfers.
   - `Engine.thawHuman(slot)` clears the flag and unfreezes the armies.
   - **Rig FZ3:** freeze, run, save; a fresh process rejoins; every copy identical after.
5. **Crash-freeze.** Every PC keeps each human's transfers since its last campaign save (`Engine.campaignSaved()` clears them, in the world save). A freeze after a crash makes them the frozen slot's held list, so the returning city replays them (spike section 3).

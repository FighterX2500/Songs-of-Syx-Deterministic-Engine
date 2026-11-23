# Plan 0019: every allied human in a battle against NPCs

SOS-160 (engine) and SOS-161 (mod), owner's request of 2026-11-08 (overnight test plan, section 4.1). Builds on plan 0016 (local battles), 0017 (watching) and 0018 M8 (one ally in a local battle). The audit before the first run: [notes/several-allies-audit.md](notes/several-allies-audit.md).

## The rule (mod spec §8, "An allied player")

- Every allied human whose army stands within 4 tiles of the battle along the map, allied with the fighting human and at war with the NPC army, is asked Join or Stay out. No answer in 30 seconds stays out.
- The battle runs on the PCs of every human who joined, apart from the world, and all their cities are frozen. The fighter's PC leads; the others follow its tape as a watcher does, and their orders go to the leader through the log.
- Each human commands their own army. The speed vote, pause, retreat, deployment slots and the result (shares by men brought, the remainder to the most men) follow the rules for battles between humans.

## What changes

1. **Join without a cap** (audit A1). `Session.localEngage` takes every ally who answered Join, in answer order: `alliesJoined(human)`. The test `anNpcBattleTakesOneAlly` becomes `anNpcBattleTakesEveryAllyWhoJoined`.
2. **Every ally's PC follows** (A2). `allyWatches` starts a view for every commander after the fighter, skipping one that is removed, being removed or gone. All their views freeze at the same report step.
3. **The tape for every view that freezes at one step** (A3). `Engine.viewOut` calls `tape.watch` for every battle view it marks sent at this step, not only the first. This was a latent bug for two plain watchers who froze at one step.
4. **Room for three groups on a side** (A4). `BattleAllies.groupOffset` keeps the whole side within vanilla's ±220 tiles: each group's half-width is cut to its share, `(440 - gap × (groups − 1)) / groups / 2`, and patch 99-allies bounds each group's rows by its own half-width, so a group grows in depth instead of losing divisions.
5. **The tape carries what ran** (A5). The fighter records the step's orders after applying them, without those its commanders table refused, so a plain watcher with no table runs the same orders.
6. **One world reload per step** (B2): several views freezing at one step reload an outside PC's world once.

## Not changed (rule gaps the owner may want later)

- Start in a local battle stays the fighter's (the allies' PCs follow the tape); the spec's "every commander, or 2 minutes" applies to battles on every PC.
- An ally who closes their view, falls behind or is removed leaves their divisions to the game's general (spec §16 still open).

## Tests

- Unit: two allies both join; one joins and one stays out; no answer; the roster holds three commanders; the speed vote settles on two of three; three shares add up with the remainder to the most men; `groupOffset` for 3 and 4 groups at 1, 20 and 40 divisions stays within ±220 with every group's half-width above zero; two views that freeze at one step both get the tape.
- Three games (the mod's `tools\battles3.py`): A3 (H, J and L win), A4 (they lose), A5 (they take an NPC town's garrison in real time).

## Determinism

Everything here feeds the battle step or the tape, so `.claude/rules/determinism.md` applies; no per-PC value enters the step. The fighter's tape is the record every follower runs.

## Results

(to fill after the runs)

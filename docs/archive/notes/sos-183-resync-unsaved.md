# SOS-183: a world resync carries the state no save holds

## What the playtest showed

Campaign `0b3e0f31fb15e7b4`, 2026-11-10, three humans over Steam. There were six desyncs, each with PC 2 (Limitless Gregg) as the odd one out while the host and Adam agreed:

| step | world parts that differ |
|---|---|
| 810330 | none (clock only) |
| 992910 | NPC courts |
| 1002120 | factions and stances |
| 1011330 | factions and stances |
| 1020570 | factions and stances |
| 1029780 | NPC courts |

From step 992910 on, one came every 9210 to 9240 steps. That is 12 game hours: a day is 1152 game seconds, at 16 steps a second. Every world resync was followed by the next desync until the session ended. The report saves' world hashes agreed, and the lobby checks the jar, so the code and the saved world were the same on every PC.

## Cause

Two values drive those two parts, and no save holds either one:

- `NPCCourt.addT` is a court's day counter. Once a day it ages the royals and draws `RND.oneIn(16)`.
- `EventFactionPeace.secondWhenWarEnds` is each faction's second when its wars end. The half-day updater draws RNG on it and makes peace (`DIP.NEUTRAL().set`). Vanilla then sets it to `peaceTime()/2`, which is already in the past, so a PC that differs makes peace again every half day.

`GameWorldLoad.unsaved()` already takes both, together with the move remainders, the entity pools, the war timer and the NPC production boosts. Views and local battles carry that block across their loads. A world resync doesn't:

- `WorldResync` has no field for it.
- `Engine.worldResyncLoad` loads the world's sections only, so each PC keeps its own values, the same values that had split.
- The world hash covers the courts' people, not their day counters, and not the peace times. So a split shows up only when it acts, up to a day later, and it stays split through every resync.

Where the split began is not known. The host fought a local battle against raiders (steps 622923 to 760458) and Adam watched part of it, while Gregg did neither. So the split lines up with which PCs reloaded for the battle and which didn't. That is the follow-up in step 3.

## Steps

1. **The resync carries the block.** `WorldResync` goes to format 3 and adds the host's `GameWorldLoad.unsaved()`, taken in `worldResyncSave` before the save. `worldResyncLoad` applies it on every PC, the host included, right after `GameWorldLoad.load`, as `Session.loadWorld` does for views. A world loaded from one save has the same entities in the same order, which views already rely on.
   *Check:* `WorldResyncTest` round-trips the block. An older format is still refused (only PCs running the same jar exchange it).
2. **The hash covers them.** The "NPC courts" part adds each court's day counter, and the "factions and stances" part adds each faction's peace time (`GameWorldLoad.hashCourtDays`, `hashPeaceTimes`). A split then desyncs within a step of its source, and the report names the part.
   *Check:* unit tests and build; in the rig, an ordinary world run shows no new desync (every PC keeps these alike when nothing splits them).
3. **Follow-up: the origin** (rig, after the playtest). The host fights a local battle against raiders, one PC watches and one stays a bystander. With step 2 in place, the hash shows the first split. Then find the load that resets one of these values on some PCs only.

## Not changed

Saves, views and local battles keep their own handling. The mod passes the resync state through unchanged: it travels in pieces with the save, so a few more kilobytes are no problem.

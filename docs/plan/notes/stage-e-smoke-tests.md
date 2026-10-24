# Stage E smoke tests (SOS-38)

One rig run per milestone. They run only after the owner's own game session ends (owner, 2026-10-23), one at a time, each with a check every 3 minutes. Build first (`.\gradlew.bat assemble test menuSafetyCheck`), and never while a run is live.

## M2B: milestone 2, the step split and the city report

The co-op check. Every copy runs the same city, so every copy's reports are the same, and the world part reads them instead of the live city.

```
python -u rig\rig.py city --tag M2B --save Mogus-72-19f86f1e048-47002c-0-121c.save --copies 3 --speeds 5,7,7 --steps 3000 --no-invade
```

**Passes when:**
- every copy reaches the main menu and loads: no `ExceptionInInitializerError` in `build\rig\inst\<k>\songsofsyx\logs` (M2B's first try crashed there, on `RDEdicts`);
- the exit code is 0: every copy matches copy 1 at every step to 3,000, and no engine reports a desync;
- each copy's stats in `summary.txt` show `reports` ≥ 100.

**Recorded, not pass or fail:** `reportBoosts` and `reportValues` (the open count from milestone 1), `reportMisses` (expected 0), `reportMaxBytes` and `reportMeanMs`, and the step times against M2A's.

If `reportMisses` is above 0, a booster was added after the first report. Find which one before milestone 4, since a follower's live read would differ from the host's.

**Result (2026-10-24): passed** on the second run. The first crashed in every copy about 27 s in: `_BOOST$Cluster.vGet(HCLASS_RACE)` read a null class, reached from the world part's diplomacy (`DipWarPlayer`, `RTrust`); fixed in `14-report-reads.patch` and `GameReport` (a null class means every class). The rerun: three copies identical to step 3,000, 200 hashes compared with none missed, 101–102 reports per copy, `reportMisses` 0, `reportMaxBytes` 13,668, mean step 6.0 ms, p99 about 25 ms.

## M3: milestone 3, transactions

The co-op check again, now with things crossing both ways: an invasion of the capitol (the rig's `invade`) and the player actions in `rig\cmds\m3.txt` (a division sent out and recalled, a recruit, a gift). Every copy runs the same city, so every copy queues the same transactions and sends the same report inputs, and the world part applies them at the same steps.

```
python -u rig\rig.py city --tag M3 --save Mogus-72-19f86f1e048-47002c-0-121c.save --copies 3 --speeds 5,7,7 --steps 24000 --cmds @rig\cmds\m3.txt
```

**Passes when:**
- every copy reaches the main menu and loads, with no exception in `build\rig\inst\<k>\songsofsyx\logs`. A `SOSDET:` message from `GameInputs` or `GameTxns` there means an input or a transaction found nothing to apply to;
- the exit code is 0: every copy matches copy 1 at every step to 24,000 (about 5 minutes at speed 5; the actions take about 3, and the rig sends them by the clock, so a shorter run ends before they apply);
- each copy's stats in `summary.txt` show `txnsApplied` > 0, `txnsSkipped` = 0 and `inputsSent` > 0, and the same three numbers on every copy;
- `reportMisses` is 0.

**Recorded, not pass or fail:** `txnsMaxInStep`, `inputsSent`, `reportMaxBytes` against M2B's (inputs make reports bigger: a soldier is a few hundred bytes), the step times, and whether a scripted event fired during the run (the game's own message log). The run can't force an event; if none fired, B6 is still unchecked in the rig, and the M4 run is the next chance.

If copy 1 differs from the others at a step where a transaction applies, the city tail used something one copy had and another didn't. Run again with `--trace-draws` from a few steps before.

**Result (2026-10-24): passed** on the fourth run.
- *Run 1* crashed in every copy when the first soldier left for the army: `Induvidual.save` found no stats saver. The game makes its layout savers (`DataO`, `RMAPS`) only while it saves a game, so outside a save they are null. Fixed with `GameSoldiers`, which makes one saver and loader set for the running game's layout and lends it for each soldier.
- *Runs 2 and 3* (6,000 and 18,000 steps) stayed identical but sent no inputs: the recall came 30 s after the send-out, before any soldier reached the map's edge. `m3.txt` now waits 90 s, and the run is 24,000 steps.
- *Run 4*: three copies identical to step 24,000, 1,600 hashes compared with none missed; `txnsApplied` 74, `txnsSkipped` 0, `inputsSent` 1 on every copy (one soldier joined); `reportMisses` 0; `reportMaxBytes` 13,857; mean step 5.2 ms, p99 19 ms. `wrecruit` was skipped: the save's army 0 has no regional division. Not seen: a scripted event (B6) and a division's return through the city tail; the run is too short for the soldier's return date.

## M4: milestone 4, follower mode and the world-only resync

The own-city check. Copy 1 is the host: its city is the one the world reads, and its reports go to the others through a file. Copies 2 and 3 run cities of their own (their city streams are seeded with their PC number, and copy 1's city commands don't reach them), and their worlds read copy 1's reports. The copies are compared on the world only. At step 3,000 copy 1 saves, and every copy reloads only the world from it, keeping its city. 108,000 steps are 30 minutes of game time.

```
python -u rig\rig.py city --tag M4 --save Mogus-72-19f86f1e048-47002c-0-121c.save --copies 3 --speeds 5,7,7 --steps 108000 --no-invade --follow --world-resync 3000 --cmds @rig\cmds\m3.txt
```

**Passes when:**
- every copy reaches the main menu and loads, with no exception in `build\rig\inst\<k>\songsofsyx\logs`;
- every copy loads the world resync at step 3,000 (`summary.txt` shows the three loads) and runs on;
- the exit code is 0: every copy's world matches copy 1's at every step to 108,000, and no engine reports a desync;
- copies 2 and 3 show `reportsReceived` > 0 in `summary.txt`, about one per 30 steps (3,600 for the run);
- `reportMisses` is 0 on every copy.

**Recorded, not pass or fail:** the world resync's save time and size and each copy's load time; `txnsApplied` and `txnsSkipped` per copy (the followers apply the world's transactions to their own cities, so theirs can differ from copy 1's, and a skipped one is expected there); the step times against M3's; how far the followers' cities drift from copy 1's (men at the end).

If a follower's world differs from copy 1's, the world part read something from the live city that the report doesn't carry. Find the first differing step in `summary.txt`, then run again to a few steps before it with `--trace-draws`; the call that differs names the read. If the copies differ only after step 3,000, the world resync left something behind: compare the sections it loads (`GameWorldLoad.KEYS`) with the read.

**Result (2026-10-24): failed on the engine's clock section; cause found.**
- *M4 run* (3 copies, 108,000 steps): every copy's world matched copy 1's at every step, and the world resync at step 3,000 loaded on every copy. But the engine's clock section split at step 98,490: copy 1 against copies 2 and 3, which agreed with each other. `aiApplied` was equal, so the world part's draw count (`worldDraws`) differed.
- *M4T* (the same run with `--trace-draws 90`; the trace now keeps the world part's draws only in own-city mode): the first differing draw is at step 99,216, where copy 1 has 339 world draws and the followers 240. Copy 1's extra draws are a raid starting: `RaiderText.set`, `RaidingUtil.attackSpot`, `MessAlly.help` and `MessDemand$DEntries.set`, from `game.raiding.Updater.update` and `RaidingCurrent.raid`.
- **Cause:** the raider decision reads the live city in the world part. `Raider.hasInterrest` reads the population, `BARRACKS.reqs.passes(player)` and `RaidingUtil.ransomCurrent()` (the population, the player's credits and RAID_SECURITY). `Raider.isScared` reads `RaidingUtil.defences()`. On a follower these values are its own city's, so only the host's world starts the raid. The fix (not built; the owner decides) is to carry these values in the city report and read them from it in own-city mode.
- **Gap:** the rig's per-step world hash (`GameHash.worldHash`) doesn't cover the RAIDING state, so only the engine's draw count caught the split.
- An audit of the other world-part reads of the live city, made after M4T, lists more candidates: tech, level and title lockers that the report doesn't back; the `FWorth` cache that the city part fills; and `INVADING`, which is stale for up to 60 steps after an invasion starts.
- *W1* (own-city, host on this PC and the laptop as follower over Radmin; 2 copies, 60,000 steps; `--remote 2`): the world resync worked across PCs (save 1,036 ms and 3.4 MB; loads of 195 ms here and 300 ms on the laptop), and the world hash matched at every step. The engine's clock section split at step 41,520 with no raider on either copy, so another read from the audit's list is at work. It is not traced yet. The host noticed the split later, at its step 58,110, because the follower's hashes reached it late. Only the clock section differed: own-city mode leaves the city's sections out of the comparison (`HashLog.worldOnly`).

## Two PCs and LLM players (2026-10-24)

`rig.py --remote K` runs copy K on another PC (here the owner's laptop over Radmin, an AMD Ryzen 3 4300U against this PC). `rig/relay` mirrors the rig's files and hands over its commands across the link. Peers' orders now reach the host (`ordersout`, `ordersfrom`). `rig/play.py` lets an LLM agent play one copy: Sonnet 5.5 agents at medium effort read `state` and send `play` actions from `rig/cmds/*.txt`.

| Run | Copies | Steps | Result |
|---|---|---|---|
| L1 | host + laptop peer | 6,000 | identical at every step; the laptop loaded the save into the same start hash |
| L2 | host + laptop peer, with the invasion | 36,000 | identical at every step; `aiApplied` 9,968 |
| L3 | host, 2 peers, laptop peer, with a few hand-sent peer actions | 24,000 | identical; the 4 actions from 3 PCs applied on every copy |
| P1 | host, 2 peers, laptop peer; LLM players on copies 1, 2 and 4 (123 actions) | 100,000 | copies 1–3 identical to 100,000; the laptop identical to 24,527, where it crashed on a rig bug (`room-consumption library 1`: `NoSuchElementException` in `Rig.later`; fixed) |
| P2 | host, 2 peers, laptop peer; LLM players on copies 1, 3 and 4 (187 actions), with the fix | 60,000 | all 4 copies identical at every step and engine hashes agree; 174 orders applied on every copy, 2 skipped |

So far no math difference between the two CPUs: no StrictMath change was needed. The players saw credits fall from 2.28 million to almost nothing, and the soldiers from 146 to about 30, while the invasion ended. It happened the same way on every copy, so it is game behaviour, not a split. It is not explained yet: a gift moves only its own amount (`DealParty.execute`).

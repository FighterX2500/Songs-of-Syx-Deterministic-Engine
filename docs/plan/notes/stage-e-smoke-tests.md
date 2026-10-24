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

**Fixes and reruns (2026-10-24, the owner's go-ahead in the morning).**
- Built: the raider's barracks requirement from the report (new field `BARRACKS_UNLOCKED`); the `FWorth` cache keyed per part (`Hooks.partKey`). Report format version 3.
- *S1* (own-city, 2 copies on this PC, 36,000 steps, with a temporary probe that logged the raider decision's inputs once per step in the world part): every copy identical, but the probe found the cause of M4: the player's army power as the world part reads it (`AD.power()`) differed between the two cities from step 13,875 (4,042 against 4,061). Every other input, the barracks requirement included, matched. `ADPower` caches an army's power in the army's data until a division joins or leaves, and the top bar filled that cache between steps from the live city (a division's equipment, training and boosts). The fix: with a session only the world part's own thread reads and fills the cache (`Hooks.worldCacheClosed`); every other caller works the power out afresh.
- *S2* (S1 again with the fix): every copy identical, and the probe's 194 lines matched on both copies. The probe was then removed.
- *W1R* (W1 again with the three fixes; host here, laptop follower): **passed.** Both copies identical to step 60,000 and the engine hashes agree, past W1's split at 41,520. The host compared only 124 of the laptop's hashes (1,656 arrived too late); the laptop compared all 2,000 of the host's.
- A second read-only audit, of NPC-player diplomacy, trade, deals and war, found two more reads, checked in the source and fixed after W1R:
  - the royals' opinion and trust of the player read the player's boosts on the population class (`HCLASS_RACE.clP()`), which went round the `BValue.vGet(Faction)` hook to a live read. NPC war, stance and deal decisions read them. In the world part a boost read on `clP()` now reads the report's new `BOOST_CLP`;
  - the army power scale (`Power.minPower`, `maxPI`) comes from reference divisions of the player's faction and was worked out once after a load by whoever called first. The world part now keeps its own scale.
- *M4R* (M4 again with all five fixes; 3 copies, 108,000 steps): every copy's world identical at every step, but the engine's clock section split again at step 99,600, copy 1 (speed 5) against copies 2 and 3 (speed 7), as in M4. So one more read in the world part differs between the cities this late in the run. A traced rerun (M4T2, `--trace-draws 90`) looks for it.
- *M4T2* (the trace): the first differing draw is at step 99,031, and this time the followers start a raid that copy 1 doesn't (`RaiderText.set`, `RaidingCurrent.raid`).
- *S3* (a new probe, the same 3 copies at speeds 5, 7 and 7, logging the raid timer and each raider's interest and fear once per world step): the first difference is at step 3,185, just after the world resync. A raider was scared on two copies and not on the third, with the same capital defences on all three.
- *S4* (S3 to step 4,200, also logging the raiders' powers): the powers were identical on every copy before the world resync and different on every copy after it. **Cause:** the world resync's load misread the raiders and `RAIDING` made new ones from each PC's own draws (`city-world-link.md`, "The world resync"). The rig's world hash doesn't cover the raiders, so nothing flagged it at the load.
- **Fix:** the world resync writes a save of the world's sections only (`GameWorldLoad.save`). The world hash now covers the raiders (power, defeat, raids, and the raider now raiding; hash format version 5).
- *S5* (S4 with the fix): every copy's probe log identical to step 4,200. The world resync save is now 625 KB in 161 ms (it was 3.4 MB in 1,100 ms); the loads take 107–146 ms.
- *M4R2* (M4 with every fix, before the hash change; 3 copies at speeds 5, 7 and 7, 108,000 steps): **PASS**. Every copy identical to copy 1 at every step, 7,200 engine hashes compared per copy with none missed, `reportMisses` 0, 21,252 transactions applied on every copy and none skipped. The world resync: 625 KB saved in 165 ms, loaded in 123–152 ms.
- *S6* (the raiders in the world hash, format 5; 3 copies, 9,000 steps with the world resync at 3,000): **PASS**.
- *W1R2* (W1 again with every fix; W1R loaded the broken world resync too): **PASS**. The laptop identical to copy 1 at every step to 60,000. The laptop compared all 2,000 of the host's engine hashes; the host compared 90 of the laptop's (1,672 came too late, because the laptop runs behind and the host doesn't wait in own-city mode). `reportMisses` 0 on both. The world resync: 625 KB saved in 152 ms, loaded in 115 ms here and 154 ms on the laptop.

**Pacing (2026-10-24, owner):** W1R2 showed the host running thousands of steps ahead of the slower laptop; mod spec §4 says the slowest PC caps the speed. The host now runs at most `--pace-lead` steps (default 60) past the slowest copy, learnt from that copy's hashes (`Engine.peerStep`). The owner asked for smoke tests only (pace tests, not desync tests):
- *P1L* (3 copies here at speeds 5, 7 and 7, 12,000 steps, world resync at 3,000): **PASS**. The copies stayed within 40 steps of each other; the host was held by the others in 44 frames, 0.4 s.
- *P1W* (the laptop as follower, 12,000 steps, world resync at 3,000): **PASS**. The host stayed within about 25 steps of the laptop (W1R2: thousands), and compared 396 of the laptop's hashes with none missed (W1R2: 90 compared, 1,672 missed). The host was held by the laptop in 8,209 frames, 52 s of the run's 200 s.

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

## SOS-32: followers on older saves (2026-10-24)

The owner's 30-minute session runs followers on their own, older cities: the host plays Mogus-72, and the followers load Mogus-40 and Mogus-20 and take the host's world at the world resync. Each follower's world differs from the host's until the resync, so the rig now compares the copies' logs from the step after it (`--follower-saves`; the runner's `compare_from`). All runs: `city`, `--follow --world-resync 15`, `rig/cmds/m3.txt` (attach, recall, recruit, gift), draws traced.

- *F1* (3 copies at speeds 5, 7 and 7; Mogus-40 and Mogus-20 followers): the engine's clock section split at step 60. The first differing world draw is at step 33 (`RDRacePopulation`).
- *F2*, *F3* (the host and the Mogus-40 follower, 300 and 150 steps, with a temporary region probe): on the follower, region 100's City Hall ran at no efficiency. **Cause:** its "Organization I–IV" tech locks read the follower's own tech (`Lockers`, a code-made `Locker`), so workforce, every building's efficiency and the region's capacity split. **Fix:** the world part reads tech, level and title unlocks on the player and the player's regions from the report's new `UNLOCK` (report format version 4). The region build points' per-update fill is now keyed by part (`RDBuildPoints`, patch 98), as the other caches are.
- *F4* (F1 with the fix, 12,000 steps): every copy's world identical at every step from 16, but the clock section split at step 5,670. The first differing world draw is at step 5,665 on the Mogus-40 follower, in a region's loyalty (`RDRaceLoyalty`).
- *F5* (the host and the Mogus-40 follower, 6,000 steps, logging every region's loyalty-target boosters every 300 steps): **PASS** to 6,000, but from step 2,400 region 100's "Army presence" booster read 19.84 on the host and 17.58 on the follower.
- *F6* (F5 with each player army's divisions in the log): the stored divisions' experience and training differed from step 300, before any command, and the host's were twice the follower's for one division. **Cause:** the game's own `WDivStored.load` adds the soldiers' stats to the totals already there and never clears them, so a second load in one JVM, or the world resync's load, counts them on top of the last save's. Army power, and so each region's army presence, then differ by PC. **Fix:** the load clears the totals first (patch 14).
- *F7* (F1 with both fixes; 3 copies, 12,000 steps): **PASS**. Every copy identical to copy 1 at every step from 16 to 12,000, 800 engine hashes compared per copy with none missed, `reportMisses` 0, no transactions skipped. The world resync: 627 KB saved in 226 ms, loaded in 232–310 ms.
- *F8* (the same save on every copy, 9,000 steps, world resync at 3,000) and *F9* (co-op, no follow mode, 6,000 steps), both with both fixes: **PASS**. Every copy identical at every step, engine hashes agree, `reportMisses` 0.
- *S32* (the owner's played session: the owner plays the host at 1x on Mogus-72, followers on Mogus-40 and Mogus-20 at 25x, draws traced): the engine's clock and world sections split at step 16,590. The first differing world draw is at step 16,570: both followers' world part made a caravan (`WArmy.checkForResources`, `returnResources`) that the host's never made. **Cause:** an army's supply targets (`ADSupply.cache`, kept until a division joins or leaves) are filled by whoever reads them first. On a follower the city part's supply rooms filled them from the follower's own divisions' kit, and the world part then judged the army's surplus by those numbers. **Fix:** only the world part fills them (patch 14, under `worldCacheClosed()`); the UI and the city part read what it made. In the same session a technology clicked in the research window showed only after reopening it: every full-screen window, the wiki, the diplomacy window and the region buildings popup paused the game while open, so the click's command waited for the window to close. **Fix:** in a session they don't pause (`Hooks.menusPause`, patch 96-cmd-world; mod spec section 4).
- *F10* (F7's setup with the supply fix, the scripted commands, 24,000 steps): the world section split at step 4,950 with the clock still equal, i.e. no extra draw. *F11*, *F12* (the same to steps 7,000 and 5,200, with a temporary probe logging every army's supplies and supply targets): the split repeats at the same step. From step 4,836 one army's equipment needs read 2,500 on the host and 1,500 on the followers. **Cause:** the world part now fills the targets itself, and reads each stored division's kit through `WDivStored.target()`, the live city division's settings: on a follower its own city's. Before, the city part usually filled them first, which hid this read. **Fix:** in the world part and a world resync's load, `target()` gives the kit and men from the report (`DIV_EQUIP`, `DIV_MEN`); only the UI reads a target's training. Probe removed.
- *F13* (F10 with both fixes): **PASS**. Every copy identical to copy 1 at every step from 16 to 24,000, past both split points (4,950 and 16,570); 1,600 engine hashes compared per copy with none missed. *F14* (F8's setup, the same save on every copy, with both fixes): **PASS** to 9,000.
- *S33* (S32 replayed on the fixed build, the owner playing the host for about 21 minutes): **PASS**. Both followers identical to the host at every step from 16 to 86,171, where the owner closed the window; 5,745 engine hashes compared per follower, none missed. The host's own hash count is 0 because its window was closed before the run's end.
- *S33, the windows:* the owner found the research icon still unchanged until the window was reopened, and the profile's banner sliders lagging, despite `menusPause`. *RW1* (rig, 2 copies, the research window opened by the rig): the step stood at 477 for about 9 s while the window was open, then went on once it closed. The cause: VIEW gives the game no time while a window holds the frame (`ds = 0`), and the session let time run only under the battle prompt and kept questions. With time running under every window in a session (patch 65), *RW2*: **PASS**, the steps ran on (802, then 1,688) and a tech downgrade applied with the window open; identical to 6,000. *BD1* (the banner as a draft, Accept and Cancel under its colours): **PASS**. Accept sent the shape and colours as one command; Cancel and closing the window each put the banner back; one command applied per copy, identical to 8,000.
- *GUI40* (SOS-40: the banners' pixels and the division banner popup as drafts; the owner playing the host on Mogus-72, one follower): **PASS**. The owner checked the profile banner, the division banner popup and the division editor on screen; 6 commands applied on both copies, identical to step 3,494, where the owner closed the window.
- *MB12* (SOS-41: the map battle of MB10 on 2 copies, nobody at either; the rig no longer closes the load screen itself; `--stall-profile`): **PASS**. Engage at step 11,798, Start 11,829, Retreat 12,760, back on the world map at 12,791 on both copies, and the run went on to 36,000, identical at every step: the load screen closes itself after the battle's end load. The Engage step took 21.6 s: 18.9 s generating the battle, of which 5.9 s was the simulation thread waiting for the minimap's GL jobs (one per 32×32 tile, each a hand-off to the GL thread), and 2.1 s for the two saves.
- *MB13* (MB12 with the minimap uploaded in one GL job, and `--restart 300`): **PASS**. Restart at step 12,246 and Start again at 12,277 on both copies, identical to 36,000; 9 commands applied per copy. The Engage step took 13.9 s: 11.0 s generating (the minimap 0.65 s, no GL waits left; the rest is the game's own generation: tile map 6.5 s, pathing 1.9 s, settlement 1.5 s, armies 0.6 s) and 2.5 s for the two saves, both kept (the end loads one, Restart the other).
- *MB14* (MB12 with `--in-battle "100 warmyname 0 SOS41"`): **PASS**. Copy 1 renamed army 0 at step 12,002, inside the battle (Start 11,871, Retreat 12,802); the world command came up at step 12,033 and was skipped on both copies (`commandsSkipped` 1 each), with the notice on copy 1 only; identical to 36,000.

## SOS-42: the speed governor (2026-10-24)

All on this PC (i5-11600K, RTX 3060 Ti) as host and the laptop (Ryzen 3 4300U, 4 threads, integrated graphics, 7.4 GB) as copy 2: Mogus-72, no invasion, 12,000 steps, both copies started at 25x.

- *G1* (the governor on, but given no target speed by a bug, so only the pacing ran): **PASS** on identity. The pair averaged 61 steps a second (about 3.8x at 1/16 s steps): the host was held by the laptop for 193 s of 196 s, the longest hold 14.9 s. Step time 3.9 ms here, 6.8 ms on the laptop.
- *G2* (the target fixed): identical, but the speed swung between 0.3x and 2.4x and the run took 456 s. Each short hold lowered the speed by another tenth, and below 2x the steps cost more per second, so some drops were large (2.0x to 0.4x).
- *G3* (lowering only when the slowest PC runs under 95% of the agreed speed's steps a second, not within 2 s of a change): identical, held at 0.3x to 0.6x, 516 s. **Cause:** vanilla's update loop gives a frame at most 1/32 s of game time and throttles the speed of a PC under 40 frames a second; the laptop draws about 25, so it ran below every speed it was given. Fix: in a session a frame's game time is its real time times the speed (`StepClock.stepsForFrame`; vanilla's only after a frame over 0.25 s, such as a pause).
- *G4* (G3 with that clock): **PASS**. The agreed speed settled between 5.3x and 10.4x and ended at 7.2x; 12,000 steps in 104 s (7.2x on average). The speed line appeared on both copies at the same steps. Identical at every step to 12,000, 400 hashes compared per copy with none missed, and the end hash is G1's (the clock changes the pace, not the result). The longest hold was 7.0 s, as in G1 to G3 (5.7 to 14.9 s), so likely the laptop's later start; not checked. After G4, a frame vanilla gives no game time (a notice or message holds it) stays held under the new clock; unit-tested, not rerun.

The rig's lock now also checks that the process it names is a `rig.py`: a runner stopped from outside left its lock, and Windows had given its process number to another program.

Two points from the camera and clicks audit:
- A5 needs no code: sprite timers and corpse dirtiness change outside the step but feed neither the step nor the hash.
- A2 (patch 67): between steps the supply room's resource set (`ROOM_SUPPLY.has`) is read, never refilled, under `outsideStep()`, not `worldCacheClosed()`. The city part fills that set itself, and `worldCacheClosed()` is true in the city part too.

## SOS-15: a battle on two PCs, the owner playing (2026-10-24)

This PC as host, the laptop as copy 2 in a 1600×900 window on its own screen, the SOS-11 field battle (2,000 v 2,000, seed 12345), `--govern`, `--trace-draws 600`.

- *V0* (stage 1 smoke: nobody at either, the laptop only watching, 3,000 steps at 1x): **PASS**, identical at every step to 3,000; held 1.5 s in all. The battle save copy 1 generates is now also sent to the laptop (`RemoteCopy.put_save`); before, only copies on this PC got it.
- *V1* (stage 1: the owner played the host, the laptop only watching, `--watch 2`): **PASS**. The owner fought the battle to a win and closed the window at step 14,242; both copies identical at every step to there (compared from the per-step logs, see below). The owner set 5x several times; each time the governor brought it down to 2.1x to 3.3x (4,000 men on the laptop), with the speed line on both copies, and back at 1x the laptop kept up. No clicks were dropped on the laptop (nobody clicked there). The rig hung after the window closed: its closed-window check counted the relay's Java, whose arguments name copy 1's folder, as the host's game. Fixed (`copy_running` skips the relay).

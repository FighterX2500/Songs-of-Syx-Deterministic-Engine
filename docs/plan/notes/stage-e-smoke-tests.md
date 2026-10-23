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

## M3: milestone 3, transactions

The co-op check again, now with things crossing both ways: an invasion of the capitol (the rig's `invade`) and the player actions in `rig\cmds\m3.txt` (a division sent out and recalled, a recruit, a gift). Every copy runs the same city, so every copy queues the same transactions and sends the same report inputs, and the world part applies them at the same steps.

```
python -u rigig.py city --tag M3 --save Mogus-72-19f86f1e048-47002c-0-121c.save --copies 3 --speeds 5,7,7 --steps 6000 --cmds @rig\cmds\m3.txt
```

**Passes when:**
- every copy reaches the main menu and loads, with no exception in `buildig\inst\<k>\songsofsyx\logs`. A `SOSDET:` message from `GameInputs` or `GameTxns` there means an input or a transaction found nothing to apply to;
- the exit code is 0: every copy matches copy 1 at every step to 6,000;
- each copy's stats in `summary.txt` show `txnsApplied` > 0, `txnsSkipped` = 0 and `inputsSent` > 0, and the same three numbers on every copy;
- `reportMisses` is 0.

**Recorded, not pass or fail:** `txnsMaxInStep`, `inputsSent`, `reportMaxBytes` against M2B's (inputs make reports bigger: a soldier is a few hundred bytes), the step times, and whether a scripted event fired during the run (the game's own message log). The run can't force an event; if none fired, B6 is still unchecked in the rig, and the M4 run is the next chance.

If copy 1 differs from the others at a step where a transaction applies, the city tail used something one copy had and another didn't. Run again with `--trace-draws` from a few steps before.

## M4: milestone 4, follower mode and the world-only resync

Prepared when milestone 4 is built. This is the 30-minute follower run with `--no-invade`.

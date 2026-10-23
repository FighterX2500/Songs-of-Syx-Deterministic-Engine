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

Prepared when milestone 3 is built.

## M4: milestone 4, follower mode and the world-only resync

Prepared when milestone 4 is built. This is the 30-minute follower run with `--no-invade`.

# Test rig

Runs the same simulation in several game copies at different game speeds and compares them step by step. It is how SOSDET proves determinism on one PC (requirements §4, §5) until the scenario suite (SOS-31) takes over.

Nothing here ships to players. The rig is a second mod, `SOSDET-rig`, built next to the engine:

| Part | What it is |
| --- | --- |
| `java/sosdet/rig/Rig.java` | A script mod that drives one game copy from a command file and logs one state hash per step |
| `patches/90-autoload.patch` | Rig-only change to the game: load a save at start-up, so runs need no clicks |
| `rig.py` | The runner: prepares the copies, starts them, sends the commands, compares the logs |

## Running

```
gradlew.bat assemble
python rig\rig.py field --tag F1
python rig\rig.py city --tag C1 --save Mogus-72-19f86f1e048-47002c-0-121c.save
```

- **field:** the SOS-11 battle, 2,000 v 2,000 (seed 12345) next to the capital of the save `sosmp-det-base` in your saves. Copy 1 generates it; every copy loads that battle save, seeds the simulation, deploys as step 0 and runs.
- **city:** the given save from your saves folder with the world held still, under a 1,000-man invasion that starts at step 1 (SOS-23).

Options: `--copies 4`, `--speeds 5,7,8,10`, `--steps 36000` (10 minutes of game time), `--keep` to leave the copies open.

The slowest copy is the reference: it runs exactly `--steps`, and its speed names the run. The default is a **5x baseline test**: the reference at 5×, the others at 7×, 8× and 10× (owner, 2026-10-22). SOS-27's acceptance runs used a 1× reference (`--speeds 3,1,5,2`). The others run with no last step, and the rig holds them the moment the reference stops, each wherever it got to; the faster copies end well past `--steps`.
- **Acceptance:** every copy matches the reference at every step up to `--steps`.
- **Stopping early:** while the copies run, the runner compares their logs every 2 s. At the first step where any copy differs, it holds every copy and ends the run, so a divergence costs only the time up to it. `--run-on` runs the whole length anyway. The copies are past that step by then; to see the state right at it, run again: everything before that step replays identically.
- **Logged too** in `summary.txt`:
  - each copy's end step;
  - whether the hashes agree over every step all copies reached;
  - each copy's end state against the copies that passed that step.

Each copy gets its own data folder under `build\rig\inst\<k>`: windowed and tiny, muted, with both mods enabled. Your own settings and saves are only read. Results go to `build\rig\runs\<tag>\`:
- `summary.txt`: start and end hashes per copy, engine stats (step times, draws), the first step where a copy differs;
- `<tag>-c<k>.csv`: the per-step hashes;
- `events-c<k>.jsonl`: everything the rig reported;
- `battle.save` (field).

The exit code is 0 when every copy is identical to copy 1. Only one rig run at a time: the game copies compete for the CPU, and the timing numbers mean little while anything else runs.

## The AI general on the host

`--ai host` runs the multiplayer setup:
- **Copy 1 is the host.** Its AI general plans on its own thread, and every change it makes goes into the AI log. Copy 1 appends that log to a file every frame.
- **The other copies are peers.** Their general is off. Every frame they read what is new in the host's file and feed it to the engine, which never lets a peer run more than 30 steps past the host's last confirmed step.
- **The host is the reference.** When it stops, each peer is held once it has passed the host's last step.

This is the flow the multiplayer mod will use, with a file in place of the network, and the default since the owner made host mode the engine's default (2026-10-22): inside the step, the general's planning stalls the simulation. `--ai instep` runs the general inside the step in every copy instead.

## State hashes and desyncs

In every run, each copy also hands its engine's state hashes (every 30 steps) to the others, the way the mod will over the network: every frame it appends `Engine.hashOut()` to its `hashes.bin` and feeds the other copies' new bytes to `Engine.hashIn`. The engine compares them; on the first difference the copy writes the engine's desync report (`desync-c<k>.txt` in the run folder) and a `desync` event. `summary.txt` says, per copy, whether the engine found a desync, and how many hashes it compared.

```
python rig\rig.py field --tag D1 --copies 2 --steps 1200 --nudge 601
```

`--nudge STEP` forces a desync: at that step, inside it, one soldier of the last copy (or `--nudge-copy K`) gets fatal injuries; the game kills them on their next update. The run doesn't stop at the difference. It passes when every copy's engine reports it at the first hashed step at or after `STEP`, in the `soldiers` section, against the nudged copy (SOS-29 acceptance).

## Resync

```
python rig\rig.py field --tag R1 --resync 12000
python rig\rig.py field --tag R2 --steps 12000 --nudge 6001 --resync-on-desync
```

`--resync STEP`: copy 1 holds at that step and saves a resync (`Engine.resyncSave`); then every copy, copy 1 included, loads it (`Engine.resyncLoad`) and runs on. `--resync-on-desync`: the first desync any engine reports makes copy 1 save a resync at its current step, and every copy loads it: the mod's recovery loop. With `--nudge`, copies are compared only after the resync. The rig keeps its run state across the load; `summary.txt` gives the save time and size and each copy's load time.

## Own cities (SOS-38)

```
python rig\rig.py city --tag F1 --no-invade --follow --world-resync 3000
```

`--follow`: every copy runs in own-city mode (`Engine.ownCity`). Copy 1's city is the one the world reads: its reports go to a file (`reportsout`), and the other copies feed that file to their engines (`reportsfrom 0`) and run cities of their own, which differ from copy 1's at once. The per-step log holds the world hash (`GameHash.worldHash`) instead of the city's, so the copies are compared on the world only. Needs the AI on the host; refuses `--replay`, `--resync` and `--resync-on-desync`, which would reload the followers' cities. `--world-resync STEP`: copy 1 holds at that step and saves (`Engine.worldResyncSave`); every copy, copy 1 included, then loads only the world from it (`Engine.worldResyncLoad`) and runs on. `summary.txt` gives the save time and size, each copy's load time, and each copy's `reportsReceived`.

`--follower-saves A,B` (with `--follow`): each follower, copies 2 to N, loads its own save instead of `--save`, as a player who joins with a city of their own. A follower's world is its save's until the world resync, so `--world-resync` must come before the first state hash (1 to 29): the resync then gives every copy the host's world, calendar, temporary boosts and world events before anything is compared or the host's first AI log entry applies (step 30). The runner compares the copies' logs from the step after the world resync. Example, the SOS-32 session's setup: `--copies 3 --save Mogus-72-19f86f1e048-47002c-0-121c.save --follow --world-resync 15 --follower-saves Mogus-40-19f4c29b177-47002c-0-c19.save,Mogus-20-19f32767f80-47002c-0-76d.save`.

`--pace-lead N` (default 60; 0 turns it off): copy 1, the host, runs at most N steps past the slowest copy, as the mod will (mod spec §4: the slowest PC caps the speed). It learns each copy's step from that copy's state hashes (`Engine.peerStep`), which come every 30 steps, so N is at least 30. Between hashes the host counts the slowest copy on at its measured pace (at most N more steps), so it slows down evenly instead of reaching the cap and standing still until the next hash; on the laptop rig this cut copy 1's standstills of 30+ frames from 109 to 26 (runs LT1, LT2). A follower runs at most one report window (under 60 steps) past the host, so every copy stays within about N + 60 steps of the slowest. `summary.txt` gives how many frames and seconds the host was held by the others. Any run with more than one copy is paced.

`--govern`: every copy starts at copy 1's speed and follows the agreed speed, and the host lowers it once a second when the slowest copy holds it back for over a tenth of that second, to the fastest speed that copy's steps per second allow; after 10 s unheld it tries a quarter faster (SOS-42). The summary lists each copy's agreed speed changes and speed lines, and the longest hold.

## Playing a copy (SOS-32)

```
python rig\rig.py field --tag P1 --play --copies 3
```

`--play`: copy 1 opens in a normal window with your own settings, as the host, at 1x. Give orders and change the speed as you like; every order and speed change goes into the host's log and the other copies (small, at 25x so they never lag) replay it at the same steps. The step length follows the agreed speed (1/60 s at 1x, 1/16 s above). At the first difference every copy is held, your window included, and each copy writes the engine's desync report. Close your window to end the run: the others are held and compared up to the last step yours reached. Don't retreat or leave the battle (that loads another save). In a city run the city and the world map are commands (SOS-34, SOS-35); `rig\play-v01.bat` starts the V0.1 session ([docs/release/V0.1.md](../docs/release/V0.1.md)). Without `--play`, copy 1's player answers world-battle prompts and the simulation's yes/no questions by itself (`answerprompts`).

`--unanswered`: copy 1 leaves world-battle prompts unanswered (it still says yes to the yes/no questions), so each one's time runs out and every copy gives the default answer at the same step (SOS-45). The events log each prompt as it opens and closes: `open WBattleSiege, armies held 1`, then `closed after 2400 steps; while it was open held armies moved 0 of 2, other armies moved 3 of 44`. The time limit is 30 s at the agreed speed: 2,400 steps at the host copy's 5x.

`--play` with `--remote K` (SOS-15): copy K runs on the other PC in a 1600×900 window with sound, to watch there; add `--govern` so it runs at the agreed speed. `--watch K[,K]`: those copies' players only watch (`Engine.watchOnly`): orders and commands clicked there are dropped, and the summary counts them. Without it, a peer's clicks go to the host and apply on every copy.

```
python rigig.py field --tag V1 --play --copies 2 --remote 2 --watch 2 --govern --trace-draws 600
```

`--commanders "ARMY0 ARMY1"` (field, SOS-15): copy 1 sets who commands each army at the battle's start: a PC (0 the host, K-1 copy K), `ai`, or `A/B`, the army's divisions with men split in half between PCs A and B (the artillery A's). The summary counts the entries skipped as not commanded by their source.

```
python rigig.py field --tag V3 --play --copies 2 --remote 2 --govern --commanders "0 1"
python rigig.py field --tag V4 --play --copies 2 --remote 2 --govern --commanders "0/1 ai"
```

`--warm K[,K]`: those copies play the autoloaded game normally for 30 game seconds before the session, so their session game is not the first one their process ran (the competing mod's first-game desync; SOS-32 found none).

`--trace-draws N` adds the call site of every simulation draw for the last N steps, dumped around a desync; it slows the copies by roughly half.

## Map battles (SOS-32)

```
python rig\rig.py city --tag MB10 --save Mogus-72-19f86f1e048-47002c-0-121c.save --no-invade --engage 900 --cmds "wbesiegenear 0 stronger"
```

`--engage N` (default -1: off): copy 1 answers a field battle's prompt with Engage instead of auto-resolve, so the battle is fought on the battle map on every copy. It clicks Start as soon as the battle deploys, Retreat N steps later (0: fights on to the end), and Close on the victory or defeat panel; every copy then loads the game saved before the battle and goes on at the same step. A siege waits instead of being resolved, so the region's defenders come out and fight in the field.

`--restart K` (with `--engage`): copy 1 restarts the battle once, K steps after Start, through the battle panel's restart; every copy loads the battle as generated and goes on at the same step, and copy 1 clicks Start again (SOS-41).

`--in-battle "STEPS ACTION..."` (with `--engage`): copy 1 does the player action once, STEPS steps after the battle's Start, for example `--in-battle "100 warmyname 0 Renamed"`. A world command whose step falls in a map battle is skipped on every copy, and the copy that gave it shows a notice, which the rig closes (`notice` event); the summary's `commandsSkipped` counts it (SOS-41).

`--stall-profile`: during a step over 100 ms every copy records its stack every 50 ms instead of once (`<tag>-stalls.txt`), so the samples show where a long step's time goes, such as the Engage step (SOS-41).

A field battle needs an enemy that stands: a weaker NPC army withdraws without a prompt, and a chase after a marching army stalls (the game's intercept re-paths only once the target has moved a tenth of the path away). `wbesiegenear 0 stronger` besieges the nearest settlement of a faction with at least twice the army's power, whose defenders then attack it. `wwarnear`, `winterceptnear` (with `stronger` and `still`) and the `warmystate` probe are there for other setups.

## The scenario suite (SOS-31)

`rig\suite.py` runs every scenario in one command and prints pass or fail per scenario, with the first step that differs:

```
python rig\suite.py --short     before each pull request, about 5 minutes
python rig\suite.py             the full suite, about 55 minutes
python rig\suite.py --record    record the expected hashes again (a change meant to alter the simulation)
python rig\suite.py --dry-run   what would run, and the state of each recording; starts no game
```

| Scenario | Full | Short | Checked by |
|---|---|---|---|
| `field` | the field battle, 3 copies at 5, 7 and 10x, 36,000 steps | 3,000 steps | copies, expected hashes |
| `assault` | Mogus-72 with its invasion, 3 copies, 36,000 steps | 6,000 steps | copies, expected hashes |
| `ownworld` | Mogus-72 own cities (M4): followers, world resync at 3,000, `m3.txt` actions, 108,000 steps | 6,000 steps, resync at 1,500, no actions | copies only |
| `mapbattle` | Mogus-72 besieged, Engage, the battle on the battle map (MB12), 2 copies, 36,000 steps | not in it | copies, expected hashes |

Every scenario passes only when its copies are identical at every step and no engine reports a desync. A recorded one must also match its expected hashes. `--record` runs it with copy 1 as the host and keeps two things in `rig\golden\<scenario>.txt` and `<scenario>.log.gz`: copy 1's hash every 300 steps and at the end, and the host's AI log, which holds the general's changes and the player's actions at their steps. A field recording also keeps its generated battle in your saves folder as `SOSDET-suite-<scenario>.save`, since a generated battle's bytes differ between runs. A later run replays that log on every copy (`--replay`, with `--battle-save` for the field battle), so its steps must give the same hashes. A recording names the scenario's arguments, its save's checksum, the start hash and the engine's AI log and hash formats; when one differs, the scenario says `RECORD` and to record again, rather than failing. The own-city world can't be replayed, since a `--follow` run needs a host, so only its copies are compared.

`--battle-save NAME` (field) starts from that battle save instead of generating one; in a `--replay` run, `--cmds` is ignored, since the log already holds the actions.

The recordings in `rig\golden` were made on the main dev PC (2026-10-25). A field recording needs its battle save, which stays in that PC's saves folder; on another PC the field scenario says `RECORD` until it is recorded there. Checked on 2026-10-25: the short suite passes in about 6 minutes (field and assault match their recordings, the own-city world stays identical through a world resync at step 1,500), and a probe that changed every step's seed made the field scenario fail at step 300, naming the expected and the actual hash. The full suite passes in 49 minutes: field, assault and map battle each match 121 expected hashes, and the own-city world stays identical for 108,000 steps through a world resync at step 3,000.

## The timing rule

```
python rig\rig.py field --tag E1 --evals
python rig\rig.py city --tag E2 --save Mogus-72-19f86f1e048-47002c-0-121c.save --evals
```

Not run for SOS-28: the owner deferred the timing evals until timing becomes an issue (2026-10-22).

`--evals` runs one copy at a time, 10 minutes of game time at speed 1 each (requirements §4):
- **host:** the general on its own thread, writing the AI log;
- **peer:** replaying that log;
- **vanilla,** only with `--vanilla`: no engine session, the game's own stepping and threads. By default the baseline is SOS-23's vanilla measurement on the same saves.

All runs start from the same save. The engine times each frame's game update (`Engine.lastFrameUpdateNanos`, the SOS-23 measure: update time per 1/60 s of game time), and a stall sampler records the simulation thread's stack for every step over 100 ms. Without a session (the vanilla baseline) there is no frame timing; the baseline comes from SOS-23's figures.

`evals.txt` shows the percentiles, the steps over 100 ms per 10 minutes, the steps over 1 s, the most frequent stall causes, whether host and peer stayed identical, and the verdict. The rule passes when p99.9 is at most 20 ms, no step takes over 1 s, and no more steps go over 100 ms than in vanilla.

In the field battle the first frame after the battle save loads is left out (owner, 2026-10-21): it holds the game's own load stall, the guards' patrol search, in vanilla too.

## The commands

`Rig.java` lists the commands a copy understands (`begin`, `loadseed`, `holdworld`, `load`, `generate`, `loadbattle`, `seed`, `invade`, `run`, `hash`, `end`). A copy reads them from `%APPDATA%\songsofsyx\sosdet-rig\command.txt` in its own data folder and answers in `events.jsonl` there.

## Hashes

- **Battle:** every soldier in a division (index, id, position, speed, injuries, division).
- **City:** every person and animal (index, type, id, position, speed; people also injuries).

Rooms, stock and stats are not hashed; a difference there shows up in the entities a few steps later. The engine's own state hash and desync report come in SOS-29.

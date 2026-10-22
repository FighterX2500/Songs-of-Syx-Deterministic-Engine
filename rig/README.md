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

## Playing a copy (SOS-32)

```
python rig\rig.py field --tag P1 --play --copies 3
```

`--play`: copy 1 opens in a normal window with your own settings, as the host, at 1x. Give orders and change the speed as you like; every order and speed change goes into the host's log and the other copies (small, at 25x so they never lag) replay it at the same steps. The step length follows the agreed speed (1/60 s at 1x, 1/16 s above). At the first difference every copy is held, your window included, and each copy writes the engine's desync report. Close your window to end the run: the others are held and compared up to the last step yours reached. Don't retreat or leave the battle (that loads another save); in a city run, don't take decisions that change the city (building, noble replacement): city commands come with SOS-34.

`--trace-draws N` adds the call site of every simulation draw for the last N steps, dumped around a desync; it slows the copies by roughly half.

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

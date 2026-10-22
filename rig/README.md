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

## The commands

`Rig.java` lists the commands a copy understands (`begin`, `loadseed`, `holdworld`, `load`, `generate`, `loadbattle`, `seed`, `invade`, `run`, `hash`, `end`). A copy reads them from `%APPDATA%\songsofsyx\sosdet-rig\command.txt` in its own data folder and answers in `events.jsonl` there.

## Hashes

- **Battle:** every soldier in a division (index, id, position, speed, injuries, division).
- **City:** every person and animal (index, type, id, position, speed; people also injuries).

Rooms, stock and stats are not hashed; a difference there shows up in the entities a few steps later. The engine's own state hash and desync report come in SOS-29.

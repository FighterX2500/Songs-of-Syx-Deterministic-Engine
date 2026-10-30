# Plan 0016: battles against NPC armies on their own clock (local battles)

Card: [SOS-93](https://fighterx2500.atlassian.net/browse/SOS-93). Mod spec 0.33 §4 and §8 (SOSGSGMP PR #72); research and obstacles O1 to O17 in SOSGSGMP `docs/plan/sos-93-npc-battles-own-clock.md`. Watching is [SOS-109](https://fighterx2500.atlassian.net/browse/SOS-109).

## What it does

In own-city mode, a human who fights an NPC army in real time runs the battle on their own PC only (a **local battle**):
- every PC's world keeps stepping in lockstep, at 1× while any local battle runs;
- the human is frozen in every world, as a viewer is (plan 0013);
- the result reaches the world as one command, at a step the host stamps, exactly once.

Battles between humans keep plan 0006's shape: every PC runs them and the world is held.

## Shape

Each step on the battle PC runs:
- **the city part:** k battle ticks, the battle's own resources and `BattleState.update` (k = 1 at the battle's 1×, 0 paused, 3 at 3×);
- **the world part:** as on every other PC, whatever the battle view says.

Every other PC runs its own city and the world as usual.

**The life of a local battle.** `LocalBattle` is a record per human, world state: the same on every PC, saved with the world and hashed.
1. **Engage at step N**, in the world part on every PC: every PC records the battle (the human, its PC, the armies, the freeze step F, the next multiple of 30 after N). The armies wait in place from N.
2. **At F's world part**, on the battle PC only: it generates the battle, on a battle seed, with the world stream's draws put back. Every PC freezes the human from F: no reports are taken or read, and transactions are held.
3. **From F+1**, the battle PC runs battle ticks in the city part.
4. **The end:** the battle's result goes out as a command. At its step, every PC applies `Manual.afterBattle`'s world effects from the armies recorded at Engage. The battle PC loads its own city back (a view's thaw: the world kept, the city as at F). The human thaws at the next multiple of 30.

## Steps

**A. Spike** (throwaway first, kept where it holds up): steps 1 to 3 at k = 1, on the rig.
- An engine switch, `Engine.localBattles(true)`, and the rig's `wlocal` action.
- The record and the freeze. The armies held through `armyHeld`.
- The step loop's gates:
  - O1, patch 10: the world part runs whatever the battle view;
  - O2, `Hooks.battleView` false in the world part.
- O4: the battle's Start keeps `GAME.updateI` on the battle PC.
- O15: the general runs in the step on the battle PC, uncaptured.
- **Pass:**
  - the two copies' world hashes stay identical from Engage through 3,000 steps of battle;
  - the other copy's city runs on;
  - the battle runs (Start, fighting) on the battle PC only.

**B. Speed and pause** (O3, O5, O16).
- k from the battle's own speed; `TIME` and `TEMP_EVENTS` once per world step.
- The world speed capped at 1× through the host's log while any local battle runs.
- A slow battle PC lowers k, not the world.

**C. The end and the result** (O8 to O10).
- The result command (kind, survivors per division as counts, captives), refused a second time.
- `Manual.afterBattle` in the world part on every PC; the battle PC's return as a thaw.
- Retreat and removal.

**D. Rules** (O11, O17, spec §8):
- prompts for the human wait until the end, then the 30-second rule;
- notifications without popups;
- campaign saves wait;
- no save before an NPC battle;
- a battle between humans waits;
- views end.

**E. The mod** (SOSGSGMP): the top bar's 1× note, the busy player, the local speed and pause, prompts, saves.

## Tests

- **Unit:** `LocalBattle` save/load round trip; F's arithmetic; the result command's encoding; a second result refused; k from a speed.
- **Rig:**
  - **B93A:** one PC, two copies, one local battle;
  - **B93B:** the same on two PCs;
  - won, lost and retreat;
  - a second local battle at once;
  - the human removed mid-battle.
- **Two-PC GUI run:** one player fights while the other builds.

## Results

### Step A (2026-10-30)

Rig, Mogus-72 with 2 humans, two copies at 5×: `SOSDET_LOCAL_BATTLES=1 rig.py city --humans 2 --copies 2 --speeds 5,5 --engage 1500 --cmds "wbesiegenear 0 stronger"`.

| Run | What happened |
|---|---|
| L93A | Engage at 11,852, Start at 11,910. World desync at 11,940: the calendar only. `TIME` is a battle resource that updates in the city part, and it stood still on the battle PC while the battle deployed (O3). |
| L93B | With the world's clock (`TIME`, `INTER`, `TEMP_EVENTS`) run every step on the battle PC: identical to step 15,000. But the battle never left deployment: the rig's Start went through the command log, which a local battle no longer uses. |
| L93C | With the rig's Start, Retreat and Close done locally: the battle fought from 11,910 to the retreat at 13,410, **no desync on either copy**. The retreat then crashed the battle PC: vanilla's exit loads the whole game saved at the freeze, which drops the session's city reports (step C). |

Found:
- **Generating takes 12.7 s in one step** on the battle PC (Mogus-72). The other PC waits, and the agreed speed falls to 1×. Step B should generate between steps, as a view's load does.
- **The battle's commands** (Start, Retreat, Close) are captured only for battles every PC runs. A local battle applies them on its own PC, outside the step. Watching (SOS-109) will need them in a local queue, at a step.


### Step C (2026-10-30)

The same rig line, with the battle's exit sending the result. The rig retreats 1,500 steps after Start.

| Run | What happened |
|---|---|
| L93D | The return works: no crash, identical through the result. World desync 34 steps later, at the thaw report: the thaw didn't set the other PC's report schedule as a view's does. |
| L93E | The first difference moved to the step after the accept: human 0's army was gone on the battle PC only. |
| L93F, L93G | With a temporary log: the other PC never ran the result, as command 7964 was its sender's PC's only. It is now a world command. |
| L93H, L93I | Identical for 180 steps more, then a draw trace found `EventFactionPeace.up` drawing on the battle PC only: its `secondWhenWarEnds` is in no save, so a full load zeroes it and a world load keeps it. It now goes through `GameWorldLoad.unsaved()`. |
| L93J | **All copies identical to step 16,000**, through the fight (1,500 steps), the result, the return, the thaw and 2,500 steps after it. |

Found:
- **The peace timers were a bug for views too:** a viewer's full load zeroed them while the other PCs kept theirs. `unsaved()` fixes both.
- **World commands on the battle PC:** `worldCommandSkipped` dropped every world command while the battle view was open. A local battle's PC now applies them, as the world runs on. No rig run has exercised this yet: it needs the other human giving a world order during the battle.
- **Every survivor count was 0 in these runs, and that is vanilla's rule:** a retreat loses 0.4 men per enemy soldier, and 4,000 enemies wipe out 498 men.

### Step B (2026-10-30)

- **The cap:** the host logs 1× from the first Engage and the speed it wants once the last human has thawed. A speed the host sets meanwhile waits for that.
- **The battle's speed:** on the battle PC the game's speed is the battle's own. `SETT`, `Armies` and `BattleThreads` run k rounds in a world step; a pause holds them as deployment does, and the world's clock runs on.
- **A slow PC lowers k:** every 5 seconds k goes down while the PC can't hold the world's rate, and back up once one more round fits.

| Run | What happened |
|---|---|
| L93K | Speed 2. The rig's per-step line differed from Start on, with the engine's hashes agreeing: nearly every resource counts as a battle's in vanilla, so the extra round ran the scripts, events and factions too. Now only the three above run again. |
| L93L | Speed 2. **All copies identical to step 15,000.** Game seconds per step: 0.0625 before, 0.0167 in the battle, 0.0625 after. |

Not shown by a run yet:
- that the battle ran two rounds a step: nothing measures the rounds;
- the pause;
- k going down on a slow PC (unit test only).

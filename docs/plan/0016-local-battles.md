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

(Filled in as the steps run.)

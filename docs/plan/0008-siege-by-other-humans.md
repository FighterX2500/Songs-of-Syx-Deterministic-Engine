# Plan 0008: a siege by a human other than slot 0 never arrives (SOS-71)

Card: [SOS-71](https://fighterx2500.atlassian.net/browse/SOS-71) · Date: 2026-10-26 · Status: diagnosed (owner approved the diagnosis, 2026-10-26): **not a bug**, see section 6 · Builds on plan 0004 (several humans) and SOS-45 (the world-battle prompt holds only its armies)

Game paths are relative to `<game>/info/SongsOfSyx-sources/`. 
## 1. What the runs show

- **H66P2** (Mogus-72, `--humans 3`, `2: wbesiegenear 0 stronger`):
  - Copy 2's army 0 (power 75) was ordered to besiege region 76 of faction 13, at peace (power 6,567).
  - It walked toward the siege tile 83,169, then stood still at 75,174 with 9 path tiles left, in the Besieging state with a valid path, to step 13,030.
  - No question opened and no prompt was logged on any copy. Only that one command applied, and the copies stayed identical.
- **H70A** (slot 0, `wbesiegenear 0 stronger`): an army of power 3,042 against region 189 of faction 17. The question opened at step 10,961, and the siege battle came at 11,834.

So the two runs differ in the army, the target and the distance, not only in the slot. **They don't prove yet that the slot is the cause.**

## 2. What the source says

- **The question itself is already per human.** In `world/entity/army/WArmyState.java:364`, vanilla asks "declare war and besiege?" only for `FACTIONS.player()`. SOSDET's `99-world-battles.patch` asks for any `Player`, with that human current, through `StepPrompts.yesNo`. `PSiege` is patched for every human as well (`15-humans.patch`).
- **The army never got that far.** The question opens only when `path().move` returns false, at the siege tile, and the army stopped 9 tiles before it.
- **An army that stands still with a valid path** means its state update is skipped, or `move` returns true without moving it. Vanilla's `WPATHING.movementSpeed` is never 0, so the candidates are SOSDET's:
  1. **`Hooks.armyHeld`** (`WArmy.update`, SOS-45): an army listed in `Session.worldPromptArmies` skips its state update. The list is set by `worldPromptOpened` and cleared on close, timeout or load. If a world-battle prompt opened for this army, for example a field battle against something near 75,174, and was never closed for copy 2's human, the army waits for good.
  2. **`WPath`'s patched `movement = 0`** (SOS-46 PT3): only the world part resets the move remainder. A world-part miss could keep the army from moving.
  3. **Something specific to this army or target:** a power-75 army, or a path that crosses a region at peace.

## 3. Steps

1. **Diagnose (rig only, nothing in `src/` changes).**
   - Extend the rig's `warmystate` probe with:
     - the step;
     - `Hooks.armyHeld` for the army;
     - the open world prompt (kind, asked human, held armies);
     - the path's move remainder.
   - **H71A:** H66P2 again, probing every 300 steps.
   - **H71B (control):** slot 0's army 0 ordered at the same region 76 (`wbesiege 0 76`).
   - If H71B also stops, the card is about the target or army, not the slot, and I come back to you before fixing.
2. **Fix at the cause,** under the determinism rules: the same on every PC, with no per-PC value in the step. If it's the held list, the likely fix is in `Hooks`' prompt bookkeeping, with a unit test. If it's a patch line, the fix goes in its patch, with a `docs/hooks.md` entry.
3. **Verify:**
   - the card's run, `--humans 3 --engage 900 --engage-copy 2 --cmds "2: wbesiegenear 0 stronger"`: the question on copy 2 only, then the siege battle, with the copies identical;
   - H70A again, so slot 0 is unchanged;
   - `gradlew.bat build`.

Short smoke runs only, each with a monitor every minute.

## 4. Mod side

Nothing expected: the mod carries the commands and prompts unchanged. If the fix changes a command's bytes, the mod's protocol doesn't change, because the engine's version travels in the session.

## 5. For the owner

1. Diagnose first, as in step 1, before any fix: OK?
2. If the control run shows slot 0 also stops at region 76, should SOS-71 be renamed to that bug, or a new card made?

## 6. Result (2026-10-26)

**H71A2** reran H66P2's command (`--humans 3 --engage 900 --engage-copy 2`, `2: wbesiegenear 0 stronger`) to 20,000 steps, with the extended `warmystate` probe every 10 s. **PASS:** all copies were identical to step 20,000.

- **The army never stopped.** It is 68 men on slow ground: it was 10 tiles out at step 12,521, 2 tiles out at 17,005, and at the siege tile by 18,164. H66P2 ended at 13,000, before the army arrived. Its last two probes showed the same tile only because they came after that run had stopped.
- **Slot 1's siege then ran just like slot 0's in H70A,** with every prompt on copy 2 only:
  - step 18,164: the question, answered yes;
  - step 18,169: the siege prompt, Wait;
  - step 18,421: Engage;
  - step 18,426: the siege battle, loaded on every copy as human 1's;
  - step 19,357: Retreat;
  - step 19,360: the defeat, accepted.
- **The probe never found the army held** (`armyHeld` false) on the way. At the end it was held by the open result prompt asking human 1, as intended.

The card's acceptance criteria are met with no engine change. The H71B control was not run, because it could no longer tell anything apart. The extended probe stays in the rig: it now notes the step, whether a world-battle prompt holds the army, the open prompt and its human, the pixel position, the next path tile and the men.

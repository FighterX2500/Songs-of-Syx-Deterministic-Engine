# Handoff: next session starts here (updated 2026-10-23, during SOS-35)

## 1. Read these first, before doing anything

1. `C:\ClaudeFolder\SOS\CLAUDE.md` and `C:\ClaudeFolder\SOS\AGENTS.md`: the mod (SOSGSGMP), its workflow and coding rules.
2. `C:\ClaudeFolder\SOSDET\CLAUDE.md`, `C:\ClaudeFolder\SOSDET\AGENTS.md` and `C:\ClaudeFolder\SOSDET\.claude\rules\determinism.md`: the engine's own rules. The work happens here.
3. The memory index, loaded automatically, especially `engine-split`, `competitor-syx-multiplayer`, `tooling-quirks` and `jira-github-setup`.
4. `docs/plan/0002-city-and-world-lockstep.md`: the overall plan and the owner's decisions. Stage order: **A → B → D → C → E**.
5. `docs/plan/0003-room-planning.md`: where SOS-34 stands. Plan 0002, decision 6: the road to SOSDET V0.1.
6. ADRs `docs/decisions/0002-player-commands.md` (accepted) and `docs/decisions/0003-room-planning.md` (accepted).

## 2. Where things stand

**Repositories:**
- **Engine:** `C:\ClaudeFolder\SOSDET`, GitHub `FighterX2500/Songs-of-Syx-Deterministic-Engine`, renamed from SOSDET. The local folder and the launcher mod name are still SOSDET; `gh` needs `--repo FighterX2500/Songs-of-Syx-Deterministic-Engine`.
- **Mod:** `C:\ClaudeFolder\SOS`, GitHub `FighterX2500/SOSGSGMP`. The mod is still in planning; nothing changed there this session.

**Merged into engine main:**
- PR #9 SOS-32 M1: orders and speed in the host's log.
- PR #10 SOS-32 M2a: city groundwork, static-draw check, requirements 0.2.
- PR #11: the repo rename.
- PR #12 SOS-34 part 1: 106 city commands, ADR 0002.

**Merged PR #13, branch `SOS-34-room-planning`:** SOS-34 part 2. It holds:
- ADR 0003 and plan 0003;
- milestone 1, area-room planning (6007 `roomBuild`);
- milestone 2, refurnish (6008 `roomRefurnish`);
- the V0.1 essentials:
  - the game's own saves lift the room plan;
  - number boxes keep the typed text;
  - the copier, mass copier and saved blueprints are blocked in a session.

Smoke run Q34H: 3 copies identical to step 14,000, with 8 commands applied on every copy.

**Current branch `SOS-35-world-lockstep`** (PR #14, main merged in): stage D of plan 0002, world lockstep, and the V0.1 package.
- **Done here:**
  - the wave brief (`docs/plan/notes/world-wave-brief.md`);
  - the world-battle prompt as commands 7900 to 7902 (`cmd.WorldBattles`, patch `99-world-battles`): the world holds while a prompt is open, the city goes on, and Engage is refused with a notice;
  - the rig no longer holds the world (`--hold-world` keeps the old runs);
  - the rig's host player answers every prompt (`answerprompts`).
- **Two agents, in worktrees under `C:\ClaudeFolder\SOSDET-wt\`:**
  - `fixes` (branch `SOS-35-world-fixes`, patch `98-world-fixes`): the W3 world determinism items;
  - `cmds` (branch `SOS-35-world-cmds`, patch `96-cmd-world`): the world, diplomacy and event commands, with rig script `rig/cmds/world.txt`.
  - Both merged, with their `docs/hooks.md` and `docs/commands.md` rows.
- **Smoke run D35B passed** (plan 0002 stage D, item 6), with the world now in the state hash.
- **PR #14** (SOS-35) is open; SOS-34 is closed (PR #13 merged).
- **V0.1 is packaged:** release notes `docs/release/V0.1.md`, `rig\play-v01.bat` starts the owner's session, `gradlew.bat releaseZip` makes `build
elease\SOSDET-0.1.0.zip`.
- **Next:** the owner's 30-minute session; then fix what it finds, then stage E.

**After V0.1:**
- stage E, the city↔world link;
- room-planning milestone 3 (copier, mass copier, prints);
- generated battles (Engage);
- the resync-with-open-prompt gap (plan 0002, stage D).

**Jira:**
- SOS-32 is open (M2b and M3 left).
- SOS-34 is done (PR #13 merged).
- SOS-35, stage D and the V0.1 package, is in review (PR #14).
- Cards close only after the owner says the PR is merged.

## 3. How to work here

- **Planning first:** propose larger work and wait for the owner's approval. One card at a time.
- **PRs:** base every PR on main, never stacked. Report at the end as AGENTS.md "Reporting" describes.
- **Rig** (`rig/rig.py`, from `C:\ClaudeFolder\SOSDET`):
  - **Automatic test** with a scripted player:
    ```
    python rig\rig.py city --tag X --save Mogus-72-19f86f1e048-47002c-0-121c.save --copies 3 --speeds 5,7,7 --steps N --cmds @rig/cmds/<area>.txt
    ```
    The test copies run off-screen. Give it enough steps: about 300 per scripted action plus 6,000.
  - **Owner play session:** `--play` (copy 1 in a normal window at 1x; close the window to end). The owner plays the city and the world; room planning and refurnish work. Without `--play`, copy 1's player answers world-battle prompts by itself.
  - **Splits:** `--trace-draws 300` names the first differing draw. The desync report lists divisions and entities per step.
  - **Never rebuild while a rig run is live:** it swaps the jars under the running copies.
- **Patches:** `python tools/mkpatch.py <patch> <edits.py>`. FILES lists every game file the patch covers; EDITS holds only the new edits. Each game file belongs to one patch. Every hook goes in `docs/hooks.md`; every command goes in `docs/commands.md`.
- **Agents for big ports:** one worktree per agent (`git worktree add ... -b <branch> <base>`), one patch and one area class each. The brief that worked is in `docs/plan/notes/agent-wave-brief.md`. Tell agents to run `./gradlew.bat` directly; `cmd /c` from Git Bash hangs (see `tooling-quirks`). Check on long-running agents: a child-less `cmd.exe` process means a hang.
- **Secrets:** keys live in `C:\ClaudeFolder\SOS\.env`, used as `GH_TOKEN=$(grep '^GITHUB_APIKEY' .env | sed ...)` in Git Bash. Never print them. Jira: `python C:\Users\FighterX2500\.claude\projects\C--ClaudeFolder-SOS\jira.py comment|move|get ...`.

## 4. Owner decisions this session (also in memory and plan 0002)

- **The engine:** full city simulation and the world map in lockstep. Copying the competing mod (MIT, with attribution in THIRD-PARTY-NOTICES.md) is allowed.
- **Stage order:** world lockstep (D) comes before the city play sessions (C), so the owner can trade.
- **World-battle prompts:** the world holds for everyone while one is open.
- **Command hooks:** in the declaring method, or at the UI call site when the class is unsafe at the menu or the method is hot.
- **Factions:** several human factions are the mod's work. The engine keeps the save's one player faction identical on every PC.
- **Room planning:** plan locally, hidden from the step; Build is one command, using the stash approach.
- **Refurnish:** the room keeps working until Build, and cancelling does nothing.
- **Auto-compact:** set to 300k tokens in `~/.claude/settings.json`.

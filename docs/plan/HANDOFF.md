# Handoff: next session starts here (updated 2026-10-24, SOS-32)

## 1. Read these first, before doing anything

1. `C:\ClaudeFolder\SOS\CLAUDE.md` and `C:\ClaudeFolder\SOS\AGENTS.md`: the mod (SOSGSGMP), its workflow and coding rules.
2. `C:\ClaudeFolder\SOSDET\CLAUDE.md`, `C:\ClaudeFolder\SOSDET\AGENTS.md` and `C:\ClaudeFolder\SOSDET\.claude\rules\determinism.md`: the engine's own rules. The work happens here.
3. The memory index, loaded automatically, especially `engine-split`, `competitor-syx-multiplayer`, `tooling-quirks` and `jira-github-setup`.
4. `docs/plan/0002-city-and-world-lockstep.md`: the overall plan and the owner's decisions. Stage order: **A → B → D → C → E**.
5. `docs/plan/0003-room-planning.md`: where SOS-34 stands. Plan 0002, decision 6: the road to SOSDET V0.1.
6. ADRs `docs/decisions/0002-player-commands.md` (accepted) and `docs/decisions/0003-room-planning.md` (accepted).

## 2. Where things stand

**Repositories:**
- **Engine:** `C:\ClaudeFolder\SOSDET`, GitHub `FighterX2500/Songs-of-Syx-Deterministic-Engine` (public). The local folder and the launcher mod name are still SOSDET; `gh` needs `--repo FighterX2500/Songs-of-Syx-Deterministic-Engine`.
- **Mod:** `C:\ClaudeFolder\SOS`, GitHub `FighterX2500/SOSGSGMP`, still in planning. PR #20 there dropped CI for a local check before merge (spec §14 to §16, `[CI]` tags became `[AT]`).

**Done and merged into engine main:**
- SOS-32 M1 (orders and speed in the host's log) and M2a (city groundwork).
- SOS-34: 106 city commands and room planning (ADR 0002, ADR 0003).
- SOS-35: world lockstep (plan 0002 stage D) and the V0.1 package (`docs/release/V0.1.md`).
- SOS-36 and SOS-37: fixes from the owner's V0.1 sessions (prompt deadlock, copier and blueprints, the minimap's draws, path finding on path threads).
- SOS-38 (PR #19, 2026-10-24): stage E, the city↔world link, in full: the step parts, the city report, the transactions, own-city mode, the world resync from a world-only save, raiders in the world hash, and pacing by the slowest PC. Results: `docs/plan/notes/stage-e-smoke-tests.md`.

**Now, SOS-32:** the owner's 30-minute session, which unifies the remaining checks (owner, 2026-10-24):
- camera and clicks (render and UI state; the C1 split when the owner moved the camera is the biggest risk);
- real-time battles started from the world map (never run in a multi-copy session; Engage is refused in a session today; own-city mode doesn't support city battles);
- own-city followers from older saves (Mogus-40 and Mogus-20) under the host's Mogus-72 world;
- draw tracing on (mean step 3.9 to 6.5 ms).

First three read-only audits (camera and clicks, battles from the map, older-save followers), then a fix plan for the owner's approval, then the session.

**Still open elsewhere:** SOS-31 (the scenario suite as one local command); the V01B rig split at step 154,365; the resync-with-open-prompt gap (plan 0002, stage D); room-planning milestone 3; generated battles (Engage); several human factions (the mod's work).

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
  - **Own cities:** `--follow` runs every copy in own-city mode with copy 1 as the host; `--world-resync STEP` reloads the followers' worlds from the host's; `--pace-lead N` (default 60) caps how far the host runs past the slowest copy; `--remote K` runs copy K on the laptop over Radmin (`SOSDET_RIG_REMOTE`). See `rig/README.md`.
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
- **No CI (owner, 2026-10-24):** a local check before merge instead; the scenario suite runs as one local command (SOS-31).
- **Pacing (owner, 2026-10-24):** the slowest PC caps the pace; the lead is easy to change and belongs to the mod's speed voting later.
- **Smoke tests:** test less; pace and catch-up checks are short smoke runs, not desync tests.

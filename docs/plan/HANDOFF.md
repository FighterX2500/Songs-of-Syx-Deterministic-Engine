# Engine notes (updated 2026-11-01, main at PR #73)

The project's handoff, for both repositories, is `C:\ClaudeFolder\SOS\docs\plan\HANDOFF.md`. Read that first. This file keeps the engine's own notes: its reading list, where it stands, how to work here and the owner's decisions about it.

## 1. Read these first, for engine work

1. `C:\ClaudeFolder\SOSDET\CLAUDE.md`, `C:\ClaudeFolder\SOSDET\AGENTS.md` and `C:\ClaudeFolder\SOSDET\.claude\rules\determinism.md`: the engine's own rules. The work happens here.
2. The memory index, loaded automatically, especially `engine-split`, `competitor-syx-multiplayer`, `tooling-quirks` and `jira-github-setup`.
3. `docs/plan/0002-city-and-world-lockstep.md`: the overall plan and the owner's decisions. Stage order: **A → B → D → C → E**.
4. `docs/plan/0003-room-planning.md`: where SOS-34 stands. Plan 0002, decision 6: the road to SOSDET V0.1.
5. ADRs `docs/decisions/0002-player-commands.md` (accepted) and `docs/decisions/0003-room-planning.md` (accepted).
6. ADR `docs/decisions/0004-several-humans.md` (accepted) and `docs/plan/0004-several-humans.md`: several humans on one world, its milestones, playtests and known gaps.
7. `docs/plan/0005-add-human.md` (the world built around several humans at setup) and `docs/plan/0006-own-city-battles.md` (battles with own cities, its milestones).
8. The plans of the mod's features, each recording the engine's half: `0007-remove-human.md` (SOS-66), `0008-siege-by-other-humans.md` (SOS-71), `0009-human-deals.md` (SOS-72), `0010-human-market.md` (SOS-77), `0011-regions-vassals.md` (SOS-73), `0012-war-between-humans.md` (SOS-74, SOS-83), `0013-city-viewing.md` (SOS-82), `0014-sieges.md` (SOS-92), `0016-local-battles.md` (SOS-93), `0017-watching-local-battles.md` (SOS-109) and `0018-several-humans-in-a-battle.md` (SOS-118).

## 2. Where things stand

**Repositories:**
- **Engine:** `C:\ClaudeFolder\SOSDET`, GitHub `FighterX2500/Songs-of-Syx-Deterministic-Engine` (public). The local folder and the launcher mod name are still SOSDET; `gh` needs `--repo FighterX2500/Songs-of-Syx-Deterministic-Engine`.
- **Mod:** `C:\ClaudeFolder\SOS`, GitHub `FighterX2500/SOSGSGMP`. It plays over direct IP and Steam: setup (map, factions, locations), the game over the network, the desync window, battles over the network, a playtest log, campaign saves and resume, disconnects, diplomacy, trade and war between players, battles between players and city viewing. Its own handoff lists what is next.

**Done and merged into engine main:**
- SOS-32 M1 (orders and speed in the host's log) and M2a (city groundwork).
- SOS-34: 106 city commands and room planning (ADR 0002, ADR 0003).
- SOS-35: world lockstep (plan 0002 stage D) and the V0.1 package (`docs/release/V0.1.md`).
- SOS-36 and SOS-37: fixes from the owner's V0.1 sessions (prompt deadlock, copier and blueprints, the minimap's draws, path finding on path threads).
- SOS-38 (PR #19, 2026-10-24): stage E, the city↔world link, in full: the step parts, the city report, the transactions, own-city mode, the world resync from a world-only save, raiders in the world hash, and pacing by the slowest PC. Results: `docs/plan/notes/stage-e-smoke-tests.md`.
- SOS-45 (PR #29): world-battle prompts hold only the battle's armies and answer themselves after 30 s.
- SOS-31 (PR #30, 2026-10-25): the scenario suite as one local command, `rig\suite.py` (short about 6 minutes, full about 49). See `rig/README.md`.
- SOS-46 (PR #31, 2026-10-25): several human factions on one world map ([ADR 0004](../decisions/0004-several-humans.md), [plan 0004](0004-several-humans.md)): up to 16 humans, each a `Player` in its own slot; the world part runs each human's world half as the current human; NPC relations, event timers and raids are kept per human. PT6 ran an hour (270,000 steps) identical on every copy. Gap still in plan 0004's milestone notes: `DIP.playerWarSecond` is still one value. Messages reach their own human's PC since SOS-89.
- SOS-52 (PR #33): the world resync with several humans; every PC resets its armies' move remainders at the load.
- SOS-53 (PR #34): the desync hold: every PC holds at the host's step, and the host chooses a resync or saving and leaving.
- SOS-55 (PR #35): `addHuman`, built as `Engine.buildWorld`: the world built around several humans at setup (plan 0005).
- SOS-56 (PR #36): `Engine.worldHash()` and `Engine.ownCityAt()`. The short suite was recorded again (commit 95288d8).
- SOS-61 (PR #37): own-city battles, plan 0006 M1: the battle PC generates the battle and sends its battle save, and every PC loads it at the same step. Engage works in own-city mode.
- SOS-64 (PR #38): a session load in a fresh process; the statics reset waits until a game exists.
- SOS-69 (PR #40): a world resync with several humans keeps the difficulty's boosters. SOS-70 (PR #41): every PC holds before a battle's Engage, for the campaign save.
- SOS-66 (PR #42, plan 0007) and SOS-65 (PR #43): a removed human's realm becomes free regions, its armies disband and its slot retires; `Engine.removeRefusal`.
- SOS-71 (PR #44): a siege by another human arrives; not a bug (plan 0008).
- SOS-72 (PR #45, plan 0009), SOS-73 (PR #51, plan 0011), SOS-77 (PR #50, plan 0010): deals between humans, regions, vassalage, trade at peace, and the market and tariffs between humans. SOS-76 (PR #47): cancelling every treaty at once.
- SOS-74 and SOS-83 (PR #53, plan 0012): war between humans, and battles between two humans; the host stamps the commanders table from the sides, which is plan 0006 M2.
- SOS-75 (PR #46), SOS-78 (PR #49), SOS-79 (PR #48), SOS-81 (PR #52): a capital needs land beyond its square, names and banners on every PC, no quick load in a session, the transfer record.
- SOS-82 (PR #54, plan 0013): viewing another human's city.
- SOS-87 to SOS-90 (PRs #55 to #59): the profile's banner, slider buttons, messages on their own player's PC, lockstep windows of half a second at every speed.
- SOS-92 step A (PR #60, plan 0014): the siege of a human's capital, its wait and the sally. SOS-94 (PR #61): the governor tells a slow PC from a slow link (hash format 8).
- Batch 1 of the 2026-10-29 playtest fixes (PR #62): SOS-97 (a view's held transactions), SOS-98 (the ruler name and the top bar's banner), SOS-99 (world news on every player), SOS-102 (Production Rate).
- SOS-92 steps B to D (PRs #63, #69, #70): the assault on a human's capital, its outcomes, allied attackers and the attacker's terms. SOS-102 (PR #64): the unit tests run again.
- SOS-106 (PR #66, plan 0015): the step profile and the path heap. SOS-93 (PR #67, plan 0016): battles against NPC armies on their human's PC. SOS-110 (PR #68): notices.
- SOS-109 (PR #70, plan 0017): watching a local battle, with SOS-111 to SOS-114. Plan 0006 M3 (PR #70): a battle's resync, `Engine.battleResyncSave` and `battleResyncLoad`.
- SOS-116 to SOS-119 (PR #71, plan 0018): several humans in one battle. SOS-120 (PR #72): setup, and market trade at 6 to 15 tiles. SOS-121 to SOS-125 (PR #73): batch 2 of the playtest fixes.

**Open PRs:** none on 2026-11-01.

**Still open in the engine:**
- Changing host during a session: requirements §3 asks for it, and `Engine.aiHost` and `aiPeer` refuse it after step 0.
- No game build check at runtime: only the build pins the game jar. A different engine build isn't detected at the lobby.
- SOS-32's leftover owner checks (the card is Done), from the owner's 30-minute session: camera and clicks (render and UI state; the C1 split when the owner moved the camera is the biggest risk), own-city followers from older saves (Mogus-40 and Mogus-20) under the host's Mogus-72 world, and draw tracing on (mean step 3.9 to 6.5 ms). Read-only audits first, then a fix plan for the owner's approval, then the session.
- The V01B rig split at step 154,365.
- The resync-with-open-prompt gap (plan 0002, stage D).
- High speeds in a playtest: SOS-90 keeps the lockstep windows at half a second at every speed; whether 5x and above now reach their full rate is still to see.
- The suite's recordings: every one in `rig\golden` is older than the AI log's format 8 (SOS-90) and the hash format 8 (SOS-94), so the suite says `RECORD` until it is recorded again (`python rig\suite.py --record`).

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
  - **Several humans:** `--humans N` (N copies) makes N - 1 NPCs humans and runs copy k as human k in own-city mode; `k: <action>` in `--cmds` is copy k's player's. It works with `--remote K`. Probes for a player: `wstatus`, `wcredits`, `wrelations`, `wwar`, `wevents`, `wsprite`. See `rig/README.md`, "Several humans".
  - **Splits:** `--trace-draws 300` names the first differing draw. The desync report lists divisions and entities per step, and, when the world differs, the world part by part (every army, each human's timers).
  - **Never rebuild while a rig run is live:** it swaps the jars under the running copies.
- **Patches:** `python tools/mkpatch.py <patch> <edits.py>`. FILES lists every game file the patch covers; EDITS holds only the new edits. Each game file belongs to one patch. Every hook goes in `docs/hooks.md`; every command goes in `docs/commands.md`.
- **Agents for big ports:** one worktree per agent (`git worktree add ... -b <branch> <base>`), one patch and one area class each. The brief that worked is in `docs/plan/notes/agent-wave-brief.md`. Tell agents to run `./gradlew.bat` directly; `cmd /c` from Git Bash hangs (see `tooling-quirks`). Check on long-running agents: a child-less `cmd.exe` process means a hang.
- **Secrets:** keys live in `C:\ClaudeFolder\SOS\.env`, used as `GH_TOKEN=$(grep '^GITHUB_APIKEY' .env | sed ...)` in Git Bash. Never print them. Jira: `python C:\Users\FighterX2500\.claude\projects\C--ClaudeFolder-SOS\jira.py comment|move|get ...`.

## 4. Owner decisions (also in memory and plan 0002)

- **The engine:** full city simulation and the world map in lockstep. Copying the competing mod (MIT, with attribution in THIRD-PARTY-NOTICES.md) is allowed.
- **Stage order:** world lockstep (D) comes before the city play sessions (C), so the owner can trade.
- **World-battle prompts:** the world holds for everyone while one is open. *Changed 2026-10-25 (SOS-45):* only the battle's armies wait, and the prompt answers itself after 30 s.
- **Command hooks:** in the declaring method, or at the UI call site when the class is unsafe at the menu or the method is hot.
- **Factions:** several human factions are the mod's work. The engine keeps the save's one player faction identical on every PC. *Superseded by [ADR 0004](../decisions/0004-several-humans.md) (2026-10-25, SOS-46):* the engine holds several humans, each in its own slot.
- **Several humans (2026-10-25):** no war between two humans through an NPC vassal or ally, only by one of them declaring it (spec change in SOSGSGMP). Every PC resets its armies' move remainders when a world resync loads (built in SOS-52).
- **Room planning:** plan locally, hidden from the step; Build is one command, using the stash approach.
- **Refurnish:** the room keeps working until Build, and cancelling does nothing.
- **Auto-compact:** set to 300k tokens in `~/.claude/settings.json`.
- **No CI (owner, 2026-10-24):** a local check before merge instead; the scenario suite runs as one local command (SOS-31).
- **Pacing (owner, 2026-10-24):** the slowest PC caps the pace; the lead is easy to change and belongs to the mod's speed voting later. The mod's lead is 1000 steps since SOS-86; the rig's `--pace-lead` still defaults to 60.
- **Smoke tests:** test less; pace and catch-up checks are short smoke runs, not desync tests.
- **Own-city battles (2026-10-25, plan 0006):** the asked human's PC generates the battle and ships it; every PC loads it at the same step.

# Engine notes (updated 2026-11-23, main at PR #104)

The project's handoff, for both repositories, is `C:\ClaudeFolder\SOS\docs\plan\HANDOFF.md`. Read that first. This file keeps the engine's own notes: what to read, where it stands, what is still open, the owner's decisions and how to work here.

## 1. Read these first

1. `CLAUDE.md`, `AGENTS.md` and `.claude/rules/determinism.md`: the engine's rules.
2. [docs/architecture.md](../architecture.md): how a session runs, the code map, the desync causes, measured costs and pitfalls.
3. [docs/api.md](../api.md): what the mod calls, and the known limits.
4. [docs/requirements.md](../requirements.md) and the ADRs in [docs/decisions/](../decisions/).
5. The memory index, loaded automatically; for engine work `tooling-quirks`, `competitor-syx-multiplayer` and `deferred-lockstep-tests`.

Finished plans, notes and spikes are in [docs/archive/](../archive/): history, not maintained.

## 2. Where things stand

- **Engine:** `C:\ClaudeFolder\SOSDET`, GitHub `FighterX2500/Songs-of-Syx-Deterministic-Engine` (public). The local folder and the launcher mod name are still SOSDET; `gh` needs `--repo FighterX2500/Songs-of-Syx-Deterministic-Engine`.
- **Mod:** `C:\ClaudeFolder\SOS`, GitHub `FighterX2500/SOSGSGMP`. Its own handoff lists what is next.

The phase is the release on the Steam Workshop (owner, 2026-11-01). Main is merged through PR #104 (2026-11-22, batch 14). What the engine does is in the [README](../../README.md), "Status"; what changed when is in `git log --first-parent main`.

**The scenario suite.** The short suite's recordings (`field-short`, `assault-short` in `rig\golden`) are in AI log 8 and hash 9: recorded again on 2026-11-06 (SOS-154), their header moved to hash 9 in batch 15 (plan 0022: the world parts are extra lines, the hashes didn't change). The short suite on 2026-11-25: `field-short` and `ownworld-short` PASS, `assault-short` FAIL from step 300, the same on main: its recording was out of date before batch 15 and needs recording again (an owner's call). The full suite's `field`, `assault` and `mapbattle` recordings are from 2026-10-25, in AI log 4 and hash 6, so those scenarios say `RECORD` and the full suite's result is FAIL until they are recorded again (`python rig\suite.py --record`). The own-city world is checked by its copies only. No scenario has several humans, carving or freezing.

## 3. Still open in the engine

- **Host change:** requirements §3 asks for it; `Engine.aiHost` and `aiPeer` refuse it after step 0.
- **SOS-32's leftover owner checks** (the card is Done), from the owner's 30-minute session: camera and clicks (render and UI state; the C1 split when the owner moved the camera is the biggest risk), own-city followers from older saves (Mogus-40 and Mogus-20) under the host's Mogus-72 world, and draw tracing on (mean step 3.9 to 6.5 ms). Read-only audits first, then a fix plan for the owner's approval, then the session.
- **The V01B rig split** at step 154,365.
- **A world resync with a world-battle prompt open** leaves the reloading PC without that prompt.
- **High speeds in a playtest:** the lockstep windows stay half a second at every speed (SOS-90); whether 5x and above reach their full rate is still to see.
- **The suite:** the full suite's recordings, and scenarios with several humans, carving and freezing (above).
- **SOS-183, step 3:** where the split of the courts' day counters and the peace times began is not found. The playtest's host fought a local battle against raiders, one PC watched and one didn't; the hash now names those parts, so a rig run of that case would show the first split.
- **Mod spec §16, a commander who drops out of a battle with allies:** settled for a local battle (their divisions go to the fighting player, owner 2026-11-19); still open for a battle every PC runs, where a removal ends the battle with no result.
- **Plan 0022's limits (batch 15):** the rings keep seconds only (16,384 applied entries, 8,192 army orders) and the mod reads them a few times a second; `skipped` is per PC; a STOP and an order of the same army in the same step keep only the order. `TransferLog` still repeats a resync's re-applied transfers under new numbers (older than this batch; a follow-up).
- **Reflection with no build-time test:** about 30 reflected members in `sosdet.game.cmd.Rooms` and the 8 field handles of `PlanStores` resolve only at run time. A test that resolves them against the game jar would catch a game change at build time.

From the release audit of 2026-11-01, still open on main (checked 2026-11-23):
- **Owner:** `Pacer.MIN_SPEED` is 0.25 against the mod spec's lowest speed of 0.5x (§4); `PacerTest` follows the code. One of the two must change.
- **Owner:** debug mode and the developer tools stay open in multiplayer (mod spec §4). The engine ignores the developer flag in one simulation check only (`Hooks.developerFlag`).
- **Peer data:** `LocalTape`, `ViewCity`, `AiLog` and `CityReport` size arrays from lengths read off the network, so a bad length crashes the game instead of dropping that PC. Low risk between friends, since the lobby compares builds.
- **A trade round twice:** `TradeManager.sosdetOtherHumans` skips slot 0 rather than the human `FACTIONS.player()` trades as, so once slot 0 is eliminated one human trades twice a round. It is deterministic.
- **Null crash:** `GameInputs.applyGlobalEvent` throws when a data mod removes one of the two storm events (`Events.byKey` gives null for the twin).
- **Plausible, not proven:** `RDDistance`'s per-human slices go stale after a load if a world event reads them before `FACTIONS` updates; `HeldTxn` resolves an army supply by index, so an index reused during a freeze could hit the wrong army; a removed ally's disbanded army stays in a local battle's saved sides (`Side.div` is null-safe, the rest of `afterBattle` wasn't traced); `36-path-heap`'s tie-break wasn't proved equal to vanilla's tree, so single-player paths could differ; SyxSpeed is credited in that patch but not in THIRD-PARTY-NOTICES.md, and whether any code was taken needs confirming.

## 4. Owner decisions

- **The engine:** the full city simulation and the world map run in lockstep. Copying the competing mod (MIT, with attribution in THIRD-PARTY-NOTICES.md) is allowed.
- **Several humans** ([ADR 0004](../decisions/0004-several-humans.md), SOS-46): each human in its own slot. No war between two humans through an NPC vassal or ally, only by one of them declaring it (2026-10-25). Every PC resets its armies' move remainders when a world resync loads (SOS-52).
- **World-battle prompts** (SOS-45, 2026-10-25): only the battle's armies wait, and the prompt answers itself after 30 s.
- **Own-city battles** (2026-10-25): the asked human's PC generates the battle and ships it; every PC loads it at the same step.
- **Command hooks:** in the declaring method, or at the UI call site when the class is unsafe at the menu or the method is hot.
- **Room planning:** plan locally, hidden from the step; Build is one command, using the stash. **Refurnish:** the room keeps working until Build, and cancelling does nothing.
- **Pacing** (2026-10-24): the slowest PC caps the pace, and the lead belongs to the mod's speed voting. The mod's lead is 1000 steps since SOS-86; the rig's `--pace-lead` still defaults to 60.
- **No CI** (2026-10-24): a local check before merge, with the scenario suite as one local command (SOS-31).
- **Smoke tests:** test less; pace and catch-up checks are short smoke runs, not desync tests.
- **Auto-compact:** 300k tokens in `~/.claude/settings.json`.
- Superseded: the world held for everyone while a prompt was open (until SOS-45); the engine kept the save's one player faction (until ADR 0004); stage order D before C (done).

## 5. How to work here

- **Planning first:** propose larger work and wait for the owner's approval. One piece of work at a time; base every PR on main, never stacked. Report as AGENTS.md "Reporting" describes.
- **Rig:** `rig/rig.py` and the suite, with every option, are in [rig/README.md](../../rig/README.md). Never rebuild while a rig run is live: it swaps the jars under the running copies.
- **Patches:** `python tools/mkpatch.py <patch> <edits.py>`. FILES lists every game file the patch covers; EDITS holds only the new edits. Each game file belongs to one patch. Every hook goes in `docs/hooks.md`; every command in `docs/commands.md`.
- **Agents for big ports:** one worktree per agent, one patch and one area class each; the brief that worked is in [docs/archive/notes/agent-wave-brief.md](../archive/notes/agent-wave-brief.md). Tell agents to run `./gradlew.bat` directly: `cmd /c` from Git Bash hangs. A child-less `cmd.exe` under a long-running agent means a hang.
- **Secrets:** keys live in `C:\ClaudeFolder\SOS\.env`, used as `GH_TOKEN=$(grep '^GITHUB_APIKEY' .env | sed ...)` in Git Bash. Never print them.

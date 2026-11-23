# SOSDET

Deterministic simulation engine for Songs of Syx V71: the same save, seed, players' commands and AI log give a bit-identical game on every PC. The city, the world map and field battles all run in lockstep. Its first user is the SoS GSG MP multiplayer mod ([SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP)).

@AGENTS.md

AGENTS.md holds the coding rules; `.claude/rules/determinism.md` holds the determinism rules. This file holds the project workflow. If they ever seem to conflict, stop and ask.

Read [docs/architecture.md](docs/architecture.md) first: how a session runs, the code map, the known desync causes and the pitfalls. The workflow below is the owner's own; contributors from outside can skip its approval steps.

## Source of truth

- `docs/requirements.md`: what the engine must do. Read the relevant section before any work. Never change a rule in it without asking me first; when I approve a change, update it in the same PR.
- The decisions it comes from live in SOSGSGMP: [ADR 0003](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0003-battle-lockstep.md) (lockstep battles, AI general on the host) and [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md) (this split). The spike reports there ([SOS-11](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/archive/spikes/determinism.md), [SOS-23](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/archive/spikes/city-determinism.md)) say what was found and why.
- `docs/decisions/`: the engine's own ADRs, numbered `0001-short-name.md`. Each records context, the decision, the options rejected and why.
- `docs/hooks.md`: every change to the game's own code, with the reason it exists.

## Current phase: release on Steam Workshop (owner, 2026-11-01)

- Main is merged through PR #104 (2026-11-22). What is built: [README](README.md), "Status"; the history: `git log --first-parent main`.
- What is still open, the owner's decisions and how to work here: [docs/plan/HANDOFF.md](docs/plan/HANDOFF.md). The project's handoff, for both repositories, is `C:\ClaudeFolder\SOS\docs\plan\HANDOFF.md`.
- Finished plans, notes and spikes are in [docs/archive/](docs/archive/): history, not maintained.

Spikes for open questions go under `spikes/<name>/`. A live spike's report goes in `docs/plan/spikes/<name>.md` and moves to `docs/archive/spikes/` once the spike is done.

## Working style

- Planning first: for anything larger than a small fix, propose a plan and wait for my approval before editing.
- One piece of work at a time, on its own branch and PR.
- Game runs one at a time on this PC, and one combined smoke run per stage (the owner's direction: speed up, test less). Never build while a rig run is live. Use parallel read-only subagents for audits and reviews; give any agent that edits files its own worktree.
- Before each PR: `gradlew.bat build`, then `python rig\suite.py --short` (about 6 minutes; ask me before running it). A change meant to alter the simulation records the suite again (`--record`) and says so in the PR (SOS-31).
- The timing rule (requirements §4) applies to any change that touches the step.
- Write multi-line scripts with the Write and Edit tools, not shell heredocs.
- Never print the keys in `.env` (SOSGSGMP holds them).
- Report at the end as AGENTS.md "Reporting" describes, plus which requirements sections the work touched.

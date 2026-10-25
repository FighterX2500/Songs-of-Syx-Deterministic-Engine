# SOSDET

Deterministic simulation engine for Songs of Syx V71: the same save, seed, players' commands and AI log give a bit-identical game on every PC. The city, the world map and field battles all run in lockstep. Its first user is the SoS GSG MP multiplayer mod ([SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP)).

@AGENTS.md

AGENTS.md holds the coding rules; `.claude/rules/determinism.md` holds the determinism rules. This file holds the project workflow. If they ever seem to conflict, stop and ask.

Read [docs/architecture.md](docs/architecture.md) first: how a session runs, the code map, the known desync causes and the pitfalls. The workflow below is the owner's own; contributors from outside can skip its Jira and approval steps.

## Source of truth

- `docs/requirements.md`: what the engine must do. Read the relevant section before any work. Never change a rule in it without asking me first; when I approve a change, update it in the same PR.
- The decisions it comes from live in SOSGSGMP: [ADR 0003](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0003-battle-lockstep.md) (lockstep battles, AI general on the host) and [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md) (this split). The spike reports there ([SOS-11](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/plan/spikes/determinism.md), [SOS-23](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/plan/spikes/city-determinism.md)) say what was found and why.
- `docs/decisions/`: the engine's own ADRs, numbered `0001-short-name.md`. Each records context, the decision, the options rejected and why.
- `docs/hooks.md`: every change to the game's own code, with the reason it exists.

## Current phase: V0.1 play sessions (SOS-32)

- Built: the engine core, the AI log, the state hash, resync and battle orders (cards SOS-25 to SOS-32 under the epic SOS-22; SOS-31, the scenario suite, and SOS-32 are still open); the city commands (SOS-34); the world lockstep and the V0.1 package (SOS-35); the V0.1 session fixes (SOS-36, SOS-37); stage E of [plan 0002](docs/plan/0002-city-and-world-lockstep.md), the city↔world link, with own-city mode, the world resync and pacing (SOS-38).
- Now: SOS-32, the owner's 30-minute session: camera and clicks, real-time battles started from the world map, and own-city followers from older saves (Mogus-40, Mogus-20), with draw tracing on. Read-only audits come first.
- Engage (world battles on the battle map) is refused in a session until generated battles are ported.
- Where the last session stopped: [docs/plan/HANDOFF.md](docs/plan/HANDOFF.md).

Spikes for open questions go under `spikes/<name>/` and end with a report in `docs/plan/spikes/<name>.md`.

## Working style

- Planning first: for anything larger than a small fix, propose a plan and wait for my approval before editing.
- One Jira card at a time, on the SOS board (Jira project SOS). The branch is `SOS-N-short-name`, and the PR title starts with the key. Close a card only when I say its PR is merged.
- Game runs one at a time on this PC, and one combined smoke run per stage (the owner's direction: speed up, test less). Never build while a rig run is live. Use parallel read-only subagents for audits and reviews; give any agent that edits files its own worktree.
- Before each PR: `gradlew.bat build`, then `python rig\suite.py --short` (about 6 minutes; ask me before running it). A change meant to alter the simulation records the suite again (`--record`) and says so in the PR (SOS-31).
- The timing rule (requirements §4) applies to any change that touches the step.
- Write multi-line scripts with the Write and Edit tools, not shell heredocs.
- Never print the keys in `.env` (SOSGSGMP holds the Jira and GitHub keys).
- Report at the end as AGENTS.md "Reporting" describes, plus which requirements sections the work touched.

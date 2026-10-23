# SOSDET

Deterministic simulation engine for Songs of Syx V71: the same battle save, seed, orders and AI log give a bit-identical simulation on every PC. Its first user is the SoS GSG MP multiplayer mod ([SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP)), which uses it for lockstep battles.

@AGENTS.md

AGENTS.md holds the coding rules; `.claude/rules/determinism.md` holds the determinism rules. This file holds the project workflow. If they ever seem to conflict, stop and ask.

Read [docs/architecture.md](docs/architecture.md) first: how a session runs, the code map, the known desync causes and the pitfalls. The workflow below is the owner's own; contributors from outside can skip its Jira and approval steps.

## Source of truth

- `docs/requirements.md`: what the engine must do. Read the relevant section before any work. Never change a rule in it without asking me first; when I approve a change, update it in the same PR.
- The decisions it comes from live in SOSGSGMP: [ADR 0003](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0003-battle-lockstep.md) (lockstep battles, AI general on the host) and [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md) (this split). The spike reports there ([SOS-11](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/plan/spikes/determinism.md), [SOS-23](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/plan/spikes/city-determinism.md)) say what was found and why.
- `docs/decisions/`: the engine's own ADRs, numbered `0001-short-name.md`. Each records context, the decision, the options rejected and why.
- `docs/hooks.md`: every change to the game's own code, with the reason it exists.

## Current phase: implementation

Cards, in order, under the epic SOS-22 on the SOS board:

1. SOS-25: spike, how the engine reaches players (patched jar, launcher or local build).
2. SOS-26: the engine build (patch set applied to the game source, compiled with ECJ).
3. SOS-27: engine core (fixed step, random streams, jobs in the step, cosmetic call sites, city fixes).
4. SOS-28: AI general on the host and the AI log.
5. SOS-29: state hash and desync report.
6. SOS-30: save and load at a step (resync).
7. SOS-31: scenario suite in CI.
8. SOS-32: the deferred timing and determinism tests.

The spike patches in SOSGSGMP `spikes/determinism/patches/` show what works. They are never copied as-is: each hook is written again, small and named, with its logic in engine code. Spikes for open questions still go under `spikes/<name>/` here and end with a report in `docs/plan/spikes/<name>.md`.

## Working style

- Planning first: for anything larger than a small fix, propose a plan and wait for my approval before editing.
- One Jira card at a time, on the SOS board (Jira project SOS). The branch is `SOS-N-short-name`, and the PR title starts with the key. Close a card only when I say its PR is merged.
- Game runs one at a time on this PC. Use parallel read-only subagents for audits and reviews; give any agent that edits files its own worktree.
- The timing rule (requirements §4) applies to any change that touches the step.
- Write multi-line scripts with the Write and Edit tools, not shell heredocs.
- Never print the keys in `.env` (SOSGSGMP holds the Jira and GitHub keys).
- Report at the end as AGENTS.md "Reporting" describes, plus which requirements sections the work touched.

# SOSDET requirements

Version 0.2 · 2026-10-23

What the deterministic engine must do. It does not say how; that belongs in `docs/decisions/` and the cards. Changes to a rule here need the owner's approval, and the change goes in the same PR.

The engine exists for the SoS GSG MP multiplayer mod ([SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP), [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md)). Where this file and the mod's spec (`docs/spec.md` there) disagree, stop and ask.

## 1. Purpose

Every PC that starts from the same save and seed (a battle, a city or the campaign world) and applies the same orders, players' commands and AI log at the same steps has a bit-identical simulation state after every step. That holds at any game speed and with any local setting (graphics, resolution, language, camera, sound volume, light cycle).

## 2. Scope

- **Game build:** Songs of Syx V71, build 71.44, pinned (mod spec §3). Later game versions are out of scope.
- **v1:** field battles, as decided in the mod's [ADR 0003](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0003-battle-lockstep.md), including the AI general on the host.
- **City (owner, 2026-10-23; plan 0002):** the whole city simulation runs in lockstep, including players' city commands (SOS-34). Lockstep for siege assaults still waits on the mod's decision SOS-17.
- **World map (owner, 2026-10-23; plan 0002):** the world simulation runs in lockstep on every PC, and the host's save recovers a PC that splits. Everything the world reads from a city, and every change the world makes to a city, crosses as a stamped input applied at the same world step on every PC (plan 0002, stage E).
- **Several human factions (owner, 2026-10-25; [ADR 0004](decisions/0004-several-humans.md), plan 0004, SOS-46):** up to 16 humans share one world, each a `Player` in its own slot, the same on every PC. The world part treats every human as vanilla's single player, one at a time as the step's current human, and reads each human's values from that human's city reports; NPC relations, event timers and raids are kept per human. Each PC runs only its own human's city. A PC's local human never enters the step. An old single-player save loads as one human in slot 0. Replaces "one player faction" (owner, 2026-10-23).
- **Not in the engine:** networking, the lobby and every other multiplayer feature. The engine runs and is tested with no network.

## 3. What the mod can do through the API

- Read the current step, and set the last step the engine may run. The engine never runs past it, and runs nothing while paused.
- Read how far another PC has got (`Engine.peerStep`: the last step its state hashes reached). The host caps its own last step at the slowest PC's step plus a lead the mod chooses (`Engine.pace`), so the slowest PC caps the speed (mod spec §4). With `Engine.followAgreedSpeed`, every PC's game speed follows the agreed speed, and the host's governor lowers that to what the slowest PC holds and raises it back toward the host player's speed, with a line on every PC's top bar (SOS-42). The rig's lead is `--pace-lead`, its governor `--govern`.
- Let a PC's player only watch (`Engine.watchOnly`): orders and commands clicked there are dropped before any log, so they change nothing on any PC (spec 0.12 watch-only viewing; SOS-15).
- Say who commands each battle division and each army's artillery (`Engine.commanders`): a PC or the host's AI general. Set by the host and applied at the step it stamps on every PC; an order or a change of the general for a division its source does not command is skipped on every PC (`Engine.ordersNotCommanded`), each PC's player selects only its own, and the table is in the resync state and the state hash. Which player commands what, and where each army deploys, is the mod's to decide (SOS-15).
- Give an order to apply at a given step. It is applied at exactly that step on every PC, never on arrival.
- Give a player's command (a city or world action, carrying the issuing PC) to apply at a given step. It is applied exactly once at that step on every PC; one that moves goods, soldiers or money carries a transaction ID.
- Set the seed of the simulation random stream.
- Choose host or peer mode. On the host, the AI general runs and the engine hands out its AI log entries; on every PC, the engine takes the entries in and applies each at its step.
- Read the state hash at a step.
- Save the battle at a step and load it at that step (start and resync). The save carries the step number and the AI log entries not yet applied. AI state that vanilla doesn't save (the offence `flanking` value) is saved, or drawn again on the simulation stream after loading ([ADR 0003](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0003-battle-lockstep.md) consequences). Every PC, the host included, loads a resync (owner, 2026-10-22, SOS-30): a game that ran on keeps caches a freshly loaded one rebuilds.
- Switch a PC from peer to host mid-battle: after a host change, the new host starts its own general (ADR 0003).
- With several humans (SOS-46): say which human slot is this PC's (`Engine.localHuman`) and which is each PC's (`Engine.pcHuman`), so each PC's commands act for its own human and its city's reports feed that human. `Engine.asHuman` reads the game as another human between steps only (for the rig's probes). Making a human is still setup through the adapter (`GameHumans.add`, the rig's `makehuman`); the `addHuman` call of [ADR 0004](decisions/0004-several-humans.md) is not built yet.
- Every data format crossing the API (orders, AI log entries, engine save data) carries a format version.

## 4. Targets

On the reference PC (Intel Core i5-11600K, 16 GB RAM; mod spec §13) unless a line says otherwise. The SOS-11 and SOS-23 numbers were measured on the dev PC; a run on the reference PC (or one known to be slower) is part of SOS-32.

| Target | Requirement |
| --- | --- |
| Determinism, one PC | Several game copies at different speeds stay identical for 36,000 steps (10 minutes) in the 2,000 v 2,000 field battle; also in the Mogus-72 city assault (see §6) |
| Determinism, two PCs | A 2,000 v 2,000 battle stays identical for 30 minutes on two PCs (mod spec §13), with different CPUs ([ADR 0003](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0003-battle-lockstep.md), "still to prove") |
| Step time | p99.9 at most 20 ms; no step over 1 s; steps over 100 ms no more often than vanilla in the same run, per 10 minutes of game time; for the field battle and the city assault (owner, 2026-10-21). Measured as in SOS-23: one copy running alone, 10 minutes at speed 1. The vanilla load stall at the first step after a battle save loads (about 1.1–1.2 s, `Patrol.find`) is left out (owner, 2026-10-21; SOS-32) |
| Stall evidence | Each step over 100 ms can be explained: what it was doing (the SOS-23 rig used a stall sampler) |
| Desync detection | The hash is taken often enough that the mod can detect any desync within 2 s (mod spec §13) |
| Determinism, city | Several game copies stay identical through a 30-minute session in the Mogus-72 city with the owner giving city commands (plan 0002, stage C) |
| Determinism, world | Several game copies running the world and a city stay identical for 30 minutes with the AI host (plan 0002, stage D) |
| Desync report | Every desync can produce a report: the step, which part of the state differs, recent orders, commands and AI log |
| Resync | Saving and loading at a step leaves room for the mod to recover within 10 s (mod spec §13). Not met yet with several humans: the world resync refuses several humans, and a report miss throws (SOS-46; an open question for the owner) |
| AI log delay | Entries are applied 30 steps (0.5 s) after the host's general writes them, inside the 1 s order latency (mod spec §13) |

## 5. Verification

- A scenario suite (save, seed, order log, AI log, expected hashes every N steps) replayed in several copies at different speeds; it runs as one local command, `python rig\suite.py` (SOS-31, [rig/README.md](../rig/README.md)). It has no scenario with several humans yet.

## 6. Open questions

- [x] **City assault as a target.** Settled 2026-10-23: the whole city runs in lockstep (plan 0002), so the Mogus-72 city, invasion included, is a determinism and timing target now. Lockstep for siege assaults between players still waits on SOS-17.
- [x] **Test saves.** Settled in SOS-31: the suite's saves stay in each PC's own saves folder, a field recording's battle save too; the recordings name each save's checksum ([rig/README.md](../rig/README.md)).

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
- **One player faction:** the engine keeps the save's player faction identical on every PC, and a PC's local player never enters the step. Several human factions in one world are the mod's work (owner, 2026-10-23).
- **Not in the engine:** networking, the lobby and every other multiplayer feature. The engine runs and is tested with no network.

## 3. What the mod can do through the API

- Read the current step, and set the last step the engine may run. The engine never runs past it, and runs nothing while paused.
- Give an order to apply at a given step. It is applied at exactly that step on every PC, never on arrival.
- Give a player's command (a city or world action, carrying the issuing PC) to apply at a given step. It is applied exactly once at that step on every PC; one that moves goods, soldiers or money carries a transaction ID.
- Set the seed of the simulation random stream.
- Choose host or peer mode. On the host, the AI general runs and the engine hands out its AI log entries; on every PC, the engine takes the entries in and applies each at its step.
- Read the state hash at a step.
- Save the battle at a step and load it at that step (start and resync). The save carries the step number and the AI log entries not yet applied. AI state that vanilla doesn't save (the offence `flanking` value) is saved, or drawn again on the simulation stream after loading ([ADR 0003](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0003-battle-lockstep.md) consequences). Every PC, the host included, loads a resync (owner, 2026-10-22, SOS-30): a game that ran on keeps caches a freshly loaded one rebuilds.
- Switch a PC from peer to host mid-battle: after a host change, the new host starts its own general (ADR 0003).
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
| Resync | Saving and loading at a step leaves room for the mod to recover within 10 s (mod spec §13) |
| AI log delay | Entries are applied 30 steps (0.5 s) after the host's general writes them, inside the 1 s order latency (mod spec §13) |

## 5. Verification

- A scenario suite (save, seed, order log, AI log, expected hashes every N steps) replayed in several copies at different speeds; it runs in CI (card SOS-31).
- Until then, the spike rig in SOSGSGMP (`spikes/determinism/matrix.sh`).

## 6. Open questions

- [x] **City assault as a target.** Settled 2026-10-23: the whole city runs in lockstep (plan 0002), so the Mogus-72 city, invasion included, is a determinism and timing target now. Lockstep for siege assaults between players still waits on SOS-17.
- [ ] **Test saves.** Where the scenario suite's saves live, since game files are never committed (SOS-31).

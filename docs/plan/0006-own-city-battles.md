# Plan 0006: battles with own cities (SOS-61)

Status: M1 merged (SOS-61, PR #37); M2 and M3 open.

Mod spec §8 (Military) and §4 (battles pause the world; every player watches). The engine half of the mod's SOS-62.

## Decision (owner, 2026-10-25)

The asked human's PC generates the battle and ships it; every PC loads it at the same step. This replaces "every PC generates the battle in the same step" (mod ADR 0003 amendment, spec §8) for own-city mode, where each PC's own city leaves different state behind (division slots, formations, its human's boosts). The other choice, shipping that PC's whole game so every PC generates, was rejected: a larger save to send and the battle's spec to rebuild after the load, about twice the work. Co-op battles stay as they are.

## How a battle runs

1. **Engage at step N** (world part, every PC). The decision command applies as before. In own-city mode every PC first notes the battle: its human (the one the prompt asks), and the world stream's draw count. Then `spec.engage()` runs on its own stream (`battleSeed(N)`): every PC saves its own city as `__beforeBattle` and generates a battle from its own state. Only the battle PC's is kept. After it, the world stream is reseeded and its draw count put back, so the rest of step N's world part runs alike on every PC.
2. **Hold for the battle.** No step runs past N until the battle PC's battle is loaded. The battle PC's engine hands out its `__battle` save (`Engine.battleOut()`). The mod sends it, and every PC, the battle PC too, loads it with `Engine.battleIn(bytes)`, the way Restart reloads a battle (SOS-41): every PC then runs a freshly loaded battle.
3. **Battle mode** (from that load until the battle ends):
   - the city part's stream is the step's seed on every PC, not this PC's own;
   - no city reports are taken or read, and the world part waits for none: the world stands still (spec §4), and its changes are undone at the end anyway;
   - `FACTIONS.player()` is the battle's human on every PC, so its boosts are the battle PC's live ones;
   - city commands apply on every PC (the battle is everyone's game now);
   - the hash covers the battle: divisions, soldiers, projectiles, entities, the clock;
   - Restart is off.
4. **Return.** Retreat or Close ends the battle as in co-op: every PC loads its own `__beforeBattle`, so its own city comes back as it was at N. The session goes on at that step. Every PC clears the reports and the feeds as at a session's start: each takes its load report and waits for every other's. The world's draw count goes back to N's. The result prompt opens for the battle's human; Accept is a world command, as after an auto-resolve.

## API for the mod

- `Engine.battle()`: null, or the battle running: its step N, its human and PC, and whether the battle save is still awaited.
- `Engine.battleOut()`: on the battle PC, once, the battle save's bytes; null otherwise.
- `Engine.battleIn(bytes)`: every PC, between steps at N, loads the battle.

## Milestones

- **M1 (this card):** a human's army against an NPC army, two copies in the rig (`--humans 2`), then two PCs. The battle is hashed, ends by Retreat or Close, every PC returns to its own city, Accept applies, and the world stays identical for a few thousand steps after. A desync in the battle stops the game with a report; no resync yet.
- **M2:** the commanders table set from the sides (each human commands their own divisions, the host's AI the NPCs, others watch); Retreat only from the battle's human's PC.
- **M3:** resync during a battle.
- **Later:** human against human (the other human's boosts, retreat for either side, captives for the winner).

## Known gaps in M1

- Transactions the world queued for a city in step N itself, before Engage, are dropped by the return load (the save holds the city before its step N tail).
- Anyone can retreat the battle, and anyone can order any division (no commanders table yet).

## M1 results (2026-10-25)

Mogus-72, two humans, `--engage 900`, copy 1's army besieges a region whose army comes out to fight.

| Run | Copies | Battle | Result |
|---|---|---|---|
| OB1 | 2 on this PC, 12,000 steps | Engage at 11,829; the battle PC's save (2.6 MB) loaded on both at 11,860 | identical to 12,000 (stopped in the battle) |
| OB2 | 2 on this PC, 18,000 steps | Engage 11,831, loaded at 11,862, Start, Retreat at 12,793, back in each city at 12,824, defeat accepted | identical at every step, 600 engine hashes agree |
| OB3 | this PC and the laptop (`--remote 2`), 18,000 steps | Engage, loaded at 11,844 on both PCs, Retreat at 12,779, back at 12,810 | identical at every step, 600 engine hashes agree |

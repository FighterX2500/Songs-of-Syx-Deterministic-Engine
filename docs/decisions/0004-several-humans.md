# 0004: Each human faction is a Player in its own slot, the same on every PC

Status: Accepted (owner, 2026-10-25) · Card: [SOS-46](https://fighterx2500.atlassian.net/browse/SOS-46) · Builds on: [plan 0004](../plan/0004-several-humans.md), the [spike report](../plan/spikes/several-humans.md), SOSGSGMP [ADR 0005](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0005-world-lockstep-and-city-world-link.md) (amendment of 2026-10-25)

## Context

- **What the multiplayer game needs.** 2 to 16 humans share one world. Every PC runs the whole world in lockstep, and each PC runs only its own human's city. The world part must hold every human the same way on every PC and treat each one as vanilla treats its single player (SP1, SP2).
- **What V71 has.** `FACTIONS` holds 64 slots: a `Player` in slot 0 and 63 `FactionNPC`s. The save writes the slots in order with no type tags. The world asks "is this the player?" about 150 times with `== FACTIONS.player()`, and casts the other factions to `FactionNPC` 54 times.
- **City and UI code** (about 620 references in `settlement/` and `view/`) reads `FACTIONS.player()` as the faction that owns this PC's capitol.
- **The spike** (2026-10-25) turned 2 NPCs of Mogus-72 into `Player`s in their own slots:
  - the game saved and loaded them to the same world hash, with a byte-identical `FACTIONS` section;
  - a `Player` assumes it is the only one in four places: built only at game creation, the texture cache, game-wide registrations under fixed keys, and boosters that read their own instance;
  - the first frame then fails on the expected `(FactionNPC)` casts.

## Decision

**One `Player` per human, in its own slot, the same on every PC.**
- Each human's faction is a `Player` object, in the same slot on every PC. NPCs keep their slots.
- **Spares.** `FACTIONS`'s constructor builds a fixed number of spare `Player`s, the same on every PC and in every game (a `Player` composes sprites, so it can only be built while the game is created, and the texture cache must include them).
  - A human slot takes a spare and its index; the NPC that was there leaves the game.
  - Turning an NPC or a free region into a human is setup, before the session (`addHuman`), never inside a step.
- **One registration.** Only the first `Player` registers game-wide values, locks and boosters. Each value and booster reads the `Player` it is asked about, and in the world part that means that human's city report.
- **`FACTIONS.active()`** holds every human.
- **Save.** The save names the human slots, with a format version, in a section of the "before" list that loads ahead of `FACTIONS`. Its load puts a spare into each named slot, and each human slot is saved in `Player`'s layout. An old single-player save loads as one human in slot 0.
- **`FACTIONS.player()`** gets one hook:
  - outside the world part (this PC's city part, the UI, local view) it returns this PC's human;
  - inside the world part it returns the step's *current human*. Code about one human runs once per human in faction order, with the current human set to each in turn. Elsewhere in the world part the current human is *none*, and `player()` throws with the call site.
- **"Is this the player?"** in code that loops over factions becomes `isHuman(f)`. Casts to `FactionNPC` get `instanceof` guards or human branches (milestone 2).

## Options rejected

- **Fixed human slots 0 to 15, NPCs moved up.** It moves the NPCs, so every faction-indexed array in an old save would need remapping. It still needs several `Player`s, so it saves none of the work above.
- **Humans as `FactionNPC`s with AI off, on every PC.** This PC's city would have no faction of its own: settlement code reads `FACTIONS.player()`'s capitol and realm. The humans would also run NPC code (court, stockpile, NPC bonuses).
- **Puppets only for the other humans** (the mod's old sketch). This PC's human is the `Player` and the others are NPCs, which differs per PC and desyncs at every `== player()` branch.
- **One index permutation per PC** (this PC's human always in slot 0). Every loop over faction indices would run in a different order on each PC, so the draws would differ.
- **Building the humans' `Player`s when they join.** A running game can't compose their sprites.

## Consequences

- **Patched files** (milestone 1): `FACTIONS` (spares, `active()`, the current human), `BoostCompound` with `PLevels`, `PTech` and `PTitles` (each booster reads the human asked), `PCredits` and `BValue` (the values read the human asked). Registering once needs no patch to `Player`'s parts: the engine (`GameHumans`) sets the game-wide registries aside by reflection while it builds each spare, as the spike did, so `PBonusSetting` stays unpatched. The save section is the engine's own special saver. `FACTIONS` is engine-owned by patch 12, `PCredits` and `BValue` by 14, `PTech` by 96, `PTitles` by 98; `BoostCompound` and `PLevels` are in the new patch 15.
- **The spare count is part of the texture cache.** Changing it rebuilds the cache once on every PC. It sets the most humans a game can have.
- **Per-human state** that vanilla keeps once (opinion, `DipWarPlayer`, events' timers, raids) becomes per human in milestones 3 and 4, through the current human.
- **`PlayerColors.saver`** is static, so every human saves the same colours until it gets a per-slot saver.
- **Local view** (fog of war, minimap, overlays) stays this PC's human's, outside the step (G8).

## Amendments (2026-10-25, after SOS-46 was built)

- **`player()` doesn't throw yet.** In the world part, outside a human's own work, `FACTIONS.player()` gives the first human, not *none*. The rule that it throws waits until no single-player site is left ([plan 0004](../plan/0004-several-humans.md), M1). Until then such a site reads slot 0 without an error.
- **`Player.java` is patched**, for other reasons than registering once: the city and world halves of its update (patch 12, SOS-38), and in SOS-46 the reads of each human's report and the audit fixes (patches 12 and 14; see `docs/hooks.md`). Registering once still needs no patch: `PBonusSetting` stays unpatched.
- **`addHuman` isn't built.** A human is made only through `GameHumans.add(slot)` (the rig's `makehuman`): an active NPC with a capitol, humans in slot order, then a save and load. A free region can't become a human yet. *Status:* built as `Engine.buildWorld` (SOS-55, PR #35, [plan 0005](../plan/0005-add-human.md)): the world built around every human's location at setup.

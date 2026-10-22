# 0002: Players' commands go through the host's log, one entry kind with a registry of commands

Status: Proposed (SOS-34) · Card: [SOS-34](https://fighterx2500.atlassian.net/browse/SOS-34) · Builds on: [plan 0002](../plan/0002-city-and-world-lockstep.md) (stage B, owner's decisions of 2026-10-23), requirements §2–§3

## Context

The whole city now runs in lockstep (requirements 0.2). Every player action that changes the city must change it at the same step on every PC. SOS-32 already does this for battle orders and speed changes: a hook captures the action, the host stamps it at step N + 30 in its log, peers send theirs to the host, and every PC applies the entry at its step through one applier (`GameAi`).

The competing mod (timjrobinson/songs-of-syx-multiplayer, MIT) maps 225 player actions to the simulation methods they call; 186 belong to the city. Its mechanism is a `-javaagent` that hooks each method's entry and reaches the target through a reflective walk of the object graph. It sends some payloads with Java serialization, assumes one shared player faction, and runs some actions on the issuer at once and again from the command. Our rules forbid Java serialization and require transaction IDs; the developer's condition rules out a launch-time agent.

Constraints found while surveying (SOS-34 research):
- **Menu-unsafe classes:** seven target classes crash at the main menu if shipped, because the game's script scan initializes every class in a mod jar: `Armies`, `Humanoid`, `Cart`, `EventKiller`, `RoomEmploymentIns`, `Immigration$Immigrator`, `FCredits$CTYPE`.
- **Hot setters:** two of the competitor's hooks are on the simulation's hottest setters (`INT$IntImp.set`, `BOOLEAN$BOOLEANImp.set`).
- **Issuer-only state:** UI state that only the issuer has (furniture rotation and group, placer caches, the profile's wall material and saved blueprints) must travel inside the command.
- **Shared drafts:** render code that rewrites a shared draft (the competitor's deal popup produced 185,792 commands) must never be captured.

## Decision

**One log, one path.**
- A command is a new entry kind in the host's log: `COMMAND`. Its payload starts with the command's id; the rest is that command's encoded arguments.
- Commands are captured, stamped at step N + 30, sent from peers to the host (`Orders`), ordered by sequence number, applied once inside the step at their stamped step, and listed in the desync report exactly as battle orders are.
- A repeated entry changes nothing; a missing one fails, as today.
- The log's and the order stream's format versions go up by one.

**Capture rule.**
- A hooked call becomes a command only when this PC's player made it: in a session with a log, outside the step, not on the general's thread, and not while a command is being applied. The hook asks `Hooks.ordersCapturing()`, as battle orders do.
- The same method runs normally when the simulation calls it, and when the applier calls it to apply a command.
- Draft and preview code is never hooked; only the call that commits the action is.

**Where hooks sit** (owner, 2026-10-23):
- **The declaring method, by default.** Its first line asks the area's command class whether to capture: `if (sosdet.game.cmd.Rooms.upgradeSet(this, level)) return;`.
- **The UI call site** when the declaring class is menu-unsafe or the method is on a hot path: `if (!sosdet.game.cmd.Laws.set(x, v)) x.set(v);`.

**Commands and their codecs** live in `sosdet.game.cmd`, one class per area: rooms, tools, employment, laws, military, nobles and names.
- Each command has a stable numeric id from its area's range, so areas can be written in parallel. Each also has a name for the report, an encoder used by the hook and an applier that decodes and calls the game.
- Ids are never reused. A command whose meaning changes gets a new id.
- Codecs are hand-written on `CmdWriter`/`CmdReader`: ints, doubles, booleans, strings and stable game indices (room instance, entity id, division, race, resource, room blueprint, region, faction, tech, noble office and so on).
- There is no reflective object walk. A singleton target (for example `SETT.ROOMS().GUARD`) is named by the command itself.
- Java serialization is never used.

**Targets that may be gone.** A command whose target no longer exists at its step (a room demolished, a person dead) is skipped. The skip is identical on every PC, since every PC has the same state at that step, and it is counted.

**What the issuer's UI knows travels with the command.** Rotation, size, furniture group, wall material, saved blueprints and similar inputs are arguments. A placer cache keyed on the tick is recomputed by the applier before it acts.

**Feedback only for the issuer.**
- While applying, `Hooks.issuedHere()` says whether this PC issued the command.
- The mod tells the engine its PC number; the host is 0.
- Sounds, camera moves and opening a window happen only then.

**Random numbers.** Commands apply inside the step, so their draws come from the step's reseeded stream, in log order. Unlike the competitor's per-command seed, two commands in one step never get the same seed.

**Exactly once.**
- The log's sequence number makes every command apply once.
- A command that moves goods, soldiers or money carries a transaction ID, the issuing PC and its sequence number, which the ledger of plan 0002 stage E uses across cities.

**Coverage.**
- `docs/commands.md` lists every entry point from the competitor's survey, plus ours, with its game method, hook place, command id and status.
- An action not on the list stays local, and the rig's per-step comparison catches it in play. SOS-34's acceptance requires every listed command to be exercised at least once, by a rig script or a play session.

**One player faction.** A command never reads `FACTIONS.player()` as "the issuer". It carries the issuing PC, and the engine applies it to the save's one player faction (requirements §2). Several human factions are the mod's work.

## Options rejected

- **A `-javaagent` with ASM, as the competitor does.** It needs the game's JVM arguments changed and a restart, which isn't a mod inside the game (developer's condition, ADR 0001). It also hides the hook surface from `docs/hooks.md`.
- **Reflective object paths for targets.** The competitor walks up to 600,000 objects from 13 roots. That is slow, fragile across game versions, and unnecessary when every command names its target.
- **Running an action on the issuer at once and again from the command.** It makes the issuer's state differ for 30 steps. The competitor needed it for a placer whose UI reads the result in the same click; we keep such UI state local and capture only the commit.
- **A separate command log next to the AI log.** It would mean two sequence numbers, two delivery paths and two orderings of what applies at one step. One log keeps one order.
- **Hooking the generic value holders.** `INT$IntImp.set` and `BOOLEAN$BOOLEANImp.set` run millions of times per step. The UI call sites are few and are hooked instead.

## Consequences

- About 150 game files carry a hook, fewer where a UI call site covers many declaring methods (one `ModuleEmployment` site instead of 19 `autoEmploy` overrides). Each is listed in `docs/hooks.md` and `docs/commands.md`.
- A player's action takes effect 30 steps after the click: 0.5 s at 1x. The issuer sees it then, like everyone else.
- The applier must handle an action whose UI preconditions changed between click and step. Each command checks what it needs and skips identically everywhere.
- Plan 0002 stage C (city play sessions) adds a city part to the state hash, so a desync names the subsystem.

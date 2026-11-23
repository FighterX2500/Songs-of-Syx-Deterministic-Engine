# Plan 0007: a removed human's realm becomes free regions (SOS-66)

Card: [SOS-66](https://fighterx2500.atlassian.net/browse/SOS-66) · Date: 2026-10-26 · Status: approved (owner, 2026-10-26), merged (PR #42; `Engine.removeRefusal` in PR #43) · Mod spec 0.17 §11 (Removing a player, Disconnects); the mod's SOS-65 calls it · Builds on [ADR 0004](../decisions/0004-several-humans.md), [plan 0004](0004-several-humans.md), [plan 0006](0006-own-city-battles.md)

Game paths are relative to `<game>/info/SongsOfSyx-sources/`. Nothing here has been run yet.

**The owner's direction (2026-10-26):** a new NPC realm built from the player's realm is too much work. The whole realm becomes neutral regions instead. This replaces the card's "one new NPC faction with a city from the last report". It changes the mod's spec, §11 and its [AT] in §15 (section 4).

## 1. Goal

A player drops, and the host chooses Continue without them. From step S on every PC, that player's human leaves the game the way vanilla destroys a faction:
- every region it held, its capital included, is free, with no owner: vanilla's neutral regions, which anyone may take;
- its armies are disbanded;
- no PC waits for that player's reports or runs anything for them again.

The removal is in the world hash and the world save, so a resync and a resume keep it.

## 2. What the game and the engine have

- **Vanilla destroys factions at runtime.** `FACTIONS.remove(faction)` disbands every army (`armies().disbandAll()`) and frees every region (`RD.clearFaction`, which calls `RD.setFaction(region, null)` on each). `EventFactionCollapse` and the region panel's "abandon" button free regions the same way, inside the world step or from a command. A free region keeps its own region data (population, buildings) and is a target like any other neutral region.
- **A human is a `Player` in its slot** (ADR 0004). Turning an NPC into a human (`GameHumans.add`) works only at setup, with a save and load after it, so a running session can't swap a slot back.
- **Per-human state is kept by a human's place among the human slots.** That covers relations, events and raids (`SOSDET_RELATIONS`, `SOSDET_EVENTS`), and the distance and opinion slices (`Hooks.humanIndex`, `Session.ordinal`). About 30 loops in patches 12, 14 to 16, 96 and 98 run once per human through `Hooks.humanSlots()`.

## 3. Design

**The human slot stays, retired.** The `Player` stays in its slot as a *retired* human with no realm and no army. Every other human keeps its place and its slices. A retired human:
- is left out of `FACTIONS.active()`;
- is skipped by every per-human loop, with one check (`Hooks.retired(slot)`) at each `humanSlots()` site;
- is skipped by report pacing, the report feed, transactions, prompts and the hash's per-human parts;
- is saved as retired: `SOSDET_HUMANS` version 2 adds a flag per slot, and version 1 still loads.

The slot is never used again: spec §11 has no reconnect into a running session, and a resume refuses a removed player.

**The API, on the host: `Engine.removeHuman(int slot)`.** It refuses slot 0, the host. It puts a REMOVE entry in the host's AI log, stamped for the step the host is held at. The dropped player's missing report holds the host there, and no peer runs past the host's confirmed step, so every PC has the entry before it can run that step.

**At step S, at `stepBegin`, before the world part, on every PC:**
1. **A battle with that human in it:**
   - **An own-city battle** ends as Close does: every PC loads its own `__beforeBattle`. No result applies (there is no Accept), so the other side's armies are as they were at Engage.
   - **An open world-battle prompt** for that human is dropped, and so is an Engage still waiting in the log.
2. **The realm:** as `FACTIONS.remove` does for an NPC: every army is disbanded, then every region is freed.
3. **Diplomacy:** the retired `Player`'s stances are set to neutral with everyone, so no one is left at war with it. That human's slices are cleared: NPC opinions of them, their war timer, their current raid, their requests.
4. **Engine state:** that human's reports, feeds and pending transactions are dropped, pacing stops waiting for them, and the PC that ran them is marked gone.

**At a resume without a player** (the mod's "start without them"), the same removal runs before step 0.

## 4. The spec change (SOSGSGMP, to approve)

Spec 0.17 has two places to change. In §11 "Removing a player" and its [AT] in §15 "Disconnects":
- "Continue without them turns the player's city and territory into a neutral NPC realm" becomes "Continue without them frees every region the player held (vanilla's neutral regions) and disbands their armies".
- "In a battle or siege … all armies stay intact" becomes "In a battle or siege, Continue without them ends it for the player's side with no result; the other side's armies stay as they were".

The spec PR goes in SOSGSGMP, with or before SOS-65.

## 5. Rejected

- **One new NPC faction for the realm, with a city from the last report** (this plan's first version): the owner found it too much work for what it gives.
- **Putting a `FactionNPC` back in the human slot.** The NPC that slot once held was dropped at setup, and any object still holding the `Player` would break.
- **Leaving the human out of the human slots.** Every later human's place would shift. Each would read another human's relations, events and raids, and the saved slices would load into the wrong humans.

## 6. Milestones

| | What | Verified by |
|---|---|---|
| M1 | The retired flag (save v2, the hash), `Hooks.retired`, `active()`, the guards at every `humanSlots()` site; the REMOVE entry and `Engine.removeHuman`; armies, regions, diplomacy and slices | Build; unit tests for the save's v1 and v2 load and the entry's codec |
| M2 | Reports, pacing, feeds and transactions for a retired human; the battle and prompt handling; removal at the start | Build |
| M3 | Audit before the smoke (new per-PC state): every `humanSlots()` and `isHuman` site, and what a free region does with a retired human's leftovers | An agent audit, and its fixes |
| M4 | Rig `removehuman <slot>` on copy 1, after copy 2 is stopped; the smokes below, under 5 minutes each | See below |

**Smokes (rig, Mogus-72):**
- **H66A:** `--humans 3`. Copy 2 is stopped and human 2 removed at about step 3,000. Copies 1 and 3 stay identical for 1,800 steps after. Every former region of human 2 has no owner, and none of its armies is left.
- **H66B:** the same, then a world resync and a save and load. The hash is the same, and the removal is kept.
- **H66C:** a battle (plan 0006's OB2, with 3 humans) whose human is removed during it. Every PC is back in its own city, the other side's armies are as at Engage, and the copies stay identical after.
- **One human:** a one-human run gives the same world hash as main, and a save made on main loads.

The suite: ask the owner before `suite.py --short`. Nothing changes without a removal, so the recorded suite should still pass.

## 7. Patches and docs

- Patches 12 (`FACTIONS.active()`), 15, 16, 96 and 98: the guard at each per-human loop, with a row in `docs/hooks.md` for each new site.
- `docs/requirements.md` (removing a human), `docs/architecture.md` (a retired human), and ADR 0004's amendments (the retired slot).

## 8. Risks

- **Per-human sites that index by position** rather than by slot, for example `n = humanSlots().length` for a slice count: a skipped human must still keep its slice. M3's audit lists every site.
- **Objects that still name the human:** old messages, the trade ledger, battle records. They are harmless if they only show it, and the audit checks that none acts on it.
- **`FACTIONS.remove` takes a `FactionNPC`.** The removal calls its two parts itself, `disbandAll` and freeing each region, so no vanilla code needs changing for a `Player`.
- **Size:** about 30 guard sites, plus the battle and prompt handling. One PR, in two or three commits.

## 9. Results (2026-10-26)

Rig, Mogus-72, this PC, three copies at speeds 5, 7 and 8 unless noted:

| Run | What | Result |
|---|---|---|
| H66A | `--remove 2:2400 --steps 6000` | Copy 2 stopped at 2,570, removal at 2,609, slot 1 retired at 2,640 on copies 1 and 3 (0 regions, 0 armies, not active); identical to 6,000, no desync |
| H66B | H66A with `--world-resync 3600` | Retired at 2,640, the world reloaded at 3,600 on copies 1 and 3; identical to 6,000, no desync |
| H66C | Human 1's field battle (`--engage-copy 2`), `--remove 2:b300 --steps 4500` | Battle loaded at 1,365, copy 2 stopped at 1,782, removal at 1,890; the battle ended with no result and every copy was back in its own city at 1,921; retired at 1,922; identical to 4,500, no desync |
| One human | `--copies 2 --steps 3000`, this branch and main | The same end hash on both, `cf35feb0794ab7c5` at step 3,000 |

What the smokes found and fixed:
- **The rig numbered hash peers by copy (1, 2, 3)**, every other rig command from 0 for the host as the engine and the mod do. So `Pacer.drop` dropped the wrong PC, and the host stood still two pace leads past the removed copy's last hash (the first H66A). `hashpeer` now numbers from 0.
- **A world load read the retired human's city report** while loading its stored city divisions, which retirement clears (the first H66B; a campaign save loaded in a fresh process would crash the same way). The `SOSDET_CITY_DIVS` section now leaves a retired human's store out: its armies are gone, and nothing reads the store.

Not covered, or left as gaps:
- **A world resync doesn't carry the retired flags.** Every PC already holds them by then, and a resync waits until a removal pending is retired (`worldResyncSave` refuses meanwhile).
- **The mod must stop relaying a removed player's reports** once it calls `removeHuman`; the engine drops any taken after the cutoff anyway.
- **A battle awaiting the dropped PC's battle save, or an Engage from it still to begin,** is refused: the host returns to the last save instead.
- **A battle's other side** keeping its armies as they were is by reasoning: the result prompt closes unaccepted, and casualties apply only on accept. The rig doesn't count armies.
- **Found in passing, not this card:** a siege by a human other than slot 0 (`2: wbesiegenear 0 stronger`) never arrives. The army stops 9 tiles from the settlement and no "declare war?" question opens, while slot 0's does (H70A). H66C used a field battle instead.
- The recorded suite (`suite.py --short`) was not run; nothing changes without a removal, and the one-human run matches main.

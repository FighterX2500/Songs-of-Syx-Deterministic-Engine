# Using the engine from a mod

For a mod that drives SOSDET. To change the engine itself, read [docs/architecture.md](architecture.md).

SOSGSGMP is the one full user. Its calls are in `sosgsgmp.game.EnginePlay` and `WorldSetupAdapter`. In this repository the test rig is the example: `rig/java/sosdet/rig/Rig.java`.

## No stability promise

SOSDET V1 Beta promises no API stability. Methods, formats and behaviour can change in any release, and there is no API version.

The formats that cross the API carry version numbers, and most refuse another version. Beyond that:
- **Game build:** SOSDET stops the game at start-up on any build but 71.44 (`sosdet.GameBuild`).
- **Engine build:** nothing in the engine compares it between PCs. `Sosdet.version()` gives the release number, which doesn't change with each build. SOSGSGMP hashes the jar and compares the hash in its lobby (`menu.MpBuilds`).

If your mod needs to compare builds between PCs, it has to do that itself.

## Compiling against the jar

There is no published artifact. Build SOSDET ([README](../README.md), "Building"), then compile against its mod jar and the game's jar, for both only at compile time:

```kotlin
dependencies {
    compileOnly(files("<game>/SongsOfSyx.jar"))
    compileOnly(files("<SOSDET>/build/mod/SOSDET/V71/script/SOSDET.jar"))
}
```

- Never shade or copy the jar: it holds game classes. Players install SOSDET as its own mod, enabled ahead of yours.
- Your mod is an ordinary script mod (`script.SCRIPT`).
- The engine is compiled with `--release 21`, and the game's bundled JRE is 21.

## What to call

- **`sosdet.Engine` is the API.** It has about 150 static methods, and their Javadoc is the reference. [Requirements §3](requirements.md#3-what-the-mod-can-do-through-the-api) says what each group is for.
- **Types it hands back:** `HumanStart`, `OwnBattle`, `LocalBattle`, `View`, `Assault`, `BattleOutcome`, `Notice`, `DesyncHold`, `Stats`, `Carved`, `Applied`, `ArmyOrder`, `sosdet.hash.Desync`.
- **`sosdet.Hooks` is for patches.** It is public because the patched game classes call it. Don't call it from a mod. The same goes for `sosdet.Cosmetic` and `sosdet.game`.

Most `Engine` methods throw `IllegalStateException` with no session. `active()`, `locationProblem`, `buildWorld`, `bannerBytes`, `loadBanner`, `titleRaces` and `worldHash` work without one.

## The calls, by topic

**The run.** `step()` is the current step. `runUntil(last)` sets the last step the engine may run, and `runFreely()` lifts it; the engine never runs past it, and runs nothing while paused. On the host, `stepPastPeers()` gives a step for something every PC must do together, such as a campaign save: no PC can have passed it when word of it arrives, at any speed.

**Pace and speed.** `peerStep(pc)` is the last step that PC's state hashes reached. On the host, `pace(pcs, lead)` caps its own last step at the slowest of those PCs' steps plus `lead`, so the slowest PC caps the speed (mod spec §4; the rig's `--pace-lead`). After `followAgreedSpeed()`, every PC's game speed follows the agreed speed: the host's governor lowers it to what the slowest PC holds and raises it back toward the host player's speed, with a line on every PC's top bar (SOS-42; the rig's `--govern`). `capacity()` is the steps a second this PC could run, from its frames that didn't wait for another PC; it goes out with the hashes. The governor lowers the speed for a PC whose capacity is under it, and for the link only when waits take over a third of the time for 5 seconds, one step at a time (SOS-94).

**Orders, commands and the AI log.** `aiHost(startId)` or `aiPeer(startId)` chooses the mode: on the host the AI general runs and the engine hands out its log; every PC applies each entry at its step. Orders and players' commands come from the game's own UI through hooks. The host stamps each with a step, and every PC applies it exactly once at that step, never on arrival; a command that moves goods, soldiers or money carries a transaction ID. `seed(seed)` sets the simulation stream's seed. `watchOnly(true)` makes this PC's player a watcher: orders and commands clicked there are dropped before any log (SOS-15). The host's `commanders(byDivision, artilleryByArmy)` says who commands each battle division and each army's artillery: a PC or the host's AI general. It applies at the step the host stamps; an order or a change of the general from a source that doesn't command that division is skipped on every PC (`ordersNotCommanded()` counts them), each PC's player selects only their own, and the table is in the resync state and the state hash. Several humans in one battle each command their own divisions this way (SOS-118). Which player commands what, and where each army deploys, is the mod's to decide.

**Hashes, holds and resyncs.** `hash(step)` is the state hash at a step.
- With no own cities, `resyncSave` and `resyncLoad` save a battle at a step and load it at that step. The save carries the step and the AI log entries not yet applied; AI state vanilla doesn't save (the offence `flanking` value) is saved, or drawn again on the simulation stream ([ADR 0003](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0003-battle-lockstep.md) in SOSGSGMP). `battleResyncSave` and `battleResyncLoad` do the same for a battle on the battle map. Every PC, the host included, loads a resync: a game that ran on keeps caches a freshly loaded one rebuilds.
- **The desync hold (SOS-53):** when the host's hashes first differ from another PC's, the host logs a hold entry. Every PC, the host included, applies it at the same step and runs nothing past it, whatever the last step allows. `desyncHold()` gives each PC the desync's step, the differing PC and the hold step. The host then chooses a world resync at the hold step (`worldResyncSave` on the host, `worldResyncLoad` on every PC, which ends the hold), or saving and leaving.
- **The world resync** carries every human's ledger and loads every human's world sections (city divisions, relations, events), each PC keeping its own city. It also carries the host's world state that no save holds: the move remainders, the entity pools, the war timer, the courts' day counters, the NPC production boosts and the peace times. Every PC puts that back after its load (SOS-52, SOS-183).

**Battles.** `battleHold(true)` holds every PC before a battle's Engage, and `battleGo()` lets it go on, so the mod can save the campaign first (SOS-70). With `localBattles(true)`, a human's battle against NPC armies runs on that human's PC only, every PC's world running on (SOS-93); `localBattle(slot)` describes it. Another human can watch it (`viewTapeOut`, `viewTapeIn`; SOS-109). A restart by vote: "What to pump", below.

**Humans and setup.**
- `localHuman(slot)` and `pcHuman(pc, slot)` say which human slot is this PC's and which is each PC's, so each PC's commands act for its own human and its city's reports feed that human. `asHuman(slot)` reads the game as another human, between steps only (the rig's probes).
- On the confirmed terrain, `buildWorld` gives each human a capital at its picked location, in slots 0 to N-1 with the lobby's race, name and banner, then NPC countries and routes as vanilla makes them, with vanilla's small NPC states around every human (SOS-55). One PC builds the world and every PC loads its save. `locationProblem` tells every PC's location picker why a location can't be picked: the terrain, or closer than 6 tiles to another human's.
- **Titles (SOSGSGMP spec §5 Titles).** A `HumanStart` can carry the titles its human holds (title indices) and, for each, the races its player's profile earned it with: `titleRaces()` gives this PC's profile's count per title. Whatever builds or adds the human (`buildWorld`, the carve, `addHuman`) gives it exactly those titles; the counts are world state, in section `SOSDET_TITLES`, so a title is as strong on every PC (patch 98, `Hooks.titleRaces`). A human with none set, as in every world from before, gets vanilla's base strength, as before. `heldTitles(slot)` and `heldTitleRaces(slot)` read a human's titles back, for a hot-joiner given the host's.
- The rig makes a human through the adapter (`GameHumans.add`, its `makehuman`). The `addHuman` call [ADR 0004](decisions/0004-several-humans.md) planned was built as `buildWorld`; today's `addHuman` is hot-join's, below.
- **Removing a human (SOS-66):** the host's `removeHuman(slot)` logs a removal that applies at the same step on every PC; `removeRefusal(slot, atStart)` says why it would be refused. A battle that human is in ends with no result, every army of it is disbanded and every region it held is freed, as vanilla destroys a faction. It stays in its slot, retired (`retired(slot)`), so every other human keeps its place, and nothing runs for it again. Until that step no PC waits for its reports past the last one the host has. `removeHumanAtStart(slot)` does the same for a resume without that player.

**Between humans.** `dealPropose`, `dealAnswer`, `dealCancel`, `setTariff` and `declareWar` (SOS-72 to SOS-74, SOS-77) each have a refusal check the mod's window shows first (`dealRefusal`, `dealAcceptRefusal`, `dealCancelRefusal`, `tariffRefusal`, `warRefusal`); each is a command applied at one step on every PC. `transfers(afterSeq, out)` gives every human's money and goods transfers between steps, the same on every PC (SOS-81).

**For the logs (SOS-249).** Between steps, `applied(afterSeq, out)` gives every AI log entry applied or skipped, every kind, the general's and the players', as `Applied` records; `aiKindName(kind)` and `commandName(id)` name the kind and a command's id, and `lastApplied()` is the number to read after next time. `armyOrders(afterSeq, out)` gives every world army's order and creation in the step's world part as `ArmyOrder` records (move, besiege, raid, intercept, stop, disband, teleport, create), the NPCs', the rebels' and the humans' alike; `lastArmyOrder()` is the number to read after. Both are rings of 16,384 and 8,192 entries, and each call returns how many entries after `afterSeq` were already overwritten. Every PC records the same entries, but an `Applied`'s `skipped` may differ between PCs: a stationed player's order (SOS-234), and in own-city mode a city's command, which only its own PC runs. A resync drops the entries past its step, which come again under new numbers (never given twice, so a PC that had run past the step has a gap). Nothing in them reaches the step or the hash.

**City views (SOS-82).** The host's `viewBegin(viewer, owner)` and `viewEnd` start and end one. The owner's city goes out through `viewOut` and comes in through `viewIn`, and view hashes compare the two (`viewHashOut`, `viewHashIn`). A city has any number of viewers: a viewer already there loads the owner's save again through `viewRejoin` as another joins (SOS-151).

**Sieges of a human's capital (SOS-92).** `besiegedSince` and `assaultInDays`: when the siege began and the days to the assault. `sallyOut` and `sallyRefusal`: the defender's sally. `assault()` and `assaultOutcomeTake()`: the assault and its outcome. `assaultTerms`, `assaultTermsRefusal` and `assaultPeopleInCity`: the attacker's terms.

## Carved city-states

A city-state can be carved into a running world as a new 3x3 region (SOS-186, SOSGSGMP `docs/archive/hot-join.md`):

- `carveProblem(x, y)` says why a centre can't be used, or null. Any PC, between steps. A centre must pass vanilla's capital terrain rule; the square and a 1-tile ring must lie on the map and the square inside regions; the centre must be at least 6 tiles from every region's town and every human's capital; no army, caravan or camp may stand on the square; it may not touch a besieged region or a human's capital square, cut a region in two or cut a road off its town; the world needs a free region; and a road must be able to reach the town of each region the square touches.
- `carve(x, y, name)` carves it on the PC that saves the world: the square, its region info and name, a road and path to the town of each region it touches (no road straight to another human: vanilla's path graph joins a region's town to its borders only, so humans 6 to 15 tiles apart keep SOS-120's trade route), and the split rule for the land it is carved in (unowned land becomes a new NPC; a region of an NPC realm of 4 or more that isn't its capital breaks away; a new NPC is neutral and sanctified). The region has no owner yet. `Carved.noSlot` says a new NPC was wanted but all 64 faction slots are in use.
- `uncarve(region)` undoes one, in any order: only the roads and path steps no other carve uses go.
- The undo log is the save section `SOSDET_CARVES`, loaded with the world and in the world hash. Per carve it holds the square's tiles and the region each held before, the road tiles and path steps its links added, the regions it was cut from and linked to, and the NPC the land became.
- After either, every PC loads the world from that PC's save: a world changed in place on one PC never matches one that was loaded (SOS-38, SOS-184).
- **Setup by carving (SOS-187):** `buildWorldForCarving` builds the world around a stand-in capital for the host that vanilla's generator places, with NPC slots 1 to N-1 kept free. Every PC loads it, and every player, the host too, picks a spot on the real map (`carveLinks` names the regions a spot would link to). `carveHumans` then carves each into slot 0 to N-1 in order; a spot an earlier carve made unfit moves at most 3 tiles. The host's capital moves to their city-state, the stand-in becomes a new neutral NPC state, and the host's city is made at their capital (SOS-214). Every PC loads that save before step 0.
- **A gone human's city-state (SOS-188):** `uncarveDue()` names a carved region no human holds any more (its human removed, or eliminated and the city given to a neutral NPC), or -1; it allocates nothing, so a mod can ask every frame. The host then calls `worldEditHold()`: every PC holds at a step a little ahead, as for a desync, but `desyncHold().edit()` is true (no PC differs, nothing to choose). At the hold step the host calls `uncarve(region)` and `worldResyncSave()`; every PC's `worldResyncLoad` ends the hold.
- **A new human (hot-join, SOS-189):** `joinSlot()` names the slot a newcomer would take, the first free NPC slot above the last human's, or -1 when the map has none. `addHuman(human, credits)` carves the newcomer's city-state at its spot (or the nearest that fits, 3 tiles at most) and makes that slot a human with `credits`. Hot-join restarts every PC: each loads its own copy of the same campaign save in its session, calls `addHuman` with the same arguments before step 0, saves and loads that save, then runs. The carve and the new human are the same on every PC (seeded from the world, rig runs CE1, CE2, AH2); a load with no session isn't, so `addHuman` comes after a session's load. The names a carve gives a new NPC state, its capital and its king come from vanilla's race name lists, whose cursors are per-PC state; every such edit first sets them from the seeded stream, past any human's name, so every PC names the new state alike (SOSGSGMP HJ5).

## Frozen humans

A player can leave a running campaign and come back later (SOS-193, SOSGSGMP spec §11 Freezing a player, [plan 0020](archive/0020-freeze.md)):

- `freezeHuman(slot)`, host, between steps: refused as `removeHuman` is (`freezeRefusal` says why, or null). The entry applies at the same step on every PC, as a removal does, and nobody waits for that PC from the call on. The human keeps its slot and its capital; every stance but a trade or a pact becomes neutral (its wars end, its vassals go free); its other regions become free land; its open deals and the siege of its capital end; its armies go home and stand still, as they are.
- While frozen (`frozen(slot)`), every PC reads its city as its last report showed it, also after any load (the report is in the world save, section `SOSDET_FROZEN`). The transactions for its city wait there too. No NPC makes war on it, raids it or gathers on its border; deals, wars and tariffs with it are refused; it neither buys nor sells, and nobody trades with it (SOS-240); its city can't be viewed.
- `removeHuman(slot)` still removes a frozen human for good, at once.
- **Frozen at the start of a resume:** `freezeHumanAtStart(slot)` freezes a missing player's human instead of removing it, so they can rejoin later ([plan 0021](archive/0021-freeze-at-start.md)). Every PC, own-city mode, after the load and before the run, for the same slots, as `removeHumanAtStart`; the slot has no PC, so leave it out of `pcHuman` and the host's `pace`. It freezes in the first world part on every PC, as `freezeHuman` does, and every PC reads its city from the last report the save holds; no PC waits for it. `freezeAtStartRefusal(slot)` says why it would be refused, or null: slot 0, no human's slot, a human removed or frozen already, after step 0, or a save made before this version, which holds no such report (the host then removes the player or waits). Since this version the world save keeps every playing human's last report for this (`SOSDET_FROZEN` format 2).
- **Rejoin:** at a restart like hot-join's, the host calls `rejoinOut(file)` right after the campaign save every PC reloads; every PC, the returning one from its own freeze save, calls `rejoinLoad(file, state)` in its session before step 1, then `thawHuman(slot)`. The returning PC's city goes on where it stopped, and the transactions that waited for it apply there. On the returning PC, `addMissingHumans(hostSlots)` comes first, right after its freeze save's load: the human slots of players who joined since it froze become stand-ins, which the host's world replaces; then it saves and loads its game again, as after `addHuman` (SOS-204). `rejoinLoad` refuses a game whose human slots still differ from the host's.

## Stationed armies

A human's army can be stationed at another human's capital (SOS-233, SOSGSGMP spec §9, ADR 0011 there). Between steps: `stationRefusal(army, host)` says why this PC's player can't station that army there, or null; `station(army, host)` sends the world command (7980). `stationEndRefusal(army)` and `stationEnd(army)`: the host sends it away, or its player calls it back (7981). `stationSupplyRefusal(army)` and `stationSupply(army)`: the host fills its supplies from the host's stockpiles (7982, then the `STATION_SUPPLY` transaction and the city's supply input). `stationGrantRefusal(army)` and `stationGrant(army, granted)`: its player gives the host command of its soldiers in the host's city, or takes it back (7983, `Notice.STATION_GRANT`; batch 13). `stationedCount()`, `stationedArmy(i)`, `stationedOwner(i)`, `stationedHost(i)`, `stationedGranted(i)` read the records. The records are world state, in section `SOSDET_STATIONS` and the world hash; the world part ends one when its rules break, with `Notice.STATION_ENDED`.

A stationed army's soldiers stand in the host's city for as long as it is stationed (batch 13, 10b second version, owner 2026-11-19), up to the city's free divisions, and fight any invasion there: `stationFights(owner, host, invader)` tells whether its player is at war with the invader (the mod's invasion window uses it). The world part sends the army's divisions (transaction `STATION_DIVS`) as it is stationed, as soon as the world army's men change (after a battle above all: the station book keeps the men last sent, owner 2026-11-19), and every 120 game seconds after (the refresh: men, equipment and training as the world army has them, so the world army's own supplies keep them up); the host's city puts them into free divisions of its army 0, mustered beside the throne, adds or sends away people to match, and takes them all out when the stationing ends (`STATION_LEAVE`). They don't count in the host's population (hook `99-stationed`), and have no demuster. Each division's dead come off its world division in the city's next report (input `STATION_RESOLVE`, sent only when someone died). While their player views the host's city, they command those divisions (their orders go to the log although a view only watches otherwise); the host commands them only while the grant is on. In an assault too: the commanders table, the same on every PC, leaves army 0 to the defender, and a stationed player's order for its own division passes it on the PCs that run the city, by the registry (`AiApplier.stationedOrder`). Which divisions hold them is the host's city state, in section `SOSDET_STATIONED_DIVS`, never in the world save.

**The camp** (stationing's third version, owner 2026-11-19). The station book (version 4) keeps, per stationed army, the keys (`StationBook.divKey`: the world division's type and index) of its divisions in the host's city; the others are in the camp, the army beside the city. Soldiers come in at the city's reachable entry point nearest the army. `stationCampState(div)` / `stationCamp(div)` are the division popup's Leave for the camp (command 7984, transaction `STATION_CAMP`); `stationMoveRefusal(army)` says why a stationed army with divisions in the city can't move (the move, intercept, siege and disband commands refuse it too), `stationAttackRefusal(army)` why a stationed army can't be attacked, `stationDivisions(army, inCity)` counts each side. Station on a stationed army sends the camp's divisions back in. A stationed army is never a battle's target nor starts one, but helps in one nearby.

## Threads

- Call the engine on the game thread only.
- A network thread only decodes bytes and queues them. The game thread feeds them in.
- A script's `update` runs inside the step. Don't call the engine from it, and don't change game state from it.
- Call from `GAME.addAfterUpdate`, which runs after the frame's update, outside the step. Register again after each load: `Engine.afterEachLoad` runs an action then.

## Starting a session

Every PC does the same, in this order. A session holds at step 0 until it is told to run.

1. **Before the load:** `Engine.begin()`, then `aiHost(startId)` or `aiPeer(startId)`, the same `startId` on every PC. Then `localPc(pc)`: 0 on the host, from 1 on a peer. Then `ownCity()` if each PC runs its own city. Then `armLoadSeed(seed)`, the same on every PC, never 0. Optionally `workFolder(dir)`: the engine's working saves (`__battle`, `__beforeBattle`, `__view`, `__beforeView`, `__viewReload`) go there instead of the game's saves folder, where they would show in the player's Load list (SOS-155).
2. **The load:** every PC loads the same save. For a new world, one PC calls `buildWorld`, and every PC loads its save.
3. **After the load, at step 0:** `pcHuman(pc, slot)` for each PC, `seed(seed)`, and `speed(agreed)` if it isn't 1x. A PC whose human isn't slot 0 calls `ownCityAt(x, y)` on a new world. At a resume without a player, every PC calls `removeHumanAtStart(slot)` or `freezeHumanAtStart(slot)` for that player's slot.
4. **To start:** `followAgreedSpeed()` on every PC, `pace(peerPcs, lead)` on the host, and `runFreely()` or `runUntil(step)`.

`seed`, `speed`, `aiHost`, `aiPeer`, `ownCity`, `hashEvery`, `ownCityAt`, `removeHumanAtStart` and `freezeHumanAtStart` throw after step 0. End with `Engine.end()`: the game is then vanilla again, apart from what [docs/hooks.md](hooks.md) lists.

## What to pump

The engine has no network. Your mod carries every byte. Each frame, after the update:

| Bytes | Out | In |
| --- | --- | --- |
| AI log | host: `aiLogOut()` | every peer: `aiLogIn(bytes)` |
| Orders | peer: `ordersOut()` | host: `ordersIn(pc, bytes)` |
| Hashes | every PC: `hashOut()` | every other PC: `hashIn(pc, bytes)` |
| City reports, own cities | every PC: `reportsOut()` | every other PC: `reportsIn(pc, bytes)` |

Bytes may arrive in any pieces, but deliver them all, in order, for each sender. The engine has no timeouts. A peer holds where the host's log ends, and the world waits for the reports it reads.

Only the host sets the world's speed. On every other PC that follows it (`followAgreedSpeed()`), a press of another speed changes nothing: the buttons go back to the agreed speed at the next frame, and `takeSpeedAsked()` returns the pressed speed once (0 otherwise), for your mod to pass on as a request. Pauses and battles' speeds are never requests.

A report from a PC whose game data isn't this one's (other mods or game files) is not read: `reportMismatch()` returns it, a `sosdet.report.ReportMismatch` whose `what` names the size that differs (a `Count`, such as `BOOSTS`), with `theirs`, `ours` and the sender's `pc`. The world then waits for that PC's reports, so the mod ends the session with that message (SOS-159).

When they come up:

| What | Out | In |
| --- | --- | --- |
| A battle with own cities | battle PC: `battleOut()` | every PC, that one too, at the Engage step: `battleIn(bytes)` |
| A city view | owner's PC: `viewOut()`, `viewHashOut(viewer)` | viewer's PC: `viewIn(bytes)`, `viewHashIn(bytes)` |
| Watching a local battle | `viewTapeOut(viewer)` | `viewTapeIn(bytes)` |
| A world resync | host: `worldResyncSave(file)` | every PC, the host too: `worldResyncLoad(file, state)` |
| A battle resync | host: `battleResyncSave(file)` | every PC, the host too: `battleResyncLoad(file, state)` |
| A resync with no own cities | host: `resyncSave(file)` | every PC, the host too: `resyncLoad(file, state, afterSet)` |
| A battle's restart (SOS-231) | the battle's PC or the defender: `viewOut()` | every watcher or viewer whose `View.rejoinAt` is the restart's step: `viewRejoin(bytes)` |

A resync carries a save file as well as the returned bytes, so send both. A restart by vote is the mod's to ask: `battleHere()` names the battle this PC's player sees, `takeRestartAsked()` says when they pressed the battle's own Restart, and the host checks `restartRefusal(key)` and calls `restartBattle(key)` once the players agree. At that step every PC that runs the battle goes back to its start: a battle every PC runs, a local battle with its allies and watchers, and the assault; every PC skips it alike when the battle can no longer restart then. Every PC hears the result as a `Notice.RESTART`. The engine never resyncs on its own: `desyncHold()` says when every PC has held, and your mod shows the choice.

For a desync hunt, put both PCs' lines side by side: `desyncReport()` (the world's; its world line, and `desync().worldParts()`, name the world part where the two PCs first differ, and its item lines, one per faction, region, NPC court and NPC stock of a differing part, compare line by line, SOS-250), `viewDesyncReport()` (the viewer's copy of a viewed city), `hashedDivisions(step)` with `hashInterval()` (the divisions this PC hashed at a step, owner and viewer alike), and `traceDraws(steps)` with `drawTrace(from, to)` (the code behind every world draw).

## Rules for a mod

- Never change simulation state on one PC. A player's action reaches the game's own UI call, where a hook turns it into a command. The rig's scripted player does that through `Engine.betweenSteps`.
- Never feed a per-PC value into the step.
- Every PC needs the same save, seed, game build, engine build and simulation mods.
- The determinism rules are in [.claude/rules/determinism.md](../.claude/rules/determinism.md).

## Limits today

The one list of known limits. The README and the release notes repeat a short part of it for players.

**The engine**
- V71, build 71.44 only.
- The host can't change during a session: `aiHost` and `aiPeer` throw after step 0.
- `removeHuman` and `freezeHuman` refuse slot 0.
- In `ownCity` mode each city differs by PC, so only the world is compared.
- The slowest PC caps the speed, and a speed change applies about half a second later.
- In a session, Quick Load and the game's autosave are off, and each title is worth the base 0.5.
- Other mods that change the same game classes or the simulation are untested. A city report from a PC whose game data differs is not read (`reportMismatch()`).
- The scenario suite checks little: see [rig/README.md](../rig/README.md).
- The army orders ring (`armyOrders`, SOS-249) makes one entry of a stop and another order of the same army at the same step, the order: the game's orders stop the army first, so a stop that really follows another order within one step is not recorded.

**The world**
- A world-battle prompt holds only its armies' own movement (SOS-45). The world AI can still teleport, re-route or disband a held army (`War.java`, for one), which vanilla never meets because its prompt pauses the world. It happens alike on every PC.
- A world resync while a world-battle prompt is open leaves the PC that reloads without that prompt.
- A world question has no time limit, unlike a prompt's 30 seconds. A battle's return and a view's thaw wait for it, so a player who ignores one delays another player's return.
- A few slot-0 leftovers: vanilla's `DipStance.any` compares with `FACTIONS.active().get(0)`, so a human at war with slot 0 can draw NPC joiners against it; the raid debug actions act on slot 0 only.

**Battles**
- A world command whose step falls in a battle is skipped, with a notice to its PC: the battle's end loads the game saved before it.
- Transactions the world queued for a city in the Engage step itself are dropped by the return load: the save holds the city before that step's tail.
- The host generating its own local battle holds the other PCs about 12.7 s.
- In a local battle, Start is the fighter's only; the allies' PCs follow its tape. An ally who closes their view, falls behind or is removed leaves their divisions to the fighting player (owner, 2026-11-19).
- A watch of a local battle ends once the watcher is 3 minutes behind (`Session.WATCH_BEHIND_SECONDS`). Each join resets the fighter's camera to the throne and closes its panels and selection. A join costs the fighter a save of 2 to 3 MB (about 1 s) and the watcher a load of 6 to 7 s.

**Views**
- A room the viewer was still drawing (never built) is lost at the view's load; built plans come back with its city.
- A city question open on the owner's PC at the view's freeze step has no copy on the viewer, so the owner's answer is skipped there.
- A viewer runs at 3.5x while it is more than 60 steps behind the host's confirmed step. The view ends when it hasn't caught up 60 s after its load, or falls more than 60 steps behind for over 60 s (mod spec §10).

**Freezing after a crash**
- The returning city comes back from its last campaign save. Soldiers who joined a world division and goods put into army supply after that save exist twice, in the world and in the city (accepted). What the world sent that city between the save and the freeze is lost: the replay of those transfers ([plan 0020](archive/0020-freeze.md), step 5) is not built.

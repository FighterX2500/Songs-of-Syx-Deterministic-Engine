# SOS-35 wave brief: the world map in lockstep (plan 0002, stage D)

You do one part of stage D for SOSDET, the deterministic lockstep engine for Songs of Syx V71.44:
- the world fixes, or
- the world, diplomacy and event commands.

One other agent does the other part at the same time, in its own git worktree, so stay inside yours. This brief follows the SOS-34 wave brief (`docs/plan/notes/agent-wave-brief.md`). Read that one too: its "Where things are", "The pattern", menu safety, mkpatch, build and commit rules all apply here, with the changes below.

## Where things are

- **Your worktree and branch:** given in your prompt: `C:\ClaudeFolder\SOSDET-wt\<part>`, branch `SOS-35-world-<part>`.
  - Work and commit only there.
  - Never touch `C:\ClaudeFolder\SOSDET` (the main checkout) or the other worktree.
  - Never push.
- **Rules:** read these first and follow them:
  - `CLAUDE.md`, `AGENTS.md` and `.claude/rules/determinism.md`;
  - `docs/decisions/0002-player-commands.md`;
  - `docs/plan/0002-city-and-world-lockstep.md` (stage D);
  - `docs/hooks.md`;
  - `docs/commands.md`.
- **Game source (read only):** `C:\ClaudeFolder\Songs of Syx\info\SongsOfSyx-sources`. `SOS_GAME_DIR` is set.
- **Inventories** (in `C:\Users\FIGHTE~1\AppData\Local\Temp\claude\C--ClaudeFolder-SOS\e62a2a72-6f3e-4557-b653-9fdfcc04501d\scratchpad\`):
  - `competitor-determinism.md`, section "# World" (W1 to W4), especially the W3 table;
  - `competitor-commands.md` (the competing mod's survey of every entry point);
  - the competitor's source under `competitor\` (MIT; rewrite it to our design).
- **Edits files for mkpatch:** keep them outside the worktree, in `C:\Users\FIGHTE~1\AppData\Local\Temp\claude\C--ClaudeFolder-SOS\cf30f6fd-466a-48ac-a39e-a16e2b6fa6f9\scratchpad\wave35\<part>\`.

## Context you need

- **The world will run inside the step.** Today a session holds the world still (`Hooks.worldHeld`, patch 10). I am removing that hold in parallel with you. Write your code as if the world updates inside every step on every PC, in vanilla order (city first, then the world).
- **SOSDET V0.1 is co-op.** Every PC plays the same save and the same player faction, so `FACTIONS.player()` is the same faction on every PC. Several human factions are the mod's later work. Don't redesign "the player"; just never feed a per-PC value (this PC's profile, view, developer flag, render frame count) into the step.
- **The UI-cache treatment of patch 67 (read-only UI):**
  - outside the step (`sosdet.Hooks.outsideStep()` true), a cache is read but never refreshed, and nothing saved is written;
  - inside the step it refreshes as in vanilla.
  - See `patches/67-read-only-ui.patch` for examples.
- **No rig runs and no game starts:** don't run `rig/rig.py` or the game. I run one smoke run at the end of stage D (owner's decision: fewer tests, then a full V0.1 product).
  - You still add rig actions and a rig script for every command you make, so that run exercises them.
  - Build with `./gradlew.bat build --console=plain` from Git Bash, run directly (never `cmd /c`). It must pass.

## Part "fixes": world determinism patches (patch `98-world-fixes`)

Fix the W3 items, each the smallest way that works:

| # | Item |
|---|---|
| 3 | `RDLevelsTmp.level` read by `RDBuilding`: the local building popup's draft levels must never reach the simulation. Inside the step, read the real level. |
| 4 | `Emissaries.penaltyMul`: the cache is keyed on the render frame `VIEW.RI()`. Key it on the step (`GAME.updateI()`), or recompute inside the step. |
| 5 | `RDRaces.Visuals.cache`: render writes saved region data. Use the patch 67 pattern, or another way that keeps saved data out of render. |
| 6 | `RDBoostCache.get`: a saved cache filled by whoever calls first. Patch 67 pattern. |
| 7 | `DipWarPlayer.init` and `distress`: patch 67 pattern. |
| 8 | `RaidingUtil.cache`: patch 67 pattern. |
| 9 | The exact-equality `updateI` caches: check each one listed, and fix only those whose refresh writes saved data or draws RND. Report the rest as checked. |
| 12 | `DealBools.problem` reads `S.get().developer`. In a session, use developer = false. |
| 13 | `PTitles` title boosts use `raceValue` from this PC's profile. Find the smallest deterministic answer for V0.1, for example a fixed value in a session or the value carried in the save. Explain your choice in the report, since this changes what the player sees. |
| 14 | `FactionProfileFlusher`: check that a save load overwrites the profile's name and banner; report. |
| — | Audit `DIP.TMP()` (the deal draft that UI render rewrites) and `Deal.java:74` (a `VIEW.RI()` cache): can a UI write reach state the step reads? Fix it if it can. |

- Also scan the rest of the W3 table and `world/**`, `game/faction/**`, `game/raiding/**`, `game/events/world*` for anything else the step reads that is per-PC. Fix what is clearly needed; list what is doubtful.
- **Not yours:**
  - the world-battle prompt (W3 #1, #2, `WBattles.canPoll`, `ResolverPlayer`, `UIWBattlePrompt`), which I do;
  - `GAME.java` and patch 10;
  - player commands.
- New engine hooks go in `src/main/java/sosdet/Hooks.java` (one short method each, javadoc'd like the others). Add unit tests only for game-free logic.

## Part "cmds": world, diplomacy and event commands (patch `96-cmd-world`)

- Port the `docs/commands.md` rows with stage `D` in the areas world, diplomacy and events, except the rows I keep (below).
- **Area classes and id ranges** (from `Commands.java`):
  - `World.java`, 7000;
  - `Diplomacy.java`, 8000;
  - `Events.java`, 9000 (new: update the javadoc of `Commands`).
  - Add them to the `Commands` index. You are the only agent touching `Commands.java` and `Args.java`, so you may add `Args` helpers this time, for example a world army, a region, a faction or a world division.
- **The deal popup:** hook only the commit (`Deal.execute`), never the draft. The draft is rewritten every frame; the competing mod once produced 185,792 commands from it. A committed deal must carry every term the draft held.
- **UI-drawn RND (W3 #17):** if an action draws RND in the UI before it commits (`WArmyConstructor.placeFirst`, `DealDrawfter.canGift`), the command must either carry the drawn values or make the draw inside the step.
- **Rows I keep, so skip them:**
  - the world-battle prompt and its decisions (`UIWBattlePrompt.*`, `WBattleSpec.engage/auto/retreat`, `WBattleSiege.*`, `WBattleResult.accept`, `BattleAssist`/`BattleBattle` rows);
  - generated battles (`world (generated battle)` rows, `IDeploy`, `BattleState.*`).
- **Rig actions:** put them in `rig/java/sosdet/rig/act/WorldActions.java`, with the script in `rig/cmds/world.txt`.
  - Targets must resolve deterministically in Mogus-72, for example "the player's world army N", "the player's capital region", "the first NPC faction by index".
  - Keep the script to safe actions: move an army and stop it, change a banner, set a deal or stance with one faction and set it back, and so on. Avoid anything that starts a world battle; that path is mine.
- **Not yours:** the world fixes patch `98-world-fixes` and the game files it touches. If you must hook a file the fixes agent also changes (for example `RDLevelsTmp`, `DIP`, `Deal`, `DealBools`, `Emissaries`, `PTitles`, `RDBoostCache`), hook its UI call site instead, or say so in your report.

## Your final report (under 800 words plus the lists)

1. **What you changed:** for each fix, the game file, the hook and why. For each command: id, name, the game call, the hook place and the arguments.
2. **docs/commands.md rows** (cmds part): the entry point as written there and its new status.
3. **docs/hooks.md rows** for your patch, in that table's format.
4. **Doubts:** semantics, menu safety, items checked and left alone, and edits in patches other than your own.
5. **Build result:** the commands you ran and their results; your commits (`git log --oneline`).

Don't edit `docs/hooks.md` or `docs/commands.md`; I merge both parts and write them.

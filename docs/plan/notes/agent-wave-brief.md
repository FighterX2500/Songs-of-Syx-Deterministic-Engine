# SOS-34 wave brief: port the city's player actions to commands

You port one area of Songs of Syx V71.44's player actions to commands in SOSDET, the deterministic lockstep engine. Four other agents port other areas at the same time, each in its own git worktree, so stay inside yours.

## Where things are

- **Your worktree and branch:** given in your prompt (C:\ClaudeFolder\SOSDET-wt\<area>, branch SOS-34-cmd-<area>). Work and commit only there.
  - Never touch C:\ClaudeFolder\SOSDET (the main checkout) or other worktrees.
  - Never push.
- **Rules:** read these in your worktree first and follow them. CLAUDE.md, AGENTS.md and .claude/rules/determinism.md, plus:
  - docs/decisions/0002-player-commands.md (the mechanism; it is the design you implement);
  - docs/commands.md (the coverage list, with your area's rows).
- **Game source (read only):** C:\ClaudeFolder\Songs of Syx\info\SongsOfSyx-sources. `SOS_GAME_DIR` is set. Most files are CRLF, a few LF.
- **The competing mod's survey:** C:\Users\FIGHTE~1\AppData\Local\Temp\claude\C--ClaudeFolder-SOS\e62a2a72-6f3e-4557-b653-9fdfcc04501d\scratchpad\competitor-commands.md. Its source is in the same folder under competitor\_cmd\ (for example syx-multiplayer/src/syxmp/agent/AutoSyncTable.java and sync/*.java). The survey has, for every entry point:
  - the declaring class and method descriptor;
  - the UI classes that call it;
  - the menu-unsafe classes (§3.2);
  - the per-file list (§3.3);
  - their pitfalls (§4).

  It is MIT-licensed and may be copied, but rewrite it to our design. Their object paths, Java serialization and `-javaagent` mechanics are not ours.

## The pattern (copy it exactly)

The two pilots show everything:
- `src/main/java/sosdet/game/cmd/Laws.java` (curfew, id 1000) with patch `patches/96-cmd-laws.patch`;
- `src/main/java/sosdet/game/cmd/Military.java` (a division's men target, id 4000) with patch `patches/96-cmd-military.patch`.

For each player action:

1. **Find what the UI really calls.** Find the call in the game source that commits the action, and the UI classes that call it.
   - Hook only the committing call, never draft, preview, hover or render code. Their deal popup rewrote a draft every frame and produced 185,792 commands.
2. **Choose the hook place** (owner's decision):
   - **Default:** the declaring method's first lines: `if (sosdet.game.cmd.<Area>.<name>(args)) return;`. For a method with a return value, return what the UI can safely ignore, or hook at the call site instead.
   - **The UI call site instead** (`if (!sosdet.game.cmd.<Area>.<name>(args)) target.method(args);`) when the declaring class:
     - is menu-unsafe: `Armies`, `Humanoid` (and `$HumanoidResource`), `Cart`, `EventKiller`, `RoomEmploymentIns`, `Immigration$Immigrator`, `FCredits$CTYPE`; or
     - is on a hot simulation path (the generic `INT$IntImp.set`, `BOOLEAN$BOOLEANImp.set`, `RMapInt.set`, `ArrayInt.set` and so on).
   - **Also prefer the call site** when one UI call covers many overrides, for example one hook where the UI calls `autoEmploy` instead of 19 overriding methods.
   - **Menu safety:** our build ships every game class a patch changes, and the game's main menu calls `Class.forName` on every class in a mod jar. A shipped class whose static initializer (static blocks and static field initializers) touches `TIME`, `Config.battle()`, `SETT`, `GAME`, `STATS`, `HCLASSES` or anything else that is null at the menu crashes the game. `util.text.D.ts(...)` and plain constants are safe. Check every class you patch, and use the call site if it isn't safe.
3. **Write the command** in your area's class `src/main/java/sosdet/game/cmd/<Area>.java`:
   - an id constant from your range;
   - an entry in `ALL`: `new Command(ID, "name", r -> { ...decode...; call the game; return true; })`. Return false when a target is gone, and the engine then skips the command on every PC;
   - a `public static boolean <name>(...)` capture method, exactly like `Laws.curfew`: `if (!Commands.capturing()) return false; Commands.capture(new CmdWriter(ID)...); return true;`.
   - **Codecs:** use `CmdWriter`/`CmdReader` (ints, booleans, doubles, strings) and `Args`:
     - room instance, with a blueprint check;
     - room blueprint, entity or humanoid, race, resource, HCLASS (nullable as -1), division.
   - **New types:** if you need an argument type `Args` lacks, add the helper **in your area class** (private static), not in `Args.java`, which other agents also use. Note it in your report so I can move it.
   - **Order:** decode in exactly the order you encode; `CmdReader.end()` fails loudly otherwise.
   - **Re-calling the game:** the applier calls the same game method the hook sits in. That is safe, because inside the step `Commands.capturing()` is false and the original body runs.
   - **Issuer-only state:** carry everything the action reads from the issuer's UI or profile as arguments (rotation, size, furniture group, wall material, selected item, the value typed). A placer cache the UI keeps must be recomputed by the applier before it acts.
   - **Feedback:** sounds, camera moves and opening windows happen only when `sosdet.Hooks.issuedHere()` is true while applying, or simply stay on the UI side if they happen before the call.
   - **No mutable static state** in your classes; constants only.
4. **Write the hook** in a patch with the helper: `python tools/mkpatch.py 96-cmd-<area> <edits.py>`. Run `python tools/mkpatch.py` with no arguments, or read its docstring, for the format.
   - Keep your edits files OUTSIDE the worktree, in C:\Users\FIGHTE~1\AppData\Local\Temp\claude\C--ClaudeFolder-SOS\e62a2a72-6f3e-4557-b653-9fdfcc04501d\scratchpad\wave\<area>\.
   - `FILES` lists every game file your patch covers (old and new); `EDITS` holds only the new edits.
   - Every hook line gets a comment `// SOSDET: ... (ADR 0002)`, as in the pilots.
   - **Files another patch owns:** each game file belongs to exactly one patch, and the build fails otherwise. Find a file's owner with `grep -l "^+++ b/<path>" patches/*.patch`. If another patch already changes a file you must hook, put your edit into THAT patch (mkpatch with that patch's name, its full FILES list and only your new edit), and say so in your report.
5. **Add a rig action** in `rig/java/sosdet/rig/act/<Area>Actions.java` for every command: a short name and simple arguments, queued with `Engine.betweenSteps(() -> <the same call the UI makes>)`, like `LawActions` and `MilitaryActions`.
   - Targets must resolve deterministically in the test save Mogus-72 (a big late-game city with every room type), for example "the first room instance of blueprint KEY", "the player's division N", "race index".
   - Then write `rig/cmds/<area>.txt`: one action per line, covering every command at least once, in an order that makes sense (set something, then set it back).
6. **Build:** `./gradlew.bat build --console=plain` in your worktree must pass (run it directly from Git Bash; never through `cmd /c`, which hangs). That covers compile, tests, patch application, the byte-identical checks and `staticDrawCheck`.
   - Add unit tests only for game-free logic you write.
   - Do NOT run rig/rig.py and do NOT start the game; I run the rig after merging, one area at a time.
7. **Commit** to your branch, in small commits named `SOS-34 <area>: ...`, each ending with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Out of your scope

- World, diplomacy and event rows: stage D, later.
- Battle orders and battle settings: already done.
- Developer-only buttons.
- Docs: do NOT edit docs/hooks.md or docs/commands.md, since others edit them too. Put the rows in your report instead.

## Your final report (under 700 words plus the lists)

1. **Commands:** id, name, the game call, the hook place (declaring method or call site, and why), the arguments.
2. **Status of each of your area's rows in docs/commands.md:** the entry point as written there, and the new status. Use `done: command <id> <name>, patch <patch>`, or `not needed: <why>`, or `left: <why>`.
3. **docs/hooks.md rows** for your patch, in that table's format: `| patch | point | game file | calls | why no API or reflection route | card |`.
4. **Edits in other patches,** helpers added outside `Args`, and anything you were unsure of (semantics, return values, state the UI keeps, menu safety).
5. **Build result:** the commands you ran and their results.

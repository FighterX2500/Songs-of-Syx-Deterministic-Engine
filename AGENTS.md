# AGENTS.md

Guidance for coding agents working on SOSDET, the deterministic simulation engine for Songs of Syx. These are the general coding rules; `CLAUDE.md` adds the project workflow, `docs/requirements.md` defines what the engine must do, and `.claude/rules/determinism.md` holds the determinism rules, which win where they are stricter.

## Project Facts
Fill these in and keep them current. Agents must not guess them.

*   **Target game version:** `V71` (build 71.44, from `game/VERSION.java`), pinned (requirements §2).
*   **Platform:** Windows only. Use `gradlew.bat` and Windows paths.
*   **Game folder (`<game>`):** outside the repository, located through the `SOS_GAME_DIR` environment variable. On the main dev PC: `C:\ClaudeFolder\Songs of Syx`. Nothing under it is ever committed.
*   **Game source:** `<game>/info/SongsOfSyx-sources/`, shipped with the game. Read it freely; never copy or commit it. The engine changes it only through its patch set (see "Patches to Game Code").
*   **Game jar:** `<game>/SongsOfSyx.jar`. Never shade, copy, or commit it. It already contains steamworks4j 1.9.0 and the Steam native libraries.
*   **Compiling game source:** ECJ 3.40.0 with `-g -preserveAllLocals -encoding UTF-8 -proc:none`, at `--release 8` for `snake2d` and `integrations` and `--release 16` for the rest, inside the build's JDK ([engine ADR 0001](docs/decisions/0001-build-and-packaging.md)). Without `-proc:none`, 26 classes differ from the shipped ones. ECJ is a build tool only; players never run it.
*   **Engine's own code:** package `sosdet`, `javac --release 21`, the level of the game's bundled JRE (`<game>/jre/release`: Temurin 21.0.11). Game code compiled at 8 and 16 can call it.
*   **Release:** a mod folder, `build/mod/SOSDET` (`_Info.txt`, `V71/script/SOSDET.jar`): the engine's classes plus every class compiled from the patched game files ([engine ADR 0001](docs/decisions/0001-build-and-packaging.md)).
*   **Game source index:** codegraph (`npm i -g @colbymchenry/codegraph`, telemetry off), built once per PC with `codegraph init -y "%SOS_GAME_DIR%\info\SongsOfSyx-sources"`; the index stays in the game folder. Use it first to find definitions and callers; confirm with grep when a complete list matters.
*   **Build:** `gradlew.bat build` (Gradle 8.14.3 wrapper; needs `SOS_GAME_DIR`, a JDK 21+ and `git`). It includes the byte-for-byte checks: the patched files compiled unchanged, and the whole untouched game rebuilt.
*   **Fast check:** `gradlew.bat compileJava compileTestJava`.
*   **Tests:** `gradlew.bat test` (JUnit 5).
*   **Dev install and launch:** `gradlew.bat installMod` / `uninstallMod` (`%APPDATA%\songsofsyx\mods\SOSDET`, then enable it in the launcher); `gradlew.bat runGame` starts the game with the mod, without the launcher. `-Psosdet.patches=<dir>` builds another patch set, for example a test fixture.
*   **Determinism test:** the test rig, `python rig/rig.py field|city --tag <tag>` after `gradlew.bat assemble` ([rig/README.md](rig/README.md)): 4 copies at different speeds, compared every step. The scenario suite (SOS-31) grows from it.
*   **Game log / crash output:** `%APPDATA%\songsofsyx\logs\` (error files `error<date>.txt`); also `<game>/errorLogs/`.

## Design Review: KISS, DRY, SRP, Cohesion, and Coupling
Apply these criteria to the touched behavior before declaring a change complete. Explain concrete tradeoffs when a criterion is only partially met; passing tests alone does not establish design quality.

*   **KISS:** Prefer direct control flow and the smallest existing API that expresses the requirement. Use early returns and named methods to keep the main flow readable. Do not introduce interfaces, abstract base classes, generic type parameters, builders, factories, or configuration solely for hypothetical reuse.
*   **DRY:** Give each rule and side effect one authoritative implementation. Similar-looking code with different semantics does not justify a shared abstraction.
*   **SRP:** Keep the hook (where the game calls in), the engine logic (what happens) and the API (what the mod sees) separate.
*   **High Cohesion:** Keep an operation's state, lifecycle, invariants, and focused tests with its owner. Avoid scattering one behavior's flags across unrelated classes or static fields.
*   **Low Coupling:** The mod sees only the API. Engine logic that doesn't need game classes doesn't import them.
*   **Behavioral Evidence:** Prove a change with the determinism test and the timing rule, not only with unit tests. Exercise save/load round-trips whenever persisted state changes.
*   **Scope Discipline:** Improve boundaries required by the change without redesigning adjacent systems. Report remaining material gaps specifically instead of claiming blanket compliance with these principles.

## Surgical Changes
Touch only what you must. Clean up only your own mess.

*   **Minimal Impact:** In game source above all: change only the lines a hook needs. Don't "improve" adjacent code, comments, or formatting, and don't run a formatter over game files.
*   **Style Match:** Match existing style, even if you'd do it differently.
*   **Cleanup Scope:** Remove imports/fields/methods that YOUR changes made unused. Don't remove pre-existing dead code unless asked.
*   **Traceability:** Every changed line should trace directly to the user's request or the Jira card being worked on.

## Goal-Driven Execution
Define success criteria. Loop until verified.

*   **Verifiable Goals:** Transform tasks into verifiable goals (e.g., "fix the desync" → "the scenario that diverged at step 2,138 stays identical for 36,000 steps").
*   **Reproduce First:** For a desync, find the first step and the state that differs before fixing it. If it only reproduces in-game, state the steps and what you could not verify.
*   **Stepwise Verification:** For multi-step tasks, state a brief plan with verification steps for each part.
*   **Build Verification:** Always build after changes. Run the fast check, then the tests, then the full build. A change is not done while there are compiler errors or new warnings in touched files.
*   **Game runs:** one at a time on the dev PC (they compete for CPU and change the timing).

## Java Rules

### Language and API
*   Stay within the configured `--release` level of the code being compiled (game source at its shipped level, see Project Facts).
*   Always use `@Override`. No raw types. No unchecked casts without a one-line justification and the narrowest possible `@SuppressWarnings`.
*   Use the narrowest visibility that works. Do not widen visibility just to make something testable; move the logic into a testable class instead.
*   Prefer `final` fields and immutable value objects for data passed across the API.
*   Implement `equals`/`hashCode` together, and only when the type is used as a key or compared by value.
*   Use try-with-resources for anything closeable.

### Exceptions and Logging
*   Do not swallow exceptions. No empty `catch` blocks; no `catch (Throwable)` or `catch (Exception)` except at a single top-level boundary that logs with context and rethrows or disables the feature deliberately.
*   Fail fast on programmer errors (invalid arguments, broken invariants) with a message naming the offending value.
*   Prefix all log output with `SOSDET`. Never log inside the step or per entity, except behind a one-shot guard.

### Performance in Hot Paths
Code called every step, every frame or per entity must:
*   Not allocate: no `new`, no autoboxing, no varargs, no string concatenation, no lambdas/streams/iterators that allocate. Reuse preallocated arrays and buffers.
*   Not perform reflection lookups, file I/O, or logging.
*   Keep the timing rule (requirements §4): measure a change that touches the step.

### State and Lifecycle
*   No mutable `static` state; constants `static final` only if truly immutable. A game session can start several times in the same JVM, and static fields leak state between sessions.
*   Open question for SOS-27: the game keeps its own state in static classes (for example `RND`), so some engine state may have to be static. Propose each case to the owner, with where it is reset (battle start and every load).
*   Simulation state is touched only by the simulation thread, inside the step. The one exception is the host's AI general (`.claude/rules/determinism.md`). Do not start other threads without explicit approval.
*   The engine has no network code; the mod carries its data.

## Songs of Syx Rules

### Patches to Game Code
The engine's patch set is its product. Use, in this order of preference: the game's public API; reflection in one place per member, resolved once at init, failing with a clear message that names the member and the targeted game version; a change to the game's own code.

*   Each game file is changed by exactly one patch. A patch is a source diff with 2 lines of context; it never contains whole game files.
*   A hook only calls into engine code. The logic lives in the engine, where it can be tested.
*   Every hook is listed in `docs/hooks.md` with its hook-surface point, the game file, and why no API or reflection route was enough.
*   A patch that no longer applies fails the build with the file named.

### Determinism
Every line that runs inside the step, or feeds the state hash, follows `.claude/rules/determinism.md`.

### Persistence (Save/Load)
*   `save` and `load` must write and read the same fields in exactly the same order. Change them together, in the same commit.
*   Every format the engine writes (engine save data, AI log entries, orders, scenarios) starts with a format version, and `load` handles every previous version still in circulation.
*   Every change to persisted state needs a round-trip test: save → load → assert equal state.
*   Never persist object references, indices, or IDs whose meaning can change between sessions without a stable lookup key.
*   Never use Java built-in serialization (`ObjectOutputStream`).

### Dependencies and Packaging
*   No third-party runtime libraries unless explicitly approved in an ADR.
*   A release holds the engine's own classes and the classes compiled from the patched game files, and nothing else ([engine ADR 0001](docs/decisions/0001-build-and-packaging.md); a test checks it). Never commit a built jar or game classes; releases are published only when the owner decides. Verify jar contents after packaging changes.
*   Static initializers of engine classes must not touch game state: the game's script scan initializes every class in the mod jar while it builds the main menu (SOS-25).

## Reporting
When finishing, report: what changed and why, how it was verified (commands run and results, determinism and timing runs), what was verified only by reasoning or needs manual in-game testing, which `docs/requirements.md` sections the work touched, and any remaining gaps against the principles above.

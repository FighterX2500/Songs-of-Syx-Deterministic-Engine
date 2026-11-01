# Patch set

The engine's changes to the game's own source, applied by the build (`applyPatches`) to copies of the shipped V71 source.

- One file per topic, numbered in apply order: `20-rnd-streams.patch`.
- Unified diff with 2 lines of context and `a/` `b/` paths relative to the game source root, for example `b/snake2d/util/rnd/RND.java`. The game source uses CRLF line endings, and so do the patch's lines; `.gitattributes` keeps git from converting them.
- Each game file is changed by exactly one patch; the build fails if two patches touch one file.
- A patch changes existing game files only. New code is engine code under `src/main/java/sosdet/`.
- A hook only calls into engine code (AGENTS.md "Patches to Game Code"), and every hook is listed in `docs/hooks.md`.
- A patch that no longer applies fails the build, naming the patch and git's message.

Make or extend a patch with the development tool:

```
python tools/mkpatch.py <patch name> <edits.py>
```

It needs `SOS_GAME_DIR`, and its usage is at the top of `tools/mkpatch.py`. It writes the patch with `diff -ru -U2 a b`.

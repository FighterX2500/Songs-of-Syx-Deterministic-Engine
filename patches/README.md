# Patch set

The engine's changes to the game's own source, applied by the build (`applyPatches`) to copies of the shipped V71 source. Empty until SOS-27.

- One file per topic, numbered in apply order: `20-rnd-split.patch`.
- Unified diff with 2 lines of context and `a/` `b/` paths relative to the game source root, for example `b/snake2d/util/rnd/RND.java`. The game source uses CRLF line endings, and so do the patch's lines; `.gitattributes` keeps git from converting them.
- Each game file is changed by exactly one patch; the build fails if two patches touch one file.
- A patch changes existing game files only. New code is engine code under `src/main/java/sosdet/`.
- A hook only calls into engine code (AGENTS.md "Patches to Game Code"), and every hook is listed in `docs/hooks.md`.
- A patch that no longer applies fails the build, naming the patch and git's message.

Make a patch with GNU diff from Git Bash, from a folder holding the original (`a/`) and changed (`b/`) copies:

```
diff -ru -U2 a b > patches/NN-topic.patch
```

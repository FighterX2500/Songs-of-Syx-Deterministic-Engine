# SOSDET

Deterministic simulation engine for Songs of Syx V71 (build 71.44). The same battle save, seed, orders and AI log give a bit-identical simulation on every PC. The SoS GSG MP multiplayer mod ([SOSGSGMP](https://github.com/FighterX2500/SOSGSGMP)) uses it for lockstep battles.

- What it must do: [docs/requirements.md](docs/requirements.md)
- Coding rules: [AGENTS.md](AGENTS.md); determinism rules: [.claude/rules/determinism.md](.claude/rules/determinism.md)
- Changes to the game's code: [docs/hooks.md](docs/hooks.md)
- Why it is a separate project: [ADR 0004](https://github.com/FighterX2500/SOSGSGMP/blob/main/docs/decisions/0004-engine-split.md) in SOSGSGMP

No game files or game source are ever committed. The build reads the game from `SOS_GAME_DIR`. Where the scenario suite keeps its test saves is settled in SOS-31.

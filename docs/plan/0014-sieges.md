# Plan 0014: sieges of a human's capital (SOS-92)

Card: [SOS-92](https://fighterx2500.atlassian.net/browse/SOS-92). Mod spec §9 and ADR 0006 (SOSGSGMP). The whole plan, engine and mod, is SOSGSGMP `docs/plan/sieges.md`, approved by the owner on 2026-10-29. This file records the engine's side of each step as it is built.

Status: step A merged (PR #60, 2026-10-30); steps B and C in part are in PR #63 (open).

## Step A: the siege and its wait

**Besieging a human's capital.** At war, `ARMY_BESIEGE` accepts another human's capital (`World.otherHumanRefused` keeps only the peace check). The capital still never falls to `Util.conquer` (patch 15): only to the assault, step B.

**The wait** ([SiegeBook](../../src/main/java/sosdet/game/siege/SiegeBook.java)):
- When `PSiege.besige` first marks a human's capital besieged, the engine records the game second and the city's population, read from its report (`Sieges.began`). When `PSiege.update` clears the mark, the record goes (`Sieges.ended`). A new siege waits from its own start.
- The assault is allowed after one season per started 1,000 people, from 1 to 4 (mod spec §9), counted from the population at the start (owner, 2026-10-29).
- The book is world state:
  - it is saved with the world (`GameSieges`, section `SOSDET_SIEGES`);
  - a world load carries it (`GameWorldLoad.KEYS`);
  - it is in the world hash (part 7, with the deals and tariffs).
  - A save without the section gives an empty book, and an empty book hashes as nothing, so a game without a besieged human hashes as before.

**Storm.** The besieger's siege prompt (`BattleSiege`) greys out auto-resolve on another human's capital, with the days left of the wait. Once the wait has passed it stays greyed out with "The assault on a player's capital isn't built yet." The decision command refuses it too (`WorldBattles.applyDecide`). `ResolverPlayer.besige` no longer opens the capture panel for a human's capital with no defenders.

**The sally at any time.**
- Command 7970 (`Sieges`, `Engine.sallyOut`): at its step, the next siege poll asks the defender as at the siege's start (`WBattles.sosdetSally`, patch 15).
  - Against a human besieger, that is SOS-83's Fight or Fall Back.
  - Against an NPC, it is vanilla's sally.
- Refused while a battle question is open, or when the capital isn't besieged.

**The blockade.** A besieged human's capital buys nothing and sells nothing in the market, with NPCs or other humans (`TradeSorter.buy`, `Hooks.humanBlockaded`). Vanilla's own blockade (`SENTRY.isClosed`) works in the city, which in own-city mode runs only on its owner's PC. A deal between humans with goods to or from a besieged capital is refused; money still moves (`HumanDeals.blockadeRefusal`).

**For the mod:** `Engine.besiegedSince`, `assaultInDays`, `sallyRefusal`, `sallyOut`.

**Rig:** the probe `wsiege` and the actions `sally` and `sallyother` (rig README).

### Results

Rig runs on Mogus-72 with three humans (`--humans 3 --make-humans 2,24`, copy 3 on the laptop), slot 24 declaring war and besieging slot 0's capital (`3: dealwar 0;3: wbesiegehuman 0 0 capitol`). Each run was identical on every copy, with no engine desync.

- **S92A to S92D** (18,000 to 30,000 steps): the siege begins with its record (3,064 to 3,066 people, 4 seasons, "the assault can start in 16 days"), Storm is refused with the days left, and a goods deal with the besieged human is refused. A sally while the defender's own question is open is refused; with no question open it is asked at once (S92C, step 16,261).
- **S92E** (24,000 steps, `--unanswered`, `sallyother`): the defender's first question ran out at step 10,765 and the besieger's siege window opened; the sally made during that window was taken, not refused, and asked the defender as soon as the window closed (step 13,166).

Not covered by a run: the wait running out (Storm then stays refused until step B), and the mod's popup and Sally out button, which need a campaign with soldiers (the owner's playtest).

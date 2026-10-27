# Plan 0012: war between humans (SOS-74, milestone 1)

Card: [SOS-74](https://fighterx2500.atlassian.net/browse/SOS-74). The mod's plan, with the audit and the owner's decisions: SOSGSGMP `docs/plan/war.md` (approved 2026-10-27). Real-time battles between two humans are milestone 2, [SOS-83](https://fighterx2500.atlassian.net/browse/SOS-83). Until then the mod doesn't offer war in its Diplomacy tab.

## What changes

- **The war command** (`HumanDeals`, id 8104, `Engine.declareWar`/`warRefusal`):
  - one human declares war on another at once, with no answer;
  - it applies with the declarer as the current human (`DIP.set` reads the player for its war timer);
  - it calls `DIP.WAR().set` directly, so no NPC's opinion changes (mod spec §6);
  - it works from any stance, and a vassalage between the two ends.
  - Refused: not two humans, the same human, a removed human, already at war.
- **The only way two humans go to war** is that command:
  - **allies:** `DIP.ally` never sets a stance between two humans, into war or out of it, unless the follower is the instigator's vassal (`HumanDeals.allyFollows`, patch 15). This carries out plan 0004's decision: "a war between two humans comes only from one of them declaring it";
  - **besieging:** a besiege of another human's region needs the two at war already (`World.otherHumanRefused`), so the siege question never declares war between humans;
  - **garrison attacks:** a garrison's attack on a human's army needs the war already, and never declares it.
- **Sieges of a human's regions:** any region but the capital, once at war. A capital stays refused until the assault of mod spec §9.
- **Peace:** the existing Peace treaty in a deal, with money, goods and regions.
- **Not world state of its own:** the stance lives in `DIP`, already saved and hashed.

## Audit

- **`OpsStance.signPeace`'s cast** of every war enemy to `FactionNPC` is already guarded (patch, ADR 0004), so peace with an NPC while at war with a human is safe.
- **The `DIP` change listeners** (`DipWarPlayer`, `RaidingMap`, `RDDistance`, `EventDiplomacy`, `Invador`) are already per human (SOS-46). The war name's random pick runs in the world part on every PC alike.
- **Battles between two humans** still go through one human's prompt, with Auto. That is SOS-83's.

## Checks

- **W74A, rig, 3 humans, Mogus-72** (`--make-humans 2,24`):
  - slot 0 and slot 2 become allies;
  - slot 24 declares war on slot 2: slot 0 must not be at war with slot 24;
  - slot 24's army besieges slot 2's capital (refused), then another region of slot 2's;
  - peace with 100 denari;
  - every copy identical.
- **twopc** with `--war`: both PCs at war at the same step, hashes agree.

## Results (2026-10-27)

- **W74A** (14,000 steps): copies identical, 932 hashes, none missed. Slot 0 allied with slot 2; slot 24 declared war on slot 2, and slot 0 stayed NEUTRAL with slot 24 (the ally rule). The siege of slot 2's capitol was refused: the army stayed fortified. Slot 2 has no other region, and the peace came after the run's last step, so W74B followed.
- **W74B** (26,000 steps): copies identical, 1,732 hashes, none missed.
  - slot 24 declared war on slots 2 and 0;
  - the siege of slot 0's capitol was refused;
  - slot 24's army went to slot 0's other region (region 100), arrived and opened the siege battle prompt for slot 24;
  - slot 24 offered slot 2 peace with 100 denari, accepted: slots 2 and 24 NEUTRAL, while slot 0 (slot 2's ally) stayed at war with slot 24.
- **Not run:** the siege prompt's outcome and a capture of the region (the prompt timed out to its default, no probe after it); peace with an NPC while at war with a human (the guard is vanilla-path code checked by reading).

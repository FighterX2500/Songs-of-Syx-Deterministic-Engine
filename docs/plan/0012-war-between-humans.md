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

## Milestone 2: battles between two humans (SOS-83)

Card: [SOS-83](https://fighterx2500.atlassian.net/browse/SOS-83). The mod's plan, with the audit and the owner's decisions (2026-10-27, every one as recommended: 1a, 2a, 3a, 4a): SOSGSGMP `docs/plan/battles-between-players.md`. Below, A is the human whose PC builds the battle (army 0) and B the other (army 1).

### What changes

- **A. The prompt** (`Resolver.init`, patch 15; `WorldBattles`): when both sides have a human, both are asked, Fight only (Auto and Retreat greyed out and refused in the command). Each Fight marks its human; when both have, or 30 s pass, the battle engages at the next step, which the hold before battles sees (SOS-70). A is the side whose army moved into the tile (`WorldBattles.secondAttacks`).
- **B. Commanders** (`Session.commandBattle`, `deployReady`; patch 94): the host stamps the commanders table through its log as the battle loads: army 0 to A's PC, army 1 to B's PC (the AI general against NPCs). Start needs both sides' commanders, or 2 minutes (`deployTimer`). Army 1 deploys in army 0's area turned about the map's centre.
- **C. B's own army** (`BValue`, patch 14; `Hooks.battleArmy1Human`, `army1Boost`, `army1BoostClP`): army 1 is an NPC in vanilla (`FACTIONS.otherFaction()`). Between two humans every boost of army 1's divisions, soldiers and faction reads B's city report as it was at Engage (`OwnBattle.otherReport`; a battle's load clears the reports, so the engine keeps that one). B's divisions' men, kit and training already came from B's report (SOS-32, patch 14). Names, banners and formation: not changed (army 1 shows the battle PC's banners).
- **D. Retreat and the result** (`Session.retreatSide`, `MapBattles`, patch 99-map-battles; `Hooks.worldPromptOpened`, `BattleOutcome`): only a side's own PC retreats it (against an NPC only A's; another PC's Retreat is skipped on every PC). When B retreats, army 0 wins and army 1 pays vanilla's retreat penalty mirrored, with none captured. The result prompt asks the winner: A on a victory, else B. `Engine.battleOutcomeTake()` gives the mod the winner, loser, retreat and losses once, for the loser's window. Each side's losses reach its own city through the division owner's transactions (patch 14's `relDeath`, ADR 0005), as for any human's stored division. B's panels read from B's side (`resultForArmy1`, `concludeTitle`; display only).
- **E. The sally** (`Resolver.besige`, patch 15; `worldPromptSally`): when a human besieges another human's region with soldiers in it, the defender is asked to sally first: Fight engages a battle between the two humans built on the defender's PC (army 1 the besieger's); Fall Back, or no answer in 30 s, lets the siege go on to the besieger's prompt. Auto is greyed out and refused.

### Not in this milestone

The battle speed vote; allies joining a battle between humans; resync in a battle (plan 0006 M3); several humans on one side; B choosing captives and spoils when B wins (vanilla's defaults are shipped to B); army 1's names, banners and formation; a capital's siege and assault.

### Results

Rig, 3 humans on Mogus-72 (`--make-humans 2,24`: slots 0, 2 and 24; copy 1 = slot 0, the host; copy 3 = slot 24), 2026-10-28. Every run: all copies identical at every step, engine hashes agree, none missed.

- **W83A** (16,000 steps): slot 24's army meets slot 0's. Auto refused; one side pressed Fight, the other didn't: the battle engaged after 30 s, built on slot 24's PC (the attacker).
- **W83B** (16,000): both sides pressed Start; the host's AI general gave army 1 no orders (`notcommanded` skips on every copy).
- **W83C** (13,000): army 1 retreated: slot 24 won and got the result prompt (`victory accept`). A second battle, built by slot 0's PC, where army 0 retreated: the result went to slot 24 (army 1). The probe: slot 24's offence 1.173 as army 0 (live) and 1.173 as army 1 (read from its report).
- **W83D**: the field battle before the sally; slot 0's army retreated and slot 24 won, routed as in W83C. Stopped early; W83E repeats it with slot 0's armies disbanded.
- **W83E** (26,000): the sally: only slot 0 (the defender) was asked, Fight; the battle was built on slot 0's PC, slot 24's besieger (army 1) retreated, slot 0 won and accepted.
- **W83F, W83G** (16,000, 24,000): the sally declined (no answer, 30 s). **Bug found:** the besieger was never asked about its siege afterwards: the army stayed Besieging with no prompt to step 24,000. Vanilla's poll passes `first` as true on every siege order of the player, so each order asked the defender again. **Fixed** (patch 15, `PSiege`, `ResolverPlayer`): the defender is asked only when the order began the siege, and Fall Back polls the besieger's siege again.
- **W83H** (16,000): after the fix, the sally timed out at step 11,448 and the besieger (copy 3) got its siege prompt at step 13,849 (`siege wait`, the default).

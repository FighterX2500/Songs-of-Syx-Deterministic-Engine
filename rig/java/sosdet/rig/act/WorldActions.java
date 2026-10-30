package sosdet.rig.act;

import game.GAME;
import game.battle.div.Div;
import game.event.engine.EVENT_HANDLER;
import game.event.engine.Event;
import game.faction.FACTIONS;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.deal.Deal;
import game.faction.npc.FactionNPC;
import game.faction.player.emmi.EmiTypeRoy;
import game.faction.royalty.Royalty;
import game.faction.royalty.opinion.ROPINION;
import game.boosting.superb.SuperSpec.SuperSpecImp;
import game.raiding.Raider;
import game.raiding.RaidingCurrent;
import init.race.RACES;
import init.trade.TR;
import settlement.stats.STATS;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.game.cmd.Diplomacy;
import sosdet.game.cmd.Events;
import sosdet.game.cmd.World;
import sosdet.game.deal.DealBook;
import sosdet.rig.Rig;
import world.WORLD;
import world.army.AD;
import world.army.ADDiv;
import world.army.WDivRegional;
import world.entity.army.WArmy;
import world.entity.army.WArmyState;
import world.map.regions.Region;
import world.region.RD;
import world.region.building.RDBuilding;

/**
 * The rig's scripted player: the world map, diplomacy and event answers, each the call the game's UI makes (SOS-35).
 * Where the hook sits at the UI's call site, the action makes the hooked call as that site does. An army is the
 * player's army by its place in the faction's list (-1 the last); "the NPC" is the first active NPC faction by index;
 * "the capital" is the player's capital region. Names are one word.
 */
public final class WorldActions {

    private WorldActions() {
    }

    /** sallyother: waiting for a world prompt that asks another human (test tooling: one rig process, one run). */
    private static boolean sallyAwaiting;

    /**
     * After each frame, outside the step, as the prompt answers: with {@code sallyother} queued, sallies out once a
     * world-battle prompt that asks another human is open, such as the besieger's siege window (SOS-92).
     */
    public static void afterFrame() {
        if (!sallyAwaiting || Hooks.worldPrompt() == null || Hooks.worldPromptHuman() == FACTIONS.player().index()) {
            return;
        }
        sallyAwaiting = false;
        String why = Engine.sallyRefusal();
        if (why != null) {
            Rig.note("sally during slot " + Hooks.worldPromptHuman() + "'s prompt refused: " + why);
            return;
        }
        Engine.sallyOut();
        Rig.note("sallies out during slot " + Hooks.worldPromptHuman() + "'s prompt "
                + Hooks.worldPrompt().getClass().getName() + " at step " + Engine.step());
    }

    /** Queues the action named by {@code a[0]} to run outside the step; false if this area has no such action. */
    public static boolean run(String[] a) {
        switch (a[0]) {
        case "wmove": { // wmove <army> <dx> <dy>: the army tool's move order, to a tile this far from the army
            int army = Integer.parseInt(a[1]);
            int dx = Integer.parseInt(a[2]);
            int dy = Integer.parseInt(a[3]);
            Rig.later(() -> {
                WArmy w = army(army);
                w.setDestination(w.ctx() + dx, w.cty() + dy);
            });
            return true;
        }
        case "wstop": { // wstop <army>: the army panel's stop button
            int army = Integer.parseInt(a[1]);
            Rig.later(() -> army(army).stop());
            return true;
        }
        case "wraid": { // wraid <army> 1|0: the army panel's raid toggle
            int army = Integer.parseInt(a[1]);
            boolean raid = a[2].equals("1");
            Rig.later(() -> army(army).raid(raid));
            return true;
        }
        case "wintercept": { // wintercept <army> <other army>: the army tool on another army (may start a battle)
            int army = Integer.parseInt(a[1]);
            int other = Integer.parseInt(a[2]);
            Rig.later(() -> army(army).intercept(army(other)));
            return true;
        }
        case "warmystate": { // warmystate <army>: notes the army's state, tile, path and target (a probe for the run log)
            int army = Integer.parseInt(a[1]);
            Rig.later(() -> {
                WArmy w = army(army);
                WArmy t = w.intercepting();
                Rig.note("army " + w.armyIndex() + " " + w.state().name(w) + " at " + w.ctx() + "," + w.cty() + " path "
                        + (w.path().isValid() ? w.path().destX() + "," + w.path().destY() + " left " + w.path().remaining() : "none")
                        + (t == null ? "" : " target " + t.armyIndex() + " at " + t.ctx() + "," + t.cty() + " " + t.state().name(t))
                        // SOS-71: whether a world-battle prompt holds it in place, and where it stands below a tile
                        + " step " + Engine.step() + " held " + Hooks.armyHeld(w) + " prompt "
                        + (Hooks.worldPrompt() == null ? "none" : Hooks.worldPrompt().getClass().getSimpleName() + " asks " + Hooks.worldPromptHuman())
                        + " px " + w.body().cX() + "," + w.body().cY() + " next " + w.path().x() + "," + w.path().y()
                        + " men " + AD.men(null).get(w));
            });
            return true;
        }
        case "wsprite": { // wsprite <army>: what the army's sprite asks each frame it is drawn (WPath.moving), on this PC only (PT3)
            int army = Integer.parseInt(a[1]);
            Rig.later(() -> {
                WArmy w = army(army);
                w.path().moving(w.body());
            });
            return true;
        }
        case "wcorrupt": { // wcorrupt [army]: a test desync, on this PC only (SOS-52): the second human's rebellion
            // timer +1000 s (in the world hash, kept in SOSDET_EVENTS); with an army, also that army's move remainder
            // (the PT3 kind, outside the hash until the army moves)
            int army = a.length > 1 ? Integer.parseInt(a[1]) : -1;
            Rig.later(() -> {
                try {
                    java.lang.reflect.Field timer = game.events.world.EventWorldRebellion.class.getDeclaredField("timer");
                    timer.setAccessible(true);
                    double[] t = (double[]) timer.get(GAME.events().rebellion);
                    t[1] += 1000;
                    if (army >= 0) {
                        java.lang.reflect.Field movement = world.map.pathing.WPath.class.getDeclaredField("movement");
                        movement.setAccessible(true);
                        movement.setFloat(army(army).path(), 0.9f);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("SOSDET rig: wcorrupt", e);
                }
                Rig.note("corrupted step " + Engine.step() + " rebellion timer of human 2" + (army >= 0 ? ", army " + army + " remainder" : ""));
            });
            return true;
        }
        case "wstatus": // wstatus: notes the player's slot, treasury, capital, regions, armies and wars (a probe for an agent player)
            Rig.later(() -> Rig.note(status()));
            return true;
        case "wtrade": // wtrade: notes each human's trade partners (the capitals its traders reach, SOS-77) and its stance with
            // every other human, read as that human between steps (SOS-101)
            Rig.later(() -> Rig.note(tradeRoutes()));
            return true;
        case "wprofile": // wprofile: turns the step profile on (SOS-106), then each call notes each game part's mean ms a step since the last
            Rig.later(() -> Rig.note(Engine.stepProfile()));
            Engine.profileSteps(true);
            return true;
        case "wprod": // wprod: notes the goods window's production and consumption totals of the first goods, and how many
            // workplaces count in them (SOS-102), as that window reads them between steps
            Rig.later(() -> Rig.note(production()));
            return true;
        case "wcredits": // wcredits: notes the player's credits in and out by kind, today and yesterday (a treasury drop's cause)
            Rig.later(() -> Rig.note(credits()));
            return true;
        case "wrelations": { // wrelations [npc slot]: notes the NPC's king, and its stance, stored opinion (each entry), dealings, opinion and trust toward
            // each human, read as that human between steps (the NPC by default: the one wgift gives to; SOS-46 M3)
            int slot = a.length > 1 ? Integer.parseInt(a[1]) : -1;
            Rig.later(() -> Rig.note(relations(slot < 0 ? npc() : npcAt(slot))));
            return true;
        }
        case "wwar": // wwar: notes each human's cached potential enemies (DipWarPlayer, as the next war timer reads them), each
            // with its trust, and the war timer, read as that human between steps (SOS-46 playtest PT1)
            Rig.later(() -> Rig.note(war()));
            return true;
        case "wevents": // wevents: notes each human's war timer and warner, rebellion timer and realm cursor, raid and raid
            // timer, each read as that human between steps (SOS-46 M4)
            Rig.later(() -> Rig.note(events()));
            return true;
        case "wwarnear": { // wwarnear <army> [stronger] [still]: war declared on the faction of the nearest NPC army (stronger
            // and still as for winterceptnear), by the faction screen's deal with Declare war (SOS-32; the attack tool's
            // own-region rule refuses the old route through it, SOS-46)
            int army = Integer.parseInt(a[1]);
            java.util.List<String> flags = java.util.Arrays.asList(a).subList(2, a.length);
            boolean stronger = flags.contains("stronger");
            boolean still = flags.contains("still");
            Rig.later(() -> {
                WArmy w = army(army);
                WArmy best = null;
                long bestD = Long.MAX_VALUE;
                for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
                    WArmy e = WORLD.ENTITIES().armies.tryGet(i);
                    if (e == null || !(e.faction() instanceof FactionNPC) || AD.men(null).get(e) == 0
                            || ((FactionNPC) e.faction()).capitolRegion() == null
                            || (stronger && AD.power().get(e) <= AD.power().get(w))
                            || (still && e.state() != WArmyState.fortified && e.state() != WArmyState.fortifying)) {
                        continue;
                    }
                    long dx = e.ctx() - w.ctx();
                    long dy = e.cty() - w.cty();
                    if (dx * dx + dy * dy < bestD) {
                        bestD = dx * dx + dy * dy;
                        best = e;
                    }
                }
                if (best == null) {
                    throw new IllegalStateException("no NPC army");
                }
                Rig.note("war on faction " + best.faction().index() + ", whose army " + best.armyIndex() + " is at distance "
                        + (long) Math.sqrt(bestD));
                Deal d = new Deal();
                d.setFactionAndClear((FactionNPC) best.faction());
                d.bools.WAR.set(true);
                if (!d.canBeAccepted()) {
                    throw new IllegalStateException("faction " + best.faction().index() + " refuses the war declaration");
                }
                if (!Diplomacy.dealAccept(d)) {
                    throw new IllegalStateException("no session");
                }
            });
            return true;
        }
        case "winterceptnear": { // winterceptnear <army> [stronger] [still] [slot=<n>]: wintercept on the nearest army at war
            // with the player (a field battle, SOS-32); stronger: only an army of more power, which fights instead of
            // withdrawing; still: only a fortified or fortifying army (a chase after a marching army re-paths and never
            // closes); slot=<n>: only an army of the faction in slot n, such as another human's (SOS-83)
            int army = Integer.parseInt(a[1]);
            java.util.List<String> flags = java.util.Arrays.asList(a).subList(2, a.length);
            boolean stronger = flags.contains("stronger");
            boolean still = flags.contains("still");
            int slot = -1;
            for (String f : flags) {
                if (f.startsWith("slot=")) {
                    slot = Integer.parseInt(f.substring(5));
                }
            }
            int onlySlot = slot;
            Rig.later(() -> {
                WArmy w = army(army);
                WArmy best = null;
                long bestD = Long.MAX_VALUE;
                for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
                    WArmy e = WORLD.ENTITIES().armies.tryGet(i);
                    if (e == null || e.faction() == FACTIONS.player() || AD.men(null).get(e) == 0
                            || (onlySlot >= 0 && (e.faction() == null || e.faction().index() != onlySlot))
                            || (e.faction() != null && !DIP.WAR().is(e.faction(), FACTIONS.player()))
                            || (stronger && AD.power().get(e) <= AD.power().get(w))
                            || (still && e.state() != WArmyState.fortified && e.state() != WArmyState.fortifying)) {
                        continue;
                    }
                    long dx = e.ctx() - w.ctx();
                    long dy = e.cty() - w.cty();
                    if (dx * dx + dy * dy < bestD) {
                        bestD = dx * dx + dy * dy;
                        best = e;
                    }
                }
                if (best == null) {
                    throw new IllegalStateException("no army at war with the player");
                }
                Rig.note("intercepting army " + best.armyIndex() + " at distance " + (long) Math.sqrt(bestD) + ", power "
                        + AD.power().get(best) + " against " + AD.power().get(w));
                w.intercept(best);
            });
            return true;
        }
        case "wbesiege": { // wbesiege <army> <region index>: the army tool on a settlement (starts a siege)
            int army = Integer.parseInt(a[1]);
            int reg = Integer.parseInt(a[2]);
            Rig.later(() -> army(army).besiege(WORLD.REGIONS().getByIndex(reg)));
            return true;
        }
        case "wbesiegenear": { // wbesiegenear <army> [stronger]: wbesiege on the nearest other faction's settlement, one at peace
            // first; stronger: only a faction of more power than the army, whose defenders stand and fight (SOS-32)
            int army = Integer.parseInt(a[1]);
            boolean stronger = a.length > 2 && a[2].equals("stronger");
            Rig.later(() -> {
                WArmy w = army(army);
                Region best = null;
                long bestD = Long.MAX_VALUE;
                for (Region r : WORLD.REGIONS().active()) {
                    if (r.faction() == null || r.faction() instanceof game.faction.player.Player // no other human's (PT5)
                            || (stronger && AD.power().get(r.faction()) <= 2 * AD.power().get(w))) {
                        continue;
                    }
                    long dx = r.cx() - w.ctx();
                    long dy = r.cy() - w.cty();
                    // a faction at peace first: besieging it asks "declare war and besiege?" (cmd.StepPrompts)
                    long d = dx * dx + dy * dy + (DIP.WAR().is(r.faction(), FACTIONS.player()) ? 1L << 40 : 0);
                    if (d < bestD) {
                        bestD = d;
                        best = r;
                    }
                }
                if (best == null) {
                    throw new IllegalStateException("no other faction's settlement");
                }
                Rig.note("besieging region " + best.index() + " of faction " + best.faction().index() + ", power "
                        + AD.power().get(best.faction()) + " against " + AD.power().get(w));
                w.besiege(best);
            });
            return true;
        }
        case "wdisband": { // wdisband <army>: the army panel's disband button
            int army = Integer.parseInt(a[1]);
            Rig.later(() -> army(army).disband());
            return true;
        }
        case "warmyname": { // warmyname <army> <name>: the army panel's name input
            int army = Integer.parseInt(a[1]);
            String name = a[2];
            Rig.later(() -> {
                WArmy w = army(army);
                if (!World.armyName(w, name)) {
                    w.name.clear().add(name);
                }
            });
            return true;
        }
        case "wcreate": { // wcreate <army>: the army list's recruit placer, on that army's tile
            int army = Integer.parseInt(a[1]);
            Rig.later(() -> {
                WArmy w = army(army);
                if (!World.armyCreate(w.ctx(), w.cty())) {
                    WORLD.ENTITIES().armies.create(w.ctx(), w.cty(), FACTIONS.player());
                }
            });
            return true;
        }
        case "wreassign": { // wreassign <army> <division> <to army>: the army panel's reassign picker
            int army = Integer.parseInt(a[1]);
            int div = Integer.parseInt(a[2]);
            int to = Integer.parseInt(a[3]);
            Rig.later(() -> {
                WArmy w = army(army);
                WArmy target = army(to);
                if (!World.divsReassign(w, selected(w, div), target)) {
                    w.divs().get(div).reassign(target);
                }
            });
            return true;
        }
        case "wdivdisband": { // wdivdisband <army> <division>: the army panel's division disband button
            int army = Integer.parseInt(a[1]);
            int div = Integer.parseInt(a[2]);
            Rig.later(() -> {
                WArmy w = army(army);
                if (!World.divsReassign(w, selected(w, div), null)) {
                    w.divs().get(div).disband();
                }
            });
            return true;
        }
        case "wbanner": { // wbanner <army> <division> <banner>: the army panel's banner editor
            int army = Integer.parseInt(a[1]);
            int div = Integer.parseInt(a[2]);
            int banner = Integer.parseInt(a[3]);
            Rig.later(() -> {
                WArmy w = army(army);
                if (!World.divsBanner(w, selected(w, div), banner)) {
                    w.divs().get(div).bannerSet(banner);
                }
            });
            return true;
        }
        case "wdivorder": { // wdivorder <army> <place> <place>: a division card dragged onto another place
            int army = Integer.parseInt(a[1]);
            int after = Integer.parseInt(a[2]);
            int insert = Integer.parseInt(a[3]);
            Rig.later(() -> army(army).divs().insert(after, insert));
            return true;
        }
        case "wtraining": { // wtraining <army> <training index> <0..1>: the editor, on the army's first regional division
            int army = Integer.parseInt(a[1]);
            int t = Integer.parseInt(a[2]);
            double v = Double.parseDouble(a[3]);
            Rig.later(() -> regional(army).target.trainingSet(STATS.BATTLE().TRAINING_ALL.get(t), v));
            return true;
        }
        case "wequip": { // wequip <army> <equipment index> <0..1>: the editor, on the army's first regional division
            int army = Integer.parseInt(a[1]);
            int e = Integer.parseInt(a[2]);
            double v = Double.parseDouble(a[3]);
            Rig.later(() -> regional(army).target.equipSet(STATS.EQUIP().BATTLE_ALL().get(e), v));
            return true;
        }
        case "wmen": { // wmen <army> <men>: the editor's size, on the army's first regional division
            int army = Integer.parseInt(a[1]);
            int men = Integer.parseInt(a[2]);
            Rig.later(() -> regional(army).target.menSet(men));
            return true;
        }
        case "wrace": { // wrace <army> <race index>: the editor's race, on the army's first regional division
            int army = Integer.parseInt(a[1]);
            int race = Integer.parseInt(a[2]);
            Rig.later(() -> regional(army).target.raceSet(RACES.all().get(race)));
            return true;
        }
        case "wrbanner": { // wrbanner <army> <banner>: the editor's banner, on the army's first regional division
            int army = Integer.parseInt(a[1]);
            int banner = Integer.parseInt(a[2]);
            Rig.later(() -> regional(army).target.bannerISet(banner));
            return true;
        }
        case "wartillery": { // wartillery <army> <artillery index> <amount>: the army panel's artillery slider
            int army = Integer.parseInt(a[1]);
            int art = Integer.parseInt(a[2]);
            int v = Integer.parseInt(a[3]);
            Rig.later(() -> AD.supplies().arts().get(art).target.set(army(army), v));
            return true;
        }
        case "wrecruit": { // wrecruit <army> <from army>: the recruit button, with the editor set as that army's first regional division
            int army = Integer.parseInt(a[1]);
            int from = Integer.parseInt(a[2]);
            Rig.later(() -> {
                WArmy w = army(army);
                WDivRegional spec = regional(from);
                if (!w.divs().canAdd() || !World.recruit(w, spec.target)) {
                    throw new IllegalStateException("no room in the army, or no session");
                }
            });
            return true;
        }
        case "wmerc": { // wmerc <army> <mercenary index>: the mercenary screen's recruit button
            int army = Integer.parseInt(a[1]);
            int merc = Integer.parseInt(a[2]);
            Rig.later(() -> {
                if (!World.mercHire(army(army), merc)) {
                    throw new IllegalStateException("no session");
                }
            });
            return true;
        }
        case "wattach": { // wattach <army> <city division>: the city army screen's send out
            int army = Integer.parseInt(a[1]);
            int div = Integer.parseInt(a[2]);
            Rig.later(() -> AD.cityDivs().attach(army(army), cityDiv(div)));
            return true;
        }
        case "wrecall": { // wrecall <city division>: the city army screen's recall
            int div = Integer.parseInt(a[1]);
            Rig.later(() -> AD.cityDivs().attach(null, cityDiv(div)));
            return true;
        }
        case "wregionname": { // wregionname <name>: the region panel's name input, on the capital
            String name = a[1];
            Rig.later(() -> {
                Region reg = capital();
                if (!World.regionName(reg, name)) {
                    reg.info.name().clear().add(name);
                }
            });
            return true;
        }
        case "wedict": { // wedict <race index> <edict index> <1|0>: the population panel's edict box, on the capital
            int race = Integer.parseInt(a[1]);
            int edict = Integer.parseInt(a[2]);
            int v = Integer.parseInt(a[3]);
            Rig.later(() -> {
                if (!World.regionEdict(capital(), RD.RACES().all.get(race), RD.RACES().edicts.all.get(edict), v)) {
                    throw new IllegalStateException("no session");
                }
            });
            return true;
        }
        case "wsqueeze": // wsqueeze: the output panel's squeeze button, on the capital
            Rig.later(() -> {
                if (!World.regionSqueeze(capital())) {
                    RD.OUTPUT().squeze(capital());
                }
            });
            return true;
        case "wbuilding": { // wbuilding <building index> <level>: the building popup, one level drafted and accepted, on the capital
            int b = Integer.parseInt(a[1]);
            int level = Integer.parseInt(a[2]);
            Rig.later(() -> {
                Region reg = capital();
                if (b < 0 || b >= RD.BUILDINGS().all.size()) { // the list answers null past its end (PT4 crash)
                    throw new IllegalArgumentException("no building " + b);
                }
                RDBuilding building = RD.BUILDINGS().all.get(b);
                RD.BUILDINGS().tmp(true, reg).levelSet(building, level);
                if (!World.regionBuildings(reg)) {
                    throw new IllegalStateException("no session");
                }
            });
            return true;
        }
        case "wautonomy": // wautonomy <region index>: the region panel's release button (loses the region)
        case "wabandon": { // wabandon <region index>: the region panel's abandon button (loses the region)
            boolean autonomy = a[0].equals("wautonomy");
            int reg = Integer.parseInt(a[1]);
            Rig.later(() -> {
                Region r = WORLD.REGIONS().getByIndex(reg);
                if (!(autonomy ? World.regionAutonomy(r) : World.regionAbandon(r))) {
                    throw new IllegalStateException("no session");
                }
            });
            return true;
        }
        case "wattack": { // wattack <region index> <world army index> <1|0 declare war>: the attack tool on the player's
            // region, its garrison attacking a foreign army in it (starts a battle)
            int reg = Integer.parseInt(a[1]);
            int army = Integer.parseInt(a[2]);
            boolean war = a[3].equals("1");
            Rig.later(() -> {
                WArmy target = WORLD.ENTITIES().armies.tryGet(army);
                if (target == null) {
                    throw new IllegalStateException("no world army " + army);
                }
                if (!World.regionAttack(WORLD.REGIONS().getByIndex(reg), target, war)) {
                    throw new IllegalStateException("no session");
                }
            });
            return true;
        }
        case "wgift": { // wgift <denari>: the faction screen's deal with the NPC, the player giving denari, accepted
            int credits = Integer.parseInt(a[1]);
            Rig.later(() -> {
                Deal d = new Deal();
                d.setFactionAndClear(npc());
                d.player.credits.set(credits);
                if (!d.canBeAccepted()) {
                    throw new IllegalStateException("the NPC refuses the gift");
                }
                if (!Diplomacy.dealAccept(d)) {
                    d.execute(true);
                }
            });
            return true;
        }
        case "wpeace": // wpeace: the faction screen's peace deal with the strongest NPC at war with the player, with the
            // denari it asks, accepted (SOS-46)
            Rig.later(() -> {
                FactionNPC f = null;
                for (FactionNPC ff : DIP.WAR().player()) {
                    if (f == null || AD.power().get(ff) > AD.power().get(f)) {
                        f = ff;
                    }
                }
                if (f == null) {
                    throw new IllegalStateException("the player is at war with no NPC");
                }
                Deal d = new Deal();
                d.setFactionAndClear(f);
                d.bools.PEACE.set(true);
                int asked = Math.max(0, (int) -d.valueCredits() + 1);
                d.player.credits.set(asked);
                if (!d.canBeAccepted()) {
                    throw new IllegalStateException("faction " + f.index() + " refuses peace for " + asked + " denari");
                }
                Rig.note("peace with faction " + f.index() + " for " + asked + " denari");
                if (!Diplomacy.dealAccept(d)) {
                    d.execute(true);
                }
            });
            return true;
        case "wproposal": { // wproposal <happiness>: the NPC's proposal message accepted (an empty deal; skipped without a request)
            double happiness = Double.parseDouble(a[1]);
            Rig.later(() -> {
                Deal d = new Deal();
                d.setFactionAndClear(npc());
                if (!Diplomacy.dealProposal(d, happiness)) {
                    throw new IllegalStateException("no session");
                }
            });
            return true;
        }
        case "wdecline": // wdecline: an NPC message declined (no effect without a request)
            Rig.later(() -> npc().request.expire());
            return true;
        case "wemissary": { // wemissary <type among the royal ones> <amount>: the emissary slider, on the NPC's king
            int type = Integer.parseInt(a[1]);
            int v = Integer.parseInt(a[2]);
            Rig.later(() -> {
                EmiTypeRoy t = FACTIONS.player().emissaries.roys.get(type);
                t.set(npc().king(), v);
            });
            return true;
        }
        case "weventchoice": { // weventchoice <choice>: the current event's message, one choice
            int choice = Integer.parseInt(a[1]);
            Rig.later(() -> {
                EVENT_HANDLER h = GAME.EVENT();
                Event e = h.current();
                if (e == null || !Events.eventChoice(e, choice, h.occ(e))) {
                    throw new IllegalStateException("no current event, or no session");
                }
            });
            return true;
        }
        case "wraiderpay": // wraiderpay: the current raider's demand paid, with nothing asked (skipped without one)
            Rig.later(() -> {
                Raider r = currentRaider();
                if (r == null || !Events.raiderPay(r, 0, new int[TR.ALL().size()])) {
                    throw new IllegalStateException("no current raider, or no session");
                }
            });
            return true;
        case "wallyhelp": // wallyhelp: an ally's help against the current raider asked for
            Rig.later(() -> {
                Raider r = currentRaider();
                if (r == null || !Events.raiderAllyHelp(r)) {
                    throw new IllegalStateException("no current raider, or no session");
                }
            });
            return true;
        case "dealpropose": { // dealpropose <slot> none|peace|trade|pact|ally|cancel <give denari> <ask denari> [<goods>]: a
            // deal to the human in <slot> (SOS-72); <goods>: that many of the goods this city has most of to trade
            int slot = Integer.parseInt(a[1]);
            int treaty = treaty(a[2]);
            int give = Integer.parseInt(a[3]);
            int ask = Integer.parseInt(a[4]);
            int goods = a.length > 5 ? Integer.parseInt(a[5]) : 0;
            Rig.later(() -> {
                int[] pairs = goods == 0 ? new int[0] : new int[] {mostTradable().index(), goods};
                DealBook.Terms t = new DealBook.Terms(treaty, give, ask, pairs, new int[0]);
                String why = Engine.dealRefusal(slot, t);
                if (why != null) {
                    throw new IllegalStateException("deal refused: " + why);
                }
                Engine.dealPropose(slot, t);
                Rig.note("proposed to slot " + slot + ": treaty " + a[2] + ", gives " + give + " denari and "
                        + (goods == 0 ? "no goods" : goods + " " + TR.ALL().get(pairs[0]).name) + ", asks " + ask + " denari");
            });
            return true;
        }
        case "dealregions": { // dealregions <slot> give|ask <n>: a deal giving the human in <slot> n of this human's
            // regions, or asking n of theirs, each the first the rule allows (SOS-73)
            int slot = Integer.parseInt(a[1]);
            boolean give = a[2].equals("give");
            int n = Integer.parseInt(a[3]);
            Rig.later(() -> {
                int me = FACTIONS.player().index();
                int giver = give ? me : slot;
                int receiver = give ? slot : me;
                int[] chosen = new int[0];
                game.faction.Faction g = FACTIONS.getByIndex(giver);
                for (int i = 0; i < g.realm().regions() && chosen.length < n; i++) {
                    int r = g.realm().region(i).index();
                    if (Engine.dealRegionRefusal(giver, receiver, r, chosen) == null) {
                        chosen = java.util.Arrays.copyOf(chosen, chosen.length + 1);
                        chosen[chosen.length - 1] = r;
                    }
                }
                if (chosen.length < n) {
                    throw new IllegalStateException("only " + chosen.length + " of " + n + " regions can be given");
                }
                DealBook.Terms t = new DealBook.Terms(DealBook.NO_TREATY, 0, 0, new int[0], new int[0],
                        give ? chosen : new int[0], give ? new int[0] : chosen, DealBook.DEFAULT_TRIBUTE);
                String why = Engine.dealRefusal(slot, t);
                if (why != null) {
                    throw new IllegalStateException("deal refused: " + why);
                }
                Engine.dealPropose(slot, t);
                Rig.note("proposed to slot " + slot + ": " + (give ? "gives" : "asks") + " regions " + java.util.Arrays.toString(chosen));
            });
            return true;
        }
        case "dealvassal": { // dealvassal <slot> protector|vassal <percent>: proposes that this human becomes the overlord
            // (protector) or the vassal of the human in <slot>, with a yearly tribute of <percent> (SOS-73)
            int slot = Integer.parseInt(a[1]);
            int treaty = treaty(a[2]);
            int percent = Integer.parseInt(a[3]);
            Rig.later(() -> {
                DealBook.Terms t = new DealBook.Terms(treaty, 0, 0, new int[0], new int[0], new int[0], new int[0], percent);
                String why = Engine.dealRefusal(slot, t);
                if (why != null) {
                    throw new IllegalStateException("deal refused: " + why);
                }
                Engine.dealPropose(slot, t);
                Rig.note("proposed to slot " + slot + ": " + a[2] + " with tribute " + percent + "%");
            });
            return true;
        }
        case "dealanswer": { // dealanswer yes|no: answers the oldest open proposal made to this PC's human (SOS-72)
            boolean yes = a[1].equals("yes");
            Rig.later(() -> {
                int me = FACTIONS.player().index();
                for (DealBook.Proposal p : Engine.deals()) {
                    if (p.to() == me) {
                        String why = yes ? Engine.dealAcceptRefusal(p.id()) : null;
                        Rig.note("answers deal " + p.id() + " from slot " + p.from() + ": " + (yes ? "yes" : "no")
                                + (why == null ? "" : " (" + why + ")"));
                        Engine.dealAnswer(p.id(), yes);
                        return;
                    }
                }
                throw new IllegalStateException("no open proposal to slot " + me);
            });
            return true;
        }
        case "dealcancel": { // dealcancel <slot>: cancels every treaty with the human in <slot> at once (SOS-76)
            int slot = Integer.parseInt(a[1]);
            Rig.later(() -> {
                String why = Engine.dealCancelRefusal(slot);
                if (why != null) {
                    throw new IllegalStateException("cancel refused: " + why);
                }
                Engine.dealCancel(slot);
                Rig.note("cancels every treaty with slot " + slot);
            });
            return true;
        }
        case "wsiege": // wsiege: notes each human's capital siege: since, days to the assault, Storm's refusal, and this human's sally (SOS-92)
            Rig.later(() -> Rig.note(siege()));
            return true;
        case "sally": // sally: this human sallies out of its besieged capital (SOS-92); refused when it can't
            Rig.later(() -> {
                String why = Engine.sallyRefusal();
                if (why != null) {
                    throw new IllegalStateException("sally refused: " + why);
                }
                Engine.sallyOut();
                Rig.note("sallies out");
            });
            return true;
        case "siegewait": { // siegewait <slot>: the siege of the human in <slot>'s capital began long enough ago that its
            // wait has passed, at its step on every copy (test tooling, SOS-92 B)
            int slot = Integer.parseInt(a[1]);
            Rig.later(() -> {
                sosdet.game.cmd.Sieges.waitPassedForTest(slot);
                Rig.note("siege wait of slot " + slot + " passed");
            });
            return true;
        }
        case "wassault": // wassault: notes the assault running, the last one over and a throne awaiting terms (SOS-92 B, D)
            Rig.later(() -> {
                sosdet.Assault last = Engine.lastAssault();
                Rig.note("wassault step " + Engine.step() + ": now " + Engine.assault() + "; last " + last + "; invading "
                        + settlement.main.SETT.INVADOR().invading() + "; taken " + Engine.assaultTaken()
                        + (last != null && last.termsKind() >= 0 ? "; terms " + sosdet.game.siege.AssaultTerms.name(last.termsKind())
                                + " " + last.termsPercent() + "% credits " + last.termsCredits() : ""));
            });
            return true;
        case "wassaultend": { // wassaultend <held|taken|retreat>: the running assault's invasion ends so, on every copy (test tooling, SOS-92 D)
            int outcome = java.util.List.of("held", "taken", "retreat").indexOf(a[1]);
            if (outcome < 0) {
                throw new IllegalArgumentException("wassaultend held|taken|retreat, not " + a[1]);
            }
            Rig.later(() -> {
                sosdet.game.cmd.Sieges.endForTest(outcome);
                Rig.note("assault end asked: " + a[1]);
            });
            return true;
        }
        case "wterms": { // wterms <sack|vassalage> <percent>: this human, the attacker, chooses what to take (SOS-92 D)
            int kind = a[1].equals("sack") ? sosdet.game.siege.AssaultTerms.SACK : sosdet.game.siege.AssaultTerms.VASSALAGE;
            int percent = Integer.parseInt(a[2]);
            Rig.later(() -> {
                String why = sosdet.game.cmd.Sieges.termsRefusal(kind, percent);
                if (why != null) {
                    throw new IllegalStateException("terms refused: " + why);
                }
                sosdet.game.cmd.Sieges.terms(kind, percent);
                Rig.note("terms chosen: " + a[1] + " " + percent + "%");
            });
            return true;
        }
        case "sallyother": // sallyother: this human sallies out once a world prompt asking another human is open (SOS-92)
            sallyAwaiting = true;
            return true;
        case "dealwar": { // dealwar <slot>: declares war on the human in <slot> at once (SOS-74)
            int slot = Integer.parseInt(a[1]);
            Rig.later(() -> {
                String why = Engine.warRefusal(slot);
                if (why != null) {
                    throw new IllegalStateException("war refused: " + why);
                }
                Engine.declareWar(slot);
                Rig.note("declares war on slot " + slot);
            });
            return true;
        }
        case "wbesiegehuman": { // wbesiegehuman <army> <slot> [capitol]: wbesiege on the human in <slot>'s region nearest the
            // army, one that isn't its capitol (or, with capitol, its capitol: refused, SOS-74)
            int army = Integer.parseInt(a[1]);
            int slot = Integer.parseInt(a[2]);
            boolean capitol = a.length > 3 && a[3].equals("capitol");
            Rig.later(() -> {
                WArmy w = army(army);
                Region best = null;
                long bestD = Long.MAX_VALUE;
                for (Region r : WORLD.REGIONS().active()) {
                    if (r.faction() == null || r.faction().index() != slot || r.capitol() != capitol) {
                        continue;
                    }
                    long dx = r.cx() - w.ctx();
                    long dy = r.cy() - w.cty();
                    if (dx * dx + dy * dy < bestD) {
                        bestD = dx * dx + dy * dy;
                        best = r;
                    }
                }
                if (best == null) {
                    throw new IllegalStateException("slot " + slot + " has no such region");
                }
                Rig.note("army " + army + " besieges region " + best.index() + " of slot " + slot + (capitol ? " (its capitol)" : ""));
                w.besiege(best);
            });
            return true;
        }
        case "tariff": { // tariff <slot> <percent>: sets this human's tariff on the human in <slot> at once (SOS-77)
            int slot = Integer.parseInt(a[1]);
            int percent = Integer.parseInt(a[2]);
            Rig.later(() -> {
                String why = Engine.tariffRefusal(slot, percent);
                if (why != null) {
                    throw new IllegalStateException("tariff refused: " + why);
                }
                Engine.setTariff(slot, percent);
                Rig.note("sets the tariff on slot " + slot + " to " + percent + "%");
            });
            return true;
        }
        case "wtradeinfo": // wtradeinfo: notes this city's goods for sale (amount at price), the goods it would buy below its
            // price cap, its trade partners with distance and toll, and the goods it owns (SOS-77: to pick an export one human has that another imports)
            Rig.later(() -> {
                game.faction.player.Player me = FACTIONS.player();
                StringBuilder b = new StringBuilder("trade of slot ").append(me.index()).append(": sells");
                for (init.trade.TRADABLE t : TR.ALL()) {
                    int n = me.seller(t).removeMax();
                    if (n > 0) {
                        b.append(' ').append(t.key()).append(':').append(n).append('@').append(me.seller(t).removePrice(1));
                    }
                }
                b.append("; buys");
                for (init.trade.TRADABLE t : TR.ALL()) {
                    if (me.buyer(t).buyPriority(1, 1) > 0) {
                        b.append(' ').append(t.key()).append("<").append(me.buyer(t).priceCapsI.get()).append(" min$")
                                .append(me.buyer(t).minMoney.get());
                    }
                }
                b.append("; partners");
                for (world.map.pathing.WRegFinder.RegDist p : world.region.RD.DIST().tradePartners(me)) {
                    if (p.reg.faction() != me && p.reg.faction() != null) {
                        b.append(' ').append(p.reg.faction().index()).append(" at ").append(p.distance).append(" toll ")
                                .append((int) game.faction.trade.TradeManager.toll(p.reg.faction(), me, p.distance));
                    }
                }
                b.append("; owns");
                for (init.trade.TRADABLE t : TR.ALL()) {
                    if (me.seller(t).playerOwned() > 0) {
                        b.append(' ').append(t.key()).append(':').append(me.seller(t).playerOwned());
                    }
                }
                Rig.note(b.toString());
            });
            return true;
        case "transfers": { // transfers <step>: notes the kept transfers made up to <step>: how many, a checksum, and
            // the last 3. Every copy past <step> notes the same (SOS-81)
            long upTo = Long.parseLong(a[1]);
            Rig.later(() -> {
                java.util.List<sosdet.txn.TransferLog.Transfer> out = new java.util.ArrayList<>();
                Engine.transfers(Math.max(0, Engine.lastTransfer() - sosdet.txn.TransferLog.CAPACITY), out);
                out.removeIf(t -> t.step() > upTo);
                long sum = 0;
                for (sosdet.txn.TransferLog.Transfer t : out) {
                    sum = 31 * sum + t.txnId() + t.human() * 7L + t.kind().ordinal() + t.goods() * 13L
                            + Double.doubleToLongBits(t.amount()) + t.price() + t.reason() * 17L + t.other();
                }
                StringBuilder b = new StringBuilder("transfers up to step ").append(upTo).append(": ").append(out.size())
                        .append(", checksum ").append(Long.toHexString(sum)).append(", last");
                for (int i = Math.max(0, out.size() - 3); i < out.size(); i++) {
                    sosdet.txn.TransferLog.Transfer t = out.get(i);
                    b.append(" [").append(t.step()).append(' ').append(t.human()).append(' ').append(t.kind()).append(' ')
                            .append(t.goods()).append(' ').append((long) t.amount()).append(' ').append(t.price()).append(' ')
                            .append(t.reason()).append(' ').append(t.other()).append(']');
                }
                Rig.note(b.toString());
            });
            return true;
        }
        case "deals": { // deals: notes the open proposals, the latest answers, this human's treaties with each other
            // human and its treasury (a probe for the run log, SOS-72)
            Rig.later(() -> {
                StringBuilder b = new StringBuilder("deals at second ").append((int) game.time.TIME.currentSecond()).append(": open");
                for (DealBook.Proposal p : Engine.deals()) {
                    b.append(" [").append(p.id()).append(' ').append(p.from()).append('>').append(p.to()).append(" treaty ")
                            .append(p.terms().treaty).append(" expires ").append(p.expires()).append(']');
                }
                b.append("; answers");
                for (DealBook.Answer x : Engine.dealAnswers()) {
                    b.append(" [").append(x.id()).append(' ').append(x.outcome()).append(']');
                }
                game.faction.player.Player me = FACTIONS.player();
                b.append("; slot ").append(me.index()).append(" treasury ").append((long) me.credits().credits());
                b.append(" regions ").append(me.realm().regions());
                game.faction.Faction lord = DIP.overlord(me);
                if (lord != null) {
                    b.append(" vassal of ").append(lord.index()).append(" at ").append(Engine.tributePercent(me.index(), lord.index())).append('%');
                }
                for (int h : Hooks.humanSlots()) {
                    if (h != me.index()) {
                        b.append("; with ").append(h).append(' ').append(DIP.get(me, FACTIONS.getByIndex(h)).key());
                        b.append(" tariff ").append(Engine.tariff(me.index(), h)).append('/').append(Engine.tariff(h, me.index()))
                                .append(" revenue ").append(Engine.tariffRevenue(me.index(), h, false));
                    }
                }
                Rig.note(b.toString());
            });
            return true;
        }
        default:
            return false;
        }
    }

    private static int treaty(String name) {
        switch (name) {
        case "none":
            return DealBook.NO_TREATY;
        case "peace":
            return DealBook.PEACE;
        case "trade":
            return DealBook.TRADE;
        case "pact":
            return DealBook.PACT;
        case "ally":
            return DealBook.ALLY;
        case "cancel":
            return DealBook.CANCEL_ALL;
        case "protector":
            return DealBook.PROTECTOR;
        case "vassal":
            return DealBook.VASSAL;
        default:
            throw new IllegalArgumentException("no treaty " + name);
        }
    }

    /** The goods this PC's city has most of to trade. */
    private static init.trade.TRADABLE mostTradable() {
        init.trade.TRADABLE best = null;
        for (init.trade.TRADABLE t : TR.ALL()) {
            if (best == null || FACTIONS.player().res().getAvailable(t) > FACTIONS.player().res().getAvailable(best)) {
                best = t;
            }
        }
        return best;
    }

    /** The player's credits by kind: in and out today, then yesterday; kinds with no flow are left out. */
    private static String credits() {
        game.faction.player.Player p = FACTIONS.player();
        StringBuilder b = new StringBuilder("credits slot ").append(p.index()).append(" treasury ")
                .append((long) p.credits().getD()).append(" | kind: in/out today, in/out yesterday");
        for (game.faction.player.PCredits.CredHistory h : p.credits().all()) {
            if (h.IN.get(0) == 0 && h.OUT.get(0) == 0 && h.IN.get(1) == 0 && h.OUT.get(1) == 0) {
                continue;
            }
            b.append(" | ").append(h.type).append(": ").append(h.IN.get(0)).append('/').append(h.OUT.get(0)).append(", ")
                    .append(h.IN.get(1)).append('/').append(h.OUT.get(1));
        }
        return b.toString();
    }

    /** The player's state in one line: what an agent player reads before it acts. */
    /** Each human's capital siege (SOS-92), and whether a goods deal with each could go now. */
    private static String siege() {
        StringBuilder b = new StringBuilder("siege:");
        for (int slot : Hooks.humanSlots()) {
            Region cap = FACTIONS.getByIndex(slot).capitolRegion();
            b.append(" slot ").append(slot).append(" since ").append(Engine.besiegedSince(slot)).append(" assault in ")
                    .append(Engine.assaultInDays(slot)).append(" days, storm: ")
                    .append(cap == null ? "no capital" : sosdet.game.cmd.Sieges.stormRefusal(cap));
            if (slot != FACTIONS.player().index()) {
                DealBook.Terms goods = new DealBook.Terms(DealBook.NO_TREATY, 0, 0, new int[] {0, 1}, new int[0]);
                b.append(", a goods deal: ").append(Engine.dealRefusal(slot, goods));
            }
            b.append(';');
        }
        return b.append(" sally: ").append(Engine.sallyRefusal()).append(", prompt open: ")
                .append(Hooks.worldPrompt() == null ? "none" : Hooks.worldPrompt().getClass().getSimpleName()).toString();
    }

    private static String status() {
        game.faction.player.Player p = FACTIONS.player();
        StringBuilder b = new StringBuilder("status slot ").append(p.index())
                .append(" credits ").append((long) p.credits().getD());
        Region cap = p.capitolRegion();
        b.append(" capital ").append(cap == null ? "none" : cap.index() + " at " + cap.cx() + "," + cap.cy());
        b.append(" regions ").append(p.realm().regions()).append(" [");
        for (int i = 0; i < p.realm().regions(); i++) {
            b.append(i == 0 ? "" : " ").append(p.realm().region(i).index());
        }
        b.append("] armies [");
        for (int i = 0; i < p.armies().all().size(); i++) {
            WArmy w = p.armies().all().get(i);
            b.append(i == 0 ? "" : "; ").append(i).append(": world ").append(w.armyIndex()).append(" at ").append(w.ctx())
                    .append(",").append(w.cty()).append(" men ").append(AD.men(null).get(w)).append(" power ")
                    .append((long) AD.power().get(w)).append(" ").append(w.state().name(w));
        }
        b.append("] wars [");
        boolean first = true;
        for (FactionNPC f : DIP.WAR().player()) {
            b.append(first ? "" : " ").append(f.index());
            first = false;
        }
        for (int slot : Hooks.humanSlots()) { // another human, at war through an ally or a vassal (PT2)
            if (slot != p.index() && DIP.get(p, FACTIONS.getByIndex(slot)) == DIP.WAR()) {
                b.append(first ? "" : " ").append("human").append(slot);
                first = false;
            }
        }
        return b.append("]").toString();
    }

    private static WArmy army(int i) {
        int n = FACTIONS.player().armies().all().size();
        if (n == 0) {
            throw new IllegalStateException("the player has no world army");
        }
        return FACTIONS.player().armies().all().get(i < 0 ? n + i : i);
    }

    /** An army panel's selection of one division. */
    private static boolean[] selected(WArmy w, int div) {
        if (div >= w.divs().size()) {
            throw new IllegalStateException("the army has no division " + div);
        }
        boolean[] s = new boolean[w.divs().size()];
        s[div] = true;
        return s;
    }

    private static WDivRegional regional(int army) {
        WArmy w = army(army);
        for (int i = 0; i < w.divs().size(); i++) {
            ADDiv d = w.divs().get(i);
            if (d instanceof WDivRegional) {
                return (WDivRegional) d;
            }
        }
        throw new IllegalStateException("the army has no regional division");
    }

    private static Div cityDiv(int i) {
        return GAME.ARMIES().player().divisions().get(i);
    }

    private static Region capital() {
        Region reg = FACTIONS.player().capitolRegion();
        if (reg == null) {
            throw new IllegalStateException("the player has no capital");
        }
        return reg;
    }

    /**
     * An NPC's relations with each human, each read as that human between steps (SOS-46 M3). "stored" (the sum of the
     * opinion's stored values) and "dealings" (gifts and extortion) are world state, the same on every PC; "opinion" and
     * "trust" add values this PC computes live, so they may differ between PCs.
     */
    private static String relations(FactionNPC f) {
        StringBuilder b = new StringBuilder("relations npc ").append(f.index()).append(" king ").append(f.court().king().roy().name()); // the king: a new one starts with fresh opinions
        Royalty king = f.court().king().roy();
        for (int slot : Hooks.humanSlots()) {
            int before = Engine.asHuman(slot);
            try {
                double stored = 0;
                double dealings = 0;
                StringBuilder parts = new StringBuilder(); // each stored entry by name
                for (SuperSpecImp<Royalty> s : ROPINION.BOOST().imps()) {
                    stored += s.value.getD(king);
                    if (s.value.getD(king) != 0) {
                        parts.append(' ').append(s.key).append('=').append(String.format(java.util.Locale.ROOT, "%.4f", s.value.getD(king)));
                    }
                    if (s.key.equals("DEALINGS")) {
                        dealings = s.value.getD(king);
                    }
                }
                b.append(" | human ").append(slot).append(" stance ").append(DIP.get(f, FACTIONS.getByIndex(slot)).key())
                        .append(String.format(java.util.Locale.ROOT, " stored %.6f dealings %.6f opinion %.3f trust %.3f", stored,
                                dealings, ROPINION.get(f), ROPINION.trust().get(f))).append(" [").append(parts).append(" ]");
            } finally {
                Engine.asHuman(before);
            }
        }
        return b.toString();
    }

    /**
     * Each human's event timers and raid, each read as that human between steps (SOS-46 M4): the war timer (it grows
     * while the human is at peace), the warner (an NPC slot, or -1), the rebellion timer and realm cursor (below the
     * human's region count), the raider raiding it (or none) and its raid timer. All are world state, the same on every PC.
     */
    private static String events() {
        StringBuilder b = new StringBuilder("events");
        Object dip = GAME.events().world.dip;
        Object rebellion = GAME.events().rebellion;
        for (int slot : Hooks.humanSlots()) {
            int before = Engine.asHuman(slot);
            try {
                int k = Hooks.humanIndex();
                Raider raider = ((RaidingCurrent) patched(GAME.raiders(), "current")).current();
                b.append(" | human ").append(slot).append(String.format(java.util.Locale.ROOT,
                        " warCount %.3f warner %d rebellion %.1f ri %d regions %d raid %s raidTimer %.1f",
                        (Double) patched(dip, "sosdetWarCount", k), (Integer) patched(dip, "sosdetWarner", k),
                        (Double) patched(rebellion, "sosdetTimer", k), (Integer) patched(rebellion, "sosdetRi", k),
                        FACTIONS.player().realm().regions(), raider == null ? "none" : raider.name,
                        (Double) patched(GAME.raiders(), "sosdetRaidTimer", k)));
            } finally {
                Engine.asHuman(before);
            }
        }
        return b.toString();
    }

    private static String war() {
        StringBuilder b = new StringBuilder("war");
        Object dip = GAME.events().world.dip;
        for (int slot : Hooks.humanSlots()) {
            int before = Engine.asHuman(slot);
            try {
                b.append(" | human ").append(slot).append(String.format(java.util.Locale.ROOT, " warCount %.3f potential",
                        (Double) patched(dip, "sosdetWarCount", Hooks.humanIndex())));
                for (FactionNPC f : DIP.WAR_PLAYER().potential()) {
                    b.append(String.format(java.util.Locale.ROOT, " %d(%.3f)", f.index(), ROPINION.trust().get(f)));
                }
            } finally {
                Engine.asHuman(before);
            }
        }
        return b.toString();
    }

    /** A patch-added public method, with int arguments (the rig, like the engine, compiles against the vanilla jar). */
    /** The raider raiding this PC's human: its own slot of the raid (SOS-46 M4), not slot 0's field (PT5). */
    private static Raider currentRaider() {
        return ((RaidingCurrent) patched(GAME.raiders(), "current")).current();
    }

    private static Object patched(Object target, String name, int... args) {
        Class<?>[] types = new Class<?>[args.length];
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = int.class;
            values[i] = args[i];
        }
        try {
            return target.getClass().getMethod(name, types).invoke(target, values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SOSDET rig: no " + target.getClass().getSimpleName() + "." + name + " in this build", e);
        }
    }

    private static FactionNPC npcAt(int slot) {
        if (slot < 0 || slot >= FACTIONS.MAX() || !(FACTIONS.getByIndex(slot) instanceof FactionNPC)) {
            throw new IllegalArgumentException("slot " + slot + " is no NPC");
        }
        return (FactionNPC) FACTIONS.getByIndex(slot);
    }

    private static FactionNPC npc() {
        for (FactionNPC f : FACTIONS.NPCs()) {
            if (f.isActive()) {
                return f;
            }
        }
        throw new IllegalStateException("no active NPC faction");
    }

    /** {@code wprod} (SOS-102): per goods with any producer, its net rate and the sources counted, read-only. */
    private static String production() {
        StringBuilder s = new StringBuilder("wprod step ").append(Engine.step()).append(':');
        int shown = 0;
        for (init.resources.RESOURCE res : init.resources.RESOURCES.ALL()) {
            double net = 0;
            int counted = 0;
            for (settlement.room.industry.module.RoomProduction.Source src : settlement.main.SETT.ROOMS().PROD.producers(res)) {
                double am = src.am();
                if (am != 0) {
                    counted++;
                    net += am;
                }
            }
            for (settlement.room.industry.module.RoomProduction.Source src : settlement.main.SETT.ROOMS().PROD.consumers(res)) {
                double am = src.am();
                if (am != 0) {
                    counted++;
                    net -= am;
                }
            }
            if (counted == 0) {
                continue;
            }
            s.append(' ').append(res.key).append(String.format(java.util.Locale.ROOT, " %.1f (%d)", net, counted));
            if (++shown == 8) {
                break;
            }
        }
        return s.toString();
    }

    /** {@code wtrade} (SOS-101): per human, its stance with each other human and the factions its traders reach. */
    private static String tradeRoutes() {
        StringBuilder s = new StringBuilder("wtrade step ").append(Engine.step()).append(':');
        for (int h : Hooks.humanSlots()) {
            int before = Engine.asHuman(h);
            try {
                game.faction.Faction me = FACTIONS.getByIndex(h);
                s.append(" | slot ").append(h).append(' ').append(me.name).append(" capital ")
                        .append(me.capitolRegion() == null ? "none" : me.capitolRegion().info.name()).append(" regions ")
                        .append(me.realm().regions());
                for (int o : Hooks.humanSlots()) {
                    if (o != h) {
                        game.faction.diplomacy.DipStance st = game.faction.diplomacy.DIP.get(me, FACTIONS.getByIndex(o));
                        s.append("; with slot ").append(o).append(' ').append(st.key()).append(st.trades ? " (trades)" : "");
                    }
                }
                s.append("; reaches");
                if (me.capitolRegion() != null) {
                    for (world.map.pathing.WRegFinder.RegDist d : RD.DIST().tradePartners(me)) {
                        if (d.reg.faction() != null && d.reg.faction() != me) {
                            s.append(' ').append(d.reg.faction().name).append("@" + d.distance);
                        }
                    }
                }
            } finally {
                Engine.asHuman(before);
            }
        }
        return s.toString();
    }
}

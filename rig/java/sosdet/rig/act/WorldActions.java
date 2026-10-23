package sosdet.rig.act;

import game.GAME;
import game.battle.div.Div;
import game.event.engine.EVENT_HANDLER;
import game.event.engine.Event;
import game.faction.FACTIONS;
import game.faction.diplomacy.deal.Deal;
import game.faction.npc.FactionNPC;
import game.faction.player.emmi.EmiTypeRoy;
import game.raiding.Raider;
import init.race.RACES;
import init.trade.TR;
import settlement.stats.STATS;
import sosdet.game.cmd.Diplomacy;
import sosdet.game.cmd.Events;
import sosdet.game.cmd.World;
import sosdet.rig.Rig;
import world.WORLD;
import world.army.AD;
import world.army.ADDiv;
import world.army.WDivRegional;
import world.entity.army.WArmy;
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
        case "wbesiege": { // wbesiege <army> <region index>: the army tool on a settlement (starts a siege)
            int army = Integer.parseInt(a[1]);
            int reg = Integer.parseInt(a[2]);
            Rig.later(() -> army(army).besiege(WORLD.REGIONS().getByIndex(reg)));
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
        case "wattack": { // wattack <region index> <army> <1|0 declare war>: the attack tool (starts a battle)
            int reg = Integer.parseInt(a[1]);
            int army = Integer.parseInt(a[2]);
            boolean war = a[3].equals("1");
            Rig.later(() -> {
                if (!World.regionAttack(WORLD.REGIONS().getByIndex(reg), army(army), war)) {
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
                Raider r = GAME.raiders().current.current();
                if (r == null || !Events.raiderPay(r, 0, new int[TR.ALL().size()])) {
                    throw new IllegalStateException("no current raider, or no session");
                }
            });
            return true;
        case "wallyhelp": // wallyhelp: an ally's help against the current raider asked for
            Rig.later(() -> {
                Raider r = GAME.raiders().current.current();
                if (r == null || !Events.raiderAllyHelp(r)) {
                    throw new IllegalStateException("no current raider, or no session");
                }
            });
            return true;
        default:
            return false;
        }
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

    private static FactionNPC npc() {
        for (FactionNPC f : FACTIONS.NPCs()) {
            if (f.isActive()) {
                return f;
            }
        }
        throw new IllegalStateException("no active NPC faction");
    }
}

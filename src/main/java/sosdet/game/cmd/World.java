package sosdet.game.cmd;

import java.util.List;

import game.GAME;
import game.battle.div.Div;
import game.battle.util.DIV_SPEC.DIV_SPECE;
import game.faction.FACTIONS;
import game.faction.FCredits.CTYPE;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.npc.FactionNPC;
import game.faction.player.Player;
import game.faction.royalty.opinion.ROPINION;
import init.constant.Config;
import init.race.Race;
import settlement.stats.STATS;
import settlement.stats.colls.StatsBattle.StatTraining;
import settlement.stats.equip.EquipBattle;
import snake2d.util.datatypes.COORDINATE;
import sosdet.Hooks;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import view.main.VIEW;
import world.WORLD;
import world.army.AD;
import world.army.ADDiv;
import world.army.ADSupplies.ADArtillery;
import world.army.WDivMercenary;
import world.army.WDivRegional;
import world.entity.army.WArmy;
import world.map.regions.Region;
import world.region.RD;
import world.region.building.RDBuilding;
import world.region.building.RDBuildPoints.RDBuildPoint;
import world.region.pop.RDEdicts.RDRaceEdict;
import world.region.pop.RDRace;

/**
 * The world map (ids 7000-7899; 7900-7999 are the world-battle prompt's): the player's armies, their divisions,
 * recruiting and mercenaries, and the player's regions (stage D, plan 0002). Each command re-checks what the UI
 * checked before it acted, since the world may have moved on by the command's step; a command whose army, division
 * or region is gone is skipped on every PC alike.
 */
public final class World {

    static final int ARMY_MOVE = 7000;
    static final int ARMY_INTERCEPT = 7001;
    static final int ARMY_BESIEGE = 7002;
    static final int ARMY_STOP = 7003;
    static final int ARMY_RAID = 7004;
    static final int ARMY_DISBAND = 7005;
    static final int ARMY_NAME = 7006;
    static final int DIVS_REASSIGN = 7007;
    static final int DIVS_BANNER = 7008;
    static final int DIV_ORDER = 7009;
    static final int REGIONAL_TRAINING = 7010;
    static final int REGIONAL_EQUIP = 7011;
    static final int REGIONAL_MEN = 7012;
    static final int REGIONAL_RACE = 7013;
    static final int REGIONAL_BANNER = 7014;
    static final int ARTILLERY = 7015;
    static final int RECRUIT = 7016;
    static final int MERC_HIRE = 7017;
    static final int ARMY_CREATE = 7018;
    static final int CITY_DIV_ATTACH = 7019;
    static final int REGION_AUTONOMY = 7020;
    static final int REGION_ABANDON = 7021;
    static final int REGION_NAME = 7022;
    static final int REGION_EDICT = 7023;
    static final int REGION_SQUEEZE = 7024;
    static final int REGION_BUILDINGS = 7025;
    static final int REGION_ATTACK = 7026;

    static final List<Command> ALL = List.of(
            new Command(ARMY_MOVE, "armyMove", r -> {
                WArmy a = Args.ownArmy(r);
                int tx = r.i();
                int ty = r.i();
                if (a == null) {
                    return false;
                }
                a.setDestination(tx, ty);
                return true;
            }),
            new Command(ARMY_INTERCEPT, "armyIntercept", r -> {
                WArmy a = Args.ownArmy(r);
                WArmy other = Args.army(r);
                if (a == null || other == null) {
                    return false;
                }
                a.intercept(other);
                return true;
            }),
            new Command(ARMY_BESIEGE, "armyBesiege", r -> {
                WArmy a = Args.ownArmy(r);
                Region reg = Args.region(r);
                if (a == null || reg == null || otherHumans(reg, a)) {
                    return false;
                }
                a.besiege(reg);
                return true;
            }),
            new Command(ARMY_STOP, "armyStop", r -> {
                WArmy a = Args.ownArmy(r);
                if (a == null) {
                    return false;
                }
                a.stop();
                return true;
            }),
            new Command(ARMY_RAID, "armyRaid", r -> {
                WArmy a = Args.ownArmy(r);
                boolean raid = r.b();
                if (a == null) {
                    return false;
                }
                a.raid(raid);
                return true;
            }),
            new Command(ARMY_DISBAND, "armyDisband", r -> {
                WArmy a = Args.ownArmy(r);
                if (a == null) {
                    return false;
                }
                a.disband();
                return true;
            }),
            new Command(ARMY_NAME, "armyName", r -> {
                WArmy a = Args.ownArmy(r);
                String name = r.s();
                if (a == null) {
                    return false;
                }
                a.name.clear().add(name);
                return true;
            }),
            new Command(DIVS_REASSIGN, "divsReassign", r -> {
                boolean toArmy = r.b();
                WArmy to = Args.ownArmy(r);
                ADDiv[] divs = divs(r);
                if (toArmy && to == null) {
                    return false;
                }
                boolean any = false;
                for (ADDiv d : divs) {
                    WArmy from = d == null ? null : d.army();
                    if (from == null || from == to) {
                        continue;
                    }
                    if (to != null && (!to.divs().canAdd() || !sameTile(from, to))) {
                        continue; // the army screen's pick: room in the target, on the same tile
                    }
                    d.reassign(to);
                    any = true;
                }
                return any;
            }),
            new Command(DIVS_BANNER, "divsBanner", r -> {
                int banner = r.i();
                ADDiv[] divs = divs(r);
                if (banner < 0 || banner >= GAME.ARMIES().banners.size()) {
                    return false;
                }
                for (ADDiv d : divs) {
                    if (d != null) {
                        d.bannerSet(banner);
                    }
                }
                return true;
            }),
            new Command(DIV_ORDER, "divOrder", r -> {
                WArmy a = Args.ownArmy(r);
                int after = r.i();
                int insert = r.i();
                if (a == null || after < 0 || insert < 0 || after >= a.divs().size()
                        || insert >= a.divs().size()) {
                    return false;
                }
                a.divs().insert(after, insert);
                return true;
            }),
            new Command(REGIONAL_TRAINING, "regionalTraining", r -> {
                WDivRegional d = regional(r);
                StatTraining t = STATS.BATTLE().TRAINING_ALL.get(r.i());
                double value = r.d();
                if (d == null) {
                    return false;
                }
                d.target.trainingSet(t, value);
                return true;
            }),
            new Command(REGIONAL_EQUIP, "regionalEquip", r -> {
                WDivRegional d = regional(r);
                EquipBattle e = STATS.EQUIP().BATTLE_ALL().get(r.i());
                double value = r.d();
                if (d == null) {
                    return false;
                }
                d.target.equipSet(e, value);
                return true;
            }),
            new Command(REGIONAL_MEN, "regionalMen", r -> {
                WDivRegional d = regional(r);
                int men = r.i();
                if (d == null) {
                    return false;
                }
                d.target.menSet(men);
                return true;
            }),
            new Command(REGIONAL_RACE, "regionalRace", r -> {
                WDivRegional d = regional(r);
                Race race = Args.race(r);
                if (d == null || race == null) {
                    return false;
                }
                d.target.raceSet(race);
                return true;
            }),
            new Command(REGIONAL_BANNER, "regionalBanner", r -> {
                WDivRegional d = regional(r);
                int banner = r.i();
                if (d == null) {
                    return false;
                }
                d.target.bannerISet(banner);
                return true;
            }),
            new Command(ARTILLERY, "artillery", r -> {
                ADArtillery art = AD.supplies().arts().get(r.i());
                WArmy a = Args.ownArmy(r);
                int value = r.i();
                if (a == null) {
                    return false;
                }
                art.target.set(a, value);
                return true;
            }),
            new Command(RECRUIT, "recruit", r -> {
                WArmy a = Args.ownArmy(r);
                Race race = Args.race(r);
                int men = r.i();
                int banner = r.i();
                double[] training = doubles(r);
                double[] equip = doubles(r);
                if (a == null || race == null) {
                    return false;
                }
                return recruit(a, race, men, banner, training, equip);
            }),
            new Command(MERC_HIRE, "mercHire", r -> {
                WArmy a = Args.ownArmy(r);
                int merc = r.i();
                if (a == null || merc < 0 || merc >= AD.mercenaries().size()) {
                    return false;
                }
                return hire(a, merc);
            }),
            new Command(ARMY_CREATE, "armyCreate", r -> {
                int tx = r.i();
                int ty = r.i();
                return createArmy(tx, ty);
            }),
            new Command(CITY_DIV_ATTACH, "cityDivAttach", r -> {
                boolean toArmy = r.b();
                WArmy a = Args.ownArmy(r);
                Div div = Args.div(r);
                if (toArmy) {
                    if (a == null || !a.divs().canAdd() || AD.cityDivs().attachedArmy(div) != null) {
                        return false;
                    }
                } else if (AD.cityDivs().attachedArmy(div) == null) {
                    return false;
                }
                AD.cityDivs().attach(a, div);
                return true;
            }),
            new Command(REGION_AUTONOMY, "regionAutonomy", r -> {
                Region reg = Args.region(r);
                if (reg == null || reg.faction() != FACTIONS.player() || FACTIONS.frees() <= 5) {
                    return false;
                }
                RD.setFaction(reg, null, true);
                FactionNPC f = FACTIONS.activateNext(reg, null, true);
                f.generate(RD.RACES().get(FACTIONS.player().race()), true);
                ROPINION.OTHER().liberate(f);
                DIP.VASSAL().set(f, FACTIONS.player());
                GAME.events().world.dip.dismissWelcome(f);
                return true;
            }),
            new Command(REGION_ABANDON, "regionAbandon", r -> {
                Region reg = Args.region(r);
                if (reg == null || reg.faction() != FACTIONS.player()) {
                    return false;
                }
                RD.setFaction(reg, null, true);
                return true;
            }),
            new Command(REGION_NAME, "regionName", r -> {
                Region reg = Args.region(r);
                String name = r.s();
                if (reg == null) {
                    return false;
                }
                reg.info.name().clear().add(name);
                return true;
            }),
            new Command(REGION_EDICT, "regionEdict", r -> {
                Region reg = Args.region(r);
                RDRace race = RD.RACES().all.get(r.i());
                RDRaceEdict edict = RD.RACES().edicts.all.get(r.i());
                int value = r.i();
                if (reg == null || reg.faction() != FACTIONS.player()) {
                    return false;
                }
                for (RDRaceEdict e : RD.RACES().edicts.all) {
                    e.toggled(race).set(reg, 0);
                }
                edict.toggled(race).set(reg, value);
                return true;
            }),
            new Command(REGION_SQUEEZE, "regionSqueeze", r -> {
                Region reg = Args.region(r);
                if (reg == null || reg.faction() != FACTIONS.player()
                        || RD.DEVASTATION().current.getD(reg) >= 0.25) {
                    return false;
                }
                RD.OUTPUT().squeze(reg);
                return true;
            }),
            new Command(REGION_BUILDINGS, "regionBuildings", r -> {
                Region reg = Args.region(r);
                int owner = r.i();
                int[] levels = new int[r.i()];
                for (int i = 0; i < levels.length; i++) {
                    levels[i] = r.i();
                }
                if (reg == null || ownerIndex(reg) != owner || levels.length != RD.BUILDINGS().all.size()) {
                    return false;
                }
                return buildings(reg, levels);
            }),
            new Command(REGION_ATTACK, "regionAttack", r -> {
                Region reg = Args.region(r);
                WArmy a = Args.ownArmy(r);
                boolean declareWar = r.b();
                if (reg == null || a == null || otherHumans(reg, a)) {
                    return false;
                }
                if (declareWar) {
                    DIP.WAR().set(reg.faction(), a.faction());
                }
                WORLD.BATTLES().regAttack(reg, a);
                return true;
            }));

    private World() {
    }

    /**
     * True for a region of another human: battles and sieges between humans are not this engine's yet (plan 0004,
     * "Outside this card"), and a human's lost capitol would leave it without one (audit B1 to B4).
     */
    private static boolean otherHumans(Region reg, WArmy a) {
        return reg.faction() instanceof Player && reg.faction() != a.faction();
    }

    // ---------------------------------------------------------------- capture (hooks)

    /** Hook in {@code WArmy.setDestination}: the army tool's move order. */
    public static boolean armyMove(WArmy a, int tx, int ty) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.army(new CmdWriter(ARMY_MOVE), a).i(tx).i(ty));
        return true;
    }

    /** Hook in {@code WArmy.intercept}: the army tool on another army. */
    public static boolean armyIntercept(WArmy a, WArmy other) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.army(Args.army(new CmdWriter(ARMY_INTERCEPT), a), other));
        return true;
    }

    /** Hook in {@code WArmy.besiege}: the army tool on a settlement. */
    public static boolean armyBesiege(WArmy a, Region reg) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.region(Args.army(new CmdWriter(ARMY_BESIEGE), a), reg));
        return true;
    }

    /** Hook in {@code WArmy.stop}: the army panel's stop button. */
    public static boolean armyStop(WArmy a) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.army(new CmdWriter(ARMY_STOP), a));
        return true;
    }

    /** Hook in {@code WArmy.raid(boolean)}: the army panel's raid toggle. */
    public static boolean armyRaid(WArmy a, boolean raid) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.army(new CmdWriter(ARMY_RAID), a).b(raid));
        return true;
    }

    /** Hook in {@code WArmy.disband}: the army panel's disband button, after its yes/no prompt. */
    public static boolean armyDisband(WArmy a) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.army(new CmdWriter(ARMY_DISBAND), a));
        return true;
    }

    /** Hook in the army panel's name input ({@code Army}): the army's name, whole after each edit. */
    public static boolean armyName(WArmy a, CharSequence name) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.army(new CmdWriter(ARMY_NAME), a).s(name.toString()));
        return true;
    }

    /**
     * Hook in the army panel ({@code Army}): the selected divisions moved to another army ({@code to}), or disbanded
     * ({@code to} null). One command for the whole selection, since the panel's loop counts on each division leaving
     * the list as it goes.
     */
    public static boolean divsReassign(WArmy army, boolean[] selected, WArmy to) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(divs(Args.army(new CmdWriter(DIVS_REASSIGN).b(to != null), to), army, selected));
        return true;
    }

    /** Hook in the army panel's banner editor ({@code Army}): the banner of every selected division. */
    public static boolean divsBanner(WArmy army, boolean[] selected, int banner) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(divs(new CmdWriter(DIVS_BANNER).i(banner), army, selected));
        return true;
    }

    /** Hook in {@code ADDivs.insert}: a division card dragged to another place in the army panel. */
    public static boolean divOrder(WArmy a, int after, int insert) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.army(new CmdWriter(DIV_ORDER), a).i(after).i(insert));
        return true;
    }

    /** Hook in {@code WDivRegional.target.trainingSet}: the division editor's training target. */
    public static boolean regionalTraining(WDivRegional d, StatTraining t, double value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.worldDiv(new CmdWriter(REGIONAL_TRAINING), d).i(t.tIndex).d(value));
        return true;
    }

    /** Hook in {@code WDivRegional.target.equipSet}: the division editor's equipment target. */
    public static boolean regionalEquip(WDivRegional d, EquipBattle e, double value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.worldDiv(new CmdWriter(REGIONAL_EQUIP), d).i(e.indexMilitary()).d(value));
        return true;
    }

    /** Hook in {@code WDivRegional.target.menSet}: the division editor's size. */
    public static boolean regionalMen(WDivRegional d, int men) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.worldDiv(new CmdWriter(REGIONAL_MEN), d).i(men));
        return true;
    }

    /** Hook in {@code WDivRegional.target.raceSet}: the division editor's race. */
    public static boolean regionalRace(WDivRegional d, Race race) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.race(Args.worldDiv(new CmdWriter(REGIONAL_RACE), d), race));
        return true;
    }

    /** Hook in {@code WDivRegional.target.bannerISet}: the division editor's banner. */
    public static boolean regionalBanner(WDivRegional d, int banner) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.worldDiv(new CmdWriter(REGIONAL_BANNER), d).i(banner));
        return true;
    }

    /** Hook in an artillery type's target ({@code ADArtillery.target.set}): the army panel's artillery slider. */
    public static boolean artillery(ADArtillery art, WArmy a, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.army(new CmdWriter(ARTILLERY).i(artIndex(art)), a).i(value));
        return true;
    }

    /**
     * Hook in the army panel's recruit button ({@code ArmyRecruit}): a new division raised in the realm, with the
     * editor's race, size, banner, training and equipment.
     */
    public static boolean recruit(WArmy a, DIV_SPECE spec) {
        if (!Commands.capturing()) {
            return false;
        }
        CmdWriter w = Args.race(Args.army(new CmdWriter(RECRUIT), a), spec.race()).i(spec.men()).i(spec.bannerI());
        w.i(STATS.BATTLE().TRAINING_ALL.size());
        for (StatTraining t : STATS.BATTLE().TRAINING_ALL) {
            w.d(spec.training(t));
        }
        w.i(STATS.EQUIP().BATTLE_ALL().size());
        for (EquipBattle e : STATS.EQUIP().BATTLE_ALL()) {
            w.d(spec.equip(e));
        }
        Commands.capture(w);
        return true;
    }

    /** Hook in the mercenary screen's recruit button ({@code UIMerenaries}): one mercenary band, by its index. */
    public static boolean mercHire(WArmy a, int merc) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.army(new CmdWriter(MERC_HIRE), a).i(merc));
        return true;
    }

    /**
     * Hook in the army list ({@code List}): a new army, at the tile placed, or for the realm's first army
     * ({@code tx} -1) at a tile of the capital's region the step draws, as vanilla's random pick.
     */
    public static boolean armyCreate(int tx, int ty) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(ARMY_CREATE).i(tx).i(ty));
        return true;
    }

    /** Hook in {@code WDivStoredAll.attach}: city divisions sent out to a world army, or recalled ({@code a} null). */
    public static boolean cityDivAttach(WArmy a, Div div) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.div(Args.army(new CmdWriter(CITY_DIV_ATTACH).b(a != null), a), div));
        return true;
    }

    /** Hook in the region panel ({@code PlayInfo}): the region released as a new vassal faction. */
    public static boolean regionAutonomy(Region reg) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.region(new CmdWriter(REGION_AUTONOMY), reg));
        return true;
    }

    /** Hook in the region panel ({@code PlayInfo}): the region abandoned. */
    public static boolean regionAbandon(Region reg) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.region(new CmdWriter(REGION_ABANDON), reg));
        return true;
    }

    /** Hook in the region panel's name input ({@code PlayInfo}): the region's name, whole after each edit. */
    public static boolean regionName(Region reg, CharSequence name) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.region(new CmdWriter(REGION_NAME), reg).s(name.toString()));
        return true;
    }

    /** Hook in the region's population panel ({@code PlayPop}): one race edict on or off, the others off. */
    public static boolean regionEdict(Region reg, RDRace race, RDRaceEdict edict, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        int ei = 0;
        while (RD.RACES().edicts.all.get(ei) != edict) {
            ei++;
        }
        Commands.capture(Args.region(new CmdWriter(REGION_EDICT), reg).i(race.index()).i(ei).i(value));
        return true;
    }

    /** Hook in the region's output panel ({@code PlayOutput}): squeeze the region. */
    public static boolean regionSqueeze(Region reg) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.region(new CmdWriter(REGION_SQUEEZE), reg));
        return true;
    }

    /**
     * Hook in the building popup's accept button ({@code PlayBuildingsPop}): the drafted level of every building in
     * the region. The draft stays this PC's; the command carries its levels whole, with the region's owner then.
     */
    public static boolean regionBuildings(Region reg) {
        if (!Commands.capturing()) {
            return false;
        }
        CmdWriter w = Args.region(new CmdWriter(REGION_BUILDINGS), reg).i(ownerIndex(reg));
        w.i(RD.BUILDINGS().all.size());
        RD.BUILDINGS().tmp(false, reg); // as the popup's render does every frame, so the draft is the one read
        for (RDBuilding b : RD.BUILDINGS().all) {
            w.i(RD.BUILDINGS().tmp().level(b, reg));
        }
        Commands.capture(w);
        return true;
    }

    /** Hook in the attack tool ({@code PlayToolAttack}): an army attacks a region, declaring war first if asked. */
    public static boolean regionAttack(Region reg, WArmy a, boolean declareWar) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.army(Args.region(new CmdWriter(REGION_ATTACK), reg), a).b(declareWar));
        return true;
    }

    // ---------------------------------------------------------------- apply (inside the step)

    /** {@code ArmyRecruit}'s recruit button, with its checks: room in the army and conscripts of the race. */
    private static boolean recruit(WArmy a, Race race, int men, int banner, double[] training, double[] equip) {
        if (!a.divs().canAdd() || men > AD.conscripts().available(race).get(FACTIONS.player())
                || training.length != STATS.BATTLE().TRAINING_ALL.size()
                || equip.length != STATS.EQUIP().BATTLE_ALL().size()) {
            return false;
        }
        WDivRegional d = AD.regional().create(race, (double) men / Config.battle().MEN_PER_DIVISION, a);
        d.bannerSet(banner);
        // The city's stockpiles give the supplies in the step's city tail, and its next report brings them (ADR 0005)
        if (!Hooks.armySupplies(a, race, men)) {
            throw new IllegalStateException("SOSDET: a recruit command applied outside the world part");
        }
        for (int i = 0; i < training.length; i++) {
            d.target.trainingSet(STATS.BATTLE().TRAINING_ALL.get(i), training[i]);
        }
        for (int i = 0; i < equip.length; i++) {
            d.target.equipSet(STATS.EQUIP().BATTLE_ALL().get(i), equip[i]);
        }
        return true;
    }

    /** {@code UIMerenaries}' recruit button, for one band still free, offered and affordable. */
    private static boolean hire(WArmy a, int merc) {
        WDivMercenary d = AD.mercenaries().get(merc);
        if (d.army() != null || d.men() == 0 || d.disbanded() || merc >= AD.mercenaries().max()
                || !a.divs().canAdd()) {
            return false;
        }
        int cost = AD.mercenaries().signingCost(merc);
        if (cost >= FACTIONS.player().credits().credits()) {
            return false;
        }
        d.reassign(a);
        FACTIONS.player().credits().inc(-cost, CTYPE.MERCINARIES);
        return true;
    }

    /** {@code List}'s recruit button and placer: the placer's checks, and the first army's random tile. */
    private static boolean createArmy(int tx, int ty) {
        Faction f = FACTIONS.player();
        if (!f.armies().canCreate() || !WORLD.ENTITIES().armies.canCreate()) {
            return false;
        }
        if (tx < 0) {
            if (f.capitolRegion() == null || f.armies().all().size() != 0) {
                return false;
            }
            COORDINATE c = WORLD.PATH().rnd(f.capitolRegion());
            tx = c.x();
            ty = c.y();
        } else if (!WORLD.PATH().map.is.is(tx, ty) || WORLD.REGIONS().map.get(tx, ty) == null
                || WORLD.REGIONS().map.get(tx, ty).faction() != f) {
            return false;
        }
        WArmy e = WORLD.ENTITIES().armies.create(tx, ty, f);
        if (Hooks.issuedHere()) {
            VIEW.world().UI.armies.openList(e);
        }
        return true;
    }

    /**
     * {@code PlayBuildingsPop}'s accept: the drafted levels' cost, charged when the region is the player's, then the
     * levels. The step checks again what the popup checked: the treasury, and that no build point goes below zero
     * with the new levels; if one does, the old levels come back and nothing is charged (SOS-36).
     */
    private static boolean buildings(Region reg, int[] levels) {
        int cost = 0;
        for (int i = 0; i < levels.length; i++) {
            RDBuilding b = RD.BUILDINGS().all.get(i);
            int current = b.level.get(reg);
            int level = levels[i];
            if (level < 0 || level >= b.levels.size()) {
                return false;
            }
            if (level > current) {
                cost += b.levels.get(level).cost - b.levels.get(current).cost;
            }
        }
        boolean player = reg.faction() == FACTIONS.player();
        if (player && cost > FACTIONS.player().credits().getD()) {
            return false;
        }
        int[] old = new int[levels.length];
        for (int i = 0; i < levels.length; i++) {
            old[i] = RD.BUILDINGS().all.get(i).level.get(reg);
            RD.BUILDINGS().all.get(i).level.set(reg, levels[i]);
        }
        for (RDBuildPoint p : RD.BUILDINGS().costs.ALL) {
            if (p.bo.get(reg) < 0) {
                for (int i = 0; i < old.length; i++) {
                    RD.BUILDINGS().all.get(i).level.set(reg, old[i]);
                }
                return false;
            }
        }
        if (player) {
            FACTIONS.player().credits().inc(-cost, CTYPE.CONSTRUCTION);
        }
        return true;
    }

    // ---------------------------------------------------------------- argument helpers

    /** The selected divisions of an army panel, in their order. */
    private static CmdWriter divs(CmdWriter w, WArmy army, boolean[] selected) {
        int n = 0;
        for (int i = 0; i < selected.length && i < army.divs().size(); i++) {
            if (selected[i]) {
                n++;
            }
        }
        w.i(n);
        for (int i = 0; i < selected.length && i < army.divs().size(); i++) {
            if (selected[i]) {
                Args.worldDiv(w, army.divs().get(i));
            }
        }
        return w;
    }

    /** Divisions as {@link #divs(CmdWriter, WArmy, boolean[])} wrote them; a division gone since is null. */
    private static ADDiv[] divs(CmdReader r) {
        ADDiv[] divs = new ADDiv[r.i()];
        for (int i = 0; i < divs.length; i++) {
            ADDiv d = Args.worldDiv(r);
            divs[i] = d != null && d.army() != null && d.faction() != FACTIONS.player() ? null : d; // own only (SOS-46)
        }
        return divs;
    }

    private static WDivRegional regional(CmdReader r) {
        ADDiv d = Args.worldDiv(r);
        return d instanceof WDivRegional && (d.army() == null || d.faction() == FACTIONS.player()) ? (WDivRegional) d : null; // own only (SOS-46)
    }

    private static double[] doubles(CmdReader r) {
        double[] v = new double[r.i()];
        for (int i = 0; i < v.length; i++) {
            v[i] = r.d();
        }
        return v;
    }

    private static int artIndex(ADArtillery art) {
        int i = 0;
        while (AD.supplies().arts().get(i) != art) {
            i++;
        }
        return i;
    }

    private static int ownerIndex(Region reg) {
        return reg.faction() == null ? -1 : reg.faction().index();
    }

    private static boolean sameTile(WArmy a, WArmy b) {
        return a.ctx() == b.ctx() && a.cty() == b.cty();
    }
}

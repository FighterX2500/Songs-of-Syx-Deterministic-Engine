package sosdet.game;

import java.util.ArrayList;
import java.util.IdentityHashMap;

import game.GAME;
import game.battle.div.Div;
import game.boosting.BOOSTABLES;
import game.boosting.BOOSTABLE_O;
import game.boosting.BOOSTING;
import game.boosting.BValue;
import game.boosting.Boostable;
import game.boosting.Booster;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.player.PLevels.Level;
import game.faction.player.PTitles.PTitle;
import game.faction.player.Player;
import game.tourism.TOURISM;
import init.race.RACES;
import init.race.Race;
import init.resources.RESOURCE;
import init.tech.TECH;
import init.tech.TECHS;
import init.trade.TR;
import init.trade.TRADABLE;
import init.trade.TRADABLEO;
import init.trade.TRADE_TYPE;
import init.type.CRIMES;
import init.type.HCLASS;
import init.type.HCLASS_RACE;
import init.type.HCLASSES;
import init.type.HTYPES;
import init.value.GVALUES;
import init.value.Lock;
import init.value.Locker;
import init.value.Lockers;
import init.value.Value;
import settlement.battle.invasion.Invador;
import settlement.main.SETT;
import settlement.stats.Induvidual;
import settlement.stats.POP;
import settlement.stats.STATS;
import settlement.stats.colls.StatsBattle.StatTraining;
import settlement.stats.equip.EquipBattle;
import snake2d.util.sets.LIST;
import sosdet.report.CitySource;
import sosdet.report.Field;
import sosdet.report.ReportLayout;
import world.army.AD;
import world.army.WDIV;
import world.map.regions.Region;
import world.region.RD;

/**
 * Takes the player's city report from the live city (ADR 0005, SOS-38): each field as the game's own code reads it.
 * It runs in the city part, where the report hooks read the live city, so it can call the patched methods.
 */
public final class GameReport implements CitySource {

    /** Every boost value a booster reads, in the order the boostables and their boosters list them. */
    private final ArrayList<BValue> boosts = new ArrayList<>();
    private final IdentityHashMap<BValue, Integer> boostIndex = new IdentityHashMap<>();
    private final LIST<Value<Faction>> values = GVALUES.FACTION.all();
    private final IdentityHashMap<Value<Faction>, Integer> valueIndex = new IdentityHashMap<>();
    /** Every unlock of a tech, level or title on a faction or a region, in the order the game lists them. */
    private final ArrayList<Lock<?>> unlocks = new ArrayList<>();
    private final IdentityHashMap<Locker<?>, Integer> unlockIndex = new IdentityHashMap<>();
    private final ReportLayout layout;
    /** {@code Invador.ref}, private: the ref the city's next invasion gets (C6). */
    private final java.lang.reflect.Field invasionRef;

    public GameReport() {
        try {
            invasionRef = Invador.class.getDeclaredField("ref");
            invasionRef.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("SOSDET: no Invador.ref in this game (SOSDET supports Songs of Syx V71,"
                    + " build 71.44)", e);
        }
        // A booster hands its boost value to what it boosts; this one only notes it. Every booster in the game
        // reads its value this way (RDBuilding's one exception reads nothing)
        BOOSTABLE_O seen = v -> {
            if (!boostIndex.containsKey(v)) {
                boostIndex.put(v, boosts.size());
                boosts.add(v);
            }
            return 0;
        };
        for (Boostable bo : BOOSTING.ALL()) {
            for (Booster b : bo.all()) {
                b.get(seen);
            }
            for (Booster b : bo.fGlobal) {
                b.get(seen);
            }
        }
        for (int i = 0; i < values.size(); i++) {
            valueIndex.put(values.get(i), i);
        }
        for (TECH t : TECHS.ALL()) {
            addUnlocks(t.lockers);
        }
        // slot 0's levels and titles: only its unlocks are the game's locks (GameHumans.spare), on every PC (SOS-46)
        Player first = (Player) FACTIONS.getByIndex(0);
        for (Level lv : first.level().all()) {
            addUnlocks(lv.lockers);
        }
        for (PTitle ti : first.titles.all()) {
            addUnlocks(ti.lockers);
        }
        // in sosdet.report.Count order
        layout = new ReportLayout(TR.ALL().size(), TRADE_TYPE.all.size(), RACES.all().size(), HCLASSES.ALL().size(),
                GAME.ARMIES().player().divisions().size(), STATS.EQUIP().BATTLE_ALL().size(),
                STATS.BATTLE().TRAINING_ALL.size(), boosts.size(), values.size(), unlocks.size());
    }

    /** A person's unlocks get no slot: the world part reads none on the player's people. */
    private void addUnlocks(Lockers ls) {
        for (Lock<?> lock : ls.all()) {
            if (lock.lockable.values == GVALUES.FACTION || lock.lockable.values == GVALUES.REGION) {
                unlockIndex.put(lock.unlocker, unlocks.size());
                unlocks.add(lock);
            }
        }
    }

    /**
     * The slot of the human that is {@code t} or owns it (its region, one of its people), or -1: what an unlock reads a
     * city's state for (ADR 0004).
     */
    public static int ownerHuman(Object t) {
        Faction f = t instanceof Faction ? (Faction) t : t instanceof Region ? ((Region) t).faction()
                : t instanceof Induvidual ? ((Induvidual) t).faction() : null;
        return f instanceof Player ? f.index() : -1;
    }

    /** True for one of a city's people: an unlock on a person has no report slot. */
    public static boolean person(Object t) {
        return t instanceof Induvidual;
    }

    /** An unlock's value on the player, or on its capital for a region's: the same, as each reads only the player's. */
    @SuppressWarnings("unchecked") // the lock's values are GVALUES.FACTION's or GVALUES.REGION's, so is its locker
    private static double playerUnlock(Lock<?> lock) {
        if (lock.lockable.values == GVALUES.FACTION) {
            return ((Locker<Faction>) lock.unlocker).progress(FACTIONS.player());
        }
        Region capitol = FACTIONS.player().capitolRegion();
        return capitol == null ? 0 : ((Locker<Region>) lock.unlocker).progress(capitol); // no realm left (SOS-46)
    }

    @Override
    public ReportLayout layout() {
        return layout;
    }

    @Override
    public int indexOf(Field f, Object key) {
        Integer i = f == Field.BOOST ? boostIndex.get(key) : f == Field.VALUE ? valueIndex.get(key)
                : f == Field.UNLOCK ? unlockIndex.get(key) : null;
        return i == null ? -1 : i;
    }

    @Override
    public int faction() {
        return FACTIONS.player().index();
    }

    @Override
    public int[] humans() {
        return GameHumans.get().slots();
    }

    @Override
    public void retiredAgain(int slot, boolean eliminated) {
        GameHumans.get().retiredAgain(slot, eliminated);
    }

    @Override
    public boolean eliminated(int slot) {
        return GameHumans.get().eliminated(slot);
    }

    @Override
    public boolean retired(int slot) {
        return GameHumans.get().retired(slot);
    }

    @Override
    public void fill(double[] v) {
        ReportLayout l = layout;
        v[l.slot(Field.CREDITS, 0)] = FACTIONS.player().credits().getD();
        v[l.slot(Field.WORTH_SEASON, 0)] = FACTIONS.player().credits().worth.get(0) - FACTIONS.player().credits().worth.get(1);
        v[l.slot(Field.ENTRY_CLOSED, 0)] = bool(SETT.ENTRY().isClosed());
        v[l.slot(Field.ENTRY_POINTS, 0)] = bool(SETT.ENTRY().points.hasAny());

        for (TRADABLEO<RESOURCE> t : TR.RES()) {
            int i = t.index();
            RESOURCE r = t.t;
            v[l.slot(Field.HELD, i)] = r.owned() + t.ps().promised().get(null); // owned() subtracts what was promised
            v[l.slot(Field.EXPORT_AMOUNT, i)] = SETT.ROOMS().EXPORT.tally.amount.get(r);
            v[l.slot(Field.EXPORT_CAP, i)] = SETT.ROOMS().EXPORT.tally.capacity.get(r);
            v[l.slot(Field.IMPORT_CAP, i)] = SETT.ROOMS().IMPORT.tally.capacity.get(r);
            v[l.slot(Field.IMPORT_FREE, i)] = SETT.ROOMS().IMPORT.tally.spaceForTribute(r);
            v[l.slot(Field.STOCK_SPACE, i)] = SETT.ROOMS().STOCKPILE.tally().space.total(r);
            v[l.slot(Field.STOCK_KEPT, i)] = SETT.ROOMS().STOCKPILE.tally().spaceReserved.total(r)
                    + SETT.ROOMS().STOCKPILE.tally().amount.total(r);
        }
        for (TRADABLE t : TR.ALL()) {
            int i = t.index();
            v[l.slot(Field.INCOMING, i, 0)] = t.pb().attempting(null);
            v[l.slot(Field.QUEUED, i, 0)] = t.pb().toBeAdded().get(null) + t.pb().toBeStored().get(null);
            v[l.slot(Field.PROMISED, i, 0)] = t.ps().promised().get(null);
            v[l.slot(Field.OUTGOING, i, 0)] = t.ps().attempting(null);
            for (TRADE_TYPE tt : TRADE_TYPE.all) {
                v[l.slot(Field.INCOMING, i, tt.index + 1)] = t.pb().attempting(tt);
                v[l.slot(Field.QUEUED, i, tt.index + 1)] = t.pb().toBeAdded().get(tt) + t.pb().toBeStored().get(tt);
                v[l.slot(Field.PROMISED, i, tt.index + 1)] = t.ps().promised().get(tt);
                v[l.slot(Field.OUTGOING, i, tt.index + 1)] = t.ps().attempting(tt);
            }
            v[l.slot(Field.SELL_PRICE_CAP, i)] = t.ps().priceCapsI.get();
            v[l.slot(Field.SELL_LIMIT, i)] = t.ps().limit.get();
            v[l.slot(Field.BUY_PRICE_CAP, i)] = t.pb().priceCapsI.get();
            v[l.slot(Field.BUY_MIN_MONEY, i)] = t.pb().minMoney.get();
            v[l.slot(Field.BUY_LIMIT, i)] = t.pb().limit.get();
            v[l.slot(Field.BUY_LIMIT_SHARE, i)] = t.pb().limit.getD();
        }

        LIST<Race> races = RACES.all();
        for (int ri = 0; ri < races.size(); ri++) {
            Race r = races.get(ri);
            v[l.slot(Field.SLAVES, ri)] = STATS.POP().pop(r, HTYPES.SLAVE());
            v[l.slot(Field.SLAVE_PUNISH, ri)] = CRIMES.WAR().stat().punishment(HCLASSES.OTHER(), r).punish.index();
            v[l.slot(Field.CITIZENS, ri)] = FACTIONS.player().citizens(r);
            v[l.slot(Field.POP_RACE, ri)] = STATS.POP().POP.data(null).get(r, 0);
            v[l.slot(Field.PROSECUTION, ri)] = STATS.MULTIPLIERS().PROSECUTION.value(HCLASSES.CITIZEN(), r, 0);
            v[l.slot(Field.WAR_STANDING, ri)] = STATS.BATTLE().WAR.data(HCLASSES.CITIZEN()).getD(r, 0);
            v[l.slot(Field.TOURISM_PERMIT, ri)] = bool(TOURISM.permit(r));
        }
        v[l.slot(Field.STOCKADE_USED, 0)] = SETT.ROOMS().STOCKADE.punishUsed();
        v[l.slot(Field.STOCKADE_TOTAL, 0)] = SETT.ROOMS().STOCKADE.punishTotal();
        v[l.slot(Field.WAR_CRIMINALS, 0)] = CRIMES.WAR().stat().criminals(null);

        LIST<HCLASS> classes = HCLASSES.ALL();
        for (int ci = 0; ci <= classes.size(); ci++) {
            HCLASS c = ci == 0 ? null : classes.get(ci - 1);
            for (int ri = 0; ri <= races.size(); ri++) {
                Race r = ri == 0 ? null : races.get(ri - 1);
                v[l.slot(Field.PHYSICAL, ci, ri)] = POP.physical(c, r);
                v[l.slot(Field.NEXT_EXTRA, ci, ri)] = POP.next(c, r) - POP.tot(c, r);
                v[l.slot(Field.RETIREMENT, ci, ri)] = c == null || r == null ? 0
                        : STATS.WORK().RET.RETIREMENT_AGE.decree().getD(c, r);
                v[l.slot(Field.EVENT_CLASS_RACE, ci, ri)] = STATS.EVENT().stat().data(c).getD(r); // c null: every class, as HCLASS_RACE.clP() reads it
            }
        }
        v[l.slot(Field.POP_TOTAL, 0)] = STATS.POP().POP.data(null).get(null);
        v[l.slot(Field.CITY_SOLDIERS, 0)] = STATS.BATTLE().DIV.stat().data().get(null);
        v[l.slot(Field.MERCY, 0)] = AD.stats().mercy().getD(FACTIONS.player());
        v[l.slot(Field.EVENT_CURRENT, 0)] = GAME.EVENT().current() == null ? 0 : GAME.EVENT().current().allIndex + 1;

        LIST<Div> divs = GAME.ARMIES().player().divisions();
        LIST<EquipBattle> equips = STATS.EQUIP().BATTLE_ALL();
        for (int d = 0; d < divs.size(); d++) {
            Div div = divs.get(d);
            v[l.slot(Field.DIV_RACE, d)] = div.info.race() == null ? -1 : div.info.race().index();
            v[l.slot(Field.DIV_MEN, d)] = div.info.men();
            v[l.slot(Field.EVENT_DIV, d)] = STATS.EVENT().stat().div().getD(div);
            for (int e = 0; e < equips.size(); e++) {
                v[l.slot(Field.DIV_EQUIP, d, equips.get(e).indexMilitary())] = equips.get(e).target(div);
            }
        }
        for (EquipBattle e : equips) {
            int am = 0; // FWorth's count, which has no getter of its own
            for (int d = 0; d < divs.size(); d++) {
                am += divs.get(d).info.equipI(e) * divs.get(d).menNrOf();
            }
            v[l.slot(Field.EQUIP_IN_DIVS, e.indexMilitary())] = am;
        }
        fillGarrison(v, divs, equips);
        v[l.slot(Field.INVADING, 0)] = bool(SETT.INVADOR().invading());
        v[l.slot(Field.INVADING_POWER, 0)] = SETT.INVADOR().invadingPower();
        try {
            v[l.slot(Field.INVASION_REF, 0)] = invasionRef.getInt(SETT.INVADOR());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read Invador.ref", e);
        }

        Player player = FACTIONS.player();
        for (int i = 0; i < boosts.size(); i++) {
            v[l.slot(Field.BOOST, i)] = boosts.get(i).vGet(player);
            v[l.slot(Field.BOOST_CLP, i)] = boosts.get(i).vGet(HCLASS_RACE.clP());
        }
        v[l.slot(Field.RAID_SECURITY, 0)] = BOOSTABLES.CIVICS().RAID_SECURITY.get(HCLASS_RACE.clP());
        v[l.slot(Field.BARRACKS_UNLOCKED, 0)] = bool(SETT.ROOMS().BARRACKS.get(0).reqs.passes(player));
        v[l.slot(Field.PROXIMITY_TOLL, 0)] = RD.DIST().bProximityToll.get(HCLASSES.CITIZEN().get(null));
        for (int i = 0; i < values.size(); i++) {
            v[l.slot(Field.VALUE, i)] = values.get(i).d.getD(player);
        }
        for (int i = 0; i < unlocks.size(); i++) {
            v[l.slot(Field.UNLOCK, i)] = playerUnlock(unlocks.get(i));
        }
    }

    /** The capital's garrison as the world sees it: the game's own list and its own getters. */
    private void fillGarrison(double[] v, LIST<Div> divs, LIST<EquipBattle> equips) {
        ReportLayout l = layout;
        LIST<StatTraining> trainings = STATS.BATTLE().TRAINING_ALL;
        for (int n = 0; n < divs.size(); n++) {
            v[l.slot(Field.GARRISON, n)] = -1;
        }
        LIST<WDIV> garrison = RD.MILITARY().divisions(FACTIONS.player().capitolRegion());
        for (int n = 0; n < garrison.size(); n++) {
            WDIV w = garrison.get(n);
            int d = divisionOf(divs, w);
            v[l.slot(Field.GARRISON, n)] = d;
            v[l.slot(Field.GAR_MEN, d)] = w.men();
            v[l.slot(Field.GAR_EXPERIENCE, d)] = w.experience();
            for (int t = 0; t < trainings.size(); t++) {
                v[l.slot(Field.GAR_TRAINING, d, t)] = w.training(trainings.get(t));
            }
            for (EquipBattle e : equips) {
                v[l.slot(Field.GAR_EQUIP, d, e.indexMilitary())] = w.equip(e);
            }
        }
    }

    /** A garrison division's index: its settings are its city division's own. */
    private static int divisionOf(LIST<Div> divs, WDIV w) {
        for (int d = 0; d < divs.size(); d++) {
            if (divs.get(d).info == w.target()) {
                return d;
            }
        }
        throw new IllegalStateException("SOSDET: garrison division " + w.name() + " is not one of the city's");
    }

    private static double bool(boolean b) {
        return b ? 1 : 0;
    }
}

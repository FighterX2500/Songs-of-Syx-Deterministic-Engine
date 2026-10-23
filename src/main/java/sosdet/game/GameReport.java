package sosdet.game;

import game.GAME;
import game.battle.div.Div;
import game.faction.FACTIONS;
import game.tourism.TOURISM;
import init.race.RACES;
import init.race.Race;
import init.resources.RESOURCE;
import init.trade.TR;
import init.trade.TRADABLE;
import init.trade.TRADABLEO;
import init.trade.TRADE_TYPE;
import init.type.CRIMES;
import init.type.HCLASS;
import init.type.HCLASSES;
import init.type.HTYPES;
import settlement.main.SETT;
import settlement.stats.POP;
import settlement.stats.STATS;
import settlement.stats.equip.EquipBattle;
import snake2d.util.sets.LIST;
import sosdet.report.CitySource;
import sosdet.report.Field;
import sosdet.report.ReportLayout;
import world.army.AD;

/**
 * Takes the player's city report from the live city (ADR 0005, SOS-38): each field as the game's own code reads it.
 * It runs in the city part, where the report hooks read the live city, so it can call the patched methods.
 */
public final class GameReport implements CitySource {

    private final ReportLayout layout = new ReportLayout(TR.ALL().size(), TRADE_TYPE.all.size(), RACES.all().size(),
            HCLASSES.ALL().size(), GAME.ARMIES().player().divisions().size(), STATS.EQUIP().BATTLE_ALL().size());

    @Override
    public ReportLayout layout() {
        return layout;
    }

    @Override
    public int faction() {
        return FACTIONS.player().index();
    }

    @Override
    public void fill(double[] v) {
        ReportLayout l = layout;
        v[l.slot(Field.CREDITS, 0)] = FACTIONS.player().credits().getD();
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
            for (TRADE_TYPE tt : TRADE_TYPE.all) {
                v[l.slot(Field.INCOMING, i, tt.index + 1)] = t.pb().attempting(tt);
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
            }
        }
        v[l.slot(Field.POP_TOTAL, 0)] = STATS.POP().POP.data(null).get(null);
        v[l.slot(Field.CITY_SOLDIERS, 0)] = STATS.BATTLE().DIV.stat().data().get(null);
        v[l.slot(Field.MERCY, 0)] = AD.stats().mercy().getD(FACTIONS.player());

        LIST<Div> divs = GAME.ARMIES().player().divisions();
        LIST<EquipBattle> equips = STATS.EQUIP().BATTLE_ALL();
        for (int d = 0; d < divs.size(); d++) {
            Div div = divs.get(d);
            v[l.slot(Field.DIV_RACE, d)] = div.info.race() == null ? -1 : div.info.race().index();
            v[l.slot(Field.DIV_MEN, d)] = div.info.men();
            for (int e = 0; e < equips.size(); e++) {
                v[l.slot(Field.DIV_EQUIP, d, equips.get(e).indexMilitary())] = equips.get(e).target(div);
            }
        }
    }

    private static double bool(boolean b) {
        return b ? 1 : 0;
    }
}

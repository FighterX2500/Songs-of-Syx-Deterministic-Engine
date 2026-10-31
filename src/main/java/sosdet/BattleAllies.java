package sosdet;

import game.GAME;
import game.battle.Army;
import game.battle.div.Div;
import init.constant.Config;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.Induvidual;
import settlement.stats.STATS;
import world.battle.WBattles;
import world.battle.spec.WBattleResult;

/**
 * A battle with several humans on a side (mod spec §8, ADR 0009, SOS-118): what touches the game for it. The state is
 * the battle's own ({@link OwnBattle}); the rules are in {@link BattleRules}.
 */
final class BattleAllies {

    private static final int NONE = -1;
    /** A slot's width on the field, in tiles, and the gap between allied armies (mod spec §8). */
    private static final int ROW_TILES = 13;
    private static final int GAP_TILES = 20;
    private static final int ROW_DIVISIONS = 30;

    private BattleAllies() {
    }

    /** Who the prompt asks besides the two it opened for: the sides' other humans, unless they're fighting a battle. */
    static int[] asked(Session s, int main, int second, int[] a, int[] b) {
        int[] all = new int[a.length + b.length];
        int n = 0;
        for (int side = 0; side < 2; side++) {
            int[] humans = side == 0 ? a : b;
            if (second < 0 && !contains(humans, main)) {
                continue; // against NPCs a human fights only beside the one asked
            }
            for (int h : humans) {
                if (h != main && h != second && !s.localBusy(h) && !s.retired(h)) {
                    all[n++] = h;
                }
            }
        }
        return java.util.Arrays.copyOf(all, n);
    }

    private static boolean contains(int[] humans, int h) {
        for (int o : humans) {
            if (o == h) {
                return true;
            }
        }
        return false;
    }

    /** The armies of a local battle, without those of humans who aren't in it: they move on (SOS-119). */
    static int[] inBattle(BattleRoster b, int[] armies) {
        int[] kept = new int[armies.length];
        int n = 0;
        for (int a : armies) {
            game.faction.Faction f = world.WORLD.ENTITIES().armies.get(a).faction();
            if (!(f instanceof game.faction.player.Player) || b.commander(f.index()) >= 0) {
                kept[n++] = a;
            }
        }
        return java.util.Arrays.copyOf(kept, n);
    }

    /** Army {@code army}'s owner by division position, set on the battle at Engage, the same on every PC. */
    static void owners(BattleRoster b, int[] player, int[] enemy) {
        int n = Config.battle().DIVISIONS_PER_ARMY;
        int[] all = new int[2 * n];
        java.util.Arrays.fill(all, NONE);
        System.arraycopy(player, 0, all, 0, Math.min(player.length, n));
        System.arraycopy(enemy, 0, all, n, Math.min(enemy.length, n));
        b.owners = all;
        b.left = new Induvidual[2 * n][];
        for (int c = 0; c < b.commanders.length; c++) {
            b.sides[c] = b.sideIn(all, b.commanders[c], n);
        }
    }

    /** The army of commander {@code c} leaves the field at this step, on every PC; its survivors wait for the result. */
    static void leave(Session s, int c) {
        BattleRoster b = s.roster();
        int human = b.commanders[c];
        Army own = GAME.ARMIES().armies().get(b.sides[c]);
        int armyMen = 0;
        for (Div d : own.divisions()) {
            if (b.owners[d.index()] == human) {
                armyMen += STATS.BATTLE().DIV.stat().div().get(d);
            }
        }
        double perMan = BattleRules.leavingLoss(own.enemy().men(), armyMen, own.men(), WBattles.retreatPenalty);
        int[] keep = new int[b.owners.length];
        for (Div d : own.divisions()) {
            if (b.owners[d.index()] == human) {
                int men = STATS.BATTLE().DIV.stat().div().get(d);
                keep[d.index()] = men - BattleRules.divisionLoss(men, perMan, d.status().isFighting());
                b.left[d.index()] = new Induvidual[keep[d.index()]];
            }
        }
        int[] kept = new int[b.owners.length];
        for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
            if (!(e instanceof Humanoid)) {
                continue;
            }
            Humanoid h = (Humanoid) e;
            Div d = STATS.BATTLE().DIV.get(h);
            if (d == null || b.owners[d.index()] != human) {
                continue;
            }
            if (kept[d.index()] < keep[d.index()]) {
                Induvidual copy = new Induvidual(h.indu().hType(), h.indu().race());
                copy.copyFrom(h.indu());
                b.left[d.index()][kept[d.index()]++] = copy;
            }
            h.helloMyNameIsInigoMontoyaYouKilledMyFatherPrepareToDie();
        }
        for (int i = 0; i < kept.length; i++) {
            if (b.left[i] != null && kept[i] < b.left[i].length) {
                b.left[i] = java.util.Arrays.copyOf(b.left[i], kept[i]); // the rest of the division had broken and run
            }
        }
    }

    /** The result counts what left the field earlier by what it kept then. */
    static void survivors(BattleRoster b, Induvidual[][] player, int[] enemy) {
        if (b.left == null) {
            return;
        }
        int n = player.length;
        for (int i = 0; i < b.left.length; i++) {
            if (b.left[i] != null && i < n) {
                player[i] = b.left[i];
            } else if (b.left[i] != null) {
                enemy[i - n] = b.left[i].length;
            }
        }
    }

    /** At the result: several humans who won share it, each in turn; the first one's share goes in the prompt. */
    static void shares(Session s, WBattleResult r, boolean army0Lost, int[] men0, int[] men1) {
        BattleRoster b = s.roster();
        s.shares = null;
        s.resultLeft = b.goneHumans();
        int side = army0Lost ? 1 : 0;
        int[] winners = b.stillIn(side);
        if (winners.length == 0 || !b.allies()) {
            return;
        }
        int[] men = new int[winners.length];
        int[] bySide = side == 0 ? men0 : men1;
        int first = side * Config.battle().DIVISIONS_PER_ARMY;
        for (int w = 0; w < winners.length; w++) {
            for (int i = 0; i < bySide.length; i++) {
                if (b.owners[first + i] == winners[w]) {
                    men[w] += bySide[i];
                }
            }
        }
        s.shares = new ResultShares(winners, men, r.capturedRaces, r.lostResources);
        s.shares.fill(r.capturedRaces, r.lostResources);
    }

    /** Each commander's divisions, men and a boost value, the same on every PC. */
    static String line(BattleRoster b) {
        StringBuilder t = new StringBuilder();
        for (int c = 0; c < b.commanders.length; c++) {
            int divs = 0;
            int men = 0;
            double offence = 0;
            for (Army a : GAME.ARMIES().armies()) {
                for (Div d : a.divisions()) {
                    if (b.owners[d.index()] == b.commanders[c] && d.menNrOf() > 0) {
                        divs++;
                        men += d.menNrOf();
                        offence = game.boosting.BOOSTABLES.BATTLE().OFFENCE.get(d);
                    }
                }
            }
            long x = 0;
            int counted = 0;
            for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
                Div d = e instanceof Humanoid ? STATS.BATTLE().DIV.get((Humanoid) e) : null;
                if (d != null && b.owners[d.index()] == b.commanders[c]) {
                    x += e.tc().x();
                    counted++;
                }
            }
            t.append("human ").append(b.commanders[c]).append(" side ").append(b.sides[c]).append(b.gone[c] ? " gone" : "")
                    .append(": ").append(divs).append(" divisions, ").append(men).append(" men, offence ")
                    .append(offence).append(", mean tile x ").append(counted == 0 ? -1 : x / counted).append("; ");
        }
        return t.toString();
    }

    /** The number of groups a side deploys in: one per human on it, the first holding its NPC armies too. */
    static int groups(BattleRoster b, int side) {
        int n = 0;
        for (int c = 0; c < b.commanders.length; c++) {
            if (b.sides[c] == side) {
                n++;
            }
        }
        return Math.max(n, 1);
    }

    /** The deployment group of the division in position {@code di} of the side. */
    static int group(BattleRoster b, int side, int di) {
        int owner = b.owners[side * Config.battle().DIVISIONS_PER_ARMY + di];
        int g = 0;
        for (int c = 0; c < b.commanders.length; c++) {
            if (b.sides[c] == side) {
                if (b.commanders[c] == owner) {
                    return g;
                }
                g++;
            }
        }
        return 0;
    }

    /** How far from the middle of the side's line group {@code g} stands, so the groups stand 20 tiles apart. */
    static int groupOffset(BattleRoster b, int side, int g) {
        int groups = groups(b, side);
        int[] half = new int[groups];
        for (int di = 0; di < Config.battle().DIVISIONS_PER_ARMY; di++) {
            if (b.owners[side * Config.battle().DIVISIONS_PER_ARMY + di] != NONE || di == 0) {
                half[group(b, side, di)] += ROW_TILES;
            }
        }
        int total = GAP_TILES * (groups - 1);
        for (int i = 0; i < groups; i++) {
            half[i] = Math.min(half[i], ROW_DIVISIONS * ROW_TILES) / 2;
            total += 2 * half[i];
        }
        int at = -total / 2;
        for (int i = 0; i < g; i++) {
            at += 2 * half[i] + GAP_TILES;
        }
        return at + half[g];
    }
}

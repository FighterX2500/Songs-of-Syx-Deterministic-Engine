package sosdet.game;

import java.nio.BufferOverflowException;

import game.GAME;
import game.battle.div.Div;
import game.battle.setting.DivSettings;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.STATS;
import snake2d.util.sets.LIST;
import sosdet.hash.HashLog;
import sosdet.hash.StateSource;

/**
 * The battle state for the state hash (hook B13), read through the game's public members. Soldiers and other
 * entities as the rig hashed them (SOS-11, SOS-23); divisions by their counts, settings and orders; projectiles by
 * the bytes of their own save.
 */
public final class GameHash implements StateSource {

    /** Projectiles in flight at once can be many thousands, about 40 bytes each. */
    private static final int MAX_SAVE_BYTES = 1 << 23;

    private final GameCodec codec = new GameCodec(MAX_SAVE_BYTES);

    @Override
    public int divisions() {
        return GAME.ARMIES().divisions().size();
    }

    @Override
    public void hash(long[] sections, long[] divisions) {
        long others = HashLog.start();
        ENTITY[] all = SETT.ENTITIES().getAllEnts();
        int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
        for (int i = 0; i < max; i++) {
            ENTITY e = all[i];
            if (e == null || e.isRemoved()) {
                continue;
            }
            Div d = e instanceof Humanoid ? ((Humanoid) e).division() : null;
            long h = d != null ? divisions[d.index()] : others;
            h = HashLog.mix(h, i);
            h = HashLog.mix(h, e.id());
            h = HashLog.mix(h, e.body().x1());
            h = HashLog.mix(h, e.body().y1());
            h = HashLog.mix(h, Double.doubleToLongBits(e.speed.x()));
            h = HashLog.mix(h, Double.doubleToLongBits(e.speed.y()));
            if (e instanceof Humanoid) {
                h = HashLog.mix(h, Double.doubleToLongBits(STATS.NEEDS().INJURIES.COUNT.indu().getD(((Humanoid) e).indu())));
            } else {
                h = HashLog.mix(h, e.getClass().getName().hashCode());
            }
            if (d != null) {
                divisions[d.index()] = h;
            } else {
                others = h;
            }
        }
        sections[HashLog.ENTITIES] = others;
        sections[HashLog.DIVISIONS] = divisionsHash();
        sections[HashLog.PROJECTILES] = projectilesHash();
    }

    private long divisionsHash() {
        long h = HashLog.start();
        LIST<Div> divs = GAME.ARMIES().divisions();
        for (int i = 0; i < divs.size(); i++) {
            Div d = divs.get(i);
            h = HashLog.mix(h, d.men());
            h = HashLog.mix(h, d.deployed());
            h = HashLog.mix(h, Double.doubleToLongBits(d.morale()));
            DivSettings s = d.settings();
            h = HashLog.mix(h, (s.guard ? 1 : 0) | (s.charging ? 2 : 0) | (s.running ? 4 : 0) | (s.fireAtWill ? 8 : 0)
                    | (s.shouldNotMoveToFire ? 16 : 0) | (s.shouldFire ? 32 : 0) | (s.shouldbreak ? 64 : 0)
                    | (s.chargeSpeed ? 128 : 0) | (s.mustering() ? 256 : 0) | (s.moppingUp() ? 512 : 0));
            h = HashLog.mix(h, s.ammoI);
            h = HashLog.mix(h, s.formation.ordinal());
            h = codec.hash(h, d.order().path);
            h = codec.hash(h, d.order().dest);
            h = codec.hash(h, d.order().task);
        }
        return h;
    }

    private long projectilesHash() {
        try {
            SETT.PROJS().save(codec.hashBegin());
        } catch (BufferOverflowException e) {
            throw new IllegalStateException("SOSDET: projectiles' save data over " + MAX_SAVE_BYTES + " bytes", e);
        }
        return codec.hashEnd(HashLog.start());
    }

    @Override
    public String describeDivisions() {
        StringBuilder b = new StringBuilder();
        LIST<Div> divs = GAME.ARMIES().divisions();
        for (int i = 0; i < divs.size(); i++) {
            Div d = divs.get(i);
            if (d.men() == 0 && d.deployed() == 0) {
                continue;
            }
            DivSettings s = d.settings();
            b.append(String.format(java.util.Locale.ROOT,
                    "  %d: men %d, deployed %d, morale %016x, flags %s%s%s%s%s%s%s%s%s%s, ammo %d, formation %s,"
                            + " path %016x, dest %016x, task %016x%n",
                    i, d.men(), d.deployed(), Double.doubleToLongBits(d.morale()),
                    s.guard ? "G" : "-", s.charging ? "C" : "-", s.running ? "R" : "-", s.fireAtWill ? "F" : "-",
                    s.shouldNotMoveToFire ? "H" : "-", s.shouldFire ? "S" : "-", s.shouldbreak ? "B" : "-",
                    s.chargeSpeed ? "X" : "-", s.mustering() ? "M" : "-", s.moppingUp() ? "U" : "-", s.ammoI,
                    s.formation, codec.hash(HashLog.start(), d.order().path), codec.hash(HashLog.start(), d.order().dest),
                    codec.hash(HashLog.start(), d.order().task)));
        }
        return b.toString();
    }

    @Override
    public String divisionName(int index) {
        Div d = GAME.ARMIES().divisions().get(index);
        return (d.army() == GAME.ARMIES().player() ? "player" : "enemy") + " division " + d.indexArmy() + ", "
                + d.men() + " men here";
    }
}

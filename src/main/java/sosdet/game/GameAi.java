package sosdet.game;

import game.GAME;
import game.battle.div.Div;
import game.battle.formation.DIV_FORMATION;
import game.battle.formation.DivFormationImp;
import game.battle.thread.order.BattleOrder;
import game.battle.thread.order.BattleOrderPath;
import game.battle.thread.order.BattleOrderTask;
import settlement.main.SETT;
import settlement.room.main.Room;
import settlement.room.military.artillery.ArtilleryInstance;
import snake2d.util.file.SAVABLE;
import sosdet.ai.AiApplier;
import sosdet.ai.AiEntry;

/**
 * Applies AI log entries to the battle, on the simulation thread at the entry's step; and turns the general's
 * orders into entry payloads on the host's general thread. One per session.
 */
public final class GameAi implements AiApplier {

    private final GameCodec reader = new GameCodec();
    private final GameCodec writer = new GameCodec();
    // Scratch orders the entries are read into; the battle copies them (BattleOrder.Locked.set).
    private final BattleOrderPath path = new BattleOrderPath();
    private final DivFormationImp dest = new DivFormationImp();
    private final BattleOrderTask task = new BattleOrderTask();
    private long artilleryMissed;

    /** The general's thread only. */
    byte[] encode(SAVABLE order) {
        return writer.encode(order);
    }

    @Override
    public void apply(AiEntry e) {
        if (e.kind == AiEntry.ARTILLERY) {
            Room r = SETT.ROOMS().map.get(e.intAt(0), e.intAt(1));
            if (r instanceof ArtilleryInstance) {
                ((ArtilleryInstance) r).targetCooSet(e.intAt(2), e.intAt(3), false, false);
            } else {
                artilleryMissed++; // the artillery is gone by the stamped step, the same on every PC
            }
            return;
        }
        Div d = GAME.ARMIES().division((short) e.div);
        BattleOrder o = d.order();
        switch (e.kind) {
        case AiEntry.PATH:
            reader.decode(e.payload(), path);
            o.path.set(path);
            break;
        case AiEntry.DEST:
            reader.decode(e.payload(), dest);
            o.dest.set(dest);
            break;
        case AiEntry.TASK:
            reader.decode(e.payload(), task);
            o.task.set(task);
            break;
        case AiEntry.GUARD:
            d.settings().guard = e.intAt(0) != 0;
            break;
        case AiEntry.RUNNING:
            d.settings().running = e.intAt(0) != 0;
            break;
        case AiEntry.FIRE_AT_WILL:
            d.settings().fireAtWill = e.intAt(0) != 0;
            break;
        case AiEntry.FORMATION:
            d.settings().formation = DIV_FORMATION.all.get(e.intAt(0));
            break;
        default:
            throw new IllegalStateException("SOSDET: AI entry kind " + e.kind);
        }
    }

    long artilleryMissed() {
        return artilleryMissed;
    }
}

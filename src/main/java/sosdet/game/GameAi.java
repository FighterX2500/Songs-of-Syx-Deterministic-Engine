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
import snake2d.util.sets.ArrayList;
import sosdet.Hooks;
import sosdet.ai.AiApplier;
import sosdet.ai.AiEntry;
import sosdet.game.cmd.Commands;

/**
 * Applies the host's log entries to the battle, on the simulation thread at the entry's step: the AI general's
 * changes and the players' orders. Also turns orders into entry payloads: the general's on its own thread, a player's
 * on the game's thread, each with its own codec. One per session.
 */
public final class GameAi implements AiApplier {

    private final GameCodec reader = new GameCodec();
    private final GameCodec generalWriter = new GameCodec();
    private final GameCodec orderWriter = new GameCodec();
    // Scratch orders the entries are read into; the battle copies them (BattleOrder.Locked.set).
    private final BattleOrderPath path = new BattleOrderPath();
    private final DivFormationImp dest = new DivFormationImp();
    private final BattleOrderTask task = new BattleOrderTask();
    private long artilleryMissed;

    /** The general's thread only. */
    byte[] encode(SAVABLE order) {
        return generalWriter.encode(order);
    }

    /** The game's thread only: a player's order. */
    byte[] encodeOrder(SAVABLE order) {
        return orderWriter.encode(order);
    }

    /** Players' commands whose target was gone at their step (ADR 0002). */
    private long commandsSkipped;

    @Override
    public long commandsSkipped() {
        return commandsSkipped;
    }

    @Override
    public void apply(AiEntry e) {
        switch (e.kind) {
        case AiEntry.COMMAND:
            if (!Commands.apply(e.payload())) {
                commandsSkipped++; // its target was gone at its step, on every PC alike
            }
            return;
        case AiEntry.SPEED:
            Hooks.agreedSpeed(e.intAt(0) / 1000.0);
            return;
        case AiEntry.ARTILLERY:
        case AiEntry.ARTILLERY_MUSTER:
        case AiEntry.ARTILLERY_FIRE:
            artillery(e);
            return;
        case AiEntry.TELEPORT:
            ArrayList<Div> divs = new ArrayList<>(e.ints());
            for (int i = 0; i < e.ints(); i++) {
                divs.add(GAME.ARMIES().division((short) e.intAt(i)));
            }
            GAME.ARMIES().initAndTeleport(divs);
            return;
        default:
            division(GAME.ARMIES().division((short) e.div), e);
        }
    }

    private void division(Div d, AiEntry e) {
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
        case AiEntry.HOLD_FIRE:
            d.settings().shouldNotMoveToFire = e.intAt(0) != 0;
            break;
        case AiEntry.AMMO:
            d.settings().ammoI = (short) e.intAt(0);
            break;
        case AiEntry.MUSTER:
            d.settings().musteringSet(e.intAt(0) != 0);
            break;
        case AiEntry.MOP_UP:
            d.settings().moppingSet(e.intAt(0) != 0);
            break;
        default:
            throw new IllegalStateException("SOSDET: AI log entry kind " + e.kind);
        }
    }

    /** An artillery piece is found again at its stamped step by one of its tiles. */
    private void artillery(AiEntry e) {
        Room r = SETT.ROOMS().map.get(e.intAt(0), e.intAt(1));
        if (!(r instanceof ArtilleryInstance)) {
            artilleryMissed++; // the artillery is gone by the stamped step, the same on every PC
            return;
        }
        ArtilleryInstance a = (ArtilleryInstance) r;
        if (e.kind == AiEntry.ARTILLERY) {
            a.targetCooSet(e.intAt(2), e.intAt(3), e.intAt(4) != 0, e.intAt(5) != 0);
        } else if (e.kind == AiEntry.ARTILLERY_MUSTER) {
            a.muster(e.intAt(2) != 0);
        } else {
            a.fireAtWill(e.intAt(2) != 0);
        }
    }

    long artilleryMissed() {
        return artilleryMissed;
    }
}

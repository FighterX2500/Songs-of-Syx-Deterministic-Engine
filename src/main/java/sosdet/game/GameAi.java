package sosdet.game;

import java.util.ArrayDeque;

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
import sosdet.ai.Commanders;
import sosdet.game.cmd.CommandParts;
import sosdet.game.cmd.Commands;
import sosdet.game.cmd.HumanDeals;
import sosdet.game.cmd.MapBattles;

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
    /** World commands of this step, held for its world part; empty between steps. */
    private final ArrayDeque<WorldCommand> worldCommands = new ArrayDeque<>();

    private static final class WorldCommand {
        final byte[] payload;
        /** The PC that gave it. */
        final int pc;

        WorldCommand(byte[] payload, int pc) {
            this.payload = payload;
            this.pc = pc;
        }
    }

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

    /**
     * A world command applies at the start of its step's world part, in order: it reads the city through the report
     * and changes it through transactions, as the world part does (ADR 0005). During a map battle it is skipped, on
     * every PC alike: the battle's end loads the game saved before it, which would undo it (SOS-41).
     */
    @Override
    public void worldPart() {
        HumanDeals.expire(); // before the commands: an answer at the step a proposal expires is too late (SOS-72)
        WorldCommand c;
        while ((c = worldCommands.poll()) != null) {
            int human = Hooks.humanOf(c.pc);
            if (Hooks.retired(human)) {
                commandsSkipped++; // given before its human was removed, due after: nothing acts for a removed human (SOS-66)
                continue;
            }
            Hooks.currentHuman(human); // it acts for its PC's human (ADR 0004)
            try {
                if (MapBattles.worldCommandSkipped(c.pc) || !Commands.apply(c.payload)) {
                    commandsSkipped++;
                }
            } finally {
                Hooks.currentHuman(0);
            }
        }
    }

    @Override
    public void apply(AiEntry e) {
        switch (e.kind) {
        case AiEntry.COMMAND:
            byte[] payload = e.payload();
            if (CommandParts.world(payload)) {
                worldCommands.add(new WorldCommand(payload, e.pc));
                return;
            }
            if (!CommandParts.everyPc(payload) && !Hooks.cityCommandHere(e.pc)) {
                return; // own-city mode: another PC's own city
            }
            if (!Commands.apply(payload)) {
                commandsSkipped++; // its target was gone at its step, on every PC alike
            }
            return;
        case AiEntry.SPEED:
            // the agreed speed, then (since SOS-42) the host player's and the PC that held it down
            Hooks.agreedSpeed(e.intAt(0) / 1000.0, e.intAt(e.ints() > 1 ? 1 : 0) / 1000.0, e.ints() > 2 ? e.intAt(2) : -1);
            return;
        case AiEntry.ARTILLERY:
        case AiEntry.ARTILLERY_MUSTER:
        case AiEntry.ARTILLERY_FIRE:
            artillery(e);
            return;
        case AiEntry.HOLD:
            Hooks.desyncHold(e.step, e.intAt(0), ((long) e.intAt(1) << 32) | (e.intAt(2) & 0xFFFFFFFFL)); // SOS-53
            return;
        case AiEntry.REMOVE:
            Hooks.humanRemoved(e.intAt(0)); // SOS-66
            return;
        case AiEntry.VIEW:
            Hooks.viewEntry(e.step, e.intAt(0), e.intAt(1), e.intAt(2)); // SOS-82
            return;
        case AiEntry.TELEPORT:
            ArrayList<Div> divs = new ArrayList<>(e.ints());
            Commanders c = Hooks.commanders();
            for (int i = 0; i < e.ints(); i++) {
                if (c.allows(e.pc, e.intAt(i))) {
                    divs.add(GAME.ARMIES().division((short) e.intAt(i)));
                } else {
                    c.skip(); // placed by a PC that doesn't command it (SOS-15)
                }
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
        Commanders c = Hooks.commanders();
        if (!c.allowsArtillery(e.pc, a.army().index())) {
            c.skip(); // its source doesn't command that army's artillery (SOS-15)
            return;
        }
        c.claimArtillery(e.pc, a.army().index());
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

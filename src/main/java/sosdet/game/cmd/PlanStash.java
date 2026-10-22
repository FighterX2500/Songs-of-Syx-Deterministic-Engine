package sosdet.game.cmd;

import settlement.room.main.Room;
import sosdet.ai.CmdWriter;

/**
 * This PC's area-room plan around the simulation (ADR 0003). The game's room placer keeps its plan in the shared tile
 * stores, where the UI and the renderer see it; the simulation must not. Before the first step of a frame (and around
 * a resync save) the plan is lifted out: its tiles' values are kept here and the stores get what the simulation holds
 * without a plan. After the frame's steps they go back. The placer object itself (blueprint, bounds, history, upgrade,
 * structure, walls) is never touched by a lift, so a plan survives any number of steps.
 *
 * <p>A command that uses the placer inside a step (Build, a one-item room) first records the local plan
 * ({@link #meet()}); after the steps the plan is rebuilt from that record instead of restored. A plan whose tiles
 * another PC's room took meanwhile is rebuilt the same way, without those tiles and the furniture on them.
 *
 * <p>One per session. Game thread only. A lift and a restore allocate nothing.
 */
public final class PlanStash {

    /** The largest plan: a room's maximum size ({@code Room.MAX_SIZE}, V71.44). */
    private static final int MAX_TILES = Room.MAX_SIZE;

    /** False for a session without a running game (unit tests): nothing is lifted. */
    private final boolean game;
    private final PlanTiles tiles;
    /** The game's tile stores, reached at first use. */
    private PlanStores stores;
    private boolean lifted;
    /** The local plan as a command inside this frame's steps found it; rebuilt from this after the steps. */
    private PlanRecord local;

    public PlanStash() {
        this(true);
    }

    private PlanStash(boolean game) {
        this.game = game;
        tiles = new PlanTiles(game ? MAX_TILES : 0);
    }

    /** A stash for a session without a running game (unit tests): it never lifts anything. */
    public static PlanStash off() {
        return new PlanStash(false);
    }

    public boolean lifted() {
        return lifted;
    }

    /** Takes the local plan out of the shared tile stores, unless it is out already. */
    public void lift() {
        if (!game || lifted) {
            return;
        }
        lifted = true;
        tiles.clear();
        stores().read(tiles, true);
    }

    /**
     * Puts the local plan back after the frame's steps: as it was, or rebuilt from its record when a command used the
     * placer meanwhile or a room took some of its tiles.
     */
    public void restore() {
        if (!game || !lifted) {
            return;
        }
        lifted = false;
        PlanRecord rebuild = local;
        local = null;
        if (rebuild == null) {
            if (stores().free(tiles)) {
                stores().writeBack(tiles);
                tiles.clear();
                return;
            }
            rebuild = stores().record(tiles); // the placer is as the lift left it
        }
        tiles.clear();
        Plans.rebuildLocal(rebuild);
    }

    /** A load replaces the world and resets the placer: a lifted plan is gone with it. */
    public void discard() {
        lifted = false;
        local = null;
        tiles.clear();
    }

    /**
     * Inside the step, before a command uses the shared room placer: this PC's own plan is recorded first (once per
     * frame), and rebuilt from the record after the steps.
     */
    void meet() {
        if (!game) {
            return;
        }
        lift(); // a step has lifted it already; this covers a command applied outside the frame's steps
        if (local == null && stores().planning()) {
            local = stores().record(tiles);
        }
    }

    /**
     * The record of the local plan as it stands (the player pressing Build): from the stores between steps, from the
     * stash while it is lifted.
     */
    void capture(CmdWriter w) {
        if (lifted) {
            (local != null ? local : stores().record(tiles)).write(w);
            return;
        }
        tiles.clear();
        stores().read(tiles, false);
        PlanRecord r = stores().record(tiles);
        tiles.clear();
        r.write(w);
    }

    private PlanStores stores() {
        if (stores == null) {
            stores = new PlanStores();
        }
        return stores;
    }
}

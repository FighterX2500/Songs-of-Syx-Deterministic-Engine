package sosdet.game.cmd;

import java.util.List;

import sosdet.Hooks;
import sosdet.ai.CmdWriter;
import sosdet.game.GameStations;
import world.entity.army.WArmy;

/**
 * Armies stationed at another human's capital (mod spec §9, SOS-233, ADR 0011), ids 7980-7982: world commands, applied
 * at their step on every PC, for the human who sent them. Station: the army's human; End: the host sends it away, or
 * its human calls it back; Supply: the host fills its supplies from its own city.
 */
public final class Stations {

    static final int STATION = 7980;
    static final int END = 7981;
    static final int SUPPLY = 7982;

    static final List<Command> ALL = List.of(
            new Command(STATION, "armyStation", r -> {
                WArmy a = Args.army(r);
                int host = r.i();
                return GameStations.get().station(a, host, Hooks.step());
            }),
            new Command(END, "armyStationEnd", r -> GameStations.get().end(Args.army(r))),
            new Command(SUPPLY, "armyStationSupply", r -> GameStations.get().supply(Args.army(r))));

    private Stations() {
    }

    /** This PC's player stations {@code a} at the capital of the human in {@code host}: captured, applied at its step. */
    public static void station(WArmy a, int host) {
        capture(Args.army(new CmdWriter(STATION), a).i(host));
    }

    /** This PC's player sends {@code a} away from its capital, or calls it back: captured, applied at its step. */
    public static void end(WArmy a) {
        capture(Args.army(new CmdWriter(END), a));
    }

    /** This PC's player, the host, supplies {@code a} from its own city: captured, applied at its step. */
    public static void supply(WArmy a) {
        capture(Args.army(new CmdWriter(SUPPLY), a));
    }

    private static void capture(CmdWriter w) {
        if (!Commands.capturing()) {
            throw new IllegalStateException("SOSDET: a stationing order outside a session's play");
        }
        Commands.capture(w);
    }
}

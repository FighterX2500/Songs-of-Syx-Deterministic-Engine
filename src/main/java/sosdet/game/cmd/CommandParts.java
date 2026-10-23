package sosdet.game.cmd;

import sosdet.ai.CmdReader;

/** Which part of the step a command applies in (ADR 0005); plain Java, no game classes. */
public final class CommandParts {

    private CommandParts() {
    }

    /**
     * True for a command that acts on the world: the army, region and diplomacy commands, paying raiders and calling an
     * ally. They apply at the start of the world part, so they read the city through the report and change it through
     * transactions. The world-battle prompt's answers stay at the step's start, as in V0.1 (the prompt holds the
     * world), and so do the choices on event messages and the killer's suspect.
     */
    public static boolean world(byte[] payload) {
        return world(new CmdReader(payload).id());
    }

    static boolean world(int id) {
        return (id >= 7000 && id < WorldBattles.DECIDE) || (id >= 8000 && id < 9000) || id == Events.RAIDER_PAY
                || id == Events.RAIDER_ALLY_HELP;
    }
}

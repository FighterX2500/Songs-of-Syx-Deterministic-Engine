package sosdet.game.cmd;

import sosdet.ai.CmdReader;

/** Which part of the step a command applies in (ADR 0005); plain Java, no game classes. */
public final class CommandParts {

    private CommandParts() {
    }

    /**
     * True for a command that acts on the world: the army, region and diplomacy commands, the world-battle prompt's
     * decisions, paying raiders and calling an ally. They apply at the start of the world part, so they read the city
     * through the report and change it through transactions; a decision there closes the prompt before the world's
     * resources update, so the world holds the same steps as before. The yes/no questions (a world one and a city one
     * share the command), the choices on event messages and the killer's suspect stay at the step's start.
     */
    public static boolean world(byte[] payload) {
        return world(new CmdReader(payload).id());
    }

    /**
     * True for a command every PC applies in own-city mode (SOS-38): the world's, and the yes/no answers. A world
     * question opens on every PC; a city one only on the PC whose city asked it, and elsewhere its answer finds no
     * question. Any other command acts on its issuer's own city.
     */
    public static boolean everyPc(byte[] payload) {
        return everyPc(new CmdReader(payload).id());
    }

    static boolean everyPc(int id) {
        return world(id) || id == StepPrompts.ANSWER;
    }

    static boolean world(int id) {
        return (id >= 7000 && id < StepPrompts.ANSWER) || (id >= 8000 && id < 9000) || id == Events.RAIDER_PAY
                || id == Events.RAIDER_ALLY_HELP;
    }
}

package sosdet.game.cmd;

import sosdet.ai.CmdReader;

/** Which part of the step a command applies in (ADR 0005); plain Java, no game classes. */
public final class CommandParts {

    private CommandParts() {
    }

    /**
     * True for a command that acts on the world: the army, region and diplomacy commands, the world-battle prompt's
     * decisions, the sieges of humans' capitals (SOS-92), a local battle's result (SOS-93), paying raiders and calling an ally. They apply at the start of the world part, so they read the city
     * through the report and change it through transactions; a decision there closes the prompt before the world's
     * resources update, so the battle's armies wait the same steps as before (SOS-45). The yes/no questions (a world one and a city one
     * share the command), the choices on event messages and the killer's suspect stay at the step's start.
     */
    public static boolean world(byte[] payload) {
        return world(new CmdReader(payload).id());
    }

    /**
     * True for a command every PC applies in own-city mode (SOS-38): the world's. A city question's answer acts on its
     * issuer's own city, as any other command (SOS-46: city questions on two PCs may share a key).
     */
    public static boolean everyPc(byte[] payload) {
        return everyPc(new CmdReader(payload).id());
    }

    static boolean everyPc(int id) {
        return world(id);
    }

    static boolean world(int id) {
        return (id >= 7000 && id < StepPrompts.ANSWER) || id == StepPrompts.WORLD_ANSWER || (id >= Sieges.SALLY && id < 8000)
                || (id >= 8000 && id < 9000)
                || id == Events.RAIDER_PAY || id == Events.RAIDER_ALLY_HELP || id == MapBattles.LOCAL_RESULT || playerIdentity(id);
    }

    /**
     * The player's faction name, ruler name and banner (SOS-78): other players see them on the world map and in the
     * faction window, so every PC applies them, for the human who sent them. The division, room, mark and subject names
     * stay in the sender's own city.
     */
    private static boolean playerIdentity(int id) {
        return id == Names.FACTION_NAME || id == Names.RULER_NAME || id == Names.BANNER_TYPE || id == Names.BANNER
                || id == Names.PLAYER_BANNER;
    }
}

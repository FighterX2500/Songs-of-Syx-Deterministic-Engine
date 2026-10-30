package sosdet.game.cmd;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sosdet.ai.CmdWriter;

class CommandPartsTest {

    /** ADR 0005: world commands apply in the world part; city ones, and the yes/no questions, at the step's start. */
    @Test
    void worldCommandsAreTheArmyRegionDiplomacyAndRaiderOnes() {
        assertTrue(CommandParts.world(new CmdWriter(World.ARMY_MOVE).i(3).bytes()));
        assertTrue(CommandParts.world(World.RECRUIT));
        assertTrue(CommandParts.world(World.REGION_ATTACK));
        assertTrue(CommandParts.world(Diplomacy.DEAL_ACCEPT));
        assertTrue(CommandParts.world(Diplomacy.EMISSARIES));
        assertTrue(CommandParts.world(Events.RAIDER_PAY));
        assertTrue(CommandParts.world(Events.RAIDER_ALLY_HELP));
        assertTrue(CommandParts.world(WorldBattles.DECIDE));
        assertTrue(CommandParts.world(WorldBattles.SIEGE_RESULT));
        assertTrue(CommandParts.world(StepPrompts.WORLD_ANSWER));
        assertTrue(CommandParts.world(Diplomacy.TRIBUTE_ACCEPT));

        assertFalse(CommandParts.world(StepPrompts.ANSWER));
        assertFalse(CommandParts.world(Events.EVENT_CHOICE));
        assertFalse(CommandParts.world(Events.KILLER_SUSPECT));
        assertFalse(CommandParts.world(6999));
        assertFalse(CommandParts.world(1000));
    }

    /**
     * Own-city mode (SOS-38): a city command acts on its issuer's city only, a city question's answer too (SOS-46);
     * world ones and world questions' answers on every PC.
     */
    @Test
    void everyPcAppliesTheWorldsCommandsAndTheWorldsAnswers() {
        assertTrue(CommandParts.everyPc(new CmdWriter(World.ARMY_MOVE).i(3).bytes()));
        assertTrue(CommandParts.everyPc(Diplomacy.DEAL_ACCEPT));
        assertTrue(CommandParts.everyPc(StepPrompts.WORLD_ANSWER));
        assertFalse(CommandParts.everyPc(StepPrompts.ANSWER));
        assertFalse(CommandParts.everyPc(Events.EVENT_CHOICE));
        assertFalse(CommandParts.everyPc(Events.KILLER_SUSPECT));
        assertFalse(CommandParts.everyPc(1000));
    }

    @Test
    void aLocalBattlesResultIsAppliedByEveryPcAndTheBattlesOwnOrdersAreNot() {
        assertTrue(CommandParts.world(MapBattles.LOCAL_RESULT));
        assertTrue(CommandParts.everyPc(new CmdWriter(MapBattles.LOCAL_RESULT).i(0).bytes()));
        assertFalse(CommandParts.everyPc(MapBattles.RESTART));
    }

    /**
     * SOS-92: the siege commands act on the world's siege book, so every PC applies them, in the world part; the S92J rig
     * run desynced when the wait's test command applied on its sender's PC only.
     */
    @Test
    void theSiegeCommandsAreWorldCommands() {
        assertTrue(CommandParts.world(Sieges.SALLY));
        assertTrue(CommandParts.everyPc(new CmdWriter(Sieges.WAIT_PASSED).i(0).bytes()));
        assertTrue(CommandParts.everyPc(Sieges.ASSAULT_END));
        assertTrue(CommandParts.everyPc(Sieges.ASSAULT_TERMS));
        assertFalse(CommandParts.everyPc(StepPrompts.ANSWER));
    }

    /**
     * SOS-78: the player's faction name, ruler name and banner reach every PC, in the world part; the other names and
     * banners stay in the sender's city.
     */
    @Test
    void thePlayersNameAndBannerAreWorldCommands() {
        assertTrue(CommandParts.world(new CmdWriter(Names.FACTION_NAME).s("Ann's Realm").bytes()));
        assertTrue(CommandParts.everyPc(Names.RULER_NAME));
        assertTrue(CommandParts.everyPc(Names.PLAYER_BANNER));
        assertTrue(CommandParts.everyPc(Names.BANNER));
        assertTrue(CommandParts.everyPc(Names.BANNER_TYPE));

        assertFalse(CommandParts.everyPc(Names.DIVISION_NAME));
        assertFalse(CommandParts.everyPc(Names.ROOM_NAME));
        assertFalse(CommandParts.everyPc(Names.MARK_NAME));
        assertFalse(CommandParts.everyPc(Names.SUBJECT_NAME));
        assertFalse(CommandParts.everyPc(Names.DIVISION_BANNER));
        assertFalse(CommandParts.everyPc(Names.COLOUR));
    }
}

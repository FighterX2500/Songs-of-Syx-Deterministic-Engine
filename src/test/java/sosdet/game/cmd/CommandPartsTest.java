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

        assertFalse(CommandParts.world(StepPrompts.ANSWER));
        assertFalse(CommandParts.world(Events.EVENT_CHOICE));
        assertFalse(CommandParts.world(Events.KILLER_SUSPECT));
        assertFalse(CommandParts.world(6999));
        assertFalse(CommandParts.world(1000));
    }
}

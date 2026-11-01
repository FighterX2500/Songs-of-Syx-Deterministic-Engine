package sosdet;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameBuildTest {

    @Test
    void theSupportedBuildPasses() {
        assertNull(GameBuild.problem("0.71.44"));
    }

    @Test
    void anotherBuildNamesBothBuildsAndWhatToDo() {
        String problem = GameBuild.problem("0.71.45");
        assertTrue(problem.contains("0.71.44"), problem);
        assertTrue(problem.contains("0.71.45"), problem);
        assertTrue(problem.contains("disable SOSDET"), problem);
    }
}

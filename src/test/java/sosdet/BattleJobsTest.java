package sosdet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BattleJobsTest {

    private final BattleJobs jobs = new BattleJobs();
    private final List<String> ran = new ArrayList<>();

    private void step(long step) {
        jobs.run(step, () -> ran.add("orders"), () -> ran.add("centres"), () -> ran.add("trajectories"),
                () -> ran.add("general"), () -> ran.add("status"), true);
    }

    @Test
    void runsInAFixedOrderWithTheGeneralTwice() {
        step(1);
        assertEquals(List.of("orders", "centres", "trajectories", "general", "general"), ran);
    }

    @Test
    void theGeneralStaysOutOfTheStepOnHostAndPeer() {
        jobs.run(30, () -> ran.add("orders"), () -> ran.add("centres"), () -> ran.add("trajectories"),
                () -> ran.add("general"), () -> ran.add("status"), false);
        assertEquals(List.of("orders", "centres", "trajectories", "status"), ran);
    }

    @Test
    void statusRunsEveryThirtyStepsOfTheSession() {
        for (long s = 1; s <= 90; s++) {
            step(s);
        }
        assertEquals(3, ran.stream().filter("status"::equals).count());
        assertEquals("status", ran.get(ran.size() - 1));
    }

    @Test
    void workingIsTrueOnlyWhileAJobRuns() {
        List<Boolean> seen = new ArrayList<>();
        Runnable look = () -> seen.add(jobs.running());
        assertFalse(jobs.running());
        jobs.run(30, look, look, look, look, look, true);
        assertFalse(jobs.running());
        assertEquals(6, seen.size());
        assertTrue(seen.stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void aFailingJobStillEndsWorking() {
        try {
            jobs.run(1, () -> {
                throw new IllegalStateException("job failed");
            }, () -> { }, () -> { }, () -> { }, () -> { }, true);
        } catch (IllegalStateException expected) {
            assertFalse(jobs.running());
            return;
        }
        throw new AssertionError("the job's failure was swallowed");
    }
}

package sosdet;

import java.util.Locale;

/** A snapshot of a session's counters and step times. Step times in ms. */
public final class Stats {

    public final long step;
    public final long simulationDraws;
    /** Draws other threads made during a step; they went to the cosmetic stream. Expected 0. */
    public final long foreignDraws;
    public final long pathJobs;
    public final long pathNanos;
    public final long timedSteps;
    public final double meanMs;
    public final double p50Ms;
    public final double p95Ms;
    public final double p99Ms;
    public final double p999Ms;
    public final double maxMs;
    public final long stepsOver100Ms;
    public final long stepsOver1S;

    Stats(long step, long simulationDraws, long foreignDraws, long pathJobs, long pathNanos, long timedSteps,
            double meanMs, double p50Ms, double p95Ms, double p99Ms, double p999Ms, double maxMs,
            long stepsOver100Ms, long stepsOver1S) {
        this.step = step;
        this.simulationDraws = simulationDraws;
        this.foreignDraws = foreignDraws;
        this.pathJobs = pathJobs;
        this.pathNanos = pathNanos;
        this.timedSteps = timedSteps;
        this.meanMs = meanMs;
        this.p50Ms = p50Ms;
        this.p95Ms = p95Ms;
        this.p99Ms = p99Ms;
        this.p999Ms = p999Ms;
        this.maxMs = maxMs;
        this.stepsOver100Ms = stepsOver100Ms;
        this.stepsOver1S = stepsOver1S;
    }

    /** One JSON object, for logs. */
    public String toJson() {
        return String.format(Locale.ROOT,
                "{\"step\":%d,\"simulationDraws\":%d,\"foreignDraws\":%d,\"pathJobs\":%d,\"pathMsPerStep\":%.4f,"
                        + "\"timedSteps\":%d,\"meanMs\":%.3f,\"p50Ms\":%.1f,\"p95Ms\":%.1f,\"p99Ms\":%.1f,\"p999Ms\":%.1f,"
                        + "\"maxMs\":%.1f,\"over100Ms\":%d,\"over1S\":%d}",
                step, simulationDraws, foreignDraws, pathJobs, timedSteps == 0 ? 0 : pathNanos / 1e6 / timedSteps,
                timedSteps, meanMs, p50Ms, p95Ms, p99Ms, p999Ms, maxMs, stepsOver100Ms, stepsOver1S);
    }
}

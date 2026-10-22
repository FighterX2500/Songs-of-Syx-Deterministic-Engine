package sosdet;

import java.util.Locale;

import sosdet.ai.AiMode;

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
    public final AiMode aiMode;
    /** AI log entries applied (host and peer). */
    public final long aiApplied;
    public final long aiArtillery;
    /** AI log entries known but not applied yet. */
    public final int aiPending;
    /** Players' orders applied (speed changes included). */
    public final long ordersApplied;
    /** Players' commands applied, and skipped because their target was gone (ADR 0002). */
    public final long commandsApplied;
    public final long commandsSkipped;
    /** The speed every PC agreed on now; it sets the step length. */
    public final double agreedSpeed;
    /** State hashes taken since the last load, their cost in ms, and other PCs' hashes compared and missed. */
    public final long hashes;
    public final double hashMeanMs;
    public final double hashMaxMs;
    public final long hashesCompared;
    public final long hashesMissed;

    Stats(long step, long simulationDraws, long foreignDraws, long pathJobs, long pathNanos, long timedSteps,
            double meanMs, double p50Ms, double p95Ms, double p99Ms, double p999Ms, double maxMs,
            long stepsOver100Ms, long stepsOver1S, AiMode aiMode, long aiApplied, long aiArtillery, int aiPending,
            long ordersApplied, long commandsApplied, long commandsSkipped, double agreedSpeed, long hashes, double hashMeanMs, double hashMaxMs, long hashesCompared, long hashesMissed) {
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
        this.aiMode = aiMode;
        this.aiApplied = aiApplied;
        this.aiArtillery = aiArtillery;
        this.aiPending = aiPending;
        this.ordersApplied = ordersApplied;
        this.commandsApplied = commandsApplied;
        this.commandsSkipped = commandsSkipped;
        this.agreedSpeed = agreedSpeed;
        this.hashes = hashes;
        this.hashMeanMs = hashMeanMs;
        this.hashMaxMs = hashMaxMs;
        this.hashesCompared = hashesCompared;
        this.hashesMissed = hashesMissed;
    }

    /** One JSON object, for logs. */
    public String toJson() {
        return String.format(Locale.ROOT,
                "{\"step\":%d,\"simulationDraws\":%d,\"foreignDraws\":%d,\"pathJobs\":%d,\"pathMsPerStep\":%.4f,"
                        + "\"timedSteps\":%d,\"meanMs\":%.3f,\"p50Ms\":%.1f,\"p95Ms\":%.1f,\"p99Ms\":%.1f,\"p999Ms\":%.1f,"
                        + "\"maxMs\":%.1f,\"over100Ms\":%d,\"over1S\":%d,\"ai\":\"%s\",\"aiApplied\":%d,\"aiArtillery\":%d,\"aiPending\":%d,"
                        + "\"ordersApplied\":%d,\"commandsApplied\":%d,\"commandsSkipped\":%d,\"agreedSpeed\":%.2f,\"hashes\":%d,\"hashMeanMs\":%.3f,\"hashMaxMs\":%.1f,\"hashesCompared\":%d,\"hashesMissed\":%d}",
                step, simulationDraws, foreignDraws, pathJobs, timedSteps == 0 ? 0 : pathNanos / 1e6 / timedSteps,
                timedSteps, meanMs, p50Ms, p95Ms, p99Ms, p999Ms, maxMs, stepsOver100Ms, stepsOver1S, aiMode, aiApplied, aiArtillery,
                aiPending, ordersApplied, commandsApplied, commandsSkipped, agreedSpeed, hashes, hashMeanMs, hashMaxMs, hashesCompared, hashesMissed);
    }
}

package sosdet;

/**
 * SOSDET, the deterministic simulation engine for Songs of Syx V71.
 *
 * <p>The engine ships as a mod whose jar also carries the game classes its patch set changes
 * (engine ADR 0001). Its API grows from SOS-27 on (requirements §3).
 */
public final class Sosdet {

    /** Prefix for every log line and error message (AGENTS.md "Exceptions and Logging"). */
    public static final String NAME = "SOSDET";

    private Sosdet() {
    }

    /** The engine release from the mod jar's manifest, or {@code "dev"} when not loaded from the jar. */
    public static String version() {
        String version = Sosdet.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}

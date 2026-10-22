package sosdet.rig;

/**
 * How long the game update of the last frame took, from the rig-only patch 91-frame-timing in VIEW. The same
 * measure as SOS-23, so engine runs and vanilla runs compare like for like. Static because the patched VIEW
 * calls it; rig tooling only, never in a release.
 */
public final class FrameTiming {

    private static volatile long lastUpdateNanos = -1;

    private FrameTiming() {
    }

    public static void updated(long nanos) {
        lastUpdateNanos = nanos;
    }

    /** The update time of the frame before the current one (the current frame's update is still running). */
    static long lastUpdateNanos() {
        return lastUpdateNanos;
    }
}

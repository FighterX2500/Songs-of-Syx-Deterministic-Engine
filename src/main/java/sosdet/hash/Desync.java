package sosdet.hash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** The first step whose hash differs from another PC's: what differs there. */
public final class Desync {

    /** The hashed step. */
    public final long step;
    /** The other PC, as the mod numbered it in {@code hashIn}. */
    public final int pc;
    /** This PC's last step when it noticed. */
    public final long noticedAt;
    private final long[] local;
    private final long[] remote;
    private final int[] divisions;

    private final long[] clockParts;
    private final long[] localParts;
    private final long[] remoteParts;

    Desync(long step, int pc, long noticedAt, long[] local, long[] remote, int[] divisions, long[] clockParts,
            long[] localParts, long[] remoteParts) {
        this.step = step;
        this.pc = pc;
        this.noticedAt = noticedAt;
        this.local = local.clone();
        this.remote = remote.clone();
        this.divisions = divisions.clone();
        this.clockParts = clockParts.clone();
        this.localParts = localParts.clone();
        this.remoteParts = remoteParts.clone();
    }

    /** The names of the sections that differ, in {@link HashLog#SECTION_NAMES} order. */
    public List<String> sections() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < HashLog.SECTIONS; i++) {
            if (local[i] != remote[i]) {
                names.add(HashLog.SECTION_NAMES[i]);
            }
        }
        return names;
    }

    /**
     * The names of the world section's parts whose running hash differs, in {@link HashLog#WORLD_PART_NAMES} order
     * (SOS-250). The first is where the world first went differently; each part's hash runs on from the one before, so
     * every later part differs with it. Empty when the world section is the same.
     */
    public List<String> worldParts() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < HashLog.WORLD_PARTS; i++) {
            if (localParts[i] != remoteParts[i]) {
                names.add(HashLog.WORLD_PART_NAMES.get(i));
            }
        }
        return names;
    }

    /** The divisions whose soldiers differ, by index. */
    public int[] divisions() {
        return divisions.clone();
    }

    /**
     * The desync report (requirements §4): the step, the parts of the state that differ, and what was applied
     * just before. {@code recentAi} and {@code recentOrders} are lines, oldest first.
     */
    public String report(StateSource names, List<String> recentAi, List<String> recentOrders) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "SOSDET desync at step %d against PC %d (noticed at step %d)%n%n", step, pc,
                noticedAt));
        b.append("Sections (this PC / the other):\n");
        List<String> parts = worldParts();
        for (int i = 0; i < HashLog.SECTIONS; i++) {
            String from = i == HashLog.WORLD && !parts.isEmpty() ? ", from part " + parts.get(0) + " on (" + parts.size()
                    + " of " + HashLog.WORLD_PARTS + " parts)" : "";
            b.append(String.format(Locale.ROOT, "  %-12s %016x %016x%s%s%n", HashLog.SECTION_NAMES[i], local[i], remote[i],
                    local[i] != remote[i] ? "  DIFFERS" : "", from));
        }
        b.append(String.format(Locale.ROOT, "\nClock on this PC: %d world draws, %d AI log entries applied, commanders %016x%n",
                clockParts[0], clockParts[1], clockParts[2]));
        b.append("\nDivisions whose soldiers differ: ").append(divisions.length == 0 ? "none" : "").append('\n');
        for (int d : divisions) {
            b.append("  ").append(d).append(": ").append(names.divisionName(d)).append('\n');
        }
        b.append("\nDivisions on this PC at step ").append(step).append(" (compare with the other PC's report):\n");
        b.append(names.describeDivisions(step));
        if (local[HashLog.ENTITIES] != remote[HashLog.ENTITIES]) {
            b.append("\nEntities outside divisions at step ").append(step).append(" (compare with the other PC's report):\n");
            b.append(names.describeEntities(step));
        }
        if (local[HashLog.WORLD] != remote[HashLog.WORLD]) {
            b.append("\nThe world at step ").append(step).append(" (compare with the other PC's report):\n");
            boolean[] differs = new boolean[HashLog.WORLD_PARTS];
            for (int i = 0; i < differs.length; i++) {
                differs[i] = localParts[i] != remoteParts[i];
            }
            b.append(names.describeWorld(step, differs));
        }
        b.append("\nAI log entries applied last (step, sequence, kind, division):\n");
        lines(b, recentAi);
        b.append("\nPlayer orders applied last:\n");
        lines(b, recentOrders);
        return b.toString();
    }

    private static void lines(StringBuilder b, List<String> lines) {
        if (lines.isEmpty()) {
            b.append("  none\n");
        }
        for (String l : lines) {
            b.append("  ").append(l).append('\n');
        }
    }

    @Override
    public String toString() {
        return "desync at step " + step + " against PC " + pc + ": " + sections() + ", divisions "
                + Arrays.toString(divisions);
    }
}

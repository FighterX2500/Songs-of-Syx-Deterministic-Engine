package sosdet.report;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * The world marks a scripted event set in the city part (B6) that the world part has not applied yet: they travel in
 * the city's next report, so until it applies, the city reads a mark as the world has it overlaid with what it sent.
 * One value per target kind (region, faction, royal), mark kind (the event flag, the event mark) and target index.
 */
public final class EventMarks {

    public static final int TARGETS = 3;
    public static final int KINDS = 2;

    private final int[][] value = new int[TARGETS * KINDS][];
    private final int[][] inFlight = new int[TARGETS * KINDS][];

    public EventMarks() {
        clear();
    }

    /** What the city reads: the last value it sent while that is in flight, otherwise the world's. */
    public int read(int target, int kind, int index, int world) {
        int k = slot(target, kind);
        return index >= 0 && index < inFlight[k].length && inFlight[k][index] > 0 ? value[k][index] : world;
    }

    /** The city sets a mark: true when that changes what it reads, so the change must travel to the world. */
    public boolean set(int target, int kind, int index, int v, int world) {
        if (index < 0) {
            throw new IllegalArgumentException("SOSDET: an event mark on target index " + index);
        }
        if (read(target, kind, index, world) == v) {
            return false;
        }
        int k = slot(target, kind);
        if (index >= value[k].length) {
            int n = Math.max(index + 1, value[k].length * 2);
            value[k] = Arrays.copyOf(value[k], n);
            inFlight[k] = Arrays.copyOf(inFlight[k], n);
        }
        value[k][index] = v;
        inFlight[k][index]++;
        return true;
    }

    /** The world part applied one mark the city sent. */
    public void applied(int target, int kind, int index) {
        int k = slot(target, kind);
        if (index >= 0 && index < inFlight[k].length && inFlight[k][index] > 0) {
            inFlight[k][index]--;
        }
    }

    public void clear() {
        for (int k = 0; k < value.length; k++) {
            value[k] = new int[0];
            inFlight[k] = new int[0];
        }
    }

    /** Writes the marks in flight, for the city's state on another PC (a city view, SOS-82). */
    public void writeTo(DataOutputStream out) throws IOException {
        for (int k = 0; k < value.length; k++) {
            out.writeInt(value[k].length);
            for (int i = 0; i < value[k].length; i++) {
                out.writeInt(value[k][i]);
                out.writeInt(inFlight[k][i]);
            }
        }
    }

    /** Replaces the marks in flight with what {@link #writeTo} wrote. */
    public void readFrom(DataInputStream in) throws IOException {
        for (int k = 0; k < value.length; k++) {
            int n = in.readInt();
            value[k] = new int[n];
            inFlight[k] = new int[n];
            for (int i = 0; i < n; i++) {
                value[k][i] = in.readInt();
                inFlight[k][i] = in.readInt();
            }
        }
    }

    private static int slot(int target, int kind) {
        if (target < 0 || target >= TARGETS || kind < 0 || kind >= KINDS) {
            throw new IllegalArgumentException("SOSDET: no event mark " + target + ", " + kind);
        }
        return target * KINDS + kind;
    }
}

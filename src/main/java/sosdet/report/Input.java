package sosdet.report;

import java.util.Arrays;

/**
 * Something the city part did to the world (ADR 0005 §2): carried in the city's next report and applied when that
 * report applies, in the world part, on every PC. A kind, a number, and the game's own save bytes of what moved.
 */
public final class Input {

    public final InputKind kind;
    public final int arg;
    private final byte[] data;

    public Input(InputKind kind, int arg, byte[] data) {
        if (kind == null) {
            throw new IllegalArgumentException("SOSDET: an input with no kind");
        }
        this.kind = kind;
        this.arg = arg;
        this.data = data.clone();
    }

    public byte[] data() {
        return data.clone();
    }

    int length() {
        return data.length;
    }

    byte[] raw() {
        return data;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Input)) {
            return false;
        }
        Input in = (Input) o;
        return in.kind == kind && in.arg == arg && Arrays.equals(in.data, data);
    }

    @Override
    public int hashCode() {
        return (kind.hashCode() * 31 + arg) * 31 + Arrays.hashCode(data);
    }
}

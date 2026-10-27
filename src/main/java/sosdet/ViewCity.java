package sosdet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import sosdet.report.Input;
import sosdet.report.InputKind;

/**
 * A city as it moves to another PC for a view (plan 0012, SOS-82): the owner's game save, and the engine's state of
 * that city the save leaves out. The same state keeps the viewer's own city while it is unloaded. Protocol version 1.
 */
final class ViewCity {

    static final int MAGIC = 0x53445643; // "SDVC"
    static final int VERSION = 1;

    /** The step the save was taken at: the view's freeze step. */
    final long step;
    final byte[] state;
    /** The world's state no save holds: move remainders and pool sizes ({@code GameWorldLoad#unsaved}). */
    final double[] moves;
    final byte[] save;

    private ViewCity(long step, byte[] state, double[] moves, byte[] save) {
        this.step = step;
        this.state = state;
        this.moves = moves;
        this.save = save;
    }

    static byte[] toBytes(long step, byte[] state, double[] moves, byte[] save) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(save.length + state.length + 32);
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(step);
            out.writeInt(state.length);
            out.write(state);
            out.writeInt(moves.length);
            for (double m : moves) {
                out.writeDouble(m);
            }
            out.writeInt(save.length);
            out.write(save);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: cannot write a city for a view", e);
        }
        return b.toByteArray();
    }

    static ViewCity fromBytes(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IllegalStateException("SOSDET: not a view's city of version " + VERSION + " (magic "
                        + Integer.toHexString(magic) + ", version " + version + ")");
            }
            long step = in.readLong();
            byte[] state = new byte[in.readInt()];
            in.readFully(state);
            double[] moves = new double[in.readInt()];
            for (int i = 0; i < moves.length; i++) {
                moves[i] = in.readDouble();
            }
            byte[] save = new byte[in.readInt()];
            in.readFully(save);
            return new ViewCity(step, state, moves, save);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: a view's city is cut short", e);
        }
    }

    /**
     * This PC's city's engine state now: its player update timer, the inputs not yet reported, and the supplies and
     * event marks in flight.
     */
    static byte[] cityState(Session s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeDouble(s.playerUpdateAcc);
            out.writeInt(s.cityInputs.size());
            for (Input i : s.cityInputs) {
                out.writeInt(i.kind.ordinal());
                out.writeInt(i.arg);
                byte[] data = i.data();
                out.writeInt(data.length);
                out.write(data);
            }
            s.sentSupplies.writeTo(out);
            s.eventMarks.writeTo(out);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: cannot write a city's state", e);
        }
        return b.toByteArray();
    }

    /** Puts back a city's engine state {@link #cityState} took. */
    static void restoreCity(Session s, byte[] state) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(state))) {
            s.playerUpdateAcc = in.readDouble();
            s.cityInputs.clear();
            int n = in.readInt();
            InputKind[] kinds = InputKind.values();
            for (int k = 0; k < n; k++) {
                int kind = in.readInt();
                if (kind < 0 || kind >= kinds.length) {
                    throw new IllegalStateException("SOSDET: a city's state has an input of kind " + kind);
                }
                int arg = in.readInt();
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                s.cityInputs.add(new Input(kinds[kind], arg, data));
            }
            s.sentSupplies.readFrom(in);
            s.eventMarks.readFrom(in);
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: a city's state is cut short", e);
        }
    }
}

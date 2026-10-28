package sosdet.report;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * The army supplies the city part sent (B2) that the world part has not applied yet: they travel in the city's next
 * report, so until it applies, the city reads an army's supply as the world has it plus what it sent. One count per
 * supply and army slot, kept with the army's iteration so a slot reused by a new army reads nothing.
 */
public final class SentSupplies {

    private int armies;
    private int[] amount = new int[0];
    private int[] iteration = new int[0];
    private int slotsUsed;

    /** The city sent {@code delta} of a supply to an army. */
    public void sent(int supply, int army, int armyIteration, int delta, int supplies, int maxArmies) {
        if (amount.length != supplies * maxArmies) {
            armies = maxArmies;
            amount = new int[supplies * maxArmies];
            iteration = new int[supplies * maxArmies];
            slotsUsed = 0;
        }
        int k = supply * armies + army;
        if (amount[k] != 0 && iteration[k] != armyIteration) {
            amount[k] = 0; // that army is gone; what it was sent is lost with it
            slotsUsed--;
        }
        if (amount[k] == 0 && delta != 0) {
            slotsUsed++;
        }
        amount[k] += delta;
        iteration[k] = armyIteration;
        if (amount[k] == 0 && delta != 0) {
            slotsUsed--;
        }
    }

    /** The world part applied what was sent. */
    public void applied(int supply, int army, int armyIteration, int delta) {
        int k = supply * armies + army;
        if (k < 0 || k >= amount.length || iteration[k] != armyIteration || amount[k] == 0) {
            return;
        }
        amount[k] -= delta;
        if (amount[k] == 0) {
            slotsUsed--;
        }
    }

    /** What the city sent the army that the world has not applied yet; no allocation. */
    public int pending(int supply, int army, int armyIteration) {
        if (slotsUsed == 0) {
            return 0;
        }
        int k = supply * armies + army;
        return k >= 0 && k < amount.length && iteration[k] == armyIteration ? amount[k] : 0;
    }

    public void clear() {
        Arrays.fill(amount, 0);
        slotsUsed = 0;
    }

    /** Writes what is in flight, for the city's state on another PC (a city view, SOS-82). */
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(armies);
        out.writeInt(slotsUsed);
        out.writeInt(amount.length);
        for (int i = 0; i < amount.length; i++) {
            out.writeInt(amount[i]);
            out.writeInt(iteration[i]);
        }
    }

    /** Replaces what is in flight with what {@link #writeTo} wrote. */
    public void readFrom(DataInputStream in) throws IOException {
        armies = in.readInt();
        slotsUsed = in.readInt();
        int n = in.readInt();
        amount = new int[n];
        iteration = new int[n];
        for (int i = 0; i < n; i++) {
            amount[i] = in.readInt();
            iteration[i] = in.readInt();
        }
    }
}

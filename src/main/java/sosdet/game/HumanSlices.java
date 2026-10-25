package sosdet.game;

import java.io.IOException;

/**
 * The engine's per-human save sections name each human by its slot; the game keeps that human's values in the slice of
 * its ordinal among the human slots (SOS-46 M3, M4). Slot 0's slice is the game's own fields, saved in the game's own
 * sections, so the engine's sections hold only the others.
 */
final class HumanSlices {

    private HumanSlices() {
    }

    /**
     * The slice of the human in {@code slot}: its place among {@code slots} (the loaded game's human slots, in slot
     * order, slot 0 first). Slot 0 and a slot no human holds are a broken save.
     */
    static int ordinal(int[] slots, int slot) throws IOException {
        for (int k = 1; k < slots.length; k++) {
            if (slots[k] == slot) {
                return k;
            }
        }
        throw new IOException("SOSDET: per-human data of slot " + slot + ", which is no human other than slot 0's");
    }
}

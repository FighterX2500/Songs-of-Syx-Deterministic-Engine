package sosdet.game.cmd;

import game.faction.FBanner;
import snake2d.util.color.ColorImp;

/**
 * The player's banner while this PC's player edits it in the profile, before it is sent (SOS-32). The profile's colour
 * sliders and shape arrows change the banner on this PC only, as in vanilla, so the previews and the world map follow
 * a drag at once; the first change keeps the banner as it was. Accept sends the whole banner as one command, which
 * every PC applies at its step; Cancel, or closing the window, puts the kept banner back. No step reads the banner's
 * shape or colours and the state hash doesn't cover them, so a draft never splits the copies.
 *
 * <p>One per session. Game thread only.
 */
public final class BannerDraft {

    /** A banner's values: its shape, then red, green and blue of the background, foreground, border and pole. */
    static final int VALUES = 13;

    private final int[] kept = new int[VALUES];
    private boolean open;

    /** True while this PC's player has changed the banner and not yet accepted or cancelled. */
    boolean open() {
        return open;
    }

    /** A change is about to be made: the first one keeps the banner as it is. */
    void keep(FBanner b) {
        if (open) {
            return;
        }
        read(b, kept);
        open = true;
    }

    /** Accept: the banner as drafted, to be sent; the draft ends. */
    int[] take(FBanner b) {
        int[] v = new int[VALUES];
        read(b, v);
        open = false;
        return v;
    }

    /** Cancel: the kept banner goes back; true if there was a draft. */
    boolean cancel(FBanner b) {
        if (!open) {
            return false;
        }
        write(b, kept); // still open: the shape's hook keeps nothing new
        open = false;
        return true;
    }

    /** A banner command applied over this PC's draft: its values win, and the draft ends. */
    void drop() {
        open = false;
    }

    static void read(FBanner b, int[] v) {
        v[0] = b.bannerType();
        ColorImp[] parts = parts(b);
        for (int p = 0; p < parts.length; p++) {
            v[1 + p * 3] = parts[p].red() & 0xFF;
            v[2 + p * 3] = parts[p].green() & 0xFF;
            v[3 + p * 3] = parts[p].blue() & 0xFF;
        }
    }

    /** Sets the banner's values; its shape goes through {@code bannerTypeSet}, so call it where that isn't captured. */
    static void write(FBanner b, int[] v) {
        b.bannerTypeSet(v[0]);
        ColorImp[] parts = parts(b);
        for (int p = 0; p < parts.length; p++) {
            parts[p].setRed(v[1 + p * 3]);
            parts[p].setGreen(v[2 + p * 3]);
            parts[p].setBlue(v[3 + p * 3]);
        }
    }

    static ColorImp[] parts(FBanner b) {
        return new ColorImp[] {b.colorBG(), b.colorFG(), b.colorBorder(), b.colorPole()};
    }
}

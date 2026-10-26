package sosdet.game.cmd;

import game.GAME;
import game.battle.DivisionBanners.DivisionBanner;
import game.faction.FACTIONS;
import game.faction.FBanner;
import init.sprite.BitmapSprite;
import snake2d.util.color.ColorImp;
import sosdet.hash.HashLog;

/**
 * A banner while this PC's player edits it, before it is sent (SOS-32, SOS-40): the player's banner in the profile, or
 * one of the division banners in the division editor's banner popup. The colour sliders, the shape arrows and the pixel
 * editor change the banner on this PC only, as in vanilla, so the previews and the world map follow a drag at once; the
 * first change keeps the banner as it was. Accept sends the whole banner as one command, which every PC applies at its
 * step; Cancel, or closing the window or popup, puts the kept banner back. No step reads a banner's shape, colours or
 * pixels. The state hash covers every player's banner (SOS-78), so it reads this PC's player's banner as every PC has
 * it: the kept one while a draft is open, and the one before the last Accept until that Accept's command applies.
 *
 * <p>One draft at a time, one per session. Game thread only.
 */
public final class BannerDraft {

    /** A draft's target: the player's banner; 0 and up is a division banner's index. */
    static final int FACTION = -1;
    /** No banner: no draft, or a colour or sprite that is no banner's. */
    static final int NONE = -2;

    /** The player's banner's values: its shape, then red, green and blue of the background, foreground, border, pole. */
    static final int FACTION_VALUES = 13;
    /** A division banner's values: red, green and blue of its symbol, then of its background. */
    static final int DIVISION_VALUES = 6;
    /** The pixels, 32 to an int, after the values. */
    static final int PIXELS = (BitmapSprite.AREA + 31) / 32;

    private final int[] kept = new int[FACTION_VALUES + PIXELS];
    private int target = NONE;
    /** The player's banner as every PC has it, while an Accept of it is on its way to its step (SOS-78). */
    private final int[] confirmed = new int[FACTION_VALUES + PIXELS];
    /** The player's banner's Accepts sent and not yet applied. */
    private int inFlight;

    /** True while this PC's player has changed a banner and not yet accepted or cancelled. */
    boolean open() {
        return target != NONE;
    }

    /** The open draft's target: {@link #FACTION}, a division banner's index, or {@link #NONE}. */
    int target() {
        return target;
    }

    /** A change to {@code t} is about to be made: the first one keeps the banner as it is. Close another draft first. */
    void keep(int t) {
        if (target == t) {
            return;
        }
        if (target != NONE) {
            throw new IllegalStateException("banner draft for " + target + " still open, keeping " + t);
        }
        if (t == FACTION && inFlight > 0) {
            System.arraycopy(confirmed, 0, kept, 0, kept.length); // the banner shows an Accept the others don't have yet
        } else {
            read(t, kept);
        }
        target = t;
    }

    /** Accept: the banner as drafted, to be sent; the draft ends. */
    int[] take() {
        int[] v = new int[size(target)];
        read(target, v);
        if (target == FACTION && inFlight++ == 0) {
            System.arraycopy(kept, 0, confirmed, 0, kept.length);
        }
        target = NONE;
        return v;
    }

    /** Cancel: the kept banner goes back; true if there was a draft. */
    boolean cancel() {
        if (target == NONE) {
            return false;
        }
        write(target, kept); // still open: the hooks keep nothing new
        target = NONE;
        return true;
    }

    /**
     * This PC's banner command for {@code t} applied with values {@code v}: they win over this PC's draft, which ends,
     * and every PC has them now.
     */
    void drop(int t, int[] v) {
        if (t == FACTION && inFlight > 0) {
            inFlight--;
            System.arraycopy(v, 0, confirmed, 0, Math.min(v.length, confirmed.length));
        }
        if (target == t) {
            target = NONE;
        }
    }

    /** A load built a new game: no draft is open, and no Accept is on its way over it. */
    public void reset() {
        target = NONE;
        inFlight = 0;
    }

    /**
     * Mixes the player banner {@code b} into {@code h} as every PC has it (SOS-78): for this PC's own player
     * ({@code own}), the banner before its open draft or its Accepts on their way; otherwise as it is. The same values,
     * in the same order, as a command carries. No allocation: the state hash runs every hashed step.
     */
    public long hash(long h, FBanner b, boolean own) {
        if (own && inFlight > 0) {
            return mixAll(h, confirmed);
        }
        if (own && target == FACTION) {
            return mixAll(h, kept);
        }
        return mixBanner(h, b);
    }

    /** Mixes banner {@code b} into {@code h} as it is: what {@link #hash} mixes when no draft is over it. */
    public static long mixBanner(long h, FBanner b) {
        h = HashLog.mix(h, b.bannerType());
        h = mixColour(h, b.colorBG());
        h = mixColour(h, b.colorFG());
        h = mixColour(h, b.colorBorder());
        h = mixColour(h, b.colorPole());
        for (int i = 0; i < PIXELS; i++) {
            int bits = 0;
            for (int p = i * 32; p < Math.min(BitmapSprite.AREA, i * 32 + 32); p++) {
                if (b.sprite.is(p)) {
                    bits |= 1 << (p % 32);
                }
            }
            h = HashLog.mix(h, bits);
        }
        return h;
    }

    private static long mixColour(long h, ColorImp c) {
        h = HashLog.mix(h, c.red() & 0xFF);
        h = HashLog.mix(h, c.green() & 0xFF);
        return HashLog.mix(h, c.blue() & 0xFF);
    }

    private static long mixAll(long h, int[] v) {
        for (int i = 0; i < FACTION_VALUES + PIXELS; i++) {
            h = HashLog.mix(h, v[i]);
        }
        return h;
    }

    /** The values a command carries for {@code t}: the banner's own, then the pixels. */
    static int size(int t) {
        return (t == FACTION ? FACTION_VALUES : DIVISION_VALUES) + PIXELS;
    }

    static void read(int t, int[] v) {
        int i = 0;
        if (t == FACTION) {
            v[i++] = FACTIONS.player().banner().bannerType();
        }
        for (ColorImp c : parts(t)) {
            v[i++] = c.red() & 0xFF;
            v[i++] = c.green() & 0xFF;
            v[i++] = c.blue() & 0xFF;
        }
        BitmapSprite s = sprite(t);
        for (int p = 0; p < PIXELS; p++) {
            v[i + p] = 0;
        }
        for (int p = 0; p < BitmapSprite.AREA; p++) {
            if (s.is(p)) {
                v[i + p / 32] |= 1 << (p % 32);
            }
        }
    }

    /**
     * Sets {@code t}'s values. The player's banner's shape goes through {@code bannerTypeSet}, so call it where that
     * isn't captured. A command recorded before SOS-40 carries the player's banner without its pixels; those stay.
     */
    static void write(int t, int[] v) {
        int i = 0;
        if (t == FACTION) {
            FACTIONS.player().banner().bannerTypeSet(v[i++]);
        }
        for (ColorImp c : parts(t)) {
            c.setRed(v[i++]);
            c.setGreen(v[i++]);
            c.setBlue(v[i++]);
        }
        if (v.length < i + PIXELS) {
            return;
        }
        BitmapSprite s = sprite(t);
        for (int p = 0; p < BitmapSprite.AREA; p++) {
            s.set(p, (v[i + p / 32] & (1 << (p % 32))) != 0);
        }
    }

    /** The banner {@code c} colours, or {@link #NONE}. */
    static int owner(ColorImp c) {
        for (ColorImp part : parts(FACTION)) {
            if (c == part) {
                return FACTION;
            }
        }
        for (int i = 0; i < GAME.ARMIES().banners.size(); i++) {
            DivisionBanner b = GAME.ARMIES().banners.get(i);
            if (c == b.col || c == b.bg) {
                return i;
            }
        }
        return NONE;
    }

    /** The banner {@code s} draws, or {@link #NONE}. */
    static int owner(BitmapSprite s) {
        if (s == FACTIONS.player().banner().sprite) {
            return FACTION;
        }
        for (int i = 0; i < GAME.ARMIES().banners.size(); i++) {
            if (s == GAME.ARMIES().banners.get(i).sprite) {
                return i;
            }
        }
        return NONE;
    }

    static ColorImp[] parts(int t) {
        if (t == FACTION) {
            FBanner b = FACTIONS.player().banner();
            return new ColorImp[] {b.colorBG(), b.colorFG(), b.colorBorder(), b.colorPole()};
        }
        DivisionBanner b = GAME.ARMIES().banners.get(t);
        return new ColorImp[] {b.col, b.bg};
    }

    private static BitmapSprite sprite(int t) {
        return t == FACTION ? FACTIONS.player().banner().sprite : GAME.ARMIES().banners.get(t).sprite;
    }
}

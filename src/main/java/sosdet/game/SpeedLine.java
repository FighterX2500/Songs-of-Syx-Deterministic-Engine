package sosdet.game;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import util.gui.misc.GText;

/** The engine's one-line speed notice under the top bar (SOS-42); its text is set again only when it changes. */
public final class SpeedLine {

    private final GText text = new GText(UI.FONT().S, 120);
    private String shown;

    public void render(SPRITE_RENDERER r, String line, int cx, int y) {
        if (line != shown) {
            shown = line;
            text.clear().add(line);
            text.warnify();
            text.adjustWidth();
        }
        text.renderC(r, cx, y + text.height() / 2);
    }
}

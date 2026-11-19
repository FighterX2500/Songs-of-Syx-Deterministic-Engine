package sosdet.game;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import settlement.main.SETT;
import settlement.room.main.throne.THRONE;

/**
 * A battle between two players has no throne (owner, 2026-11-19): nothing is built, but the throne's spot, which the
 * camera and the battle AI read through {@code THRONE.coo()}, is set where vanilla would have built it, the same on
 * every PC. {@code THRONE.setInstance} is package-private in V71; this is its one reflective use.
 */
public final class GameThrone {

    private static final Method SET_INSTANCE = resolve();

    private GameThrone() {
    }

    private static Method resolve() {
        try {
            Method m = THRONE.class.getDeclaredMethod("setInstance", int.class, int.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: THRONE.setInstance(int, int) is missing; this engine targets Songs of Syx V71", e);
        }
    }

    /** Sets the throne's spot to tile {@code tx, ty} without building it. */
    public static void spot(int tx, int ty) {
        try {
            SET_INSTANCE.invoke(SETT.ROOMS().THRONE, tx, ty);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: THRONE.setInstance(" + tx + ", " + ty + ") failed", e);
        }
    }

    /**
     * Patch 95, the battle panel's throne timer (owner, 2026-11-19): shown as vanilla's, except in a battle between two
     * players, which has no throne ({@code Hooks.battleThroneOff}); the countdown itself is off there too (patch 99-map-battles).
     */
    public static snake2d.util.gui.Hoverable.HOVERABLE unlessBetweenHumans(snake2d.util.gui.Hoverable.HOVERABLE o) {
        return new snake2d.util.gui.Hoverable.HOVERABLE.HoverableAbs(o.body().width(), o.body().height()) {
            @Override
            protected void render(snake2d.SPRITE_RENDERER r, float ds, boolean isHovered) {
                if (sosdet.Hooks.battleBetweenHumans()) {
                    return;
                }
                o.body().moveX1Y1(body.x1(), body.y1());
                o.render(r, ds);
            }

            @Override
            public boolean hover(snake2d.util.datatypes.COORDINATE mCoo) {
                if (sosdet.Hooks.battleBetweenHumans()) {
                    return false;
                }
                o.body().moveX1Y1(body.x1(), body.y1());
                o.hover(mCoo);
                return super.hover(mCoo);
            }

            @Override
            public void hoverInfoGet(snake2d.util.gui.GUI_BOX text) {
                if (!sosdet.Hooks.battleBetweenHumans()) {
                    o.hoverInfoGet(text);
                }
            }
        };
    }
}

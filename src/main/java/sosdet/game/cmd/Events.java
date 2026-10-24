package sosdet.game.cmd;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import game.GAME;
import game.event.actions.EventAction;
import game.event.engine.EChoice;
import game.event.engine.EContext;
import game.event.engine.EVENT_HANDLER;
import game.event.engine.Event;
import game.events.killer.EventKiller;
import game.faction.FACTIONS;
import game.faction.FCredits.CTYPE;
import game.raiding.Raider;
import game.raiding.RaidingCurrent;
import init.trade.TR;
import init.trade.TRADABLE;
import init.trade.TRADE_TYPE;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import view.ui.message.MessageSection;

/**
 * Events (ids 9000-9999): answers to the messages the simulation sends, an event's choice, a raider's demand, the
 * serial killer's suspect (stage D, plan 0002). Every PC holds the same messages, since the step sends them; each
 * command re-checks that its message still stands, so a second answer from the other player is skipped.
 *
 * <p>Some of what an answer does is package-private in the game; it is reached here by reflection, each member
 * resolved once when this class loads.
 */
public final class Events {

    static final int EVENT_CHOICE = 9000;
    static final int RAIDER_PAY = 9001;
    static final int RAIDER_ALLY_HELP = 9002;
    static final int KILLER_SUSPECT = 9003;

    private static final Field HANDLER_MESSAGE = field(EVENT_HANDLER.class, "mess");
    private static final Class<?> EVENT_MESSAGE = type("game.event.engine.EventMessage");
    private static final Field MESSAGE_DATA = field(EVENT_MESSAGE, "data");
    private static final Field MESSAGE_CHOICE = field(EVENT_MESSAGE, "choice");
    private static final Method CHOICE_SELECT = method(EVENT_HANDLER.class, "choiceSelect", Event.class, int.class);
    private static final Method RAIDER_CAN_PAY = method(RaidingCurrent.class, "canPay", int.class);
    private static final Method RAIDER_SET_ALLY_FIGHT = method(RaidingCurrent.class, "setAllyFight");
    private static final Constructor<?> RAIDER_THANKS = constructor(type("game.raiding.MessDemandTY"), Raider.class);

    static final List<Command> ALL = List.of(
            new Command(EVENT_CHOICE, "eventChoice", r -> {
                int ei = r.i();
                String key = r.s();
                int occurrence = r.i();
                int choice = r.i();
                return choose(ei, key, occurrence, choice);
            }),
            new Command(RAIDER_PAY, "raiderPay", r -> {
                Raider raider = raider(r.i());
                int credits = r.i();
                int[] res = new int[r.i()];
                for (int i = 0; i < res.length; i++) {
                    res[i] = r.i();
                }
                if (raider == null || res.length != TR.ALL().size()) {
                    return false;
                }
                return pay(raider, credits, res);
            }),
            new Command(RAIDER_ALLY_HELP, "raiderAllyHelp", r -> {
                Raider raider = raider(r.i());
                if (raider == null) {
                    return false;
                }
                invoke(RAIDER_SET_ALLY_FIGHT, GAME.raiders().current);
                return true;
            }),
            new Command(KILLER_SUSPECT, "killerSuspect", r -> {
                int killer = r.i();
                int murders = r.i();
                int suspect = r.i();
                EventKiller k = GAME.events().killer;
                if (murders != k.murders() || k.theKiller() == null || k.theKiller().id() != killer) {
                    return false;
                }
                k.setSuspect(suspect);
                return true;
            }));

    private Events() {
    }

    // ---------------------------------------------------------------- capture (hooks)

    /**
     * Call site in an event's message ({@code EventMessage}): one of its choices, after the message's own checks.
     * The event, its occurrence and the choice's index name it on every PC.
     */
    public static boolean eventChoice(Event e, int choice, int occurrence) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(EVENT_CHOICE).i(e.allIndex).s(e.key).i(occurrence).i(choice));
        return true;
    }

    /** Call site in a raider's demand ({@code MessDemand}): the tribute paid, as the message asked it. */
    public static boolean raiderPay(Raider raider, int credits, int[] resources) {
        if (!Commands.capturing()) {
            return false;
        }
        CmdWriter w = new CmdWriter(RAIDER_PAY).i(raiderIndex(raider)).i(credits).i(resources.length);
        for (int am : resources) {
            w.i(am);
        }
        Commands.capture(w);
        return true;
    }

    /** Call site in an ally's offer ({@code MessAlly}): its help against the current raider asked for. */
    public static boolean raiderAllyHelp(Raider raider) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(RAIDER_ALLY_HELP).i(raiderIndex(raider)));
        return true;
    }

    /** Call site in the serial killer's message ({@code Messenger}): the player names a suspect. */
    public static boolean killerSuspect(int killer, int murders, int suspect) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(KILLER_SUSPECT).i(killer).i(murders).i(suspect));
        return true;
    }

    // ---------------------------------------------------------------- apply (inside the step)

    /**
     * {@code EventMessage}'s choice button, on the event handler's message for the current occurrence (the one the
     * player answered): the choice's checks, then its count, its actions and the event's end.
     */
    private static boolean choose(int ei, String key, int occurrence, int choice) {
        EVENT_HANDLER h = GAME.EVENT();
        Event e = h.read(ei, key);
        if (e == null || h.current() != e || h.occ(e) != occurrence || choice < 0 || choice >= e.choices.size()) {
            return false;
        }
        Object message = get(HANDLER_MESSAGE, h);
        if (message == null) {
            return false;
        }
        EContext data = (EContext) get(MESSAGE_DATA, message);
        EChoice c = e.choices.get(choice);
        for (EventAction a : c.actions) {
            if (a.problem(e, data) != null) {
                return false;
            }
        }
        if (!c.request.passes(FACTIONS.player())) {
            return false;
        }
        invoke(CHOICE_SELECT, h, e, choice);
        set(MESSAGE_CHOICE, message, choice);
        for (EventAction a : c.actions) {
            a.exe(e, data);
        }
        if (h.current() == e) {
            h.set(null, false, false, false, false);
        }
        return true;
    }

    /** {@code MessDemand}'s pay button: the demand's checks, the tribute, the raider gone, its thanks sent. */
    private static boolean pay(Raider raider, int credits, int[] res) {
        RaidingCurrent current = GAME.raiders().current;
        if (current.current() != raider || !(Boolean) invoke(RAIDER_CAN_PAY, current, 0)) {
            return false; // canPay ignores its argument
        }
        if (credits > 0 && FACTIONS.player().credits().getD() < credits) {
            return false;
        }
        for (TRADABLE t : TR.ALL()) {
            if (res[t.index()] > 0 && res[t.index()] > t.ps().playerOwned()) {
                return false;
            }
        }
        FACTIONS.player().credits().inc(-credits, CTYPE.TRIBUTE);
        for (TRADABLE t : TR.ALL()) {
            FACTIONS.player().seller(t).remove(res[t.index()], TRADE_TYPE.spoils, 0, null);
        }
        current.clear();
        ((MessageSection) construct(RAIDER_THANKS, raider)).send();
        return true;
    }

    // ---------------------------------------------------------------- arguments

    private static int raiderIndex(Raider raider) {
        for (int i = 0; i < GAME.raiders().ALL().size(); i++) {
            if (GAME.raiders().ALL().get(i) == raider) {
                return i;
            }
        }
        return -1; // a message from before a world resync, whose raiders it replaced: refused on every PC (SOS-32)
    }

    /** The raider at an index, while it is still the current one. */
    private static Raider raider(int index) {
        if (index < 0 || index >= GAME.raiders().ALL().size()) {
            return null;
        }
        Raider raider = GAME.raiders().ALL().get(index);
        return GAME.raiders().current.current() == raider ? raider : null;
    }

    // ---------------------------------------------------------------- reflection (class load and command calls only)

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, Events.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SOSDET: no game class " + name + " (V71.44 expected)", e);
        }
    }

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("SOSDET: no field " + c.getName() + "." + name + " (V71.44 expected)", e);
        }
    }

    private static Method method(Class<?> c, String name, Class<?>... params) {
        try {
            Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: no method " + c.getName() + "." + name + " (V71.44 expected)", e);
        }
    }

    private static Constructor<?> constructor(Class<?> c, Class<?>... params) {
        try {
            Constructor<?> k = c.getDeclaredConstructor(params);
            k.setAccessible(true);
            return k;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: no constructor of " + c.getName() + " (V71.44 expected)", e);
        }
    }

    private static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: " + f, e);
        }
    }

    private static void set(Field f, Object target, int value) {
        try {
            f.setInt(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: " + f, e);
        }
    }

    private static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: " + m, e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("SOSDET: " + m + " failed", e.getCause());
        }
    }

    private static Object construct(Constructor<?> k, Object... args) {
        try {
            return k.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SOSDET: " + k, e);
        }
    }
}

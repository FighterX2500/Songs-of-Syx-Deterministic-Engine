package sosdet.game;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import game.GAME;
import game.battle.div.Div;
import settlement.stats.Induvidual;
import sosdet.game.cmd.Args;
import sosdet.report.CityReport;
import sosdet.report.Input;
import sosdet.report.InputKind;
import sosdet.report.SentSupplies;
import world.army.AD;
import world.army.ADSupply;
import world.army.WDivStoredAll;
import world.entity.army.WArmy;

/**
 * The city's inputs to the world (ADR 0005 §2): made in the city part from the game's own save data, and applied in
 * the world part when the report carrying them applies, through the same game calls the city part would have made.
 */
public final class GameInputs implements Consumer<CityReport> {

    private final GameCodec codec = new GameCodec();
    private final SentSupplies sent;
    /** {@code WDivStoredAll.add(Induvidual, Div)}: patch 96-cmd-world adds it, so the game jar compiled against lacks it. */
    private final Method addSoldier;

    public GameInputs(SentSupplies sent) {
        this.sent = sent;
        try {
            addSoldier = WDivStoredAll.class.getMethod("add", Induvidual.class, Div.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSDET: no WDivStoredAll.add(Induvidual, Div): patch 96-cmd-world is not"
                    + " applied to this game (SOSDET supports Songs of Syx V71, build 71.44)", e);
        }
    }

    /** B1: the soldier, as their save data, for their division in the world. */
    public Input soldierJoins(Induvidual i, int div) {
        i.save(codec.hashBegin());
        return new Input(InputKind.SOLDIER_JOINS, div, codec.written());
    }

    /** B2: {@code delta} of a supply for an army, which the city reads as sent until the world applies it. */
    public Input supply(int supply, WArmy a, int delta) {
        sent.sent(supply, a.armyIndex(), a.iteration(), delta, AD.supplies().all.size(),
                world.WORLD.ENTITIES().armies.max());
        ByteBuffer b = ByteBuffer.allocate(12).putInt(a.armyIndex()).putInt(a.iteration()).putInt(delta);
        return new Input(InputKind.SUPPLY, supply, b.array());
    }

    @Override
    public void accept(CityReport r) {
        for (Input in : r.inputs) {
            switch (in.kind) {
            case SOLDIER_JOINS:
                Induvidual i;
                try {
                    i = new Induvidual(codec.reader(in.data()));
                } catch (IOException e) {
                    throw new IllegalStateException("SOSDET: cannot read a soldier joining division " + in.arg, e);
                }
                Div div = GAME.ARMIES().player().divisions().get(in.arg);
                try {
                    addSoldier.invoke(AD.cityDivs(), i, div);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("SOSDET: a soldier could not join division " + in.arg, e);
                }
                break;
            case SUPPLY:
                ByteBuffer b = ByteBuffer.wrap(in.data());
                int index = b.getInt();
                int iteration = b.getInt();
                int delta = b.getInt();
                sent.applied(in.arg, index, iteration, delta);
                WArmy a = Args.army(index, iteration);
                if (a != null) { // an army disbanded since: the goods are lost with it
                    ADSupply s = AD.supplies().all.get(in.arg);
                    s.current().inc(a, delta);
                }
                break;
            default:
                throw new IllegalStateException("SOSDET: no applier for input kind " + in.kind);
            }
        }
    }
}

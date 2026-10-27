package sosdet.txn;

import java.util.List;

/**
 * The latest transfers of goods and money to and from the humans' cities (SOS-81): one entry for each money or goods
 * transaction the world part makes, every human's, in the order made. Every PC makes the same transactions at the same
 * steps, so every PC records the same. A record for the logs only: it is not saved, not hashed and never read by the
 * simulation. Recording writes into preallocated arrays and allocates nothing (it runs in the step). Plain Java.
 */
public final class TransferLog {

    /** What moved. */
    public enum Kind {
        /** Goods the human bought, reserved for delivery ({@code PBuyer.addReserve}); the reason is a trade type. */
        BOUGHT,
        /** Goods delivered to the human ({@code PBuyer.addDeliver}); the reason is a trade type. */
        DELIVERED,
        /** Goods the human sold or gave ({@code PSeller.remove}); the reason is a trade type. */
        SOLD,
        /** Money in (positive) or out ({@code PCredits.inc}); the reason is a credits type. */
        MONEY,
        /** Money for goods ({@code PCredits.inc} with the goods); the reason is a credits type. */
        MONEY_FOR_GOODS
    }

    /** One transfer, as read between steps. */
    public record Transfer(long seq, long step, long txnId, int human, Kind kind, int goods, double amount, int price,
            int reason, int other) {
    }

    public static final int CAPACITY = 4096;
    private static final Kind[] KINDS = Kind.values();

    private final long[] step = new long[CAPACITY];
    private final long[] txnId = new long[CAPACITY];
    private final int[] human = new int[CAPACITY];
    private final int[] kind = new int[CAPACITY];
    private final int[] goods = new int[CAPACITY];
    private final double[] amount = new double[CAPACITY];
    private final int[] price = new int[CAPACITY];
    private final int[] reason = new int[CAPACITY];
    private final int[] other = new int[CAPACITY];
    /** The sequence number of the latest entry; 0 before the first. */
    private long last;

    /**
     * In the step: one transfer. {@code goods} is a tradable's index or -1, {@code price} 0 where there is none,
     * {@code other} the other faction's index or -1.
     */
    public void record(long atStep, long id, int h, Kind k, int g, double am, int p, int r, int o) {
        int i = (int) (++last % CAPACITY);
        step[i] = atStep;
        txnId[i] = id;
        human[i] = h;
        kind[i] = k.ordinal();
        goods[i] = g;
        amount[i] = am;
        price[i] = p;
        reason[i] = r;
        other[i] = o;
    }

    /** The sequence number of the latest entry; 0 before the first. */
    public long last() {
        return last;
    }

    /**
     * Between steps: adds to {@code out} the entries after {@code afterSeq} that are still kept, oldest first, and
     * returns how many after it were already overwritten.
     */
    public long read(long afterSeq, List<Transfer> out) {
        long from = Math.max(afterSeq + 1, last - CAPACITY + 1);
        long lost = Math.max(0, from - (afterSeq + 1));
        for (long s = Math.max(from, 1); s <= last; s++) {
            int i = (int) (s % CAPACITY);
            out.add(new Transfer(s, step[i], txnId[i], human[i], KINDS[kind[i]], goods[i], amount[i], price[i], reason[i],
                    other[i]));
        }
        return lost;
    }
}

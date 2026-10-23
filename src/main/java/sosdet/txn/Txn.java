package sosdet.txn;

/**
 * One change the world part makes to the city (ADR 0005 §3): what to do and its arguments, applied in the step's city
 * tail. Pooled by {@link TxnQueue}; never kept past the step that made it, so its game objects are never saved or sent.
 */
public final class Txn {

    /** The world step that made it, then its order in that step: the same on every PC. */
    long id;
    public TxnKind kind;
    public Object a;
    public Object b;
    public Object c;
    public int i;
    public int j;
    public double d;

    public long id() {
        return id;
    }

    void clear() {
        kind = null;
        a = null;
        b = null;
        c = null;
        i = 0;
        j = 0;
        d = 0;
    }
}

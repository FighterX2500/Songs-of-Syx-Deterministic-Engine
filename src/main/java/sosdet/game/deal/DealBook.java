package sosdet.game.deal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import sosdet.hash.HashLog;

/**
 * The deals between humans (SOS-72, plan 0009): the open proposals and the latest answers. World state: every PC
 * changes it at the same steps, it is saved with the world ({@code GameDeals}) and it is in the world hash. Plain
 * Java, no game classes; the commands ({@code cmd.HumanDeals}) check the terms against the game.
 */
public final class DealBook {

    /** The open proposals one human may receive at once. */
    public static final int MAX_OPEN_TO = 8;
    /** The answers kept for the UIs to tell the proposer. */
    public static final int ANSWERS_KEPT = 16;

    /** The treaty term: none, or the treaty both sides have after an accept. */
    public static final int NO_TREATY = 0, PEACE = 1, TRADE = 2, PACT = 3, ALLY = 4, CANCEL_ALL = 5;
    private static final int TREATIES = 6;

    private static final int VERSION = 1;

    /** What became of a proposal. The code is saved: never reuse one. */
    public enum Outcome {
        ACCEPTED(1), DECLINED(2), EXPIRED(3), REFUSED(4), DROPPED(5);

        public final int code;

        Outcome(int code) {
            this.code = code;
        }

        static Outcome of(int code) throws IOException {
            for (Outcome o : values()) {
                if (o.code == code) {
                    return o;
                }
            }
            throw new IOException("SOSDET: a deal outcome of code " + code);
        }
    }

    /**
     * A deal's terms: the treaty, the money each side gives, and the goods each side gives as (tradable index, amount)
     * pairs. Immutable.
     */
    public static final class Terms {
        public final int treaty;
        public final int fromCredits;
        public final int toCredits;
        private final int[] fromGoods;
        private final int[] toGoods;

        public Terms(int treaty, int fromCredits, int toCredits, int[] fromGoods, int[] toGoods) {
            if (treaty < 0 || treaty >= TREATIES) {
                throw new IllegalArgumentException("SOSDET: a treaty term of " + treaty);
            }
            if (fromCredits < 0 || toCredits < 0) {
                throw new IllegalArgumentException("SOSDET: deal money of " + fromCredits + " and " + toCredits);
            }
            this.treaty = treaty;
            this.fromCredits = fromCredits;
            this.toCredits = toCredits;
            this.fromGoods = goods(fromGoods);
            this.toGoods = goods(toGoods);
        }

        private static int[] goods(int[] pairs) {
            if (pairs.length % 2 != 0) {
                throw new IllegalArgumentException("SOSDET: deal goods of odd length " + pairs.length);
            }
            for (int i = 0; i < pairs.length; i += 2) {
                if (pairs[i] < 0 || pairs[i + 1] <= 0) {
                    throw new IllegalArgumentException("SOSDET: deal goods " + pairs[i] + " x " + pairs[i + 1]);
                }
                for (int j = 0; j < i; j += 2) {
                    if (pairs[j] == pairs[i]) {
                        throw new IllegalArgumentException("SOSDET: deal goods name tradable " + pairs[i] + " twice");
                    }
                }
            }
            return pairs.clone();
        }

        /** The goods the proposer gives, as (tradable index, amount) pairs. */
        public int[] fromGoods() {
            return fromGoods.clone();
        }

        /** The goods the receiver gives, as (tradable index, amount) pairs. */
        public int[] toGoods() {
            return toGoods.clone();
        }

        /** True when the deal asks nothing of anyone. */
        public boolean empty() {
            return treaty == NO_TREATY && fromCredits == 0 && toCredits == 0 && fromGoods.length == 0 && toGoods.length == 0;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Terms)) {
                return false;
            }
            Terms t = (Terms) o;
            return treaty == t.treaty && fromCredits == t.fromCredits && toCredits == t.toCredits
                    && Arrays.equals(fromGoods, t.fromGoods) && Arrays.equals(toGoods, t.toGoods);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * (31 * treaty + fromCredits) + toCredits) + Arrays.hashCode(fromGoods))
                    + Arrays.hashCode(toGoods);
        }
    }

    /** An open proposal: from one human's slot to another's, until the game second {@code expires}. */
    public record Proposal(int id, int from, int to, int expires, Terms terms) {
    }

    /** What became of a proposal. */
    public record Answer(int id, int from, int to, Outcome outcome) {
    }

    private final List<Proposal> open = new ArrayList<>();
    private final List<Answer> answers = new ArrayList<>();
    private int nextId = 1;

    /** Opens a proposal; null when {@code to} already has {@link #MAX_OPEN_TO} open. */
    public Proposal propose(int from, int to, int expires, Terms terms) {
        if (from == to) {
            throw new IllegalArgumentException("SOSDET: a deal from slot " + from + " to itself");
        }
        if (openTo(to) >= MAX_OPEN_TO) {
            return null;
        }
        Proposal p = new Proposal(nextId++, from, to, expires, terms);
        open.add(p);
        return p;
    }

    private int openTo(int to) {
        int n = 0;
        for (Proposal p : open) {
            n += p.to == to ? 1 : 0;
        }
        return n;
    }

    /** The open proposal {@code id}, or null; it stays open. */
    public Proposal get(int id) {
        for (Proposal p : open) {
            if (p.id == id) {
                return p;
            }
        }
        return null;
    }

    /** Closes the open proposal {@code id} with {@code outcome}; returns it, or null when it is not open. */
    public Proposal close(int id, Outcome outcome) {
        for (int i = 0; i < open.size(); i++) {
            Proposal p = open.get(i);
            if (p.id == id) {
                open.remove(i);
                answered(p, outcome);
                return p;
            }
        }
        return null;
    }

    private void answered(Proposal p, Outcome outcome) {
        answers.add(new Answer(p.id, p.from, p.to, outcome));
        if (answers.size() > ANSWERS_KEPT) {
            answers.remove(0);
        }
    }

    /** Closes every proposal due by the game second {@code now} as expired; returns how many. */
    public int expire(int now) {
        int n = 0;
        for (int i = 0; i < open.size(); i++) {
            Proposal p = open.get(i);
            if (p.expires <= now) {
                open.remove(i--);
                answered(p, Outcome.EXPIRED);
                n++;
            }
        }
        return n;
    }

    /** A removed human (SOS-66): every proposal to or from them closes as dropped. */
    public void dropHuman(int slot) {
        for (int i = 0; i < open.size(); i++) {
            Proposal p = open.get(i);
            if (p.from == slot || p.to == slot) {
                open.remove(i--);
                answered(p, Outcome.DROPPED);
            }
        }
    }

    /** The open proposals, oldest first. */
    public List<Proposal> open() {
        return Collections.unmodifiableList(open);
    }

    /** The latest answers, oldest first. */
    public List<Answer> answers() {
        return Collections.unmodifiableList(answers);
    }

    // ---------------------------------------------------------------- save and hash

    /** The book as ints, its version first. */
    public int[] toInts() {
        IntList w = new IntList();
        w.add(VERSION);
        w.add(nextId);
        w.add(open.size());
        for (Proposal p : open) {
            w.add(p.id);
            w.add(p.from);
            w.add(p.to);
            w.add(p.expires);
            Terms t = p.terms;
            w.add(t.treaty);
            w.add(t.fromCredits);
            w.add(t.toCredits);
            w.addAll(t.fromGoods);
            w.addAll(t.toGoods);
        }
        w.add(answers.size());
        for (Answer a : answers) {
            w.add(a.id);
            w.add(a.from);
            w.add(a.to);
            w.add(a.outcome.code);
        }
        return w.toArray();
    }

    /** A book from {@link #toInts}. */
    public static DealBook fromInts(int[] v) throws IOException {
        IntReader r = new IntReader(v);
        int version = r.next();
        if (version != VERSION) {
            throw new IOException("SOSDET: a deal book of version " + version + ", this engine reads " + VERSION);
        }
        DealBook b = new DealBook();
        b.nextId = r.next();
        int n = r.count();
        for (int i = 0; i < n; i++) {
            int id = r.next();
            int from = r.next();
            int to = r.next();
            int expires = r.next();
            try {
                Terms t = new Terms(r.next(), r.next(), r.next(), r.goods(), r.goods());
                b.open.add(new Proposal(id, from, to, expires, t));
            } catch (IllegalArgumentException e) {
                throw new IOException("SOSDET: deal " + id + " in the save: " + e.getMessage(), e);
            }
        }
        int a = r.count();
        for (int i = 0; i < a; i++) {
            b.answers.add(new Answer(r.next(), r.next(), r.next(), Outcome.of(r.next())));
        }
        r.end();
        return b;
    }

    /** Mixes the whole book into {@code h}. */
    public long hash(long h) {
        for (int v : toInts()) {
            h = HashLog.mix(h, v);
        }
        return h;
    }

    private static final class IntList {
        private int[] v = new int[32];
        private int n;

        void add(int i) {
            if (n == v.length) {
                v = Arrays.copyOf(v, 2 * n);
            }
            v[n++] = i;
        }

        void addAll(int[] pairs) {
            add(pairs.length / 2);
            for (int i : pairs) {
                add(i);
            }
        }

        int[] toArray() {
            return Arrays.copyOf(v, n);
        }
    }

    private static final class IntReader {
        private final int[] v;
        private int at;

        IntReader(int[] v) {
            this.v = v;
        }

        int next() throws IOException {
            if (at >= v.length) {
                throw new IOException("SOSDET: a deal book cut short at int " + at);
            }
            return v[at++];
        }

        int count() throws IOException {
            int n = next();
            if (n < 0 || n > v.length) {
                throw new IOException("SOSDET: a deal book count of " + n);
            }
            return n;
        }

        int[] goods() throws IOException {
            int n = count();
            int[] pairs = new int[2 * n];
            for (int i = 0; i < pairs.length; i++) {
                pairs[i] = next();
            }
            return pairs;
        }

        void end() throws IOException {
            if (at != v.length) {
                throw new IOException("SOSDET: a deal book with " + (v.length - at) + " ints left over");
            }
        }
    }
}

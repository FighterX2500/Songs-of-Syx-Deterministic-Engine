package sosdet.game.deal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import sosdet.hash.HashLog;

/**
 * The deals between humans (SOS-72, plan 0009; regions and vassalage SOS-73): the open proposals, the latest answers
 * and the tribute rate of each vassalage between humans. World state: every PC
 * changes it at the same steps, it is saved with the world ({@code GameDeals}) and it is in the world hash. Plain
 * Java, no game classes; the commands ({@code cmd.HumanDeals}) check the terms against the game.
 */
public final class DealBook {

    /** The open proposals one human may receive at once. */
    public static final int MAX_OPEN_TO = 8;
    /** The answers kept for the UIs to tell the proposer. */
    public static final int ANSWERS_KEPT = 16;

    /**
     * The treaty term: none, or the treaty both sides have after an accept. With {@code PROTECTOR} the proposer becomes
     * the receiver's overlord; with {@code VASSAL} the proposer becomes the receiver's vassal (SOS-73).
     */
    public static final int NO_TREATY = 0, PEACE = 1, TRADE = 2, PACT = 3, ALLY = 4, CANCEL_ALL = 5, PROTECTOR = 6,
            VASSAL = 7;
    private static final int TREATIES = 8;

    /** A vassal's yearly tribute, in percent of its worth, unless the deal sets another (owner, 2026-10-27). */
    public static final int DEFAULT_TRIBUTE = 10;
    /** The regions one side may give in one deal. */
    public static final int MAX_REGIONS = 16;

    /** 2 (SOS-73): regions and the tribute rate in the terms, and the tributes. Version 1 still loads. */
    private static final int VERSION = 2;

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
     * A deal's terms: the treaty, the money each side gives, the goods each side gives as (tradable index, amount)
     * pairs, the regions each side gives as region indices, and the vassal's yearly tribute in percent when the treaty
     * is {@link #PROTECTOR} or {@link #VASSAL}. Immutable.
     */
    public static final class Terms {
        public final int treaty;
        public final int fromCredits;
        public final int toCredits;
        private final int[] fromGoods;
        private final int[] toGoods;
        private final int[] fromRegions;
        private final int[] toRegions;
        public final int tributePercent;

        /** Terms with no regions and the default tribute. */
        public Terms(int treaty, int fromCredits, int toCredits, int[] fromGoods, int[] toGoods) {
            this(treaty, fromCredits, toCredits, fromGoods, toGoods, new int[0], new int[0], DEFAULT_TRIBUTE);
        }

        public Terms(int treaty, int fromCredits, int toCredits, int[] fromGoods, int[] toGoods, int[] fromRegions,
                int[] toRegions, int tributePercent) {
            if (treaty < 0 || treaty >= TREATIES) {
                throw new IllegalArgumentException("SOSDET: a treaty term of " + treaty);
            }
            if (fromCredits < 0 || toCredits < 0) {
                throw new IllegalArgumentException("SOSDET: deal money of " + fromCredits + " and " + toCredits);
            }
            if (tributePercent < 0 || tributePercent > 100) {
                throw new IllegalArgumentException("SOSDET: a tribute of " + tributePercent + "%");
            }
            this.treaty = treaty;
            this.fromCredits = fromCredits;
            this.toCredits = toCredits;
            this.fromGoods = goods(fromGoods);
            this.toGoods = goods(toGoods);
            this.fromRegions = regions(fromRegions, new int[0]);
            this.toRegions = regions(toRegions, fromRegions);
            this.tributePercent = vassalage() ? tributePercent : DEFAULT_TRIBUTE;
        }

        private static int[] regions(int[] regions, int[] other) {
            if (regions.length > MAX_REGIONS) {
                throw new IllegalArgumentException("SOSDET: " + regions.length + " regions on one side of a deal");
            }
            for (int i = 0; i < regions.length; i++) {
                if (regions[i] < 0) {
                    throw new IllegalArgumentException("SOSDET: a deal region of index " + regions[i]);
                }
                for (int j = 0; j < i; j++) {
                    if (regions[j] == regions[i]) {
                        throw new IllegalArgumentException("SOSDET: deal regions name region " + regions[i] + " twice");
                    }
                }
                for (int o : other) {
                    if (o == regions[i]) {
                        throw new IllegalArgumentException("SOSDET: region " + o + " on both sides of a deal");
                    }
                }
            }
            return regions.clone();
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

        /** The regions the proposer gives, as region indices. */
        public int[] fromRegions() {
            return fromRegions.clone();
        }

        /** The regions the receiver gives, as region indices. */
        public int[] toRegions() {
            return toRegions.clone();
        }

        /** True when the treaty makes one side the other's vassal. */
        public boolean vassalage() {
            return treaty == PROTECTOR || treaty == VASSAL;
        }

        /** True when the deal asks nothing of anyone. */
        public boolean empty() {
            return treaty == NO_TREATY && fromCredits == 0 && toCredits == 0 && fromGoods.length == 0 && toGoods.length == 0
                    && fromRegions.length == 0 && toRegions.length == 0;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Terms)) {
                return false;
            }
            Terms t = (Terms) o;
            return treaty == t.treaty && fromCredits == t.fromCredits && toCredits == t.toCredits
                    && Arrays.equals(fromGoods, t.fromGoods) && Arrays.equals(toGoods, t.toGoods)
                    && Arrays.equals(fromRegions, t.fromRegions) && Arrays.equals(toRegions, t.toRegions)
                    && tributePercent == t.tributePercent;
        }

        @Override
        public int hashCode() {
            int h = 31 * (31 * (31 * (31 * treaty + fromCredits) + toCredits) + Arrays.hashCode(fromGoods))
                    + Arrays.hashCode(toGoods);
            return 31 * (31 * (31 * h + Arrays.hashCode(fromRegions)) + Arrays.hashCode(toRegions)) + tributePercent;
        }
    }

    /** An open proposal: from one human's slot to another's, until the game second {@code expires}. */
    public record Proposal(int id, int from, int to, int expires, Terms terms) {
    }

    /** What became of a proposal. */
    public record Answer(int id, int from, int to, Outcome outcome) {
    }

    /** A vassalage between humans and its yearly tribute in percent of the vassal's worth (SOS-73). */
    public record Tribute(int vassal, int overlord, int percent) {
    }

    private final List<Proposal> open = new ArrayList<>();
    private final List<Answer> answers = new ArrayList<>();
    private final List<Tribute> tributes = new ArrayList<>();
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
        for (int i = 0; i < tributes.size(); i++) {
            Tribute t = tributes.get(i);
            if (t.vassal == slot || t.overlord == slot) {
                tributes.remove(i--);
            }
        }
    }

    /** A vassalage between humans begins: {@code vassal}'s tribute to {@code overlord}, replacing any it had. */
    public void tribute(int vassal, int overlord, int percent) {
        if (vassal == overlord || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("SOSDET: a tribute of " + percent + "% from slot " + vassal + " to " + overlord);
        }
        endTribute(vassal);
        tributes.add(new Tribute(vassal, overlord, percent));
    }

    /** {@code vassal}'s tribute in percent to {@code overlord}, or {@link #DEFAULT_TRIBUTE} when the book has none. */
    public int tributePercent(int vassal, int overlord) {
        for (Tribute t : tributes) {
            if (t.vassal == vassal && t.overlord == overlord) {
                return t.percent;
            }
        }
        return DEFAULT_TRIBUTE;
    }

    /** {@code vassal}'s vassalage ended: its tribute goes. */
    public void endTribute(int vassal) {
        for (int i = 0; i < tributes.size(); i++) {
            if (tributes.get(i).vassal == vassal) {
                tributes.remove(i--);
            }
        }
    }

    /** The tributes, oldest first. */
    public List<Tribute> tributes() {
        return Collections.unmodifiableList(tributes);
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
            w.addList(t.fromRegions);
            w.addList(t.toRegions);
            w.add(t.tributePercent);
        }
        w.add(answers.size());
        for (Answer a : answers) {
            w.add(a.id);
            w.add(a.from);
            w.add(a.to);
            w.add(a.outcome.code);
        }
        w.add(tributes.size());
        for (Tribute t : tributes) {
            w.add(t.vassal);
            w.add(t.overlord);
            w.add(t.percent);
        }
        return w.toArray();
    }

    /** A book from {@link #toInts}, of this version or version 1 (no regions, no tributes). */
    public static DealBook fromInts(int[] v) throws IOException {
        IntReader r = new IntReader(v);
        int version = r.next();
        if (version != 1 && version != VERSION) {
            throw new IOException("SOSDET: a deal book of version " + version + ", this engine reads 1 to " + VERSION);
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
                Terms t = version == 1 ? new Terms(r.next(), r.next(), r.next(), r.goods(), r.goods())
                        : new Terms(r.next(), r.next(), r.next(), r.goods(), r.goods(), r.list(), r.list(), r.next());
                b.open.add(new Proposal(id, from, to, expires, t));
            } catch (IllegalArgumentException e) {
                throw new IOException("SOSDET: deal " + id + " in the save: " + e.getMessage(), e);
            }
        }
        int a = r.count();
        for (int i = 0; i < a; i++) {
            b.answers.add(new Answer(r.next(), r.next(), r.next(), Outcome.of(r.next())));
        }
        if (version >= 2) {
            int t = r.count();
            for (int i = 0; i < t; i++) {
                int vassal = r.next();
                int overlord = r.next();
                int percent = r.next();
                if (vassal == overlord || percent < 0 || percent > 100) {
                    throw new IOException("SOSDET: a tribute of " + percent + "% from slot " + vassal + " to " + overlord);
                }
                b.tributes.add(new Tribute(vassal, overlord, percent));
            }
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

        void addList(int[] list) {
            add(list.length);
            for (int i : list) {
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

        int[] list() throws IOException {
            int[] list = new int[count()];
            for (int i = 0; i < list.length; i++) {
                list[i] = next();
            }
            return list;
        }

        void end() throws IOException {
            if (at != v.length) {
                throw new IOException("SOSDET: a deal book with " + (v.length - at) + " ints left over");
            }
        }
    }
}

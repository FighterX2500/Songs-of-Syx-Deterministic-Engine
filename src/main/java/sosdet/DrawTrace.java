package sosdet;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Debugging only: where in the game's code every simulation draw came from, for the last steps (an idea from
 * timjrobinson/songs-of-syx-multiplayer, SOS-32). Dumped around a desync on two PCs, the first line that differs
 * names the code that drew differently. Walking the stack on every draw is slow, so it is off unless asked for.
 */
final class DrawTrace {

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final int FRAMES = 8;

    private final long[] steps;
    private final List<List<String>> sites;
    private int slot = -1;

    DrawTrace(int keepSteps) {
        if (keepSteps < 1) {
            throw new IllegalArgumentException("SOSDET: a draw trace of " + keepSteps + " steps");
        }
        steps = new long[keepSteps];
        sites = new ArrayList<>(keepSteps);
        for (int i = 0; i < keepSteps; i++) {
            steps[i] = -1;
            sites.add(new ArrayList<>());
        }
    }

    /** A step begins: its draws go to the oldest slot. */
    void step(long step) {
        slot = (int) (step % steps.length);
        steps[slot] = step;
        sites.get(slot).clear();
    }

    /** One simulation draw: the first game frames that called RND. */
    void draw() {
        if (slot < 0) {
            return;
        }
        sites.get(slot).add(WALKER.walk(frames -> frames
                .filter(f -> !f.getClassName().startsWith("sosdet.") && !f.getClassName().startsWith("snake2d.util.rnd."))
                .limit(FRAMES)
                .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                .collect(Collectors.joining(" < "))));
    }

    /** A division's order slot written in the step: what, with the game frames that wrote it. */
    void orderSet(String what) {
        if (slot < 0) {
            return;
        }
        sites.get(slot).add(what + " @ " + WALKER.walk(frames -> frames
                .filter(f -> !f.getClassName().startsWith("sosdet."))
                .limit(FRAMES)
                .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                .collect(Collectors.joining(" < "))));
    }

    /** The draws of the steps from {@code from} to {@code to} still kept, one line each, with a line per step. */
    String dump(long from, long to) {
        StringBuilder b = new StringBuilder();
        for (long s = Math.max(0, from); s <= to; s++) {
            int i = (int) (s % steps.length);
            if (steps[i] != s) {
                continue; // not kept, or not run yet
            }
            b.append("## step ").append(s).append(", ").append(sites.get(i).size()).append(" draws\n");
            for (String site : sites.get(i)) {
                b.append(site).append('\n');
            }
        }
        return b.toString();
    }
}

package sosdet;

/**
 * The path jobs a step queued (B9, SOS-37), kept by the game's path finder. The engine runs them at the step's end,
 * split into fixed shares: share k runs jobs k, k + shares, k + 2 * shares and so on, each share with its own
 * scratch, so the answers are the same on every PC.
 */
public interface PathQueue {

    int queued();

    /** Runs this share's jobs. Called on a path thread or the simulation thread, while the settlement holds still. */
    void run(int share, int shares);

    /** Empties the queue, once every share has run. */
    void clear();
}

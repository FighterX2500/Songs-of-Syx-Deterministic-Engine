package sosdet.game.cmd;

import sosdet.ai.CmdReader;

/** One kind of player command (ADR 0002): its stable id, its name in reports, and how every PC applies it. */
final class Command {

    /** Decodes the arguments and calls the game; returns false when the target is gone (skipped on every PC). */
    interface Applier {
        boolean apply(CmdReader r);
    }

    final int id;
    final String name;
    final Applier applier;

    Command(int id, String name, Applier applier) {
        this.id = id;
        this.name = name;
        this.applier = applier;
    }
}

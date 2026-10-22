package sosdet.rig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import game.GAME;
import game.battle.state.BattleState;
import game.battle.state.BattleStateExiter;
import game.battle.state.BattleStateResult;
import game.battle.state.BattleStateSpec;
import game.battle.util.DIV_SPEC;
import game.battle.util.DivGeneration;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.save.GameLoader;
import init.constant.Config;
import init.paths.PATHS;
import init.race.RACES;
import init.race.Race;
import script.SCRIPT;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.STATS;
import settlement.stats.colls.StatsBattle.StatTraining;
import settlement.stats.equip.EquipBattle;
import snake2d.util.datatypes.Rec;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.misc.ACTION;
import snake2d.util.rnd.RND;
import sosdet.Engine;
import sosdet.hash.Desync;
import view.main.VIEW;

/**
 * SOSDET test rig: drives one game copy from a command file and logs one state hash per step. Test tooling
 * only, shipped in its own mod (SOSDET-rig) and never in a release (engine ADR 0001). {@code rig/rig.py}
 * runs several copies and compares them.
 *
 * <p>Commands in {@code %APPDATA%\songsofsyx\sosdet-rig\command.txt}, events in {@code events.jsonl},
 * hashes in {@code <tag>.csv}:
 * <pre>
 *   begin | end                         start or end an engine session
 *   loadseed &lt;n&gt;                       loads run on the simulation stream seeded with n (0: vanilla)
 *   holdworld &lt;0|1&gt;                     hold the world map still in the city view
 *   load &lt;save&gt;                         load a save
 *   generate &lt;seed&gt; &lt;menPerSide&gt; [artillery]  generate a field battle near the capital and save it as __battle;
 *                                       artillery pieces per side, spread over the artillery types
 *   loadbattle &lt;save&gt;                   load a battle save and enter its deployment
 *   seed &lt;n&gt;                            seed the simulation stream
 *   invade &lt;men&gt;                        the next city run starts an invasion of that many men at steps 1 and 2
 *   ai &lt;instep | host ID | peer ID&gt;    where the AI general runs; before the save loads (ID names the start)
 *   ailog &lt;host|peer&gt; &lt;file&gt;           after the load: the host appends its AI log to the file every frame,
 *                                       a peer reads what is new every frame
 *   run &lt;battle|city&gt; &lt;steps&gt; &lt;speed&gt; &lt;tag&gt;  battle: deploy as step 0; run that many steps (0: no last step),
 *                                       logging each
 *   vanilla &lt;battle|city&gt; &lt;seconds&gt; &lt;speed&gt; &lt;tag&gt;  no session: the game's own stepping, for the timing baseline
 *   hashes &lt;file&gt;                      after the load: append this copy's state hashes to the file every frame
 *   hashpeer &lt;pc&gt; &lt;file&gt;               and read another copy's; on the first desync the engine finds, write
 *                                       &lt;tag&gt;-desync.txt (the engine's report) and a desync event
 *   nudge &lt;step&gt;                        forced desync: at that step, inside it, one soldier gets fatal injuries
 *   resyncat &lt;step&gt; &lt;save&gt; &lt;state&gt;     host: hold the run at that step, save the resync there and load it
 *   resyncnow &lt;save&gt; &lt;state&gt;           host: save the resync at the current step and load it
 *   resyncload &lt;save&gt; &lt;state&gt;          peer: load the host's resync and run on from its step
 *   hold                                end a run at the current step
 *   hash                                the current battle and city hashes
 * </pre>
 */
public final class Rig implements SCRIPT {

    @Override
    public CharSequence name() {
        return "SOSDET rig";
    }

    @Override
    public CharSequence desc() {
        return "Test rig for the SOSDET engine. Not for play.";
    }

    @Override
    public boolean isSelectable() {
        return false;
    }

    @Override
    public boolean forceInit() {
        return true;
    }

    /**
     * The rig's one instance per process. The game makes a new script instance for every load, which would lose a
     * run's state at a resync; test tooling only, so a static holder is fine here (engine rule: no mutable static).
     */
    private static Instance current;

    @Override
    public SCRIPT_INSTANCE createInstance() {
        if (current == null) {
            current = new Instance();
        } else {
            current.attach();
        }
        return current;
    }

    private static final class Instance implements SCRIPT_INSTANCE {

        private static final int POLL_EVERY_FRAMES = 30;

        private final Path dir = Paths.get(System.getenv("APPDATA"), "songsofsyx", "sosdet-rig");
        private final Path commandFile = dir.resolve("command.txt");
        private final Path eventsFile = dir.resolve("events.jsonl");

        private static final long STALL_NANOS = 100_000_000; // the timing rule counts steps over 100 ms

        private int frames;
        private boolean running;
        private boolean city;
        private long lastStep;
        private String tag;
        private BufferedWriter log;
        private int invadeMen;
        private long started;
        // vanilla timing baseline: no session, the game's own sub-steps
        private boolean vanilla;
        private double gameSeconds;
        private double gameSecondsLeft;
        private long subSteps;
        // timing per frame (patch 91): the update time is known one frame late
        private BufferedWriter timing;
        private double frameGameSeconds;
        private double pendingGameSeconds = -1;
        // the AI log between host and peers, through a file
        private Path aiFile;
        private boolean aiHost;
        private long aiOffset;
        // the state hashes between copies, through files
        private Path hashFile;
        private final List<Path> hashPeers = new ArrayList<>();
        private final List<Integer> hashPeerPcs = new ArrayList<>();
        private long[] hashOffsets = new long[0];
        private boolean desyncReported;
        private long nudgeStep = -1;
        // resync (SOS-30)
        private double speed;
        private long resyncStep = -1;
        private Path resyncSave;
        private Path resyncState;
        private long resyncLoadStarted;
        // stall sampler: the simulation thread's stack for every step over 100 ms
        private volatile Thread simThread;
        private volatile boolean sampling;

        Instance() {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new IllegalStateException("SOSDET rig: cannot create " + dir, e);
            }
            attach();
        }

        /** Every game, the first and each one loaded after it, calls back after each update. */
        void attach() {
            GAME.addAfterUpdate(new ACTION() {
                @Override
                public void exe() {
                    afterFrame();
                }
            });
            event("loaded", "\"session\":" + Engine.active());
        }

        /** Inside the step (a script updates before the city and the battle): the state as the previous step left it. */
        @Override
        public void update(double ds) {
            if (!running || ds <= 0) {
                return;
            }
            frameGameSeconds += ds;
            if (vanilla) {
                subSteps++;
                gameSeconds += ds;
                if (city && invadeMen > 0 && subSteps <= 2) {
                    invasion(subSteps);
                }
                return;
            }
            simThread = Thread.currentThread();
            long step = Engine.step();
            if (city && invadeMen > 0 && step <= 2) {
                invasion(step);
            }
            if (step == nudgeStep) {
                nudge();
            }
            try {
                log.write(step + "," + Long.toHexString(city ? cityHash() : battleHash()) + "," + men() + "\n");
            } catch (IOException e) {
                throw new IllegalStateException("SOSDET rig: cannot write the hash log", e);
            }
        }

        private void afterFrame() {
            if (resyncLoadStarted != 0) {
                resynced();
            }
            exchangeAiLog();
            exchangeHashes();
            if (running && resyncStep >= 0 && Engine.step() == resyncStep) {
                saveResync();
            }
            if (running) {
                timing();
                if (city && invadeMen > 0) {
                    VIEW.inters().messages.hide(); // the invasion's messages would pause the game
                }
                if (vanilla ? gameSeconds >= gameSecondsLeft : Engine.step() >= lastStep) {
                    finish();
                } else if (!vanilla) {
                    flushLog(); // the runner compares the copies while they run
                }
            }
            if (++frames % POLL_EVERY_FRAMES == 0) {
                poll();
            }
        }

        /** The host appends what its AI log wrote; a peer takes in what is new. Between frames, so before the next step. */
        private void exchangeAiLog() {
            if (aiFile == null || !Engine.active()) {
                return;
            }
            try {
                if (aiHost) {
                    Files.write(aiFile, Engine.aiLogOut(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    return;
                }
                byte[] in = readNew(aiFile, aiOffset);
                aiOffset += in.length;
                if (in.length > 0) {
                    Engine.aiLogIn(in);
                }
            } catch (IOException e) {
                throw new IllegalStateException("SOSDET rig: AI log file " + aiFile, e);
            }
        }

        /** This copy's new hashes go to its file; the other copies' new hashes go to the engine. Between frames. */
        private void exchangeHashes() {
            if (hashFile == null || !Engine.active()) {
                return;
            }
            try {
                byte[] out = Engine.hashOut();
                if (out.length > 0) {
                    Files.write(hashFile, out, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                for (int i = 0; i < hashPeers.size(); i++) {
                    byte[] in = readNew(hashPeers.get(i), hashOffsets[i]);
                    hashOffsets[i] += in.length;
                    if (in.length > 0) {
                        Engine.hashIn(hashPeerPcs.get(i), in);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("SOSDET rig: hash file", e);
            }
            Desync d = Engine.desync();
            if (d != null && !desyncReported) {
                desyncReported = true;
                try {
                    Files.write(dir.resolve(tag + "-desync.txt"), Engine.desyncReport().getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new IllegalStateException("SOSDET rig: cannot write the desync report", e);
                }
                StringBuilder sections = new StringBuilder();
                for (String name : d.sections()) {
                    sections.append(sections.length() == 0 ? "\"" : ",\"").append(name).append('"');
                }
                event("desync", String.format(Locale.ROOT, "\"step\":%d,\"pc\":%d,\"noticedAt\":%d,\"sections\":[%s],\"divisions\":%s",
                        d.step, d.pc, d.noticedAt, sections, Arrays.toString(d.divisions())));
            }
        }

        /** What a file has past {@code offset} (a record still being written waits in the engine). */
        private static byte[] readNew(Path file, long offset) throws IOException {
            if (!Files.exists(file)) {
                return new byte[0];
            }
            try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file)) {
                long size = ch.size();
                if (size <= offset) {
                    return new byte[0];
                }
                java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate((int) (size - offset));
                ch.position(offset);
                while (b.hasRemaining() && ch.read(b) > 0) {
                    // read what is there now
                }
                return Arrays.copyOf(b.array(), b.position());
            }
        }

        /**
         * Forced desync (SOS-29): the first soldier in a division gets the most injuries there are, so the game kills them
         * on their next update. A lasting change (a speed nudge healed within 30 steps in the city run NC29).
         */
        private void nudge() {
            ENTITY[] all = SETT.ENTITIES().getAllEnts();
            int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
            for (int i = 0; i < max; i++) {
                ENTITY e = all[i];
                if (e instanceof Humanoid && !e.isRemoved() && ((Humanoid) e).division() != null) {
                    Humanoid h = (Humanoid) e;
                    STATS.NEEDS().INJURIES.COUNT.indu().set(h.indu(), STATS.NEEDS().INJURIES.COUNT.indu().max(h.indu()));
                    event("nudged", String.format(Locale.ROOT, "\"step\":%d,\"entity\":%d,\"division\":%d", Engine.step(), i,
                            ((Humanoid) e).division().index()));
                    return;
                }
            }
            event("error", "\"message\":\"nudge: no soldier\"");
        }

        /** Host: saves the resync at the current step (between steps), then loads it like every peer. */
        private void saveResync() {
            long t0 = System.nanoTime();
            long step = Engine.step();
            byte[] state = Engine.resyncSave(resyncSave);
            long saved = System.nanoTime();
            try {
                Files.write(resyncState, state);
                event("resyncsaved", String.format(Locale.ROOT, "\"step\":%d,\"saveMs\":%.0f,\"saveBytes\":%d,\"stateBytes\":%d",
                        step, (saved - t0) / 1e6, Files.size(resyncSave), state.length));
            } catch (IOException e) {
                throw new IllegalStateException("SOSDET rig: cannot write " + resyncState, e);
            }
            resyncStep = -1;
            loadResync(resyncSave, state);
        }

        /** Loads a resync (every copy, the host included); the run goes on from its step once loaded. */
        private void loadResync(Path save, byte[] state) {
            resyncLoadStarted = System.nanoTime();
            event("resyncloading", "\"fromStep\":" + Engine.step());
            Engine.resyncLoad(save, state, () -> {
                if (!city) {
                    BattleState.setLoaded(exiter(), save, false);
                }
            });
        }

        /** Peer: the first frame of the game loaded for a resync. */
        private void resynced() {
            double seconds = (System.nanoTime() - resyncLoadStarted) / 1e9;
            resyncLoadStarted = 0;
            VIEW.inters().load.deactivate();
            if (!city) {
                try {
                    hideDeployPanel();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("SOSDET rig: cannot hide the deploy panel", e);
                }
            }
            GAME.SPEED.speedSet(speed);
            try {
                // lines before this for later steps came from the game before the load; the runner drops them
                log.write("resync," + Engine.step() + ",0\n");
            } catch (IOException e) {
                throw new IllegalStateException("SOSDET rig: cannot write the hash log", e);
            }
            runOn();
            event("resynced", String.format(Locale.ROOT, "\"step\":%d,\"loadSeconds\":%.1f", Engine.step(), seconds));
        }

        /** Lets the run go on to its last step, or freely. */
        private void runOn() {
            if (lastStep == Long.MAX_VALUE) {
                Engine.runFreely();
            } else {
                Engine.runUntil(lastStep);
            }
        }

        /** One line per frame: the previous frame's update time (patch 91) and the game time it advanced. */
        private void timing() {
            try {
                if (pendingGameSeconds >= 0) {
                    timing.write(FrameTiming.lastUpdateNanos() + "," + pendingGameSeconds + "\n");
                }
            } catch (IOException e) {
                throw new IllegalStateException("SOSDET rig: cannot write the timing log", e);
            }
            pendingGameSeconds = frameGameSeconds;
            frameGameSeconds = 0;
        }

        /** Records the simulation thread's stack once for every step that runs over 100 ms. */
        private void startStallSampler(Path file) {
            sampling = true;
            Thread t = new Thread(() -> {
                long sampled = 0;
                while (sampling) {
                    long start = Engine.stepStartNanos();
                    Thread sim = simThread;
                    if (start != 0 && start != sampled && sim != null && System.nanoTime() - start > STALL_NANOS) {
                        sampled = start;
                        StringBuilder sb = new StringBuilder("step over 100 ms, at step " + safeStep() + ":\n");
                        for (StackTraceElement e : sim.getStackTrace()) {
                            sb.append("  at ").append(e).append('\n');
                        }
                        try {
                            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                                    StandardOpenOption.APPEND);
                        } catch (IOException e) {
                            return; // diagnostics only; the run itself goes on
                        }
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "sosdet-rig-stall-sampler");
            t.setDaemon(true);
            t.start();
        }

        private static long safeStep() {
            return Engine.active() ? Engine.step() : -1;
        }

        /** The rig's only boundary for failures: a failed command is reported and the copy keeps running. */
        private void poll() {
            if (!Files.exists(commandFile)) {
                return;
            }
            String command;
            try {
                command = new String(Files.readAllBytes(commandFile), StandardCharsets.UTF_8).trim();
                Files.delete(commandFile);
            } catch (IOException e) {
                event("error", "\"message\":\"" + clean("reading the command: " + e) + "\"");
                return;
            }
            try {
                execute(command.split("\\s+"));
            } catch (RuntimeException | ReflectiveOperationException | IOException e) {
                event("error", "\"command\":\"" + clean(command) + "\",\"message\":\"" + clean(e.toString()) + "\"");
            }
        }

        private void execute(String[] c) throws ReflectiveOperationException, IOException {
            switch (c[0]) {
            case "begin":
                if (Engine.active()) {
                    Engine.end();
                }
                Engine.begin();
                event("begin", "");
                return;
            case "end":
                Engine.end();
                event("end", "");
                return;
            case "loadseed":
                Engine.armLoadSeed(Integer.parseInt(c[1]));
                event("loadseed", "\"seed\":" + Integer.parseInt(c[1]));
                return;
            case "holdworld":
                Engine.holdWorld(c[1].equals("1"));
                event("holdworld", "\"held\":" + c[1].equals("1"));
                return;
            case "load":
                event("loading", "\"save\":\"" + clean(rest(c)) + "\"");
                new GameLoader(PATHS.local().save().get(rest(c))).set();
                return;
            case "generate":
                generate(Integer.parseInt(c[1]), Integer.parseInt(c[2]), c.length > 3 ? Integer.parseInt(c[3]) : 0);
                return;
            case "loadbattle":
                loadBattle(rest(c));
                return;
            case "steplength":
                Engine.stepSeconds(1.0 / Integer.parseInt(c[1]));
                event("steplength", "\"perSecond\":" + c[1]);
                return;
            case "seed":
                Engine.seed(Integer.parseInt(c[1]));
                event("seeded", "\"seed\":" + Integer.parseInt(c[1]));
                return;
            case "invade":
                invadeMen = Integer.parseInt(c[1]);
                event("invade", "\"men\":" + invadeMen);
                return;
            case "ai":
                if (c[1].equals("instep")) {
                    Engine.aiInStep();
                } else if (c[1].equals("host")) {
                    Engine.aiHost(Long.parseLong(c[2], 16));
                } else if (c[1].equals("peer")) {
                    Engine.aiPeer(Long.parseLong(c[2], 16));
                } else {
                    throw new IllegalArgumentException("SOSDET rig: ai " + c[1]);
                }
                event("ai", "\"mode\":\"" + Engine.aiMode() + "\"");
                return;
            case "ailog":
                aiHost = c[1].equals("host");
                aiFile = Paths.get(rest(Arrays.copyOfRange(c, 1, c.length)));
                aiOffset = 0;
                event("ailog", "\"host\":" + aiHost + ",\"file\":\"" + clean(aiFile.toString()) + "\"");
                return;
            case "hashes":
                hashFile = Paths.get(rest(c));
                Files.deleteIfExists(hashFile);
                desyncReported = false;
                event("hashes", "\"file\":\"" + clean(hashFile.toString()) + "\"");
                return;
            case "hashpeer":
                hashPeerPcs.add(Integer.parseInt(c[1]));
                hashPeers.add(Paths.get(rest(Arrays.copyOfRange(c, 1, c.length))));
                hashOffsets = Arrays.copyOf(hashOffsets, hashPeers.size());
                event("hashpeer", "\"pc\":" + c[1]);
                return;
            case "resyncat":
                resyncStep = Long.parseLong(c[1]);
                resyncSave = Paths.get(c[2]);
                resyncState = Paths.get(c[3]);
                event("resyncat", "\"step\":" + resyncStep);
                return;
            case "resyncnow":
                resyncSave = Paths.get(c[1]);
                resyncState = Paths.get(c[2]);
                saveResync();
                return;
            case "resyncload":
                loadResync(Paths.get(c[1]), Files.readAllBytes(Paths.get(c[2])));
                return;
            case "nudge":
                nudgeStep = Long.parseLong(c[1]);
                event("nudge", "\"step\":" + nudgeStep);
                return;
            case "run":
                run(c[1].equals("city"), Long.parseLong(c[2]), Double.parseDouble(c[3]), c[4]);
                return;
            case "vanilla":
                vanilla(c[1].equals("city"), Double.parseDouble(c[2]), Double.parseDouble(c[3]), c[4]);
                return;
            case "hold":
                if (!running) {
                    throw new IllegalStateException("SOSDET rig: hold needs a run going");
                }
                lastStep = Engine.step();
                Engine.runUntil(lastStep);
                finish();
                return;
            case "hash":
                event("hash", String.format(Locale.ROOT, "\"battle\":\"%016x\",\"city\":\"%016x\"", battleHash(), cityHash()));
                return;
            default:
                event("error", "\"message\":\"unknown command: " + clean(String.join(" ", c)) + "\"");
            }
        }

        private static String rest(String[] c) {
            return String.join(" ", Arrays.copyOfRange(c, 1, c.length));
        }

        // ------------------------------------------------------------ runs

        private void run(boolean cityRun, long steps, double speed, String runTag) throws ReflectiveOperationException, IOException {
            if (!Engine.active()) {
                throw new IllegalStateException("SOSDET rig: run needs a session (begin)");
            }
            city = cityRun;
            tag = runTag;
            if (!city) {
                // Battle start is step 0, inside the simulation (hook B12); then what the Start button does besides.
                Engine.atStepZero(() -> VIEW.b().state().deploy());
                hideDeployPanel();
            }
            VIEW.inters().load.deactivate(); // the "click to continue" screen after a load would pause the game
            log = Files.newBufferedWriter(dir.resolve(tag + ".csv"), StandardCharsets.UTF_8);
            log.write("step,hash,men\n");
            openTiming();
            startStallSampler(dir.resolve(tag + "-stalls.txt"));
            Engine.resetStats();
            long from = Engine.step();
            lastStep = steps > 0 ? from + steps : Long.MAX_VALUE; // 0: until hold
            if (resyncStep >= 0) {
                Engine.runUntil(Math.min(lastStep, resyncStep)); // held there for the resync save
            } else {
                runOn();
            }
            this.speed = speed;
            started = System.nanoTime();
            running = true;
            GAME.SPEED.speedSet(speed);
            event("running", String.format(Locale.ROOT, "\"tag\":\"%s\",\"city\":%b,\"fromStep\":%d,\"toStep\":%d,\"speed\":%.1f",
                    tag, city, from, steps > 0 ? lastStep : -1, speed));
        }

        /** The timing baseline: no session, the game's own stepping and threads, for this much game time. */
        private void vanilla(boolean cityRun, double seconds, double speed, String runTag) throws ReflectiveOperationException,
                IOException {
            if (Engine.active()) {
                throw new IllegalStateException("SOSDET rig: vanilla runs without a session (end)");
            }
            city = cityRun;
            tag = runTag;
            vanilla = true;
            if (!city) {
                VIEW.b().state().deploy();
                hideDeployPanel();
            }
            VIEW.inters().load.deactivate();
            openTiming();
            gameSeconds = 0;
            gameSecondsLeft = seconds;
            subSteps = 0;
            started = System.nanoTime();
            running = true;
            GAME.SPEED.speedSet(speed);
            event("running", String.format(Locale.ROOT, "\"tag\":\"%s\",\"city\":%b,\"vanilla\":true,\"seconds\":%.1f,\"speed\":%.1f",
                    tag, city, seconds, speed));
        }

        private void openTiming() throws IOException {
            timing = Files.newBufferedWriter(dir.resolve(tag + "-timing.csv"), StandardCharsets.UTF_8);
            timing.write("updateNanos,gameSeconds\n");
            frameGameSeconds = 0;
            pendingGameSeconds = -1;
        }

        private void flushLog() {
            try {
                log.flush();
            } catch (IOException e) {
                throw new IllegalStateException("SOSDET rig: cannot flush the hash log", e);
            }
        }

        private void finish() {
            running = false;
            sampling = false;
            GAME.SPEED.speedSet(0);
            String end = Long.toHexString(city ? cityHash() : battleHash());
            try {
                timing.close();
                if (!vanilla) {
                    log.write("end," + end + "," + men() + "\n");
                    log.close();
                }
            } catch (IOException e) {
                throw new IllegalStateException("SOSDET rig: cannot close the logs", e);
            }
            if (vanilla) {
                vanilla = false;
                event("finished", String.format(Locale.ROOT, "\"tag\":\"%s\",\"vanilla\":true,\"endStep\":%d,\"gameSeconds\":%.2f,"
                        + "\"endHash\":\"%s\",\"men\":%d,\"wallSeconds\":%.1f,\"stats\":{}", tag, subSteps, gameSeconds, end, men(),
                        (System.nanoTime() - started) / 1e9));
                return;
            }
            event("finished", String.format(Locale.ROOT, "\"tag\":\"%s\",\"endStep\":%d,\"endHash\":\"%s\",\"men\":%d,\"wallSeconds\":%.1f,\"stats\":%s",
                    tag, Engine.step(), end, men(), (System.nanoTime() - started) / 1e9, Engine.stats().toJson()));
        }

        /** Step 1: the debug panel's invasion. Step 2: its launch (the end of the warning): the army enters the city. */
        private void invasion(long step) {
            try {
                Object invador = SETT.INVADOR();
                if (step == 1) {
                    Method invade = invador.getClass().getDeclaredMethod("invade", int.class, double.class);
                    invade.setAccessible(true);
                    invade.invoke(invador, invadeMen, 0.2);
                } else if (step == 2) {
                    for (Object active : (Iterable<?>) field(invador, "active")) {
                        Method launch = active.getClass().getDeclaredMethod("launch");
                        launch.setAccessible(true);
                        launch.invoke(active);
                        break;
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("SOSDET rig: invasion failed at step " + step, e);
            }
            VIEW.inters().messages.hide();
        }

        /** What the Start button does besides deploy(): hide the deployment panel, which pauses every frame. */
        private static void hideDeployPanel() throws ReflectiveOperationException {
            Object manager = field(VIEW.b(), "uiManager");
            for (Object inter : (Iterable<?>) field(manager, "inters")) {
                if (inter.getClass().getName().equals("view.battle.IDeploy")) {
                    Method hide = Class.forName("view.interrupter.Interrupter").getDeclaredMethod("hide");
                    hide.setAccessible(true);
                    hide.invoke(inter);
                }
            }
        }

        // ------------------------------------------------------------ battle saves

        /** Generates a field battle next to the player's capital, as the debug tool does, and saves it as __battle. */
        private void generate(int seed, int menPerSide, int artillery) throws ReflectiveOperationException {
            int wx = FACTIONS.player().capitolRegion().cx() + 3;
            int wy = FACTIONS.player().capitolRegion().cy();
            Method save = BattleState.class.getDeclaredMethod("save", String.class);
            save.setAccessible(true);
            save.invoke(null, "__beforeBattle");
            if (!PATHS.local().save().exists("__battle")) {
                PATHS.local().save().create("__battle");
            }
            RND.setSeed(seed);
            BattleStateSpec spec = new BattleStateSpec();
            fillSide(spec.player, menPerSide, artillery, wx, wy);
            fillSide(spec.enemy, menPerSide, artillery, wx + 1, wy);
            Constructor<BattleState> ctor = BattleState.class.getDeclaredConstructor(BattleStateExiter.class, Path.class);
            ctor.setAccessible(true);
            BattleState state = ctor.newInstance(exiter(), PATHS.local().save().get("__battle"));
            Field tiles = BattleState.class.getDeclaredField("deploymentTiles");
            tiles.setAccessible(true);
            Class<?> generator = Class.forName("game.battle.state.BattleStateGenerator");
            Constructor<?> generatorCtor = generator.getDeclaredConstructor();
            generatorCtor.setAccessible(true);
            Method generateMethod = generator.getDeclaredMethod("generate", BattleState.class, BattleStateSpec.class, Rec.class);
            generateMethod.setAccessible(true);
            generateMethod.invoke(generatorCtor.newInstance(), state, spec, tiles.get(state));
            save.invoke(null, "__battle");
            event("generated", String.format(Locale.ROOT, "\"seed\":%d,\"menPerSide\":%d,\"artillery\":%d,\"artilleryTypes\":%d,\"battle\":\"%016x\"",
                    seed, menPerSide, artillery, SETT.ROOMS().ARTILLERY.size(), battleHash()));
        }

        private void loadBattle(String name) {
            Path path = PATHS.local().save().get(name);
            event("loading", "\"save\":\"" + clean(name) + "\"");
            new GameLoader(path) {
                @Override
                public void doAfterSet() {
                    BattleState.setLoaded(exiter(), path, true);
                }
            }.set();
        }

        private static BattleStateExiter exiter() {
            return new BattleStateExiter() {
                @Override
                public void afterExit(BattleStateResult res) {
                }
            };
        }

        private static void fillSide(BattleStateSpec.SpecSide side, int men, int artillery, int wx, int wy) {
            // Artillery pieces, spread evenly over the artillery types (the first types take the remainder).
            int types = SETT.ROOMS().ARTILLERY.size();
            for (int t = 0; t < types; t++) {
                side.artillery[SETT.ROOMS().ARTILLERY.get(t).typeIndex()] = artillery / types + (t < artillery % types ? 1 : 0);
            }
            int perDiv = Config.battle().MEN_PER_DIVISION;
            int divs = Math.min(Config.battle().DIVISIONS_PER_ARMY, (men + perDiv - 1) / perDiv);
            side.wCoo.set(wx, wy);
            side.moraleBase = 1.0;
            for (int i = 0; i < divs; i++) {
                side.divs.add(division(Math.min(perDiv, men - i * perDiv)));
            }
        }

        private static DivGeneration division(int men) {
            final Race race = RACES.all().rnd();
            final var type = GAME.battle().types.rnd(race, FACTIONS.player(), RND.rFloat());
            final String name = "" + race.info.armyNames.rnd();
            final int banner = RND.rInt(GAME.ARMIES().banners.size());
            final double training = RND.rFloat();
            final double equipment = RND.rFloat();
            final double experience = RND.rFloat();
            DIV_SPEC spec = new DIV_SPEC() {
                @Override
                public double training(StatTraining t) {
                    return type.training(t) * training;
                }

                @Override
                public double equip(EquipBattle e) {
                    return type.equip(e) * equipment;
                }

                @Override
                public Race race() {
                    return race;
                }

                @Override
                public int men() {
                    return men;
                }

                @Override
                public Faction faction() {
                    return null;
                }

                @Override
                public double experience() {
                    return experience;
                }

                @Override
                public CharSequence name() {
                    return name;
                }

                @Override
                public int bannerI() {
                    return banner;
                }
            };
            return new DivGeneration(spec, spec);
        }

        // ------------------------------------------------------------ hashes

        /** FNV-1a over every soldier in a division: index, id, position, speed, injuries, division (SOS-11). */
        private static long battleHash() {
            long h = 0xcbf29ce484222325L;
            ENTITY[] all = SETT.ENTITIES().getAllEnts();
            int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
            for (int i = 0; i < max; i++) {
                ENTITY e = all[i];
                if (!(e instanceof Humanoid) || e.isRemoved()) {
                    continue;
                }
                Humanoid s = (Humanoid) e;
                if (s.division() == null) {
                    continue;
                }
                h = mix(h, i);
                h = mix(h, e.id());
                h = mix(h, e.body().x1());
                h = mix(h, e.body().y1());
                h = mix(h, Double.doubleToLongBits(e.speed.x()));
                h = mix(h, Double.doubleToLongBits(e.speed.y()));
                h = mix(h, Double.doubleToLongBits(STATS.NEEDS().INJURIES.COUNT.indu().getD(s.indu())));
                h = mix(h, s.division().index());
            }
            return h;
        }

        /** FNV-1a over every entity (people and animals): index, type, id, position, speed; people also injuries (SOS-23). */
        private static long cityHash() {
            long h = 0xcbf29ce484222325L;
            ENTITY[] all = SETT.ENTITIES().getAllEnts();
            int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
            for (int i = 0; i < max; i++) {
                ENTITY e = all[i];
                if (e == null || e.isRemoved()) {
                    continue;
                }
                h = mix(h, i);
                h = mix(h, e.getClass().getName().hashCode());
                h = mix(h, e.id());
                h = mix(h, e.body().x1());
                h = mix(h, e.body().y1());
                h = mix(h, Double.doubleToLongBits(e.speed.x()));
                h = mix(h, Double.doubleToLongBits(e.speed.y()));
                if (e instanceof Humanoid) {
                    h = mix(h, Double.doubleToLongBits(STATS.NEEDS().INJURIES.COUNT.indu().getD(((Humanoid) e).indu())));
                }
            }
            return h;
        }

        private static long mix(long h, long v) {
            for (int b = 0; b < 8; b++) {
                h ^= (v >>> (8 * b)) & 0xff;
                h *= 0x100000001b3L;
            }
            return h;
        }

        /** Battle: the player's soldiers. City: every person. */
        private int men() {
            if (!city) {
                return GAME.ARMIES().player().men();
            }
            int n = 0;
            ENTITY[] all = SETT.ENTITIES().getAllEnts();
            int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
            for (int i = 0; i < max; i++) {
                if (all[i] instanceof Humanoid && !all[i].isRemoved()) {
                    n++;
                }
            }
            return n;
        }

        // ------------------------------------------------------------ output

        private static Object field(Object o, String name) throws ReflectiveOperationException {
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException e) {
                    // not declared here; try the superclass
                }
            }
            throw new NoSuchFieldException("SOSDET rig: no field " + name + " in " + o.getClass());
        }

        private static String clean(String s) {
            return s.replace("\\", "/").replace("\"", "'");
        }

        private void event(String name, String fields) {
            String line = "{\"event\":\"" + name + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}" + System.lineSeparator();
            try {
                Files.write(eventsFile, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("SOSDET rig: cannot write " + eventsFile, e);
            }
        }

        @Override
        public void save(FilePutter file) {
        }

        @Override
        public void load(FileGetter file) throws IOException {
        }
    }
}

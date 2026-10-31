import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The rig across two PCs: mirrors the files the rig's copies exchange (commands, events, per-step logs, AI log,
 * hashes, reports, resync saves) over one TCP connection, so the runner and the copies work as if on one disk.
 *
 * <pre>
 *   java Relay serve|connect HOST PORT [--out LOCALDIR REMOTEDIR NAMES|-]... [--cmd LOCALDIR REMOTEDIR]
 *              [--map FROM TO]... [--log FILE]
 * </pre>
 *
 * --out: every file in LOCALDIR ("-"; not "*", which the Windows java launcher expands) or only the comma-separated NAMES grows into REMOTEDIR on the other PC. The files are
 * append-only (a file that shrinks is sent again from the start); "*.done" markers go after the files they mark.
 * --cmd: LOCALDIR's command.txt goes to REMOTEDIR on the other PC, renamed into place there, and is deleted here once
 * the other PC's copy took it, exactly once across reconnects. --map: incoming commands' paths FROM become TO.
 * A dropped link is retried for ever; the receiver says what it has, so nothing is lost or repeated.
 */
public final class Relay {

    private static final byte DATA = 1, NEED = 2, CMD = 3, TAKEN = 4, PING = 5;
    private static final int CHUNK = 64 * 1024, READ_TIMEOUT_MS = 20_000, PING_MS = 3_000;

    private static final class Out {
        final Path local;
        final String remote;
        final List<String> names; // empty: every file

        Out(Path local, String remote, List<String> names) {
            this.local = local;
            this.remote = remote;
            this.names = names;
        }
    }

    private final List<Out> outs = new ArrayList<>();
    private Path cmdLocal;
    private String cmdRemote;
    private final List<String[]> maps = new ArrayList<>();
    private PrintStream log = System.out;

    /** Remote path -> bytes the other PC has (as far as we know); absent: not sent yet in this process. */
    private final Map<String, Long> sent = new HashMap<>();
    private final Map<String, Long> stamps = new HashMap<>();
    /** NEED answers from the reader: remote path -> the other PC's length. */
    private final ConcurrentLinkedQueue<Object[]> needs = new ConcurrentLinkedQueue<>();
    /** Our command in flight: id and text; null when none. */
    private long cmdId;
    private String cmdText;
    private volatile long takenId = -1;
    /** Commands from the other PC: [id, dir, text] waiting to be put in place; the one put in place, waiting to be taken. */
    private final ConcurrentLinkedQueue<Object[]> incoming = new ConcurrentLinkedQueue<>();
    private Object[] placed;
    private long lastPlaced = -1;
    private final ConcurrentLinkedQueue<Long> takenOut = new ConcurrentLinkedQueue<>();
    private long bytesOut, bytesIn, statsAt = System.currentTimeMillis();

    public static void main(String[] a) throws Exception {
        Relay r = new Relay();
        boolean serve = a[0].equals("serve");
        if (!serve && !a[0].equals("connect")) {
            throw new IllegalArgumentException("Relay: serve or connect, not " + a[0]);
        }
        String host = a[1];
        int port = Integer.parseInt(a[2]);
        for (int i = 3; i < a.length; i++) {
            switch (a[i]) {
                case "--out":
                    r.outs.add(new Out(Paths.get(a[i + 1]), a[i + 2],
                            a[i + 3].equals("-") ? List.of() : List.of(a[i + 3].split(","))));
                    i += 3;
                    break;
                case "--cmd":
                    r.cmdLocal = Paths.get(a[i + 1]);
                    r.cmdRemote = a[i + 2];
                    i += 2;
                    break;
                case "--map":
                    r.maps.add(new String[] {a[i + 1], a[i + 2]});
                    i += 2;
                    break;
                case "--log":
                    r.log = new PrintStream(Files.newOutputStream(Paths.get(a[i + 1]), StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND), true, "UTF-8");
                    i += 1;
                    break;
                default:
                    throw new IllegalArgumentException("Relay: unknown option " + a[i]);
            }
        }
        r.log("start: " + String.join(" ", a));
        ServerSocket server = null;
        if (serve) {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(host, port));
        }
        while (true) {
            try (Socket s = serve ? server.accept() : new Socket()) {
                if (!serve) {
                    s.connect(new InetSocketAddress(host, port), 10_000);
                }
                s.setSoTimeout(READ_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                r.log("connected to " + s.getRemoteSocketAddress());
                r.session(s);
            } catch (IOException e) {
                r.log("link down: " + e);
            }
            Thread.sleep(serve ? 100 : 2_000);
        }
    }

    private void session(Socket s) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 256 * 1024));
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 256 * 1024));
        final IOException[] readFailed = {null};
        Thread reader = new Thread(() -> {
            try {
                while (true) {
                    read(in, out);
                }
            } catch (IOException e) {
                readFailed[0] = e;
            }
        }, "relay-reader");
        reader.setDaemon(true);
        reader.start();
        if (cmdText != null) {
            sendCmd(out); // in flight when the link dropped: again, the other PC takes it once
        }
        long pinged = 0;
        while (reader.isAlive()) {
            boolean busy = pump(out);
            if (System.currentTimeMillis() - pinged > PING_MS) {
                synchronized (out) {
                    out.writeByte(PING);
                }
                pinged = System.currentTimeMillis();
            }
            synchronized (out) {
                out.flush();
            }
            stats();
            if (!busy) {
                sleep(10);
            }
        }
        s.close();
        throw readFailed[0] != null ? readFailed[0] : new IOException("reader ended");
    }

    /** One round of everything there is to send; true if anything went. */
    private boolean pump(DataOutputStream out) throws IOException {
        boolean busy = false;
        for (Object[] n; (n = needs.poll()) != null; ) {
            log("the other PC has " + n[1] + " bytes of " + n[0] + "; sending from there");
            sent.put((String) n[0], (Long) n[1]);
        }
        for (Long id; (id = takenOut.poll()) != null; ) {
            synchronized (out) {
                out.writeByte(TAKEN);
                out.writeLong(id);
            }
        }
        List<Path[]> markers = new ArrayList<>();
        for (Out o : outs) {
            List<Path> files = new ArrayList<>();
            try (var list = Files.list(o.local)) {
                list.forEach(files::add);
            } catch (NoSuchFileException e) {
                continue; // not made yet
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (name.startsWith("command.") || !o.names.isEmpty() && !o.names.contains(name) || Files.isDirectory(f)) {
                    continue;
                }
                if (name.endsWith(".done")) {
                    markers.add(new Path[] {f, Paths.get(o.remote, name)});
                } else {
                    busy |= sendFile(out, f, Paths.get(o.remote, name).toString());
                }
            }
        }
        for (Path[] m : markers) {
            busy |= sendFile(out, m[0], m[1].toString());
        }
        if (cmdLocal != null) {
            if (cmdText != null && takenId == cmdId) {
                Files.deleteIfExists(cmdLocal.resolve("command.txt"));
                log("command " + cmdId + " taken");
                cmdText = null;
            }
            Path c = cmdLocal.resolve("command.txt");
            if (cmdText == null && Files.exists(c)) {
                try {
                    cmdText = new String(Files.readAllBytes(c), StandardCharsets.UTF_8);
                    cmdId++;
                    sendCmd(out);
                    busy = true;
                } catch (IOException e) {
                    cmdText = null; // still being renamed into place: next round
                }
            }
        }
        busy |= placeIncoming();
        return busy;
    }

    /** A marker or a save is written anew, not appended: a second battle's "11418" over "6873" isn't a longer file. */
    private static boolean rewritten(String name) {
        return name.endsWith(".done") || name.endsWith(".save") || name.endsWith(".state");
    }

    private boolean sendFile(DataOutputStream out, Path f, String remote) throws IOException {
        long size;
        try {
            size = Files.size(f);
        } catch (NoSuchFileException e) {
            return false;
        }
        Long have = sent.get(remote);
        long stamp = rewritten(remote) ? Files.getLastModifiedTime(f).toMillis() : 0;
        Long seen = stamps.put(remote, stamp);
        boolean reset = have == null || size < have || seen != null && seen != stamp;
        long from = reset ? 0 : have;
        if (!reset && from == size) {
            return false;
        }
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(CHUNK);
            long at = from;
            do {
                buf.clear();
                int n = Math.max(0, ch.read(buf, at));
                synchronized (out) {
                    out.writeByte(DATA);
                    out.writeUTF(remote);
                    out.writeLong(at);
                    out.writeBoolean(reset && at == 0);
                    out.writeInt(n);
                    out.write(buf.array(), 0, n);
                }
                bytesOut += n;
                at += n;
                if (n == 0) {
                    break; // an empty file, or one that shrank meanwhile: the next round sees it again
                }
            } while (at < size);
            sent.put(remote, at);
        } catch (NoSuchFileException e) {
            return false;
        }
        return true;
    }

    private void sendCmd(DataOutputStream out) throws IOException {
        synchronized (out) {
            out.writeByte(CMD);
            out.writeLong(cmdId);
            out.writeUTF(cmdRemote);
            out.writeUTF(cmdText);
        }
        log("command " + cmdId + " sent: " + cmdText.trim());
    }

    private void read(DataInputStream in, DataOutputStream out) throws IOException {
        byte type = in.readByte();
        switch (type) {
            case DATA: {
                Path f = Paths.get(in.readUTF());
                long offset = in.readLong();
                boolean reset = in.readBoolean();
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                bytesIn += b.length;
                write(f, offset, reset, b, out);
                return;
            }
            case NEED:
                needs.add(new Object[] {in.readUTF(), in.readLong()});
                return;
            case CMD:
                incoming.add(new Object[] {in.readLong(), in.readUTF(), in.readUTF()});
                return;
            case TAKEN:
                takenId = in.readLong();
                return;
            case PING:
                return;
            default:
                throw new IOException("Relay: unknown message " + type);
        }
    }

    private void write(Path f, long offset, boolean reset, byte[] b, DataOutputStream out) throws IOException {
        Files.createDirectories(f.getParent());
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            if (reset) {
                ch.truncate(0);
            }
            long cur = ch.size();
            if (offset > cur) {
                synchronized (out) {
                    out.writeByte(NEED);
                    out.writeUTF(f.toString());
                    out.writeLong(cur);
                }
                return;
            }
            int skip = (int) (cur - offset);
            if (skip < b.length) {
                ch.write(ByteBuffer.wrap(b, skip, b.length - skip), cur);
            }
        }
    }

    /** Puts the other PC's next command in place once the last is taken; true if anything changed. */
    private boolean placeIncoming() throws IOException {
        if (placed != null) {
            Path c = Paths.get((String) placed[1], "command.txt");
            if (Files.exists(c)) {
                return false;
            }
            takenOut.add((Long) placed[0]);
            placed = null;
            return true;
        }
        Object[] next = incoming.poll();
        if (next == null) {
            return false;
        }
        long id = (Long) next[0];
        if (id <= lastPlaced) {
            takenOut.add(id); // sent again after a reconnect; it was put in place already
            return true;
        }
        Path dir = Paths.get((String) next[1]);
        Path c = dir.resolve("command.txt");
        if (Files.exists(c)) {
            incoming.add(next); // never: the sender waits until the last is taken; keep order regardless
            return false;
        }
        String text = (String) next[2];
        for (String[] m : maps) {
            text = replaceIgnoreCase(text, m[0], m[1]);
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve("command.tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, c, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        lastPlaced = id;
        placed = new Object[] {id, next[1]};
        log("command " + id + " put in place: " + text.trim());
        return true;
    }

    static String replaceIgnoreCase(String text, String from, String to) {
        StringBuilder b = new StringBuilder();
        String lower = text.toLowerCase(java.util.Locale.ROOT), f = from.toLowerCase(java.util.Locale.ROOT);
        int at = 0;
        for (int i; (i = lower.indexOf(f, at)) >= 0; at = i + from.length()) {
            b.append(text, at, i).append(to);
        }
        return b.append(text.substring(at)).toString();
    }

    private void stats() {
        if (System.currentTimeMillis() - statsAt > 60_000) {
            log("last minute: " + bytesOut + " bytes out, " + bytesIn + " bytes in");
            bytesOut = 0;
            bytesIn = 0;
            statsAt = System.currentTimeMillis();
        }
    }

    private void log(String msg) {
        log.println(LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + " " + msg);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package sosdet.game;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.SAVABLE;

/**
 * Game objects to bytes and back, in the game's own save format, so the engine never copies the game's field
 * lists. The game's FilePutter and FileGetter only work with files; the engine reads the putter's buffer and points
 * a getter at its own bytes, one private field each (AGENTS.md "Adapter Boundary").
 *
 * <p>Not thread-safe: the host's general thread encodes, the simulation thread decodes and hashes, each with its own
 * codec.
 */
final class GameCodec {

    private static final Field PUTTER_BUFFER = field(FilePutter.class, "buffer");
    private static final Field GETTER_BUFFER = field(FileGetter.class, "buffer");
    private static final int MAX_BYTES = 1 << 16;

    private final FilePutter putter;
    private final ByteBuffer putterBuffer;
    /** The putter's own stream header comes first; payloads start after it. */
    private final int payloadStart;
    private final FileGetter getter = getter();

    /** For AI log entries (small). */
    GameCodec() {
        this(MAX_BYTES);
    }

    /** Room for {@code maxBytes} of save data at once. */
    GameCodec(int maxBytes) {
        putter = new FilePutter(Paths.get("sosdet-unused"), maxBytes);
        putterBuffer = (ByteBuffer) get(PUTTER_BUFFER, putter);
        payloadStart = putterBuffer.position();
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("SOSDET: no field " + owner.getName() + "." + name
                    + " in this game build (SOSDET supports Songs of Syx V71, build 71.44)", e);
        }
    }

    private static Object get(Field f, Object o) {
        try {
            return f.get(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot read " + f, e);
        }
    }

    /** A getter needs a real file to be made; afterwards it only ever reads the bytes given to {@link #decode}. */
    private static FileGetter getter() {
        try {
            Path tmp = Files.createTempFile("sosdet-ai", ".bin");
            try {
                new FilePutter(tmp, 64).save();
                return new FileGetter(tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("SOSDET: cannot make a reader for AI log entries", e);
        }
    }

    byte[] encode(SAVABLE object) {
        putterBuffer.position(payloadStart);
        object.save(putter);
        return Arrays.copyOfRange(putterBuffer.array(), payloadStart, putterBuffer.position());
    }

    /** Starts hashing save data: the caller writes to the returned putter, then calls {@link #hashEnd}. */
    FilePutter hashBegin() {
        putterBuffer.position(payloadStart);
        return putter;
    }

    /** Mixes what was written since {@link #hashBegin} into {@code h}; no allocation. */
    long hashEnd(long h) {
        return sosdet.hash.HashLog.mix(h, putterBuffer.array(), payloadStart, putterBuffer.position());
    }

    /** Mixes one object's save data into {@code h}. */
    long hash(long h, SAVABLE object) {
        try {
            object.save(hashBegin());
        } catch (BufferOverflowException e) {
            throw new IllegalStateException("SOSDET: save data of " + object.getClass().getName() + " over "
                    + (putterBuffer.capacity() - payloadStart) + " bytes", e);
        }
        return hashEnd(h);
    }

    /** What was written to the putter since {@link #hashBegin}, as bytes. */
    byte[] written() {
        return Arrays.copyOfRange(putterBuffer.array(), payloadStart, putterBuffer.position());
    }

    /** A reader of these bytes, for a game constructor that loads itself from one. */
    FileGetter reader(byte[] bytes) {
        try {
            GETTER_BUFFER.set(getter, ByteBuffer.wrap(bytes));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSDET: cannot point the reader at " + bytes.length + " bytes", e);
        }
        return getter;
    }

    void decode(byte[] bytes, SAVABLE into) {
        try {
            GETTER_BUFFER.set(getter, ByteBuffer.wrap(bytes));
            into.load(getter);
        } catch (IllegalAccessException | IOException e) {
            throw new IllegalStateException("SOSDET: cannot read an AI log entry of " + bytes.length + " bytes", e);
        }
    }
}

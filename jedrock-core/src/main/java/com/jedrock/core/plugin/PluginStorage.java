package com.jedrock.core.plugin;

import com.jedrock.utils.JLogger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Where a script's memory lives between restarts — the backing store behind the {@code storage} global.
 *
 * <p>Keys are grouped into <b>buckets</b>, one per plugin (and one more per player a plugin stores
 * anything about), so two scripts can both keep a {@code "count"} without meeting. A bucket is created by
 * writing to it and disappears when its last key goes, so an idle plugin costs nothing on disk.
 *
 * <p>Values are deliberately few: a string, a number, a boolean, or a structured value the scripting layer
 * has already rendered to JSON text. That covers what a script actually persists (a score, a name, a
 * saved arrangement) without dragging a serialization framework into a server whose whole point is
 * travelling light — and it means the file can be read back without executing anything.
 *
 * <p>Written like the world is: one DEFLATE stream, a dirty flag so an unchanged store is never rewritten,
 * and an atomic temp-and-move so a crash mid-write cannot destroy what was already saved. The store
 * outlives a hot-reload — data belongs to the plugin name, not to the loaded instance of the script.
 */
public final class PluginStorage {

    private static final JLogger LOGGER = JLogger.getLogger("plugin");

    /** Current on-disk format version; bump on any incompatible layout change. */
    public static final int FORMAT_VERSION = 1;

    private static final byte[] MAGIC = {'J', 'D', 'S', 'T'};

    /** The value kinds a script may persist. The ordinal is the on-disk tag, so don't reorder. */
    enum Kind {
        STRING,
        NUMBER,
        BOOLEAN,
        /** A JS object or array, stored as the JSON text the scripting layer produced from it. */
        JSON
    }

    /** One stored value: its kind and its payload, already in the form it is written in. */
    record Value(Kind kind, String text, double number, boolean flag) {

        static Value of(String s) {
            return new Value(Kind.STRING, s, 0, false);
        }

        static Value of(double d) {
            return new Value(Kind.NUMBER, null, d, false);
        }

        static Value of(boolean b) {
            return new Value(Kind.BOOLEAN, null, 0, b);
        }

        static Value json(String json) {
            return new Value(Kind.JSON, json, 0, false);
        }
    }

    private final Map<String, Map<String, Value>> buckets = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    // ===== The bucket operations the script view is built on =====

    Value get(String bucket, String key) {
        Map<String, Value> entries = buckets.get(bucket);
        return entries == null ? null : entries.get(key);
    }

    void put(String bucket, String key, Value value) {
        buckets.computeIfAbsent(bucket, b -> new ConcurrentHashMap<>()).put(key, value);
        dirty = true;
    }

    /** @return {@code true} if the key was there */
    boolean remove(String bucket, String key) {
        Map<String, Value> entries = buckets.get(bucket);
        if (entries == null || entries.remove(key) == null) {
            return false;
        }
        // An emptied bucket is left in the map rather than raced out from under a concurrent put; the
        // save skips empty ones, so it never reaches disk either way.
        dirty = true;
        return true;
    }

    String[] keys(String bucket) {
        Map<String, Value> entries = buckets.get(bucket);
        return entries == null ? new String[0] : entries.keySet().toArray(new String[0]);
    }

    int size(String bucket) {
        Map<String, Value> entries = buckets.get(bucket);
        return entries == null ? 0 : entries.size();
    }

    void clear(String bucket) {
        if (buckets.remove(bucket) != null) {
            dirty = true;
        }
    }

    /** Every bucket name currently holding something. For diagnostics and the console. */
    public Set<String> buckets() {
        return buckets.keySet();
    }

    /** Total keys across every bucket — what the console reports. */
    public int totalKeys() {
        int total = 0;
        for (Map<String, Value> entries : buckets.values()) {
            total += entries.size();
        }
        return total;
    }

    /** Whether anything changed since the last save — an untouched store is never rewritten. */
    public boolean isDirty() {
        return dirty;
    }

    // ===== Persistence =====

    /**
     * Read a store from {@code file}. A missing file is not an error — that is simply the first run. A
     * file that can't be read leaves the store empty and is <em>not</em> overwritten by the next save
     * until something writes to the store, so a corrupt file can still be inspected by hand.
     */
    public void load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream header = new DataInputStream(raw)) {
            byte[] magic = new byte[MAGIC.length];
            header.readFully(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    throw new IOException("not a Jedrock plugin store (bad magic)");
                }
            }
            int version = header.readInt();
            if (version > FORMAT_VERSION) {
                throw new IOException("plugin store format v" + version + " is newer than this server (v"
                        + FORMAT_VERSION + ")");
            }
            try (DataInputStream in = new DataInputStream(new InflaterInputStream(raw))) {
                int bucketCount = in.readInt();
                for (int b = 0; b < bucketCount; b++) {
                    String bucket = readString(in);
                    int entryCount = in.readInt();
                    Map<String, Value> entries = new ConcurrentHashMap<>(Math.max(4, entryCount));
                    for (int e = 0; e < entryCount; e++) {
                        String key = readString(in);
                        entries.put(key, readValue(in));
                    }
                    if (!entries.isEmpty()) {
                        buckets.put(bucket, entries);
                    }
                }
            }
        }
        dirty = false;
    }

    /** Write the whole store to {@code file}, atomically. Clears the dirty flag on success. */
    public void save(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(tmp);
             DataOutputStream header = new DataOutputStream(raw)) {
            header.write(MAGIC);
            header.writeInt(FORMAT_VERSION);
            header.flush();
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DataOutputStream out =
                         new DataOutputStream(new DeflaterOutputStream(raw, deflater))) {
                // Snapshot the non-empty buckets first: the count has to match what follows it, and a
                // script on another thread may be writing while this runs.
                Map<String, Map<String, Value>> written = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Map<String, Value>> bucket : buckets.entrySet()) {
                    Map<String, Value> entries = new java.util.LinkedHashMap<>(bucket.getValue());
                    if (!entries.isEmpty()) {
                        written.put(bucket.getKey(), entries);
                    }
                }
                out.writeInt(written.size());
                for (Map.Entry<String, Map<String, Value>> bucket : written.entrySet()) {
                    writeString(out, bucket.getKey());
                    Map<String, Value> entries = bucket.getValue();
                    out.writeInt(entries.size());
                    for (Map.Entry<String, Value> entry : entries.entrySet()) {
                        writeString(out, entry.getKey());
                        writeValue(out, entry.getValue());
                    }
                }
            } finally {
                deflater.end();
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    /** Save only when something changed; log rather than throw, so a bad disk never takes the server down. */
    public void saveIfDirty(Path file) {
        if (!dirty) {
            return;
        }
        try {
            save(file);
            LOGGER.info("Saved plugin storage to " + file.toAbsolutePath()
                    + " (" + buckets.size() + " bucket(s), " + totalKeys() + " key(s))");
        } catch (IOException e) {
            LOGGER.error("Failed to save plugin storage to " + file.toAbsolutePath(), e);
        }
    }

    private static void writeValue(DataOutputStream out, Value value) throws IOException {
        out.writeByte(value.kind().ordinal());
        switch (value.kind()) {
            case STRING, JSON -> writeString(out, value.text());
            case NUMBER -> out.writeDouble(value.number());
            case BOOLEAN -> out.writeBoolean(value.flag());
        }
    }

    private static Value readValue(DataInputStream in) throws IOException {
        int tag = in.readUnsignedByte();
        Kind[] kinds = Kind.values();
        if (tag >= kinds.length) {
            throw new IOException("unknown stored value kind " + tag);
        }
        return switch (kinds[tag]) {
            case STRING -> Value.of(readString(in));
            case JSON -> Value.json(readString(in));
            case NUMBER -> Value.of(in.readDouble());
            case BOOLEAN -> Value.of(in.readBoolean());
        };
    }

    /**
     * Strings are length-prefixed UTF-8 rather than {@code writeUTF}, whose two-byte length caps a value
     * at 64 KB — small for a serialized scene, which is one of the things this exists to hold.
     */
    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("negative string length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

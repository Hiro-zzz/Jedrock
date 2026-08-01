package com.jedrock.core.world;

import com.jedrock.core.inventory.Container;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Jedrock's own compact level file — reads and writes a whole world (its {@link LevelData} header plus
 * every allocated {@link BlockStorage} section) as a single stream. Deliberately not Anvil-compatible:
 * the on-disk shape mirrors the in-memory {@code short}-per-block model, so a save is close to a raw
 * dump and a load is close to a raw fill.
 *
 * <p><b>Layout.</b> A fixed, <em>uncompressed</em> header (so metadata is readable without inflating
 * the world — useful once paging lands), then the body in one DEFLATE stream:
 *
 * <pre>
 *   "JDWL"                     magic (4 bytes)
 *   int    formatVersion
 *   long   seed
 *   int    boundsChunksX, boundsChunksZ
 *   byte   generated (0/1)
 *   double spawnX, spawnY, spawnZ
 *   float  spawnYaw, spawnPitch
 *   byte   dimensionId             (v5+: 0 = overworld, -1 = nether)
 *   === DEFLATE stream from here ===
 *   int    sectionCount
 *   repeat sectionCount:
 *     int   chunkX, chunkZ
 *     byte  sectionY (0..15)
 *     short[4096] blocks (big-endian; 8192 bytes)
 *   int    biomeChunkCount
 *   repeat biomeChunkCount:
 *     int   chunkX, chunkZ
 *     byte[256] biome ids (indexed (z&lt;&lt;4)|x)
 *   int    containerCount           (v3+: chest contents)
 *   repeat containerCount:
 *     long  position (packed x,y,z)
 *     byte  slotCount
 *     repeat slotCount: short state (id&lt;&lt;4|meta), byte count,
 *                       utf customItemKey ("" = ordinary)   (v4+)
 *                       utf customStackData ("" = none)     (v6+)
 * </pre>
 *
 * <p>Only non-null sections are stored, so an all-air world costs one {@code sectionCount = 0}. DEFLATE
 * collapses the vast uniform stone/air runs, so the file is a small fraction of the sections' raw size.
 * Saves are atomic: the bytes go to a sibling {@code .tmp} and are moved into place, so a crash
 * mid-write can't corrupt an existing world.
 */
public final class LevelIO {

    /** Current on-disk format version; bump on any incompatible layout change. (v2 added the biome map,
     *  v3 the chest container contents, v4 the custom-item key each stack carries, v5 the dimension,
     *  v6 the per-stack data a single stack carries on top of that key.) */
    public static final int FORMAT_VERSION = 6;

    private static final byte[] MAGIC = {'J', 'D', 'W', 'L'};
    private static final int SECTION_SHORTS = 4096;
    private static final int SECTION_BYTES = SECTION_SHORTS * 2;
    private static final int BIOME_BYTES = 256;

    private LevelIO() {}

    /**
     * Write {@code meta} and every allocated section of {@code storage} to {@code file}, atomically.
     * Creates the parent directory if needed. A concurrent edit during the save is a benign torn read
     * of that one section (same tolerance as {@link BlockStorage#getId}).
     */
    public static void save(Path file, LevelData meta, BlockStorage storage, BiomeStorage biomes,
                            Map<Long, Container> containers) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");

        List<BlockStorage.SectionEntry> sections = storage.snapshotSections();
        List<BiomeStorage.ChunkEntry> biomeChunks = biomes.snapshot();
        try (OutputStream fileOut = Files.newOutputStream(tmp)) {
            // Header: uncompressed, directly on the file stream.
            DataOutputStream header = new DataOutputStream(fileOut);
            header.write(MAGIC);
            header.writeInt(meta.formatVersion());
            header.writeLong(meta.seed());
            header.writeInt(meta.boundsChunksX());
            header.writeInt(meta.boundsChunksZ());
            header.writeBoolean(meta.generated());
            header.writeDouble(meta.spawnX());
            header.writeDouble(meta.spawnY());
            header.writeDouble(meta.spawnZ());
            header.writeFloat(meta.spawnYaw());
            header.writeFloat(meta.spawnPitch());
            header.writeByte(meta.dimensionId()); // v5+
            header.flush();

            // Body: one DEFLATE stream over the section list.
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try (DataOutputStream body = new DataOutputStream(new DeflaterOutputStream(fileOut, deflater, 8192))) {
                body.writeInt(sections.size());
                byte[] buf = new byte[SECTION_BYTES];
                short[] scratch = new short[SECTION_SHORTS]; // one reused expansion buffer, so a compact
                for (BlockStorage.SectionEntry e : sections) {  // section is never held expanded en masse
                    body.writeInt(e.chunkX());
                    body.writeInt(e.chunkZ());
                    body.writeByte(e.sectionY());
                    e.expandInto(scratch); // materialize the section (compact or full) to 4096 cells
                    for (int i = 0, j = 0; i < SECTION_SHORTS; i++) {
                        short v = scratch[i];
                        buf[j++] = (byte) (v >>> 8);
                        buf[j++] = (byte) v;
                    }
                    body.write(buf, 0, SECTION_BYTES);
                }

                body.writeInt(biomeChunks.size());
                for (BiomeStorage.ChunkEntry e : biomeChunks) {
                    body.writeInt(e.chunkX());
                    body.writeInt(e.chunkZ());
                    body.write(e.data(), 0, BIOME_BYTES);
                }

                // Containers (chests): only non-empty ones — an empty chest is recreated on open.
                List<Map.Entry<Long, Container>> chests = new ArrayList<>();
                for (Map.Entry<Long, Container> e : containers.entrySet()) {
                    if (!e.getValue().isAllEmpty()) {
                        chests.add(e);
                    }
                }
                body.writeInt(chests.size());
                for (Map.Entry<Long, Container> e : chests) {
                    Container c = e.getValue();
                    body.writeLong(e.getKey());
                    body.writeByte(c.size());
                    for (int i = 0; i < c.size(); i++) {
                        body.writeShort(c.stateAt(i));
                        body.writeByte(c.countAt(i));
                        // v4: the custom-item key, or "" for an ordinary stack. The KEY, not a definition —
                        // this file is read long before any plugin exists to define one.
                        String key = c.customKeyAt(i);
                        body.writeUTF(key == null ? "" : key);
                        // v6: this one stack's own data, or "" for none. Opaque here by design — it is
                        // whatever the script that wrote it decided, and this file only has to give it back.
                        String data = c.customDataAt(i);
                        body.writeUTF(data == null ? "" : data);
                    }
                }
            } finally {
                deflater.end();
            }
        }

        moveIntoPlace(tmp, file);
    }

    /**
     * Read only the header of a level file — what kind of world it is, its seed, its extent and its
     * spawn — without inflating a single section. This is what the uncompressed header was for: the
     * world registry reads it for every world folder at boot to decide what to construct, and paying
     * a full load per world just to learn a dimension byte would defeat the point.
     */
    public static LevelData readHeader(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file)) {
            return readHeader(new DataInputStream(raw));
        }
    }

    /** Read the header off an already-open stream, leaving the body's DEFLATE stream untouched. */
    private static LevelData readHeader(DataInputStream header) throws IOException {
        byte[] magic = new byte[4];
        header.readFully(magic);
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
            throw new IOException("Not a Jedrock level file (bad magic)");
        }
        int version = header.readInt();
        // Load every older layout as well as the current one, so an existing world upgrades in place:
        // v2 has no chests, v3 has chests but no custom-item keys, v4 no dimension. It loads fine,
        // then the next save rewrites it at {@link #FORMAT_VERSION}, which is what save always writes.
        if (version < 2 || version > FORMAT_VERSION) {
            throw new IOException("Unsupported level format version " + version
                    + " (expected 2.." + FORMAT_VERSION + ")");
        }
        long seed = header.readLong();
        int boundsX = header.readInt();
        int boundsZ = header.readInt();
        boolean generated = header.readBoolean();
        double spawnX = header.readDouble(), spawnY = header.readDouble(), spawnZ = header.readDouble();
        float yaw = header.readFloat(), pitch = header.readFloat();
        // Every world written before v5 was an overworld — it was the only kind there was.
        int dimensionId = version < 5 ? 0 : header.readByte();
        return new LevelData(version, seed, boundsX, boundsZ, generated,
                spawnX, spawnY, spawnZ, yaw, pitch, dimensionId);
    }

    /**
     * Read a level file, filling {@code storage} with its sections, and return the header metadata.
     * Intended for single-threaded startup (no other thread touches {@code storage} yet).
     *
     * @throws IOException if the file is missing, truncated, or not a Jedrock level ({@link #FORMAT_VERSION}
     *                     mismatch included).
     */
    public static LevelData load(Path file, BlockStorage storage, BiomeStorage biomes,
                                 Map<Long, Container> containers) throws IOException {
        try (InputStream raw = Files.newInputStream(file)) {
            // Header: read straight off the raw stream (DataInputStream does no read-ahead, so the
            // body's DEFLATE stream that follows is left intact for the inflater below).
            DataInputStream header = new DataInputStream(raw);
            LevelData meta = readHeader(header);
            int version = meta.formatVersion();

            // Body: inflate the section list off the same underlying stream.
            Inflater inflater = new Inflater();
            try (DataInputStream body = new DataInputStream(new InflaterInputStream(raw, inflater, 8192))) {
                int sectionCount = body.readInt();
                if (sectionCount < 0) {
                    throw new IOException("Corrupt level: negative section count " + sectionCount);
                }
                byte[] buf = new byte[SECTION_BYTES];
                // One scratch buffer for every section: putSection compresses out of it and never keeps it,
                // so a load no longer allocates an 8 KB array per section on the way in.
                short[] scratch = new short[SECTION_SHORTS];
                for (int s = 0; s < sectionCount; s++) {
                    int chunkX = body.readInt();
                    int chunkZ = body.readInt();
                    int sectionY = body.readUnsignedByte();
                    body.readFully(buf);
                    for (int i = 0, j = 0; i < SECTION_SHORTS; i++) {
                        scratch[i] = (short) (((buf[j++] & 0xFF) << 8) | (buf[j++] & 0xFF));
                    }
                    storage.putSection(chunkX, sectionY, chunkZ, scratch);
                }

                int biomeChunkCount = body.readInt();
                if (biomeChunkCount < 0) {
                    throw new IOException("Corrupt level: negative biome chunk count " + biomeChunkCount);
                }
                for (int c = 0; c < biomeChunkCount; c++) {
                    int chunkX = body.readInt();
                    int chunkZ = body.readInt();
                    byte[] data = new byte[BIOME_BYTES];
                    body.readFully(data);
                    biomes.putChunk(chunkX, chunkZ, data);
                }

                if (version < 3) {
                    return meta; // v2 has no container section — nothing more to read
                }
                int containerCount = body.readInt();
                if (containerCount < 0) {
                    throw new IOException("Corrupt level: negative container count " + containerCount);
                }
                for (int c = 0; c < containerCount; c++) {
                    long pos = body.readLong();
                    int slots = body.readUnsignedByte();
                    Container container = new Container(slots);
                    for (int i = 0; i < slots; i++) {
                        int state = body.readUnsignedShort();
                        int count = body.readUnsignedByte();
                        // v3 chests predate custom items, so every stack in one is ordinary; v4 and v5
                        // predate per-stack data, so every stack in one is a plain instance of its item.
                        String key = version < 4 ? "" : body.readUTF();
                        String data = version < 6 ? "" : body.readUTF();
                        container.set(i, state, count, key.isEmpty() ? null : key,
                                data.isEmpty() ? null : data);
                    }
                    containers.put(pos, container);
                }
            } finally {
                inflater.end();
            }
            return meta;
        }
    }

    private static void moveIntoPlace(Path tmp, Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

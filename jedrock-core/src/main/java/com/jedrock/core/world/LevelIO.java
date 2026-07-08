package com.jedrock.core.world;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
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
 * </pre>
 *
 * <p>Only non-null sections are stored, so an all-air world costs one {@code sectionCount = 0}. DEFLATE
 * collapses the vast uniform stone/air runs, so the file is a small fraction of the sections' raw size.
 * Saves are atomic: the bytes go to a sibling {@code .tmp} and are moved into place, so a crash
 * mid-write can't corrupt an existing world.
 */
public final class LevelIO {

    /** Current on-disk format version; bump on any incompatible layout change. (v2 added the biome map.) */
    public static final int FORMAT_VERSION = 2;

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
    public static void save(Path file, LevelData meta, BlockStorage storage, BiomeStorage biomes)
            throws IOException {
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
            header.flush();

            // Body: one DEFLATE stream over the section list.
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try (DataOutputStream body = new DataOutputStream(new DeflaterOutputStream(fileOut, deflater, 8192))) {
                body.writeInt(sections.size());
                byte[] buf = new byte[SECTION_BYTES];
                for (BlockStorage.SectionEntry e : sections) {
                    body.writeInt(e.chunkX());
                    body.writeInt(e.chunkZ());
                    body.writeByte(e.sectionY());
                    short[] d = e.data();
                    for (int i = 0, j = 0; i < SECTION_SHORTS; i++) {
                        short v = d[i];
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
            } finally {
                deflater.end();
            }
        }

        moveIntoPlace(tmp, file);
    }

    /**
     * Read a level file, filling {@code storage} with its sections, and return the header metadata.
     * Intended for single-threaded startup (no other thread touches {@code storage} yet).
     *
     * @throws IOException if the file is missing, truncated, or not a Jedrock level ({@link #FORMAT_VERSION}
     *                     mismatch included).
     */
    public static LevelData load(Path file, BlockStorage storage, BiomeStorage biomes) throws IOException {
        try (InputStream raw = Files.newInputStream(file)) {
            // Header: read straight off the raw stream (DataInputStream does no read-ahead, so the
            // body's DEFLATE stream that follows is left intact for the inflater below).
            DataInputStream header = new DataInputStream(raw);
            byte[] magic = new byte[4];
            header.readFully(magic);
            if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
                throw new IOException("Not a Jedrock level file (bad magic)");
            }
            int version = header.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported level format version " + version
                        + " (expected " + FORMAT_VERSION + ")");
            }
            LevelData meta = new LevelData(
                    version,
                    header.readLong(),
                    header.readInt(),
                    header.readInt(),
                    header.readBoolean(),
                    header.readDouble(), header.readDouble(), header.readDouble(),
                    header.readFloat(), header.readFloat());

            // Body: inflate the section list off the same underlying stream.
            Inflater inflater = new Inflater();
            try (DataInputStream body = new DataInputStream(new InflaterInputStream(raw, inflater, 8192))) {
                int sectionCount = body.readInt();
                if (sectionCount < 0) {
                    throw new IOException("Corrupt level: negative section count " + sectionCount);
                }
                byte[] buf = new byte[SECTION_BYTES];
                for (int s = 0; s < sectionCount; s++) {
                    int chunkX = body.readInt();
                    int chunkZ = body.readInt();
                    int sectionY = body.readUnsignedByte();
                    body.readFully(buf);
                    short[] d = new short[SECTION_SHORTS];
                    for (int i = 0, j = 0; i < SECTION_SHORTS; i++) {
                        d[i] = (short) (((buf[j++] & 0xFF) << 8) | (buf[j++] & 0xFF));
                    }
                    storage.putSection(chunkX, sectionY, chunkZ, d);
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

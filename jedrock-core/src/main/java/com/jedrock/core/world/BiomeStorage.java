package com.jedrock.core.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-column biome ids for a baked world — one byte per {@code (x, z)}, grouped by chunk into
 * {@code byte[256]} arrays indexed {@code (z<<4)|x}. The 2D companion to {@link BlockStorage}: a
 * missing chunk reads as {@link #DEFAULT} (plains), so an all-plains or out-of-bounds column costs
 * nothing. Populated by the bake (or a load); read on the chunk-serialization path.
 */
public final class BiomeStorage {

    /** Biome id for a column with no stored data. */
    public static final byte DEFAULT = 1; // plains

    /** columnKey(chunkX, chunkZ) → biome id per column, or null (all {@link #DEFAULT}). */
    private final ConcurrentHashMap<Long, byte[]> chunks = new ConcurrentHashMap<>();

    /** Biome id (0..255) at a column. */
    public int getBiome(int x, int z) {
        byte[] c = chunks.get(key(x >> 4, z >> 4));
        return (c == null ? DEFAULT : c[index(x, z)]) & 0xFF;
    }

    /** Copy a chunk's 16×16 biome map into {@code out} (length 256), or fill {@link #DEFAULT} if absent. */
    public void fill(int chunkX, int chunkZ, byte[] out) {
        byte[] c = chunks.get(key(chunkX, chunkZ));
        if (c == null) {
            Arrays.fill(out, 0, 256, DEFAULT);
        } else {
            System.arraycopy(c, 0, out, 0, 256);
        }
    }

    /**
     * Install a chunk's biome map. For single-threaded bulk populate (bake / load); {@code data} must
     * be length 256 (indexed {@code (z<<4)|x}) and is adopted, not copied.
     */
    public void putChunk(int chunkX, int chunkZ, byte[] data) {
        chunks.put(key(chunkX, chunkZ), data);
    }

    /** One stored chunk's biome map. {@code data} is shared, not copied — hold only for a save. */
    public record ChunkEntry(int chunkX, int chunkZ, byte[] data) {}

    /** Snapshot every stored biome chunk for saving. */
    public List<ChunkEntry> snapshot() {
        List<ChunkEntry> out = new ArrayList<>(chunks.size());
        for (var e : chunks.entrySet()) {
            long k = e.getKey();
            out.add(new ChunkEntry((int) (k >> 32), (int) k, e.getValue()));
        }
        return out;
    }

    /** Number of chunks with stored biome data — for logging and tests. */
    public int loadedChunks() {
        return chunks.size();
    }

    // Index inside a chunk's 16×16 biome map: z outer, x inner (matches World.fillBiomes).
    private static int index(int x, int z) {
        return ((z & 15) << 4) | (x & 15);
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}

package com.jedrock.core.world;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The heart of Jedrock's "world as illusion" storage.
 *
 * <p>The world is a flat matrix of primitive block IDs addressed with pure bit operations.
 * Nothing is a real simulation: a block is just a {@code short} id. Memory is spent only
 * where blocks actually exist:
 *
 * <ul>
 *   <li>Blocks are grouped into 16×16×16 sections ({@code short[4096]}).</li>
 *   <li>A section is allocated <b>only</b> on the first non-air write.</li>
 *   <li>A missing column or section reads as {@link #AIR} — the ultimate lazy default.</li>
 * </ul>
 *
 * <p>Height is fixed to 0..255 (16 sections), matching JE 1.12.2.
 *
 * <p>Threading: designed for a single writer (the tick thread). Reads are safe from any
 * thread; concurrent writes into the same column are not synchronised (not needed yet —
 * block edits are not wired to the network in this layer).
 */
public final class BlockStorage {

    /** Block id representing air / "nothing here". */
    public static final short AIR = 0;

    private static final int MIN_Y = 0;
    private static final int MAX_Y = 255;
    private static final int SECTIONS_PER_COLUMN = 16;

    /** columnKey(chunkX, chunkZ) → 16 sections, each a short[4096] or null (all air). */
    private final ConcurrentHashMap<Long, short[][]> columns = new ConcurrentHashMap<>();

    /** @return block id at the given coordinates, or {@link #AIR} if nothing is stored there. */
    public int getId(int x, int y, int z) {
        if (y < MIN_Y || y > MAX_Y) {
            return AIR;
        }
        short[][] column = columns.get(columnKey(x >> 4, z >> 4));
        if (column == null) {
            return AIR;
        }
        short[] section = column[y >> 4];
        if (section == null) {
            return AIR;
        }
        return section[index(x, y, z)] & 0xFFFF;
    }

    /** Store a block id. Writing {@link #AIR} never allocates new storage. */
    public void setId(int x, int y, int z, int id) {
        if (y < MIN_Y || y > MAX_Y) {
            return;
        }

        if (id == AIR) {
            // Only clear if the backing storage already exists; do not allocate for air.
            short[][] column = columns.get(columnKey(x >> 4, z >> 4));
            if (column == null) return;
            short[] section = column[y >> 4];
            if (section == null) return;
            section[index(x, y, z)] = AIR;
            return;
        }

        short[][] column = columns.computeIfAbsent(columnKey(x >> 4, z >> 4),
                k -> new short[SECTIONS_PER_COLUMN][]);
        short[] section = column[y >> 4];
        if (section == null) {
            section = new short[4096];
            column[y >> 4] = section;
        }
        section[index(x, y, z)] = (short) id;
    }

    /** Number of currently allocated sections — useful for tests and introspection. */
    public int loadedSections() {
        int count = 0;
        for (short[][] column : columns.values()) {
            for (short[] section : column) {
                if (section != null) count++;
            }
        }
        return count;
    }

    // Index inside a 16×16×16 section: y is the outer axis, then z, then x.
    private static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    // Pack two full 32-bit chunk coordinates into one long (collision-free over int range).
    private static long columnKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}

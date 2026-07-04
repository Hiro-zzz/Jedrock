package com.jedrock.network.chunk;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which chunks a single connection has been sent and streams the difference as the player
 * moves. Recentering loads the chunks newly in range (nearest ring first, for gentler pop-in) and
 * unloads those that fell out of range — so a client only ever holds a square window around itself.
 *
 * <p>Not thread-safe by design: a connection's inbound movement is processed on a single thread,
 * so its view is only ever touched from there.
 */
public final class ChunkView {

    /** Callback for the edition-specific wire work of (de)serializing a chunk. */
    public interface Sink {
        void load(int chunkX, int chunkZ);
        void unload(int chunkX, int chunkZ);
    }

    private final int radius;
    private final Set<Long> loaded = new HashSet<>();
    private int centerX;
    private int centerZ;
    private boolean initialized;

    public ChunkView(int radius) {
        this.radius = radius;
    }

    /**
     * Recenter the view on a chunk. No-op if the center hasn't changed, so it is cheap to call on
     * every movement packet; the sink only fires for chunks that actually enter or leave the window.
     */
    public void recenter(int chunkX, int chunkZ, Sink sink) {
        if (initialized && chunkX == centerX && chunkZ == centerZ) {
            return;
        }
        this.centerX = chunkX;
        this.centerZ = chunkZ;
        this.initialized = true;

        // Drop chunks that are now outside the window.
        loaded.removeIf(key -> {
            int cx = (int) (key >> 32);
            int cz = key.intValue();
            if (Math.abs(cx - centerX) > radius || Math.abs(cz - centerZ) > radius) {
                sink.unload(cx, cz);
                return true;
            }
            return false;
        });

        // Load newly in-range chunks, ring by ring from the center outward.
        for (int ring = 0; ring <= radius; ring++) {
            for (int cx = centerX - ring; cx <= centerX + ring; cx++) {
                for (int cz = centerZ - ring; cz <= centerZ + ring; cz++) {
                    if (Math.max(Math.abs(cx - centerX), Math.abs(cz - centerZ)) != ring) {
                        continue; // only the current ring's perimeter
                    }
                    if (loaded.add(key(cx, cz))) {
                        sink.load(cx, cz);
                    }
                }
            }
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}

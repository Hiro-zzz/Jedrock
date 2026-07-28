package com.jedrock.network.chunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which chunks a single connection has been sent and streams the difference as the player
 * moves. Recentering loads the chunks newly in range (nearest ring first, for gentler pop-in) and
 * unloads those that fell out of range — so a client only ever holds a square window around itself.
 *
 * <p>Recentering (load/unload) only ever runs on the connection's own inbound-movement thread. The
 * loaded set is concurrent so a foreign thread — a block edit relayed from another player — can safely
 * ask {@link #isLoaded} whether to push a targeted chunk refresh (e.g. a placed chest's block-entity).
 */
public final class ChunkView {

    /** Callback for the edition-specific wire work of (de)serializing a chunk. */
    public interface Sink {
        void load(int chunkX, int chunkZ);
        void unload(int chunkX, int chunkZ);
    }

    private final int radius;
    private final Set<Long> loaded = ConcurrentHashMap.newKeySet();
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

        // Load newly in-range chunks, ring by ring from the center outward. Each ring walks its
        // perimeter directly — the two edge columns in full, the columns between them only top and
        // bottom — rather than scanning the whole square and discarding the interior, which visited
        // O(radius³) cells to reach the O(radius²) that exist (969 for the 289 of a radius-8 view).
        // Same order as that scan, so pop-in still arrives nearest-first.
        for (int ring = 0; ring <= radius; ring++) {
            for (int cx = centerX - ring; cx <= centerX + ring; cx++) {
                if (cx == centerX - ring || cx == centerX + ring) {
                    for (int cz = centerZ - ring; cz <= centerZ + ring; cz++) {
                        loadIfNew(cx, cz, sink);
                    }
                } else {
                    loadIfNew(cx, centerZ - ring, sink);
                    loadIfNew(cx, centerZ + ring, sink);
                }
            }
        }
    }

    /**
     * Forget every chunk this view has sent, without telling the client to unload any of them — what a
     * world switch needs. The client either drops its terrain itself (a Java Respawn, a Bedrock
     * ChangeDimension) or is about to be sent the same coordinates again from the new world; in both
     * cases an unload packet per chunk would be wasted bytes, and in the second it would make the world
     * blink. The next {@link #recenter} therefore re-sends the whole window.
     */
    public void forgetAll() {
        loaded.clear();
        initialized = false;
    }

    /** Send one chunk if this view doesn't already hold it. */
    private void loadIfNew(int chunkX, int chunkZ, Sink sink) {
        if (loaded.add(key(chunkX, chunkZ))) {
            sink.load(chunkX, chunkZ);
        }
    }

    /** Whether the chunk {@code (chunkX, chunkZ)} is currently held by this view. Thread-safe. */
    public boolean isLoaded(int chunkX, int chunkZ) {
        return loaded.contains(key(chunkX, chunkZ));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}

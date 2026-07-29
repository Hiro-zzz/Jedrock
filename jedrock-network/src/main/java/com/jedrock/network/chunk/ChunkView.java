package com.jedrock.network.chunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which chunks a single connection has been sent and streams the difference as the player
 * moves. Recentering loads the chunks newly in range (nearest ring first, for gentler pop-in) and
 * unloads those that fell out of range — so a client only ever holds a square window around itself.
 *
 * <p>Recentering is <em>almost</em> always the connection's own inbound-movement thread — but not quite: a
 * world switch recenters too, and that arrives from whichever thread asked for it (the console, an RCON
 * session, a script timer on the game loop, another player's {@code /tpall}) while the player may still be
 * walking. The pass reads the center out of its fields as it walks, so two of them interleaving can walk
 * one set of rings around two different centers and finish claiming a window it never finished sending —
 * terrain the client is simply missing until the player crosses another chunk boundary and the next pass
 * repairs it. Hence the monitor around the pass, and a {@code volatile} center so the no-op check that
 * fronts it stays lock-free.
 *
 * <p>The loaded set is concurrent for the same reason plus one more — a foreign thread (a block edit
 * relayed from another player) can safely ask {@link #isLoaded} whether to push a targeted chunk refresh.
 * Being per-chunk atomic, it is also what keeps the damage above to that one transient window rather than
 * a view that permanently disagrees with the client.
 */
public final class ChunkView {

    /** Callback for the edition-specific wire work of (de)serializing a chunk. */
    public interface Sink {
        void load(int chunkX, int chunkZ);
        void unload(int chunkX, int chunkZ);
    }

    private final int radius;
    private final Set<Long> loaded = ConcurrentHashMap.newKeySet();
    private volatile int centerX;
    private volatile int centerZ;
    private volatile boolean initialized;

    public ChunkView(int radius) {
        this.radius = radius;
    }

    /**
     * Recenter the view on a chunk. No-op if the center hasn't changed, so it is cheap to call on
     * every movement packet; the sink only fires for chunks that actually enter or leave the window.
     */
    public void recenter(int chunkX, int chunkZ, Sink sink) {
        // Lock-free fast path: the overwhelmingly common call is a movement packet that didn't cross a
        // chunk boundary, and three volatile reads on x86 are three plain loads.
        if (initialized && chunkX == centerX && chunkZ == centerZ) {
            return;
        }
        synchronized (this) {
            if (initialized && chunkX == centerX && chunkZ == centerZ) {
                return; // a concurrent recenter (a world switch, a teleport) already landed here
            }
            recenterLocked(chunkX, chunkZ, sink);
        }
    }

    /** The actual load/unload pass; the caller holds this view's monitor. */
    private void recenterLocked(int chunkX, int chunkZ, Sink sink) {
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
    public synchronized void forgetAll() {
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

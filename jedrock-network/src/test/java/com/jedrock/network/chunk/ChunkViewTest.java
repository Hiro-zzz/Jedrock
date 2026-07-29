package com.jedrock.network.chunk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Streaming diff behaviour of {@link ChunkView}. */
class ChunkViewTest {

    /** Records load/unload calls and the set of chunks currently held by the client. */
    private static final class RecordingSink implements ChunkView.Sink {
        final Set<Long> held = new HashSet<>();
        int loads, unloads;

        @Override public void load(int cx, int cz) { held.add(key(cx, cz)); loads++; }
        @Override public void unload(int cx, int cz) { held.remove(key(cx, cz)); unloads++; }

        static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    }

    @Test
    void initialRecenterLoadsTheWholeWindow() {
        ChunkView view = new ChunkView(2);
        RecordingSink sink = new RecordingSink();

        view.recenter(0, 0, sink);

        assertEquals(25, sink.loads, "5x5 window");
        assertEquals(0, sink.unloads);
        assertEquals(25, sink.held.size());
    }

    @Test
    void samCenterIsANoOp() {
        ChunkView view = new ChunkView(2);
        RecordingSink sink = new RecordingSink();
        view.recenter(3, 3, sink);
        int loadsAfterFirst = sink.loads;

        view.recenter(3, 3, sink);

        assertEquals(loadsAfterFirst, sink.loads, "no extra loads");
        assertEquals(0, sink.unloads);
    }

    /**
     * The window is streamed nearest-first — each chunk is at least as far from the centre as the one
     * before it, so pop-in fills outward instead of arriving in an arbitrary order. Pins the perimeter
     * walk that replaced the whole-square scan: same chunks, same order, a third of the iterations.
     */
    @Test
    void loadsRingByRingFromTheCentreOutward() {
        ChunkView view = new ChunkView(3);
        List<int[]> order = new ArrayList<>();
        view.recenter(10, -4, new ChunkView.Sink() {
            @Override public void load(int cx, int cz) { order.add(new int[]{cx, cz}); }
            @Override public void unload(int cx, int cz) { }
        });

        assertEquals(49, order.size(), "7x7 window");
        assertArrayEquals(new int[]{10, -4}, order.get(0), "the centre chunk comes first");
        int previousRing = 0;
        Set<Long> seen = new HashSet<>();
        for (int[] chunk : order) {
            int ring = Math.max(Math.abs(chunk[0] - 10), Math.abs(chunk[1] + 4));
            assertTrue(ring >= previousRing, "ring " + ring + " must not follow ring " + previousRing);
            assertTrue(seen.add(RecordingSink.key(chunk[0], chunk[1])),
                    "chunk " + chunk[0] + "," + chunk[1] + " sent twice");
            previousRing = ring;
        }
    }

    @Test
    void steppingOneChunkStreamsOnlyTheNewEdge() {
        ChunkView view = new ChunkView(2);
        RecordingSink sink = new RecordingSink();
        view.recenter(0, 0, sink);   // holds x,z in [-2,2]
        sink.loads = 0;              // reset counters for the step

        view.recenter(1, 0, sink);   // window shifts to x in [-1,3]

        // One column (5 chunks) leaves at x=-2, one new column (5) enters at x=3.
        assertEquals(5, sink.loads, "new column loaded");
        assertEquals(5, sink.unloads, "old column unloaded");
        assertEquals(25, sink.held.size(), "window size unchanged");
        // The client must now hold exactly x in [-1,3], z in [-2,2].
        for (int x = -1; x <= 3; x++) {
            for (int z = -2; z <= 2; z++) {
                assertTrue(sink.held.contains(RecordingSink.key(x, z)), "holds " + x + "," + z);
            }
        }
    }

    /**
     * Recentering is not single-threaded after all: a world switch (a {@code /world tp} from the console or
     * RCON, a script timer on the game loop, another player's {@code /tpall}) recenters from its own thread
     * while the player may still be walking on theirs.
     *
     * <p>A guard, not a reproduction — it is honest to say so. The per-chunk atomicity of the loaded set
     * means the next clean recenter repairs whatever an interleaving left behind, so this passed against
     * the unsynchronized version too; what it pins is the property that survives being hammered from two
     * threads, so a future change that makes the damage permanent has something to fail.
     */
    @Test
    void concurrentRecenteringLeavesTheViewConsistent() throws InterruptedException {
        for (int round = 0; round < 50; round++) {
            ChunkView view = new ChunkView(2);
            // The sink is shared across both threads, so it has to be safe to call from either.
            ChunkView.Sink sink = new ChunkView.Sink() {
                @Override public void load(int cx, int cz) {}
                @Override public void unload(int cx, int cz) {}
            };
            view.recenter(0, 0, sink);

            CountDownLatch go = new CountDownLatch(1);
            Thread walking = new Thread(() -> {
                awaitQuietly(go);
                for (int i = 1; i <= 20; i++) {
                    view.recenter(i, 0, sink);
                }
            });
            Thread travelling = new Thread(() -> {
                awaitQuietly(go);
                view.forgetAll();
                view.recenter(100, 100, sink);
            });
            walking.start();
            travelling.start();
            go.countDown();
            walking.join();
            travelling.join();

            // Settle on a center neither thread used, then the held set must be exactly that window.
            RecordingSink after = new RecordingSink();
            view.recenter(50, -50, after);
            for (int x = 48; x <= 52; x++) {
                for (int z = -52; z <= -48; z++) {
                    assertTrue(view.isLoaded(x, z), "round " + round + ": holds " + x + "," + z);
                }
            }
            assertEquals(25, loadedCount(view), "round " + round + ": exactly one 5x5 window is held");
        }
    }

    /** How many chunks the view currently holds, probed through its public {@link ChunkView#isLoaded}. */
    private static int loadedCount(ChunkView view) {
        int held = 0;
        for (int x = -60; x <= 110; x++) {
            for (int z = -60; z <= 110; z++) {
                if (view.isLoaded(x, z)) {
                    held++;
                }
            }
        }
        return held;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

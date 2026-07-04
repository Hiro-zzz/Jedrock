package com.jedrock.network.chunk;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

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
}

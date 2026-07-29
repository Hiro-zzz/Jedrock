package com.jedrock.gameloop;

import com.jedrock.utils.TickUtil;

/**
 * Rolling health metrics for the game loop: actual TPS and mean / peak milliseconds-per-tick over a
 * recent window, plus total ticks and uptime.
 *
 * <p>{@link #record} is only ever called from the single loop thread. The getters publish their
 * results through {@code volatile} fields, so a console command (or any other thread) can read a
 * consistent snapshot cheaply and lock-free.
 */
public final class TickMetrics {

    /** Window of recent ticks used to smooth TPS/MSPT — ~5 seconds at the target rate. */
    private static final int WINDOW = 100;

    private final long[] startNanos = new long[WINDOW];
    private final long[] durationNanos = new long[WINDOW];
    private int count;
    private int head;
    /** Running sum of {@link #durationNanos} over the live window — maintained, not re-summed. */
    private long sumDurationNanos;
    private long totalTicks;
    private final long startMillis = System.currentTimeMillis();

    private volatile double tps = TickUtil.TPS;
    private volatile double mspt;
    private volatile double peakMspt;

    /** Record one completed tick (loop thread only). {@code start}/{@code duration} are in nanoseconds. */
    void record(long tickStartNanos, long tickDurationNanos) {
        // Slide the window rather than re-walk it: drop the entry about to be overwritten, add the new one.
        if (count == WINDOW) {
            sumDurationNanos -= durationNanos[head];
        }
        startNanos[head] = tickStartNanos;
        durationNanos[head] = tickDurationNanos;
        sumDurationNanos += tickDurationNanos;
        head = (head + 1) % WINDOW;
        if (count < WINDOW) {
            count++;
        }
        totalTicks++;

        // nanoTime is monotonic, so the extremes need no scan: the newest start is the one just recorded,
        // and the oldest is the entry head now points at (the next to be evicted) once the window is full.
        long oldest = count == WINDOW ? startNanos[head] : startNanos[0];
        long newest = tickStartNanos;
        mspt = (sumDurationNanos / (double) count) / 1_000_000.0;

        double thisMs = tickDurationNanos / 1_000_000.0;
        if (thisMs > peakMspt) {
            peakMspt = thisMs;
        }

        // Actual TPS = ticks elapsed over the wall time they spanned, capped at the target (a loop
        // that is keeping up sleeps between ticks, so it can never exceed the target rate).
        tps = (count >= 2 && newest > oldest)
                ? Math.min(TickUtil.TPS, (count - 1) * 1_000_000_000.0 / (newest - oldest))
                : TickUtil.TPS;
    }

    public double tps() {
        return tps;
    }

    public double mspt() {
        return mspt;
    }

    /** All-time worst single tick, in milliseconds — useful for spotting lag spikes. */
    public double peakMspt() {
        return peakMspt;
    }

    public long totalTicks() {
        return totalTicks;
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - startMillis;
    }

    /** Clear the all-time peak (e.g. after inspecting a spike). */
    public void resetPeak() {
        peakMspt = mspt;
    }
}

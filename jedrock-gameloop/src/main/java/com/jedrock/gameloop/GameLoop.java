package com.jedrock.gameloop;

import com.jedrock.utils.JLogger;
import com.jedrock.utils.TickUtil;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight fixed-rate game loop.
 *
 * Characteristics:
 * - Single dedicated thread
 * - Aims for 20 TPS with best-effort compensation for drift
 * - Extremely low allocation
 * - Tickables are notified in registration order
 *
 * Do NOT put heavy work directly in tick(). Offload to worker threads.
 */
public final class GameLoop implements Runnable {

    private static final JLogger LOGGER = JLogger.getLogger(GameLoop.class);

    /**
     * Copy-on-write array of tickables. Registration is rare (startup) and rebuilds the array under
     * the lock; the 20 TPS loop reads the {@code volatile} reference and iterates by index, so a tick
     * allocates no iterator. Same visibility guarantees as {@link java.util.concurrent.CopyOnWriteArrayList},
     * without its per-iteration allocation.
     */
    private volatile Tickable[] tickables = new Tickable[0];
    private final AtomicLong currentTick = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread thread;
    private long targetTickNanos = TickUtil.MILLIS_PER_TICK * 1_000_000L;

    public synchronized void addTickable(Tickable tickable) {
        Tickable[] current = tickables;
        Tickable[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = tickable;
        tickables = next;
    }

    public synchronized void removeTickable(Tickable tickable) {
        Tickable[] current = tickables;
        int idx = -1;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == tickable) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return;
        }
        Tickable[] next = new Tickable[current.length - 1];
        System.arraycopy(current, 0, next, 0, idx);
        System.arraycopy(current, idx + 1, next, idx, current.length - idx - 1);
        tickables = next;
    }

    public long getCurrentTick() {
        return currentTick.get();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this, "Jedrock-GameLoop");
            thread.setPriority(Thread.MAX_PRIORITY - 1); // high but not absolute
            thread.start();
            LOGGER.info("GameLoop started (target " + TickUtil.TPS + " TPS)");
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException ignored) {}
        }
        LOGGER.info("GameLoop stopped at tick " + currentTick.get());
    }

    @Override
    public void run() {
        long nextTickTime = System.nanoTime();

        while (running.get()) {
            long now = System.nanoTime();
            if (now >= nextTickTime) {
                long tick = currentTick.incrementAndGet();

                // Tick everything. Read the volatile array once and iterate by index — no iterator.
                Tickable[] snapshot = tickables;
                for (int i = 0; i < snapshot.length; i++) {
                    try {
                        snapshot[i].tick(tick);
                    } catch (Throwable ex) {
                        LOGGER.error("Error during tick " + tick, ex);
                    }
                }

                // Schedule next tick, attempting to correct drift
                nextTickTime += targetTickNanos;

                // If we are behind by more than 1 full tick, catch up (simple)
                if (nextTickTime < now) {
                    nextTickTime = now + targetTickNanos;
                }
            } else {
                // Sleep / park until next tick
                long sleepNanos = nextTickTime - now;
                if (sleepNanos > 2_000_000) { // > 2ms
                    try {
                        Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                    } catch (InterruptedException ignored) {}
                } else {
                    // busy wait for sub-millisecond precision (very short)
                    Thread.onSpinWait();
                }
            }
        }
    }

    /**
     * Allows overriding tick rate (mainly for testing).
     */
    public void setTickRate(int tps) {
        if (tps <= 0) throw new IllegalArgumentException("tps must be positive: " + tps);
        this.targetTickNanos = 1_000_000_000L / tps;
    }
}

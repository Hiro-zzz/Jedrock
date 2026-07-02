package com.jedrock.gameloop;

import com.jedrock.utils.JLogger;
import com.jedrock.utils.TickUtil;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    private final List<Tickable> tickables = new CopyOnWriteArrayList<>();
    private final AtomicLong currentTick = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread thread;
    private long targetTickNanos = TickUtil.MILLIS_PER_TICK * 1_000_000L;

    public void addTickable(Tickable tickable) {
        tickables.add(tickable);
    }

    public void removeTickable(Tickable tickable) {
        tickables.remove(tickable);
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

                // Tick everything
                for (Tickable t : tickables) {
                    try {
                        t.tick(tick);
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

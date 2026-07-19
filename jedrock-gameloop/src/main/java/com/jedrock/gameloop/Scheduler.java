package com.jedrock.gameloop;

import com.jedrock.utils.JLogger;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extremely simple task scheduler tied to the GameLoop.
 *
 * Tasks are executed on the tick thread.
 * For heavy work, schedule a Runnable that dispatches to another thread.
 *
 * Scheduler is itself a Tickable.
 */
public final class Scheduler implements Tickable {

    private static final JLogger LOGGER = JLogger.getLogger(Scheduler.class);

    /**
     * A handle to a scheduled task. {@link #cancel()} stops it: a pending one-shot never fires and a
     * repeating task stops re-arming. Safe to call from any thread and idempotent. Handed back from every
     * {@code runTask*} so a caller (notably the scripting layer, which must drop a plugin's timers on
     * reload) can undo a schedule it no longer wants.
     */
    public interface Task {
        void cancel();

        boolean isCancelled();
    }

    /**
     * The scheduler's own task record. {@code executeTick} is mutated in place when a repeating task
     * re-arms (loop thread only), so the {@link Task} handle a caller holds stays valid across every
     * repeat; {@code cancelled} is atomic because {@link #cancel()} can come from any thread.
     */
    private static final class ScheduledTask implements Task {
        private long executeTick;
        private final Runnable task;
        private final boolean repeating;
        private final long periodTicks;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        ScheduledTask(long executeTick, Runnable task, boolean repeating, long periodTicks) {
            this.executeTick = executeTick;
            this.task = task;
            this.repeating = repeating;
            this.periodTicks = periodTicks;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private final ConcurrentLinkedQueue<ScheduledTask> tasks = new ConcurrentLinkedQueue<>();
    // Written by the loop thread in tick(); read by any thread that schedules a task, so publish it
    // volatile — otherwise a runTask* call off the loop thread could compute its executeTick from a
    // stale tick and fire a tick early or late.
    private volatile long lastTick = 0;

    @Override
    public void tick(long currentTick) {
        this.lastTick = currentTick;
        Iterator<ScheduledTask> it = tasks.iterator();
        while (it.hasNext()) {
            ScheduledTask st = it.next();
            if (st.isCancelled()) {
                it.remove();
                continue;
            }
            if (currentTick >= st.executeTick) {
                try {
                    st.task.run();
                } catch (Throwable t) {
                    LOGGER.error("Scheduled task threw at tick " + currentTick, t);
                }
                // A task cancelled by its own body (or concurrently) must not re-arm.
                if (st.repeating && !st.isCancelled()) {
                    st.executeTick = currentTick + st.periodTicks; // re-arm in place; handle stays valid
                } else {
                    it.remove();
                }
            }
        }
    }

    public Task runTask(Runnable task) {
        ScheduledTask st = new ScheduledTask(lastTick, task, false, 0);
        tasks.add(st);
        return st;
    }

    public Task runTaskLater(Runnable task, long delayTicks) {
        ScheduledTask st = new ScheduledTask(lastTick + Math.max(1, delayTicks), task, false, 0);
        tasks.add(st);
        return st;
    }

    public Task runTaskTimer(Runnable task, long initialDelay, long periodTicks) {
        ScheduledTask st = new ScheduledTask(lastTick + Math.max(0, initialDelay), task, true, Math.max(1, periodTicks));
        tasks.add(st);
        return st;
    }
}


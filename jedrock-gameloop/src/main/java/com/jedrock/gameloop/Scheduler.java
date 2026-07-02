package com.jedrock.gameloop;

import com.jedrock.utils.JLogger;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    private record ScheduledTask(long executeTick, Runnable task, boolean repeating, long periodTicks) {}

    private final ConcurrentLinkedQueue<ScheduledTask> tasks = new ConcurrentLinkedQueue<>();
    private long lastTick = 0;

    @Override
    public void tick(long currentTick) {
        this.lastTick = currentTick;
        Iterator<ScheduledTask> it = tasks.iterator();
        while (it.hasNext()) {
            ScheduledTask st = it.next();
            if (currentTick >= st.executeTick) {
                try {
                    st.task.run();
                } catch (Throwable t) {
                    LOGGER.error("Scheduled task threw at tick " + currentTick, t);
                }
                if (st.repeating) {
                    tasks.add(new ScheduledTask(currentTick + st.periodTicks, st.task, true, st.periodTicks));
                }
                it.remove();
            }
        }
    }

    public void runTask(Runnable task) {
        tasks.add(new ScheduledTask(lastTick, task, false, 0));
    }

    public void runTaskLater(Runnable task, long delayTicks) {
        long exec = lastTick + Math.max(1, delayTicks);
        tasks.add(new ScheduledTask(exec, task, false, 0));
    }

    public void runTaskTimer(Runnable task, long initialDelay, long periodTicks) {
        long exec = lastTick + Math.max(0, initialDelay);
        tasks.add(new ScheduledTask(exec, task, true, Math.max(1, periodTicks)));
    }
}


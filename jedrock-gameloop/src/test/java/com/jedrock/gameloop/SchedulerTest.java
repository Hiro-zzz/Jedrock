package com.jedrock.gameloop;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the tick scheduler: delayed one-shots, repeating timers, and — the reason the {@link
 * Scheduler.Task} handle exists — cancellation from a caller and from a task's own body.
 */
class SchedulerTest {

    /** Advance the scheduler over ticks {@code [from, from + count]} inclusive. */
    private static void run(Scheduler scheduler, long from, long count) {
        for (long t = from; t <= from + count; t++) {
            scheduler.tick(t);
        }
    }

    @Test
    void runTaskLaterFiresOnceAtTheDelay() {
        Scheduler scheduler = new Scheduler();
        scheduler.tick(0); // establish lastTick == 0
        AtomicInteger runs = new AtomicInteger();
        scheduler.runTaskLater(runs::incrementAndGet, 5);

        run(scheduler, 1, 3);            // ticks 1..4: not yet
        assertEquals(0, runs.get());
        run(scheduler, 5, 10);           // ticks 5..15: fires once, never again
        assertEquals(1, runs.get());
    }

    @Test
    void runTaskTimerRepeatsEveryPeriod() {
        Scheduler scheduler = new Scheduler();
        scheduler.tick(0);
        AtomicInteger runs = new AtomicInteger();
        scheduler.runTaskTimer(runs::incrementAndGet, 2, 2);

        run(scheduler, 1, 8); // ticks 2,4,6,8 -> 4 runs
        assertEquals(4, runs.get());
    }

    @Test
    void cancellingAPendingOneShotStopsItFiring() {
        Scheduler scheduler = new Scheduler();
        scheduler.tick(0);
        AtomicInteger runs = new AtomicInteger();
        Scheduler.Task task = scheduler.runTaskLater(runs::incrementAndGet, 5);

        assertFalse(task.isCancelled());
        task.cancel();
        assertTrue(task.isCancelled());

        run(scheduler, 1, 20);
        assertEquals(0, runs.get(), "a cancelled one-shot never runs");
    }

    @Test
    void cancellingATimerStopsFurtherRuns() {
        Scheduler scheduler = new Scheduler();
        scheduler.tick(0);
        AtomicInteger runs = new AtomicInteger();
        Scheduler.Task task = scheduler.runTaskTimer(runs::incrementAndGet, 2, 2);

        run(scheduler, 1, 4); // ticks 2,4 -> 2 runs
        assertEquals(2, runs.get());
        task.cancel();
        run(scheduler, 5, 20); // no more
        assertEquals(2, runs.get(), "a cancelled timer stops re-arming");
    }

    @Test
    void aTimerCanCancelItselfFromInsideItsBody() {
        Scheduler scheduler = new Scheduler();
        scheduler.tick(0);
        AtomicInteger runs = new AtomicInteger();
        Scheduler.Task[] holder = new Scheduler.Task[1];
        holder[0] = scheduler.runTaskTimer(() -> {
            if (runs.incrementAndGet() == 3) {
                holder[0].cancel();
            }
        }, 1, 1);

        run(scheduler, 1, 20);
        assertEquals(3, runs.get(), "the timer stopped itself on its third run");
    }

    @Test
    void aThrowingTaskDoesNotStopOthers() {
        Scheduler scheduler = new Scheduler();
        scheduler.tick(0);
        AtomicInteger ok = new AtomicInteger();
        scheduler.runTaskLater(() -> { throw new RuntimeException("boom"); }, 1);
        scheduler.runTaskLater(ok::incrementAndGet, 1);

        run(scheduler, 1, 3);
        assertEquals(1, ok.get(), "the second task still ran after the first threw");
    }
}

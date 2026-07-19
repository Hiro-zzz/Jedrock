package com.jedrock.core.plugin;

import com.jedrock.gameloop.Scheduler;
import org.mozilla.javascript.Function;

/**
 * The {@code scheduler} object a script sees — a thin, tick-based bridge onto the core {@link Scheduler}.
 * One per plugin, so every task a script starts is tracked against that plugin and cancelled when the
 * plugin is unloaded or hot-reloaded (a repeating timer from an old script version must not keep firing
 * into a torn-down scope). Time is in <b>ticks</b> — the server runs 20 per second.
 *
 * <pre>{@code
 *   scheduler.run(() => console.log('next tick'));            // once, on the next tick
 *   scheduler.runLater(() => giveReward(p), 100);             // once, in 5 seconds
 *   const t = scheduler.runTimer(() => tickBoss(), 20);       // every second, until…
 *   scheduler.runLater(() => t.cancel(), 20 * 60);            // …a minute from now
 * }</pre>
 *
 * The familiar {@code setTimeout} / {@code setInterval} / {@code clearTimeout} / {@code clearInterval}
 * globals are defined (in milliseconds) on top of this — see the prelude in {@link PluginManager}.
 *
 * <p>Callbacks run on the game-loop thread, serialized under the same script lock as event handlers, so a
 * task must stay quick — it holds up ticks and event dispatch while it runs.
 */
public final class ScriptScheduler {

    private final PluginManager manager;
    private final ScriptPlugin plugin;

    ScriptScheduler(PluginManager manager, ScriptPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    /** Run {@code fn} once on the next tick. Returns a handle whose {@code cancel()} un-schedules it. */
    public Scheduler.Task run(Function fn) {
        return track(fn, true, (runnable) -> manager.scheduler().runTask(runnable));
    }

    /** Run {@code fn} once, {@code delayTicks} from now (clamped to at least 1 tick). */
    public Scheduler.Task runLater(Function fn, long delayTicks) {
        return track(fn, true, (runnable) -> manager.scheduler().runTaskLater(runnable, delayTicks));
    }

    /** Run {@code fn} every {@code periodTicks}, first after {@code periodTicks}. Cancel via the handle. */
    public Scheduler.Task runTimer(Function fn, long periodTicks) {
        return runTimer(fn, periodTicks, periodTicks);
    }

    /** Run {@code fn} every {@code periodTicks}, the first run {@code initialDelay} ticks from now. */
    public Scheduler.Task runTimer(Function fn, long periodTicks, long initialDelay) {
        return track(fn, false, (runnable) -> manager.scheduler().runTaskTimer(runnable, initialDelay, periodTicks));
    }

    /**
     * Schedule {@code fn} and remember the resulting task on the plugin so a reload cancels it. The
     * {@code holder} lets the scheduled body find its own handle to drop from the plugin once a one-shot has
     * fired — safe because that removal, {@link #track}'s {@code addTask}, and teardown all run under the
     * script lock, so the handle is visibly published across threads.
     */
    private Scheduler.Task track(Function fn, boolean oneShot, java.util.function.Function<Runnable, Scheduler.Task> schedule) {
        Scheduler.Task[] holder = new Scheduler.Task[1];
        Scheduler.Task task = schedule.apply(() -> manager.runScheduled(plugin, fn, oneShot ? holder[0] : null));
        holder[0] = task;
        plugin.addTask(task);
        return task;
    }
}

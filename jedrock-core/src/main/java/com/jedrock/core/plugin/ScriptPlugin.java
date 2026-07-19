package com.jedrock.core.plugin;

import com.jedrock.api.event.EventBus;
import com.jedrock.core.command.Command;
import com.jedrock.gameloop.Scheduler;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.List;

/**
 * One loaded script — a {@code .js} file, its Rhino scope, and everything it registered while it ran. The
 * manager keeps this so a reload or shutdown can cleanly tear the script down: unregister its event
 * listeners, cancel its scheduled tasks, unregister its commands, and call its {@code onDisable} if defined.
 */
final class ScriptPlugin {

    private final String name;
    private final Scriptable scope;
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    /** Scheduled tasks this script started; cancelled on teardown so timers don't outlive a reload. */
    private final List<Scheduler.Task> tasks = new ArrayList<>();
    /** Commands this script registered; unregistered on teardown so a reload doesn't leave ghosts. */
    private final List<Command> commands = new ArrayList<>();
    private final long lastModified;
    private volatile Function onDisable;

    ScriptPlugin(String name, Scriptable scope, long lastModified) {
        this.name = name;
        this.scope = scope;
        this.lastModified = lastModified;
    }

    String name() {
        return name;
    }

    Scriptable scope() {
        return scope;
    }

    long lastModified() {
        return lastModified;
    }

    /** Remember an event subscription so it can be removed when the script is unloaded. */
    void addSubscription(EventBus.Subscription subscription) {
        subscriptions.add(subscription);
    }

    List<EventBus.Subscription> subscriptions() {
        return subscriptions;
    }

    /** Remember a scheduled task so it can be cancelled when the script is unloaded. */
    void addTask(Scheduler.Task task) {
        tasks.add(task);
    }

    /** Forget a one-shot task that has already fired, so a busy script's list doesn't grow without bound. */
    void removeTask(Scheduler.Task task) {
        tasks.remove(task);
    }

    List<Scheduler.Task> tasks() {
        return tasks;
    }

    /** Remember a registered command so it can be unregistered when the script is unloaded. */
    void addCommand(Command command) {
        commands.add(command);
    }

    List<Command> commands() {
        return commands;
    }

    Function onDisable() {
        return onDisable;
    }

    void setOnDisable(Function onDisable) {
        this.onDisable = onDisable;
    }
}

package com.jedrock.core.plugin;

import com.jedrock.api.event.Event;
import com.jedrock.api.event.EventBus;
import org.mozilla.javascript.Function;

/**
 * The {@code events} object a script sees. One per plugin, so every listener a script registers is tracked
 * against that plugin and torn down with it.
 *
 * <pre>{@code
 *   events.on('PlayerJoin', e => e.getPlayer().sendMessage('{gold}Welcome!'));
 *   events.on('BlockBreak', e => { if (isSpawn(e)) e.setCancelled(true); });
 *
 *   // Custom, script-defined events (plugin-to-plugin): any name that isn't a built-in event.
 *   events.on('shop:buy', e => console.log('bought', e.getData().item));
 *   const r = events.emit('shop:buy', { item: 'sword' });
 *   if (r.isCancelled()) { … }
 * }</pre>
 *
 * For a built-in event the callback gets the real Java event (Rhino wraps it) — call its getters/setters
 * directly. For a custom event it gets a {@link ScriptEvent}: {@code getName()}, {@code getData()},
 * {@code cancel()} / {@code isCancelled()}.
 */
public final class ScriptEvents {

    private final PluginManager manager;
    private final ScriptPlugin plugin;

    ScriptEvents(PluginManager manager, ScriptPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    /**
     * Subscribe to an event by name. A built-in name (see {@link EventTypes}) subscribes to that core event;
     * any other name subscribes to a custom event that scripts fire with {@link #emit}. The callback runs
     * whenever the event is posted; for a cancellable one it may call {@code e.setCancelled(true)}.
     */
    public void on(String eventName, Function handler) {
        if (handler == null) {
            throw new IllegalArgumentException("events.on('" + eventName + "', …) needs a function");
        }
        Class<? extends Event> type = EventTypes.byName(eventName);
        if (type != null) {
            EventBus.Subscription subscription =
                    manager.eventBus().register(type, event -> manager.callHandler(plugin, handler, event));
            plugin.addSubscription(subscription);
        } else {
            CustomEventBus.Registration registration =
                    manager.customEvents().register(eventName, plugin, handler);
            plugin.addCustomListener(registration);
        }
    }

    /** Emit a custom event with no data. */
    public ScriptEvent emit(String eventName) {
        return emit(eventName, null);
    }

    /**
     * Emit a custom event: run every {@code events.on(name, …)} listener with a {@link ScriptEvent} carrying
     * {@code data}, and return that event so the emitter can read back the (mutated) data or a cancel. The
     * name must NOT be a built-in event — the core fires those; {@code emit} is only for script-defined ones.
     */
    public ScriptEvent emit(String eventName, Object data) {
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("events.emit needs a non-empty event name");
        }
        if (EventTypes.byName(eventName) != null) {
            throw new IllegalArgumentException("'" + eventName + "' is a built-in event — the core fires it; "
                    + "emit is only for custom (script-defined) events");
        }
        return manager.customEvents().emit(manager, eventName, data);
    }
}

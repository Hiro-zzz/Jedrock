package com.jedrock.core.plugin;

import com.jedrock.api.event.Event;
import com.jedrock.api.event.EventBus;
import org.mozilla.javascript.Function;

/**
 * The {@code events} object a script sees. One per plugin, so every listener a script registers is tracked
 * against that plugin and torn down with it. The single method scripts call is {@link #on}:
 *
 * <pre>{@code
 *   events.on('PlayerJoin', e => e.getPlayer().sendMessage('{gold}Welcome!'));
 *   events.on('BlockBreak', e => { if (isSpawn(e)) e.setCancelled(true); });
 * }</pre>
 *
 * The event object handed to the callback is the real Java event (Rhino wraps it), so a script calls its
 * getters and setters directly — {@code e.getPlayer()}, {@code e.setCancelled(true)}, {@code e.getMessage()}.
 */
public final class ScriptEvents {

    private final PluginManager manager;
    private final ScriptPlugin plugin;

    ScriptEvents(PluginManager manager, ScriptPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    /**
     * Subscribe to an event by its script name (see {@link EventTypes}). The callback runs whenever the
     * core posts that event; for a cancellable event it may call {@code e.setCancelled(true)} to veto it.
     *
     * @throws IllegalArgumentException if {@code eventName} isn't a known event
     */
    public void on(String eventName, Function handler) {
        Class<? extends Event> type = EventTypes.byName(eventName);
        if (type == null) {
            throw new IllegalArgumentException("Unknown event '" + eventName + "' (see the docs for names)");
        }
        if (handler == null) {
            throw new IllegalArgumentException("events.on('" + eventName + "', …) needs a function");
        }
        EventBus.Subscription subscription =
                manager.eventBus().register(type, event -> manager.callHandler(plugin, handler, event));
        plugin.addSubscription(subscription);
    }
}

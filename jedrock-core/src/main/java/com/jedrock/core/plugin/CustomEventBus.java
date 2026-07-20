package com.jedrock.core.plugin;

import org.mozilla.javascript.Function;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The channel for script-defined custom events — {@code events.emit(name, data)} on one side,
 * {@code events.on(name, fn)} (for a name that isn't a built-in event) on the other. Shared across all
 * plugins, so one script can emit an event another script listens for. Each listener is tracked against its
 * plugin so a reload/unload drops it.
 *
 * <p>Dispatch runs each listener through {@link PluginManager#callHandler} — under the script lock, in the
 * listener's own scope, exceptions swallowed — exactly like a built-in event listener.
 */
final class CustomEventBus {

    /** A handle to remove a registered custom listener. */
    interface Registration {
        void remove();
    }

    private record Entry(ScriptPlugin plugin, Function handler) {}

    /** name → its listeners, in registration order. */
    private final Map<String, List<Entry>> byName = new ConcurrentHashMap<>();

    /** Register a listener for {@code name}; returns a handle that removes just this listener. */
    Registration register(String name, ScriptPlugin plugin, Function handler) {
        List<Entry> list = byName.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>());
        Entry entry = new Entry(plugin, handler);
        list.add(entry);
        return () -> list.remove(entry);
    }

    /**
     * Emit {@code name} with {@code data} to every listener, and return the {@link ScriptEvent} so the
     * emitter can read the (possibly mutated) data or whether a listener cancelled it. A name with no
     * listeners is a harmless no-op.
     */
    ScriptEvent emit(PluginManager manager, String name, Object data) {
        ScriptEvent event = new ScriptEvent(name, data);
        List<Entry> list = byName.get(name);
        if (list != null) {
            for (Entry entry : list) {
                manager.callHandler(entry.plugin(), entry.handler(), event);
            }
        }
        return event;
    }
}

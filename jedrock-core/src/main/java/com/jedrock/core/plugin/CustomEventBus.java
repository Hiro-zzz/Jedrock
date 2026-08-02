package com.jedrock.core.plugin;

import com.jedrock.api.event.EventPriority;
import org.mozilla.javascript.Function;

import java.util.ArrayList;
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
 * listener's own scope, exceptions swallowed — exactly like a built-in event listener. And in the same
 * order: <b>priority applies here too</b>. It would be its own small trap otherwise, because the name is
 * all a script has to go on and nothing about {@code 'shop:buy'} says which side of the built-in/custom
 * line it falls on — an option that silently does nothing on half the names is worse than one that isn't
 * offered at all.
 */
final class CustomEventBus {

    /** A handle to remove a registered custom listener. */
    interface Registration {
        void remove();
    }

    private record Entry(ScriptPlugin plugin, Function handler, EventPriority priority,
                         boolean ignoreCancelled) {}

    /** name → its listeners, ordered by priority (registration order within a priority). */
    private final Map<String, List<Entry>> byName = new ConcurrentHashMap<>();

    /** Register a listener for {@code name}; returns a handle that removes just this listener. */
    Registration register(String name, ScriptPlugin plugin, Function handler) {
        return register(name, plugin, handler, EventPriority.NORMAL, false);
    }

    /**
     * Register a listener at a priority. Insertion keeps the list sorted so {@link #emit} is a straight
     * walk — the same trade the core bus makes, for the same reason: registering is rare, firing is not.
     */
    Registration register(String name, ScriptPlugin plugin, Function handler, EventPriority priority,
                          boolean ignoreCancelled) {
        List<Entry> list = byName.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>());
        Entry entry = new Entry(plugin, handler, priority, ignoreCancelled);
        // The list is copy-on-write for the benefit of emit, which cannot then see a half-built order;
        // the lock is what makes "find the slot, insert there" one operation between two registrations.
        synchronized (list) {
            int i = 0;
            while (i < list.size() && list.get(i).priority().ordinal() <= priority.ordinal()) {
                i++;
            }
            list.add(i, entry);
        }
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
                if (entry.ignoreCancelled() && event.isCancelled()) {
                    continue;
                }
                manager.callHandler(entry.plugin(), entry.handler(), event);
            }
        }
        return event;
    }

    /** The priorities registered for {@code name}, in the order {@link #emit} would run them. For tests. */
    List<EventPriority> prioritiesFor(String name) {
        List<Entry> list = byName.get(name);
        List<EventPriority> out = new ArrayList<>();
        if (list != null) {
            for (Entry entry : list) {
                out.add(entry.priority());
            }
        }
        return out;
    }
}

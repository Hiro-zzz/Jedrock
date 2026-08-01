package com.jedrock.core.plugin;

import com.jedrock.api.event.Event;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.EventPriority;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.Locale;

/**
 * The {@code events} object a script sees. One per plugin, so every listener a script registers is tracked
 * against that plugin and torn down with it.
 *
 * <pre>{@code
 *   events.on('PlayerJoin', e => e.getPlayer().sendMessage('{gold}Welcome!'));
 *   events.on('BlockBreak', e => { if (isSpawn(e)) e.setCancelled(true); });
 *
 *   // Run AFTER the core's own enforcement, and have the last word on it:
 *   events.on('BlockBreak', e => e.setCancelled(false), {priority: 'HIGHEST'});
 *
 *   // Stop listening without reloading the plugin:
 *   const sub = events.on('PlayerMove', watchThem);
 *   sub.remove();
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
 *
 * <h2>Priority</h2>
 *
 * <p>Listeners run in priority order — {@code LOWEST}, {@code LOW}, {@code NORMAL} (the default),
 * {@code HIGH}, {@code HIGHEST}, {@code MONITOR} — and later ones decide. This is not decoration: the
 * core's own rules live on that scale. Regions enforce their flags at {@code HIGH} and custom items
 * dispatch their behaviours there too, so a script that wants to <em>overrule</em> one has to ask for
 * {@code HIGHEST}. At the default {@code NORMAL} it runs first and is then overruled itself — which is
 * what happened to every script that tried, for as long as the option wasn't offered.
 *
 * <p>{@code MONITOR} is for watching and nothing else: by the time it runs the outcome is settled and
 * something may have acted on it already.
 *
 * <p>{@code {ignoreCancelled: true}} skips the listener once something has cancelled the event. The
 * default is to run anyway, which is what makes un-cancelling at {@code HIGHEST} possible at all.
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
     *
     * @return a handle whose {@code remove()} stops just this listener
     */
    public ScriptSubscription on(String eventName, Function handler) {
        return on(eventName, handler, null);
    }

    /**
     * As {@link #on(String, Function)}, with {@code options}: {@code {priority: 'HIGHEST'}} and/or
     * {@code {ignoreCancelled: true}}. An unknown key is refused rather than ignored — a misspelt
     * {@code priorty} silently meaning "the default" is the exact failure this option exists to end.
     */
    public ScriptSubscription on(String eventName, Function handler, Object options) {
        if (handler == null) {
            throw new IllegalArgumentException("events.on('" + eventName + "', …) needs a function");
        }
        Options opts = Options.of(options);
        Class<? extends Event> type = EventTypes.byName(eventName);
        if (type != null) {
            EventBus.Subscription subscription = manager.eventBus().register(type, opts.priority(),
                    opts.ignoreCancelled(), event -> manager.callHandler(plugin, handler, event));
            plugin.addSubscription(subscription);
            return new ScriptSubscription(subscription::remove);
        }
        CustomEventBus.Registration registration = manager.customEvents()
                .register(eventName, plugin, handler, opts.priority(), opts.ignoreCancelled());
        plugin.addCustomListener(registration);
        return new ScriptSubscription(registration::remove);
    }

    /** Subscribe until the first time it fires, then unsubscribe. */
    public ScriptSubscription once(String eventName, Function handler) {
        return once(eventName, handler, null);
    }

    /**
     * Subscribe until the first time it fires, then unsubscribe — a countdown's last tick, a one-off
     * greeting, the reply to a request. Removal happens <em>before</em> the handler body runs, so a handler
     * that throws still cannot fire a second time.
     */
    public ScriptSubscription once(String eventName, Function handler, Object options) {
        if (handler == null) {
            throw new IllegalArgumentException("events.once('" + eventName + "', …) needs a function");
        }
        // The wrapper has to close over the handle, and the handle only exists once the wrapper has been
        // registered. One mutable cell breaks the circle.
        ScriptSubscription[] self = new ScriptSubscription[1];
        Function wrapper = new OnceFunction(handler, () -> {
            if (self[0] != null) {
                self[0].remove();
            }
        });
        self[0] = on(eventName, wrapper, options);
        return self[0];
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

    /** Every built-in event name, so a script — or a curious operator — can see what there is to listen for. */
    public String[] names() {
        return EventTypes.names().toArray(new String[0]);
    }

    // ===== Options =====

    /** The parsed {@code {priority, ignoreCancelled}}, with the defaults a bare {@code on} has always had. */
    private record Options(EventPriority priority, boolean ignoreCancelled) {

        static Options of(Object raw) {
            Object value = raw instanceof NativeJavaObject wrapper ? wrapper.unwrap() : raw;
            if (value == null || value instanceof Undefined) {
                return new Options(EventPriority.NORMAL, false);
            }
            if (!(value instanceof Scriptable options) || value instanceof Function) {
                throw new IllegalArgumentException(
                        "events.on options must be an object like {priority: 'HIGHEST'}");
            }
            EventPriority priority = EventPriority.NORMAL;
            boolean ignoreCancelled = false;
            for (Object id : ScriptableObject.getPropertyIds(options)) {
                String key = String.valueOf(id);
                Object property = ScriptableObject.getProperty(options, key);
                switch (key) {
                    case "priority" -> priority = parsePriority(property);
                    case "ignoreCancelled" -> ignoreCancelled = Context.toBoolean(property);
                    default -> throw new IllegalArgumentException("events.on: unknown option '" + key
                            + "' (known: priority, ignoreCancelled)");
                }
            }
            return new Options(priority, ignoreCancelled);
        }

        private static EventPriority parsePriority(Object value) {
            if (value == null || value instanceof Undefined) {
                return EventPriority.NORMAL;
            }
            String name = value.toString().trim().toUpperCase(Locale.ROOT);
            try {
                return EventPriority.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("events.on: '" + value + "' is not a priority — one of "
                        + "LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR");
            }
        }
    }

    /**
     * A {@link Function} that unsubscribes and then calls the real handler — what {@link #once} registers.
     * A wrapper rather than a flag on the registration, so "only once" lives in one place and neither bus
     * has to learn that scripts have such a thing.
     */
    private static final class OnceFunction extends BaseFunction {

        private final Function delegate;
        private final Runnable unsubscribe;

        OnceFunction(Function delegate, Runnable unsubscribe) {
            this.delegate = delegate;
            this.unsubscribe = unsubscribe;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            unsubscribe.run(); // first, so a throwing handler still cannot fire a second time
            return delegate.call(cx, scope, thisObj, args);
        }
    }
}

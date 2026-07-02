package com.jedrock.api.event;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Extremely lightweight event bus.
 * 
 * Design goals:
 * - Zero allocation on hot path when possible
 * - Simple registration (no @Subscribe annotations to avoid reflection overhead)
 * - Supports cancellation
 * 
 * This is intentionally minimal. Do not add complex priority/ordering unless measured.
 */
public final class EventBus {

    private static final Logger LOGGER = System.getLogger(EventBus.class.getName());

    private final List<ListenerRegistration<?>> listeners = new CopyOnWriteArrayList<>();

    public <E extends Event> void register(Class<E> eventType, Consumer<E> handler) {
        listeners.add(new ListenerRegistration<>(eventType, handler));
    }

    @SuppressWarnings("unchecked")
    public void post(Event event) {
        for (ListenerRegistration<?> registration : listeners) {
            if (registration.eventType.isInstance(event)) {
                try {
                    ((Consumer<Event>) registration.handler).accept(event);
                } catch (Throwable t) {
                    // A misbehaving listener must not break event dispatch for the rest.
                    LOGGER.log(Level.ERROR, "Event listener failed for " + event.getClass().getSimpleName(), t);
                }
            }
        }
    }

    public void unregister(Consumer<?> handler) {
        listeners.removeIf(reg -> reg.handler == handler);
    }

    private record ListenerRegistration<E extends Event>(Class<E> eventType, Consumer<E> handler) {}
}

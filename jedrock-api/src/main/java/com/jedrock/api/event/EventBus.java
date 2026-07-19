package com.jedrock.api.event;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The extension seam: listeners register here, the core posts here, and a cancellable event lets a
 * listener veto or reshape what the core is about to do. Deliberately small and reflection-free — no
 * {@code @Subscribe} scanning — so it stays cheap and maps cleanly onto a future scripting binding.
 *
 * <p>Design, in keeping with the illusionist philosophy (spend CPU only when you must):
 * <ul>
 *   <li><b>Priority ordering.</b> Listeners run {@link EventPriority} order, earliest first, so later
 *       ones get the final say — see {@link EventPriority}.</li>
 *   <li><b>A free hot path.</b> {@link #hasListeners} answers, in O(1) after warm-up, whether posting an
 *       event would reach anyone — so a hot caller (player movement) can skip <em>allocating</em> the
 *       event entirely when nothing is listening.</li>
 *   <li><b>Cancellation-aware.</b> A listener registered with {@code ignoreCancelled} is skipped once the
 *       event is already cancelled, so protection logic doesn't run against an edit that's already vetoed.</li>
 *   <li><b>Precise removal.</b> {@link #register} returns a handle whose {@link Subscription#remove()}
 *       unregisters exactly that listener — robust where matching a shared lambda by identity is not.</li>
 * </ul>
 *
 * <p>Thread-safety: registration and dispatch are safe to interleave (a {@link CopyOnWriteArrayList}
 * backs the listener list). Listeners run on whatever thread calls {@link #post} — often a network I/O
 * thread — so a listener must be quick and not block.
 */
public final class EventBus {

    private static final Logger LOGGER = System.getLogger(EventBus.class.getName());

    private final List<Registration<?>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Cache of "does any registered listener match this concrete event class?", so the hot path pays the
     * assignability scan once per event type, not once per post. Cleared whenever the listener set changes.
     */
    private final Map<Class<?>, Boolean> interestCache = new ConcurrentHashMap<>();

    /** Register a {@link EventPriority#NORMAL} listener that runs even for an already-cancelled event. */
    public <E extends Event> Subscription register(Class<E> eventType, Consumer<E> handler) {
        return register(eventType, EventPriority.NORMAL, false, handler);
    }

    /** Register a listener at a given priority that runs even for an already-cancelled event. */
    public <E extends Event> Subscription register(Class<E> eventType, EventPriority priority,
                                                   Consumer<E> handler) {
        return register(eventType, priority, false, handler);
    }

    /**
     * Register a listener.
     *
     * @param eventType       the event class (and its subclasses) to receive
     * @param priority        when it runs relative to other listeners
     * @param ignoreCancelled if {@code true}, skip this listener once the event is already cancelled
     * @param handler         the callback
     * @return a handle to remove exactly this listener
     */
    public <E extends Event> Subscription register(Class<E> eventType, EventPriority priority,
                                                   boolean ignoreCancelled, Consumer<E> handler) {
        Registration<E> registration = new Registration<>(eventType, priority, ignoreCancelled, handler);
        // Keep the list sorted by priority so post() is a straight walk. Insertion is rare; posting is hot.
        int i = 0;
        while (i < listeners.size() && listeners.get(i).priority.ordinal() <= priority.ordinal()) {
            i++;
        }
        listeners.add(i, registration);
        interestCache.clear();
        return registration;
    }

    /**
     * Dispatch {@code event} to every matching listener, in priority order. A listener that throws is
     * logged and skipped so one misbehaving listener can't break dispatch for the rest.
     *
     * @return the event, so a caller can post-and-inspect in one expression
     *         ({@code if (bus.post(e).isCancelled()) …})
     */
    @SuppressWarnings("unchecked")
    public <E extends Event> E post(E event) {
        boolean cancellable = event instanceof Cancellable;
        for (Registration<?> registration : listeners) {
            if (!registration.eventType.isInstance(event)) {
                continue;
            }
            if (registration.ignoreCancelled && cancellable && ((Cancellable) event).isCancelled()) {
                continue;
            }
            try {
                ((Consumer<Event>) registration.handler).accept(event);
            } catch (Throwable t) {
                LOGGER.log(Level.ERROR, "Event listener failed for " + event.getClass().getSimpleName(), t);
            }
        }
        return event;
    }

    /**
     * Whether posting an event of {@code eventType} would reach any listener. Lets a hot caller avoid
     * building an event nobody will see. Cached per concrete class, so the cost is a map lookup after the
     * first call for that type.
     */
    public boolean hasListeners(Class<? extends Event> eventType) {
        Boolean cached = interestCache.get(eventType);
        if (cached != null) {
            return cached;
        }
        boolean any = false;
        for (Registration<?> registration : listeners) {
            if (registration.eventType.isAssignableFrom(eventType)) {
                any = true;
                break;
            }
        }
        interestCache.put(eventType, any);
        return any;
    }

    /** Remove every listener registered with the given handler instance (all priorities). */
    public void unregister(Consumer<?> handler) {
        if (listeners.removeIf(registration -> registration.handler == handler)) {
            interestCache.clear();
        }
    }

    /** A handle to one registered listener, so it can be removed without re-identifying it. */
    public interface Subscription {
        /** Unregister this listener. Idempotent — removing an already-removed listener does nothing. */
        void remove();
    }

    private final class Registration<E extends Event> implements Subscription {
        private final Class<E> eventType;
        private final EventPriority priority;
        private final boolean ignoreCancelled;
        private final Consumer<E> handler;

        Registration(Class<E> eventType, EventPriority priority, boolean ignoreCancelled, Consumer<E> handler) {
            this.eventType = eventType;
            this.priority = priority;
            this.ignoreCancelled = ignoreCancelled;
            this.handler = handler;
        }

        @Override
        public void remove() {
            if (listeners.remove(this)) {
                interestCache.clear();
            }
        }
    }

    /** Snapshot of the registered event types — for diagnostics / tests, not the hot path. */
    List<Class<?>> registeredTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (Registration<?> registration : listeners) {
            types.add(registration.eventType);
        }
        return types;
    }
}

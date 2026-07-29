package com.jedrock.api.event;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
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
 * thread — so a listener must be quick and not block. Registration and removal come from several threads
 * too (a script hot-reload, a {@code /region create} on a network thread), so the ordered insertion is
 * serialized on {@link #mutationLock} — a read-modify-write over the list, which the list's own atomicity
 * does not cover.
 */
public final class EventBus {

    private static final Logger LOGGER = System.getLogger(EventBus.class.getName());

    private final List<Registration<?>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Guards the ordered insertion in {@link #register} and the version bump: finding the insertion point
     * and inserting there is a read-modify-write, so two concurrent registrations could otherwise compute
     * indices against a list that has since moved — landing a listener at the wrong priority, or throwing
     * out of bounds against a list a concurrent removal shrank.
     */
    private final Object mutationLock = new Object();

    /**
     * Bumped on every change to the listener set. It is what makes {@link #interestCache} safe to fill
     * without a lock: an entry records the version it was computed under, so a verdict reached while the
     * set was moving is simply ignored rather than trusted. Invalidating by clearing the cache instead
     * would leave the window that matters — a scan that finds nothing, a registration that clears, then
     * the scan's stale {@code false} written after the clear and cached forever, silently gating the
     * listener out of the hot path for the rest of the run.
     */
    private final AtomicLong listenerVersion = new AtomicLong();

    /**
     * Cache of "does any registered listener match this concrete event class?", so the hot path pays the
     * assignability scan once per event type, not once per post. Each entry carries the
     * {@link #listenerVersion} it was computed under; a stale one is re-scanned rather than believed.
     */
    private final Map<Class<?>, Interest> interestCache = new ConcurrentHashMap<>();

    /** A cached {@link #hasListeners} verdict, stamped with the listener-set version it was computed under. */
    private record Interest(long version, boolean any) {}

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
        // Under the lock: the scan and the insert are one operation, and the version bump follows the
        // change so a hasListeners racing it can tell its answer came from the older set.
        synchronized (mutationLock) {
            int i = 0;
            while (i < listeners.size() && listeners.get(i).priority.ordinal() <= priority.ordinal()) {
                i++;
            }
            listeners.add(i, registration);
            listenerVersion.incrementAndGet();
        }
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
        // Read the version first: a cached verdict is only trusted if the set hasn't moved since, and a
        // fresh scan is only cached against the version it actually saw.
        long version = listenerVersion.get();
        Interest cached = interestCache.get(eventType);
        if (cached != null && cached.version() == version) {
            return cached.any();
        }
        boolean any = false;
        for (Registration<?> registration : listeners) {
            if (registration.eventType.isAssignableFrom(eventType)) {
                any = true;
                break;
            }
        }
        // Racing a registration can only cache the answer under the older version, which the next call
        // discards — never a stale verdict that outlives the change that invalidated it.
        interestCache.put(eventType, new Interest(version, any));
        return any;
    }

    /** Remove every listener registered with the given handler instance (all priorities). */
    public void unregister(Consumer<?> handler) {
        synchronized (mutationLock) {
            if (listeners.removeIf(registration -> registration.handler == handler)) {
                listenerVersion.incrementAndGet();
            }
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
            synchronized (mutationLock) {
                if (listeners.remove(this)) {
                    listenerVersion.incrementAndGet();
                }
            }
        }
    }
}

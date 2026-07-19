package com.jedrock.api.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dispatch engine: priority order, cancellation-aware skipping, the hot-path gate, and removal. */
class EventBusTest {

    /** A plain event. */
    private static class Ping implements Event {}

    /** A subclass, to prove listeners registered on a base type still receive subclasses. */
    private static final class SpecialPing extends Ping {}

    /** A cancellable event carrying a running log of who touched it. */
    private static final class Edit implements Event, Cancellable {
        final List<String> trail = new ArrayList<>();
        private boolean cancelled;
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    }

    @Test
    void listenersRunInPriorityOrder() {
        EventBus bus = new EventBus();
        Edit event = new Edit();

        bus.register(Edit.class, EventPriority.HIGHEST, e -> e.trail.add("highest"));
        bus.register(Edit.class, EventPriority.LOWEST, e -> e.trail.add("lowest"));
        bus.register(Edit.class, EventPriority.NORMAL, e -> e.trail.add("normal"));
        bus.register(Edit.class, EventPriority.MONITOR, e -> e.trail.add("monitor"));

        bus.post(event);

        assertEquals(List.of("lowest", "normal", "highest", "monitor"), event.trail,
                "earliest priority first, monitor last");
    }

    @Test
    void registrationOrderBreaksTiesWithinAPriority() {
        EventBus bus = new EventBus();
        Edit event = new Edit();

        bus.register(Edit.class, EventPriority.NORMAL, e -> e.trail.add("first"));
        bus.register(Edit.class, EventPriority.NORMAL, e -> e.trail.add("second"));

        bus.post(event);

        assertEquals(List.of("first", "second"), event.trail, "same priority keeps registration order");
    }

    @Test
    void ignoreCancelledListenersAreSkippedOnceCancelled() {
        EventBus bus = new EventBus();
        Edit event = new Edit();

        bus.register(Edit.class, EventPriority.LOW, e -> e.setCancelled(true));
        bus.register(Edit.class, EventPriority.NORMAL, false, e -> e.trail.add("still-runs"));
        bus.register(Edit.class, EventPriority.HIGH, true, e -> e.trail.add("skipped"));

        bus.post(event);

        assertEquals(List.of("still-runs"), event.trail,
                "a normal listener still sees the cancelled event; an ignoreCancelled one does not");
    }

    @Test
    void postReturnsTheEventForInlineInspection() {
        EventBus bus = new EventBus();
        bus.register(Edit.class, e -> e.setCancelled(true));

        assertTrue(bus.post(new Edit()).isCancelled(), "post returns the same event, now cancelled");
    }

    @Test
    void listenersReceiveSubclassEvents() {
        EventBus bus = new EventBus();
        AtomicInteger seen = new AtomicInteger();
        bus.register(Ping.class, e -> seen.incrementAndGet());

        bus.post(new SpecialPing());

        assertEquals(1, seen.get(), "a Ping listener catches a SpecialPing");
    }

    @Test
    void hasListenersReflectsRegistrationAcrossTheTypeHierarchy() {
        EventBus bus = new EventBus();
        assertFalse(bus.hasListeners(Ping.class), "nobody listening yet");
        assertFalse(bus.hasListeners(SpecialPing.class), "nor for the subclass");

        bus.register(Ping.class, e -> {});

        assertTrue(bus.hasListeners(Ping.class), "now someone is");
        assertTrue(bus.hasListeners(SpecialPing.class), "and a subclass counts as a match too");
        assertFalse(bus.hasListeners(Edit.class), "but an unrelated type still has none");
    }

    @Test
    void hasListenersCacheIsInvalidatedWhenListenersChange() {
        EventBus bus = new EventBus();
        assertFalse(bus.hasListeners(Ping.class), "primes the cache to false");

        EventBus.Subscription sub = bus.register(Ping.class, e -> {});
        assertTrue(bus.hasListeners(Ping.class), "registering invalidates the cached false");

        sub.remove();
        assertFalse(bus.hasListeners(Ping.class), "removing invalidates the cached true");
    }

    @Test
    void aSubscriptionRemovesExactlyItsOwnListener() {
        EventBus bus = new EventBus();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();

        EventBus.Subscription subA = bus.register(Ping.class, e -> a.incrementAndGet());
        bus.register(Ping.class, e -> b.incrementAndGet());

        subA.remove();
        bus.post(new Ping());

        assertEquals(0, a.get(), "removed listener does not run");
        assertEquals(1, b.get(), "the other one still does");
    }

    @Test
    void removeIsIdempotent() {
        EventBus bus = new EventBus();
        EventBus.Subscription sub = bus.register(Ping.class, e -> {});
        sub.remove();
        sub.remove(); // must not throw
        assertFalse(bus.hasListeners(Ping.class));
    }

    @Test
    void aThrowingListenerDoesNotStopTheRest() {
        EventBus bus = new EventBus();
        AtomicInteger reached = new AtomicInteger();
        bus.register(Ping.class, EventPriority.LOW, e -> { throw new RuntimeException("boom"); });
        bus.register(Ping.class, EventPriority.HIGH, e -> reached.incrementAndGet());

        bus.post(new Ping());

        assertEquals(1, reached.get(), "the later listener still runs after one throws");
    }
}

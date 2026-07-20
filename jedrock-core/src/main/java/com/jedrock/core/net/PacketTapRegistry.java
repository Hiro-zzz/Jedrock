package com.jedrock.core.net;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The set of packet taps — listeners that see raw packets flowing in and out — and the dispatch that offers a
 * packet to them. Kept off the {@code api}/network path so the network layer only ever calls the core's
 * {@code ConnectionListener} hooks; the core owns one of these and the scripting layer registers into it.
 *
 * <p><b>Fast path.</b> {@link #hasTaps()} is a single volatile read, so the network hooks can skip all work
 * (no byte copy, no event) when nothing is listening — the common case.
 *
 * <p><b>Re-entrancy.</b> A tap may itself send a packet (an injection, or {@code player.sendMessage}), which
 * would fire the outbound taps again on the same thread. A thread-local guard makes a nested dispatch a
 * no-op, so a tap that sends can't recurse into itself — it simply doesn't re-tap the packet it just caused.
 *
 * <p>Taps run wherever the packet does (a Netty I/O thread), so a {@link Tap} must be quick and thread-safe.
 * The script bridge serializes them under the script lock.
 */
public final class PacketTapRegistry {

    /** A packet listener. Called with the event; may {@link PacketEvent#cancel()} it. */
    @FunctionalInterface
    public interface Tap {
        void handle(PacketEvent event);
    }

    /** A handle to remove a registered tap (used to drop a plugin's taps on unload/reload). */
    public interface Registration {
        void remove();
    }

    private final List<Tap> inbound = new CopyOnWriteArrayList<>();
    private final List<Tap> outbound = new CopyOnWriteArrayList<>();
    /** Cheap gate: true while any tap is registered, so the hot path is one volatile read. */
    private volatile boolean any = false;
    /** True while this thread is already dispatching, so a tap that sends a packet can't recurse. */
    private final ThreadLocal<Boolean> dispatching = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Whether any tap is registered — the network layer's skip-everything gate. */
    public boolean hasTaps() {
        return any;
    }

    /** Register an inbound (client→server) tap. */
    public Registration registerInbound(Tap tap) {
        inbound.add(tap);
        any = true;
        return () -> {
            inbound.remove(tap);
            recomputeAny();
        };
    }

    /** Register an outbound (server→client) tap. */
    public Registration registerOutbound(Tap tap) {
        outbound.add(tap);
        any = true;
        return () -> {
            outbound.remove(tap);
            recomputeAny();
        };
    }

    private void recomputeAny() {
        any = !inbound.isEmpty() || !outbound.isEmpty();
    }

    /**
     * Offer {@code event} to the taps for its direction and report whether it was cancelled. A nested call
     * (a tap sending a packet) returns {@code false} without dispatching — see the class re-entrancy note.
     * A throwing tap is isolated by the caller (the script bridge swallows and logs), so it never cancels.
     */
    public boolean dispatch(PacketEvent event) {
        if (Boolean.TRUE.equals(dispatching.get())) {
            return false; // already dispatching on this thread — don't re-tap a packet a tap caused
        }
        List<Tap> taps = event.isInbound() ? inbound : outbound;
        if (taps.isEmpty()) {
            return false;
        }
        dispatching.set(Boolean.TRUE);
        try {
            for (Tap tap : taps) {
                tap.handle(event);
            }
        } finally {
            dispatching.set(Boolean.FALSE);
        }
        return event.isCancelled();
    }
}

package com.jedrock.core.net;

import com.jedrock.api.protocol.ProtocolVersion;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The packet tap registry: the fast gate, direction routing, cancellation, teardown, and re-entrancy. */
class PacketTapRegistryTest {

    private static PacketEvent event(PacketDirection dir, int id) {
        return new PacketEvent(ProtocolVersion.PE_1_1_5, dir, id, new byte[0], null, null);
    }

    @Test
    void hasTapsGatesOnRegistration() {
        PacketTapRegistry reg = new PacketTapRegistry();
        assertFalse(reg.hasTaps(), "empty registry has no taps");
        PacketTapRegistry.Registration r = reg.registerInbound(e -> {});
        assertTrue(reg.hasTaps());
        r.remove();
        assertFalse(reg.hasTaps(), "removing the last tap clears the gate");
    }

    @Test
    void inboundAndOutboundTapsAreRoutedByDirection() {
        PacketTapRegistry reg = new PacketTapRegistry();
        AtomicInteger in = new AtomicInteger();
        AtomicInteger out = new AtomicInteger();
        reg.registerInbound(e -> in.incrementAndGet());
        reg.registerOutbound(e -> out.incrementAndGet());

        reg.dispatch(event(PacketDirection.INBOUND, 1));
        assertEquals(1, in.get());
        assertEquals(0, out.get(), "an inbound packet doesn't reach outbound taps");

        reg.dispatch(event(PacketDirection.OUTBOUND, 1));
        assertEquals(1, out.get());
        assertEquals(1, in.get());
    }

    @Test
    void cancelIsReportedFromDispatch() {
        PacketTapRegistry reg = new PacketTapRegistry();
        reg.registerInbound(e -> { if (e.getId() == 7) e.cancel(); });

        assertFalse(reg.dispatch(event(PacketDirection.INBOUND, 1)), "id 1 not cancelled");
        assertTrue(reg.dispatch(event(PacketDirection.INBOUND, 7)), "id 7 cancelled");
    }

    @Test
    void everyTapRunsEvenWhenOneCancels() {
        PacketTapRegistry reg = new PacketTapRegistry();
        AtomicInteger runs = new AtomicInteger();
        reg.registerOutbound(e -> { runs.incrementAndGet(); e.cancel(); });
        reg.registerOutbound(e -> runs.incrementAndGet());

        assertTrue(reg.dispatch(event(PacketDirection.OUTBOUND, 1)));
        assertEquals(2, runs.get(), "both taps ran; the cancel from the first didn't stop the second");
    }

    @Test
    void reentrantDispatchIsANoOp() {
        PacketTapRegistry reg = new PacketTapRegistry();
        AtomicInteger depth = new AtomicInteger();
        // A tap that "sends a packet" by dispatching again — the guard must make the nested call do nothing.
        reg.registerOutbound(e -> {
            depth.incrementAndGet();
            if (depth.get() < 5) { // would recurse forever without the guard
                reg.dispatch(event(PacketDirection.OUTBOUND, 99));
            }
        });

        reg.dispatch(event(PacketDirection.OUTBOUND, 1));
        assertEquals(1, depth.get(), "the nested dispatch was skipped, so the tap ran exactly once");
    }

    @Test
    void removingOneTapLeavesTheOther() {
        PacketTapRegistry reg = new PacketTapRegistry();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        PacketTapRegistry.Registration ra = reg.registerInbound(e -> a.incrementAndGet());
        reg.registerInbound(e -> b.incrementAndGet());

        ra.remove();
        reg.dispatch(event(PacketDirection.INBOUND, 1));
        assertEquals(0, a.get(), "the removed tap didn't run");
        assertEquals(1, b.get(), "the remaining tap did");
    }
}

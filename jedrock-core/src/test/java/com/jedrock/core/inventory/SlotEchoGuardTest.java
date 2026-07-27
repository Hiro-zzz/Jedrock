package com.jedrock.core.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The timing rule that separates a client's own inventory move from its echo of the server's. */
class SlotEchoGuardTest {

    private static final long WINDOW = 750L * 1_000_000L;

    @Test
    void anUntouchedSlotIsNeverGuarded() {
        SlotEchoGuard guard = new SlotEchoGuard(41, WINDOW);

        assertFalse(guard.isGuarded(0, 1_000L), "nothing has been pushed — the client is believed");
    }

    @Test
    void aPushedSlotIsGuardedUntilTheWindowPasses() {
        SlotEchoGuard guard = new SlotEchoGuard(41, WINDOW);
        guard.arm(4, 1_000L);

        assertTrue(guard.isGuarded(4, 1_000L + WINDOW - 1), "an echo lands inside the window");
        assertFalse(guard.isGuarded(4, 1_000L + WINDOW), "the boundary is already outside");
        assertFalse(guard.isGuarded(4, 1_000L + WINDOW * 3), "and a later, genuine move is believed");
    }

    @Test
    void guardingOneSlotDoesNotGuardTheOthers() {
        SlotEchoGuard guard = new SlotEchoGuard(41, WINDOW);
        guard.arm(4, 1_000L);

        assertFalse(guard.isGuarded(5, 1_000L),
                "a deposit out of one slot must not freeze the rest of the inventory");
    }

    @Test
    void aFullResyncGuardsEverySlot() {
        SlotEchoGuard guard = new SlotEchoGuard(41, WINDOW);
        guard.armAll(1_000L);

        for (int slot = 0; slot < 41; slot++) {
            assertTrue(guard.isGuarded(slot, 1_000L), "slot " + slot);
        }
    }

    @Test
    void aZeroWindowTurnsTheGuardOff() {
        SlotEchoGuard guard = new SlotEchoGuard(41, 0L);
        guard.armAll(1_000L);

        assertFalse(guard.isGuarded(0, 1_000L), "-Djedrock.pe.slotEchoGuardMs=0 restores raw client trust");
    }

    @Test
    void anOutOfRangeSlotIsIgnoredRatherThanThrowing() {
        SlotEchoGuard guard = new SlotEchoGuard(41, WINDOW);
        guard.arm(-1, 1_000L);
        guard.arm(99, 1_000L);

        assertFalse(guard.isGuarded(-1, 1_000L));
        assertFalse(guard.isGuarded(99, 1_000L));
    }
}

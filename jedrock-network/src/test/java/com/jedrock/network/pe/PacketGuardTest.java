package com.jedrock.network.pe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-safety limits: sane list counts, the guard that stops a hostile wire-driven loop before it spins. */
class PacketGuardTest {

    @Test
    void saneCountBounds() {
        assertTrue(PacketGuard.saneCount(0));
        assertTrue(PacketGuard.saneCount(PacketGuard.MAX_LIST_ENTRIES));
        assertFalse(PacketGuard.saneCount(-1), "negative count is hostile");
        assertFalse(PacketGuard.saneCount(PacketGuard.MAX_LIST_ENTRIES + 1), "over the cap");
        assertFalse(PacketGuard.saneCount(Integer.MAX_VALUE), "absurd count");
    }
}

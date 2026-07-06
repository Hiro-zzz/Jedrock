package com.jedrock.network.pe;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-safety limits: sane counts, and rejecting a hostile wire-driven loop before it spins. */
class PacketGuardTest {

    @Test
    void saneCountBounds() {
        assertTrue(PacketGuard.saneCount(0));
        assertTrue(PacketGuard.saneCount(PacketGuard.MAX_LIST_ENTRIES));
        assertFalse(PacketGuard.saneCount(-1), "negative count is hostile");
        assertFalse(PacketGuard.saneCount(PacketGuard.MAX_LIST_ENTRIES + 1), "over the cap");
        assertFalse(PacketGuard.saneCount(Integer.MAX_VALUE), "absurd count");
    }

    @Test
    void inventoryTransactionWithHostileActionCountIsRejected() {
        // A minimal InventoryTransaction claiming a huge action list must bail before looping.
        ByteBuf pk = Unpooled.buffer();
        ByteBufUtils.writeVarInt(pk, 0);            // transaction type
        ByteBufUtils.writeVarInt(pk, 5_000_000);    // hostile action count
        try {
            assertNull(PeBlockEditDecoder.decodeInventoryTransaction(pk),
                    "an out-of-bounds action count is refused, not looped over");
        } finally {
            pk.release();
        }
    }
}

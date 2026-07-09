package com.jedrock.network.pe;

import com.jedrock.api.world.Blocks;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared PE creative palette carries meta variants, and the 1.1.5 item slot round-trips them. */
class PeCreativePaletteTest {

    @Test
    void paletteIsRichWithValidUniqueStates() {
        int[] states = PeCreativePalette.states();
        assertTrue(states.length > 100, "a variant-rich palette (" + states.length + " entries)");

        Set<Integer> seen = new HashSet<>();
        for (int state : states) {
            int id = Blocks.idOf(state);
            assertTrue(id >= 1 && id <= 255, "id in the legacy byte range: " + id);
            assertTrue(seen.add(state), "no duplicate state: " + state);
        }
        // All 16 wool colours are present as distinct states.
        for (int meta = 0; meta < 16; meta++) {
            assertTrue(seen.contains(Blocks.state(35, meta)), "wool colour meta " + meta);
        }
        assertTrue(seen.contains(Blocks.state(159, 14)), "red terracotta");
        assertTrue(seen.contains(Blocks.state(17, 1)), "spruce log variant");
        assertTrue(seen.contains(Blocks.state(5, 5)), "dark-oak planks variant");
    }

    @Test
    void slotRoundTripsIdAndMeta() {
        ByteBuf b = Unpooled.buffer();
        int redWool = Blocks.state(35, 14);
        McpeCodec.writeSlot(b, redWool, 1);
        assertEquals(redWool, McpeCodec.readItemState(b), "id + meta survive the aux packing");
        assertFalse(b.isReadable(), "slot fully consumed");
        b.release();
    }

    @Test
    void airSlotCarriesNoMeta() {
        ByteBuf b = Unpooled.buffer();
        McpeCodec.writeSlot(b, Blocks.AIR, 1);
        assertEquals(Blocks.AIR, McpeCodec.readItemState(b));
        b.release();
    }
}
